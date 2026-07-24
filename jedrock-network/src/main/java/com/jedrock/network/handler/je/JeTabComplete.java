package com.jedrock.network.handler.je;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.util.List;

/**
 * The body of the clientbound Tab-Complete packet, shared by every Java version because the shape hasn't
 * changed across the legacy ones: a VarInt count followed by that many strings. Only the packet id differs
 * ({@code 0x0E} at 1.12.2, {@code 0x3A} at 1.8), which each handler supplies; this writes what goes after
 * it. The client replaces the last whitespace token of the text it sent with the chosen match.
 */
public final class JeTabComplete {

    private JeTabComplete() {}

    /** Write {@code matches} as a VarInt count + that many strings into {@code buf}. */
    public static void write(ByteBuf buf, List<String> matches) {
        ByteBufUtils.writeVarInt(buf, matches.size());
        for (String match : matches) {
            ByteBufUtils.writeString(buf, match);
        }
    }
}
