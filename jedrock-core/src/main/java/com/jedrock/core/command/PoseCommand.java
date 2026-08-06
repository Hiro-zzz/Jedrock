package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import com.jedrock.core.JedrockServer;
import com.jedrock.utils.text.ChatText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /pose} — build a scene where you can see it, instead of writing it blind and reloading.
 *
 * <p>Props go exactly where a real block cannot: at fractional positions, hanging unsupported, overlapping
 * each other. That is the whole appeal and also why authoring one in a script is miserable — you type
 * three coordinates, save, watch the reload, find the lantern is half a block inside the wall, and type
 * three more. This is the same arrangement built by standing where you want the thing and saying so.
 *
 * <p>What it produces is not a new format: it hands the finished props to the same {@code SceneManager}
 * a script's {@code group.save(name)} uses, so the server stands the scene back up at every boot from the
 * same file, with no plugin involved. A scene authored here and one authored in code are the same object.
 *
 * <p>A session belongs to one player and lives in memory. Nothing is saved until {@code /pose save}, and
 * {@code /pose cancel} takes the props away again — so an abandoned session leaves the world as it was,
 * and a disconnect leaves the props standing (they are ordinary server-owned entities) with the session
 * forgotten, which is recoverable by hand and not worth more machinery than that.
 */
public final class PoseCommand implements Command {

