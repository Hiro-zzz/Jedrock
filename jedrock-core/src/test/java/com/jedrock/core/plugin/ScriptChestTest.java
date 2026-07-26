package com.jedrock.core.plugin;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.command.CommandManager;
import com.jedrock.core.inventory.Container;
import com.jedrock.core.net.PacketTapRegistry;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.gameloop.Scheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A script can reach the chests players actually placed, not just the virtual ones {@code menus} makes.
 *
 * <p>The distinction that matters here is that a world chest is <em>shared</em>: the container a script
 * writes into is the same object a player's open window is bound to and the same one the level file
 * persists. So these pin both halves — the edit lands in the world's own container, and the world is
 * marked dirty so it survives a restart.
 */
class ScriptChestTest {

    private static final int CHEST = Blocks.state(Blocks.CHEST, 0);
    private static final int DIAMOND = Blocks.state(264, 0);

    private final CoreWorld world = new CoreWorld("chests", Dimension.OVERWORLD, 1L);

    private List<String> run(Path dir, String body) {
        EventBus bus = new EventBus();
        CommandManager cm = new CommandManager(null);
        // A server is what makes the `world` global exist at all — the chest API hangs off it.
        PluginManager plugins = new PluginManager(bus, new StubServer(world), new Scheduler(), cm,
                new PacketTapRegistry(), dir);
        plugins.loadSource("chest.js",
                "commands.register('probe', function (player, args) {\n" + body + "\n});", 1L);

        RecordingConnection conn = new RecordingConnection();
        CorePlayer player = new CorePlayer(UUID.randomUUID(), "P", conn, world,
                world.getSpawnLocation(), GameMode.SURVIVAL);
        cm.dispatch(player, "/probe");
        return conn.messages;
    }

    @Test
    void aChestIsOnlyThereWhenTheBlockIs(@TempDir Path dir) {
        world.setBlockId(4, 64, 4, CHEST);

        List<String> out = run(dir,
                "  player.sendMessage('none=' + (world.getChest(0, 64, 0) === null));\n"
              + "  player.sendMessage('has=' + world.hasChest(4, 64, 4));\n"
              + "  player.sendMessage('chest=' + (world.getChest(4, 64, 4) !== null));\n"
              + "  player.sendMessage('size=' + world.getChest(4, 64, 4).size());");

        assertEquals(List.of("none=true", "has=true", "chest=true", "size=27"), out,
                "a chest exists exactly where a chest block does — no phantom containers in mid-air");
    }

    @Test
    void writesLandInTheWorldsOwnContainer(@TempDir Path dir) {
        world.setBlockId(4, 64, 4, CHEST);

        List<String> out = run(dir,
                "  var chest = world.getChest(4, 64, 4);\n"
              + "  player.sendMessage('added=' + chest.add(" + DIAMOND + ", 5));\n"
              + "  player.sendMessage('count=' + chest.count(" + DIAMOND + "));\n"
              + "  player.sendMessage('slot0=' + chest.getItem(0) + 'x' + chest.getCount(0));\n"
              + "  player.sendMessage('removed=' + chest.remove(" + DIAMOND + ", 2));\n"
              + "  player.sendMessage('left=' + chest.count(" + DIAMOND + "));");

        assertEquals(List.of("added=5", "count=5", "slot0=" + DIAMOND + "x5", "removed=2", "left=3"), out);

        // The same container the rest of the server sees — not a copy the script edited in private.
        Container real = world.getChestContainer(4, 64, 4);
        assertEquals(DIAMOND, real.stateAt(0));
        assertEquals(3, real.countAt(0));
        assertTrue(world.isDirty(), "an edited chest has to be written out at the next save");
    }

    @Test
    void aFullChestReportsWhatActuallyFit(@TempDir Path dir) {
        world.setBlockId(4, 64, 4, CHEST);
        Container real = world.getChestContainer(4, 64, 4);
        for (int slot = 0; slot < real.size(); slot++) {
            real.set(slot, Blocks.state(Blocks.STONE, 0), 64);   // 27 slots of stone, no room to stack
        }

        List<String> out = run(dir,
                "  var chest = world.getChest(4, 64, 4);\n"
              + "  player.sendMessage('added=' + chest.add(" + DIAMOND + ", 4));\n"
              + "  player.sendMessage('empty=' + chest.isEmpty());\n"
              + "  chest.clear();\n"
              + "  player.sendMessage('cleared=' + chest.isEmpty());\n"
              + "  player.sendMessage('nowFits=' + chest.add(" + DIAMOND + ", 4));");

        assertEquals(List.of("added=0", "empty=false", "cleared=true", "nowFits=4"), out,
                "add returns what fit, so a script can tell a full chest from a successful drop");
    }

}
