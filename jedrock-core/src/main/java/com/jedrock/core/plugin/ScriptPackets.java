package com.jedrock.core.plugin;

import com.jedrock.api.player.Player;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.core.net.PacketTapRegistry;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.Wrapper;

/**
 * The {@code packets} object a script sees — a raw, cross-edition packet tap ("ProtocolLib on steroids").
 * One per plugin, so every listener a script registers is torn down when it unloads or hot-reloads.
 *
 * <pre>{@code
 *   // Watch (and optionally drop) client→server packets, across all four protocols.
 *   packets.onReceive(function (p) {
 *       console.log(p.getProtocol(), 'id=0x' + p.getId().toString(16), 'len=' + p.getLength());
 *       if (p.getId() === 0x03) p.cancel();   // never reaches the core
 *   });
 *
 *   packets.onSend(function (p) { ... });      // server→client, before it hits the socket
 *
 *   packets.send(player, 0x1F, [0x00, 0x01]);  // inject a raw packet (framed for the player's edition)
 * }</pre>
 *
 * A listener gets a {@code PacketEvent}: {@code getId()}, {@code getBytes()} (a Java {@code byte[]}),
 * {@code getLength()}, {@code getProtocol()}, {@code getPlayer()} (may be null pre-login), {@code cancel()}.
 * Handlers run under the script lock like event listeners — and packets are high-frequency, so keep them
 * quick. Nothing is tapped until a listener exists (a cheap gate), so an idle {@code packets} costs nothing.
 */
public final class ScriptPackets {

    private final PluginManager manager;
    private final ScriptPlugin plugin;

    ScriptPackets(PluginManager manager, ScriptPlugin plugin) {
        this.manager = manager;
        this.plugin = plugin;
    }

    /** Tap client→server packets. The handler may {@code cancel()} a packet so the core never sees it. */
    public void onReceive(Function handler) {
        require(handler, "onReceive");
        PacketTapRegistry.Registration reg = manager.packetTaps()
                .registerInbound(event -> manager.callPacketTap(plugin, handler, event));
        plugin.addPacketTap(reg);
    }

    /** Tap server→client packets. The handler may {@code cancel()} a packet so nothing is sent. */
    public void onSend(Function handler) {
        require(handler, "onSend");
        PacketTapRegistry.Registration reg = manager.packetTaps()
                .registerOutbound(event -> manager.callPacketTap(plugin, handler, event));
        plugin.addPacketTap(reg);
    }

    /**
     * Inject a raw packet at {@code player}: a numeric {@code id} and its {@code payload} (bytes after the
     * id), framed for the player's edition. {@code payload} is a JS array of byte values (e.g.
     * {@code [0x00, 0x1f]}) or a Java {@code byte[]} (such as another packet's {@code getBytes()}).
     */
    public void send(Object player, int id, Object payload) {
        // A script holds the script contract, not the core player — see ScriptWrapFactory.
        Player target = ScriptWrapFactory.unwrapPlayer(player);
        if (target == null) {
            throw new IllegalArgumentException("packets.send needs a player");
        }
        PlayerConnection connection = target.getConnection();
        if (connection != null) {
            connection.sendRawPacket(id, toBytes(payload));
        }
    }

    private static void require(Function handler, String method) {
        if (handler == null) {
            throw new IllegalArgumentException("packets." + method + "(fn) needs a function");
        }
    }

    /** Coerce a script value into a byte[]: a Java byte[] as-is, or a JS number array element-by-element. */
    private static byte[] toBytes(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        Object unwrapped = payload instanceof Wrapper w ? w.unwrap() : payload;
        if (unwrapped instanceof byte[] bytes) {
            return bytes;
        }
        if (unwrapped instanceof NativeArray array) {
            long len = array.getLength();
            byte[] out = new byte[(int) len];
            for (int i = 0; i < len; i++) {
                Object v = array.get(i, array);
                out[i] = (byte) (v == Scriptable.NOT_FOUND ? 0 : (int) Context.toNumber(v));
            }
            return out;
        }
        throw new IllegalArgumentException("packets.send payload must be a byte array");
    }
}