    /** One player's unsaved arrangement. Not persisted: this is a workbench, not a document. */
    private record Session(String name, List<PuppetEntity> props) {}

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "pose";
    }

    @Override
    public String description() {
        return "Build a scene in the world and save it";
    }

    @Override
    public String usage() {
        return "/pose new <name> | block <state> | item <state> | text <words> | mob <type> "
                + "| nudge <dx> <dy> <dz> | rotate <deg> | undo | list | save | cancel";
    }

    @Override
    public String permission() {
        return "jedrock.command.pose";
    }

    @Override
    public boolean playerOnly() {
        return true; // every position here comes from where the sender is standing
    }

    @Override
    public List<CommandArg> arguments() {
        return List.of(CommandArg.optional("action", ArgType.choice("new", "block", "item", "text",
                "mob", "nudge", "rotate", "undo", "list", "save", "cancel")),
                CommandArg.optional("value", ArgType.GREEDY));
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        Player player = (Player) sender;
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        switch (action) {
            case "new" -> begin(server, player, args);
            case "block", "item", "text", "mob" -> add(server, player, action, args);
            case "nudge" -> nudge(player, args);
            case "rotate" -> rotate(player, args);
            case "undo" -> undo(player);
            case "list" -> list(server, player);
            case "save" -> save(server, player);
            case "cancel" -> cancel(player);
            default -> help(player);
        }
    }

    // ===== The session =====

    private void begin(JedrockServer server, Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("{red}Usage: /pose new <name>");
            return;
        }
        String name = args[1];
        Session open = sessions.get(player.getUniqueId());
        if (open != null) {
            player.sendMessage("{yellow}You are already posing {white}" + ChatText.escape(open.name())
                    + "{yellow} — {white}/pose save{yellow} or {white}/pose cancel{yellow} first.");
            return;
        }
        if (server.getScenes().has(name)) {
            // Saving would replace it, which is a thing to do on purpose rather than by reusing a name.
            player.sendMessage("{yellow}A scene called {white}" + ChatText.escape(name)
                    + "{yellow} already exists. Saving will replace it.");
        }
        sessions.put(player.getUniqueId(), new Session(name, new ArrayList<>()));
        player.sendMessage("{green}Posing {white}" + ChatText.escape(name)
                + "{gray} — stand where you want a prop and use {white}/pose block <state>{gray}.");
        player.sendMessage("{dark_gray}Props go exactly where you stand, fractional position and all.");
    }

    private void add(JedrockServer server, Player player, String kind, String[] args) {
        Session session = require(player);
        if (session == null) {
            return;
        }
        if (args.length < 2) {
            player.sendMessage("{red}Usage: /pose " + kind + " <"
                    + (kind.equals("text") ? "words" : kind.equals("mob") ? "type" : "state") + ">");
            return;
        }
        Location at = player.getLocation();
        String value = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        PuppetEntity prop;
        switch (kind) {
            case "block" -> {
                Integer state = state(value);
                if (state == null) {
                    player.sendMessage("{red}'" + ChatText.escape(value) + "' is not a block state — "
                            + "a number, or {white}id:meta{red} (35:14 is red wool).");
                    return;
                }
                prop = server.getEntities().spawnFallingBlock(at, state);
            }
            case "item" -> {
                Integer state = state(value);
                if (state == null) {
                    player.sendMessage("{red}'" + ChatText.escape(value) + "' is not an item state.");
                    return;
                }
                prop = server.getEntities().spawnItem(at, state);
            }
            case "text" -> prop = server.getEntities().spawnText(at, value);
            default -> {
                EntityType type = entityType(value);
                if (type == null) {
                    player.sendMessage("{red}'" + ChatText.escape(value) + "' is not a mob this server "
                            + "knows. {gray}Try zombie, pig, chicken, cow, skeleton, creeper.");
                    return;
                }
                // Aimed the way the author is facing, since a mob that stares north regardless is the
                // first thing anybody would want to fix.
                prop = server.getEntities().spawnPuppet(type, at, type.canonicalName());
            }
        }
        session.props().add(prop);
        player.sendMessage("{green}+ {white}" + kind + " {gray}#" + session.props().size()
                + " at " + coords(at));
    }

    private void nudge(Player player, String[] args) {
        Session session = require(player);
        if (session == null) {
            return;
        }
        if (session.props().isEmpty()) {
            player.sendMessage("{yellow}Nothing to nudge yet.");
            return;
        }
        if (args.length < 4) {
            player.sendMessage("{red}Usage: /pose nudge <dx> <dy> <dz> {gray}(fractions are the point: "
                    + "0.5 is half a block)");
            return;
        }
        double dx, dy, dz;
        try {
            dx = Double.parseDouble(args[1]);
            dy = Double.parseDouble(args[2]);
            dz = Double.parseDouble(args[3]);
        } catch (NumberFormatException e) {
            player.sendMessage("{red}Those are not three numbers.");
            return;
        }
        PuppetEntity last = session.props().get(session.props().size() - 1);
        Location at = last.getLocation();
        last.teleport(new Location(at.world(), at.x() + dx, at.y() + dy, at.z() + dz,
                at.yaw(), at.pitch()));
        player.sendMessage("{gray}Moved #" + session.props().size() + " to " + coords(last.getLocation()));
    }

    private void rotate(Player player, String[] args) {
        Session session = require(player);
        if (session == null) {
            return;
        }
        if (args.length < 2) {
            player.sendMessage("{red}Usage: /pose rotate <degrees>");
            return;
        }
        double degrees;
        try {
            degrees = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("{red}'" + ChatText.escape(args[1]) + "' is not a number of degrees.");
            return;
        }
        List<PuppetEntity> props = session.props();
        if (props.isEmpty()) {
            player.sendMessage("{yellow}Nothing to rotate yet.");
            return;
        }
        // Around the arrangement's own centre, which is what somebody means by "turn it": rotating about
        // the author's feet would swing the whole thing across the room instead.
        double cx = 0;
        double cz = 0;
        for (PuppetEntity prop : props) {
            cx += prop.getLocation().x();
            cz += prop.getLocation().z();
        }
        cx /= props.size();
        cz /= props.size();
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        for (PuppetEntity prop : props) {
            Location at = prop.getLocation();
            double dx = at.x() - cx;
            double dz = at.z() - cz;
            prop.teleport(new Location(at.world(), cx + dx * cos - dz * sin, at.y(),
                    cz + dx * sin + dz * cos, at.yaw() + (float) degrees, at.pitch()));
        }
        player.sendMessage("{gray}Turned " + props.size() + " prop(s) by " + degrees + "°.");
    }

    private void undo(Player player) {
        Session session = require(player);
        if (session == null) {
            return;
        }
        if (session.props().isEmpty()) {
            player.sendMessage("{yellow}Nothing to undo.");
            return;
        }
        session.props().remove(session.props().size() - 1).remove();
        player.sendMessage("{gray}Removed the last prop — " + session.props().size() + " left.");
    }

    private void list(JedrockServer server, Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session != null) {
            player.sendMessage("{gold}Posing {white}" + ChatText.escape(session.name())
                    + "{gold}: {white}" + session.props().size() + "{gold} prop(s)");
            int i = 0;
            for (PuppetEntity prop : session.props()) {
                player.sendMessage("{gray}  #" + (++i) + " {white}"
                        + prop.getEntityType().canonicalName() + " {dark_gray}"
                        + coords(prop.getLocation()));
            }
            return;
        }
        List<String> saved = server.getScenes().names();
        player.sendMessage(saved.isEmpty()
                ? "{gray}No saved scenes. {white}/pose new <name>{gray} starts one."
                : "{gold}Saved scenes: {white}" + String.join(", ", saved));
    }

    private void save(JedrockServer server, Player player) {
        Session session = require(player);
        if (session == null) {
            return;
        }
        if (session.props().isEmpty()) {
            player.sendMessage("{yellow}Nothing to save — an empty scene is just a name.");
            return;
        }
        server.getScenes().save(session.name(), session.props());
        sessions.remove(player.getUniqueId());
        player.sendMessage("{green}Saved {white}" + ChatText.escape(session.name()) + "{green} — "
                + session.props().size() + " prop(s).");
        // The props stay standing: they are already where they belong, and despawning them so the scene
        // could immediately respawn them would only make the world blink.
        player.sendMessage("{gray}It stands back up on every boot, with no plugin involved.");
    }

    private void cancel(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            player.sendMessage("{yellow}You are not posing anything.");
            return;
        }
        for (PuppetEntity prop : session.props()) {
            prop.remove();
        }
        player.sendMessage("{gray}Dropped " + session.props().size()
                + " prop(s); the world is as it was.");
    }

    private void help(Player player) {
        player.sendMessage("{gold}/pose {gray}— build a scene where you can see it");
        player.sendMessage("{white}  new <name>{gray} start · {white}save{gray} keep it · "
                + "{white}cancel{gray} throw it away");
        player.sendMessage("{white}  block <state> · item <state> · text <words> · mob <type>"
                + "{gray} — spawned where you stand");
        player.sendMessage("{white}  nudge <dx> <dy> <dz> · rotate <deg> · undo · list");
    }

    private Session require(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage("{red}Not posing anything. {white}/pose new <name>{red} to start.");
        }
        return session;
    }

    /**
     * A block, however anybody writes one: {@code red_wool}, {@code wool:14}, {@code 35:14} or
     * {@code 35}. The parsing lives in {@link com.jedrock.api.item.ItemNames} so a prop is named the
     * same way an item given by {@code /give} is; {@code null} here for what that refuses.
     */
    static Integer state(String value) {
        int state = com.jedrock.api.item.ItemNames.parse(value);
        return state <= 0 ? null : state;
    }

    private static EntityType entityType(String value) {
        String want = value.trim().toLowerCase(Locale.ROOT);
        for (EntityType type : EntityType.values()) {
            if (type.canonicalName().equalsIgnoreCase(want) || type.name().equalsIgnoreCase(want)) {
                return type;
            }
        }
        return null;
    }

    private static String coords(Location at) {
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", at.x(), at.y(), at.z());
    }
}
