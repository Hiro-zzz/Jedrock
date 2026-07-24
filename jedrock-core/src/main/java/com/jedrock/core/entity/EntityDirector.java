package com.jedrock.core.entity;

import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.Hologram;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.player.ArmorSlot;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Location;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.text.ChatText;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The puppeteer: everything the server shows that isn't a player or a block.
 *
 * <p>Owns the live rosters of {@linkplain CorePuppet puppets} (mobs, NPCs, item / block props, text
 * labels) and {@linkplain CoreHologram holograms}, and is the single seam through which their visual
 * state reaches clients — spawning one relays it to everyone online, changing one relays the change,
 * and a joining player is caught up through {@link #showAllTo}. Nothing here is ever simulated: a
 * puppet moves only because something told it to.
 *
 * <p>The entities themselves hold a back-reference to this director rather than to the server, so the
 * relay path is the only thing they know about the outside world.
 */
public final class EntityDirector {

    private static final JLogger LOGGER = JLogger.getLogger(EntityDirector.class);

    private final PlayerRegistry players;
    private final CoreWorld world;
    private final PuppetRegistry puppets = new PuppetRegistry();
    /** Live holograms. Iterated on every join and mutated rarely, so a copy-on-write list fits. */
    private final List<CoreHologram> holograms = new CopyOnWriteArrayList<>();

    public EntityDirector(PlayerRegistry players, CoreWorld world) {
        this.players = players;
        this.world = world;
    }

    // ===== Puppets (server-puppeteered visual entities) =====

    public PuppetEntity spawnPuppet(EntityType type, Location at, String name) {
        return register(new CorePuppet(type, name, world, at, this), at);
    }

    public PuppetEntity spawnItem(Location at, int state) {
        return register(new CorePuppet(EntityType.ITEM, EntityType.ITEM.canonicalName(),
                world, at, this, state), at);
    }

    public PuppetEntity spawnFallingBlock(Location at, int state) {
        return register(new CorePuppet(EntityType.FALLING_BLOCK, EntityType.FALLING_BLOCK.canonicalName(),
                world, at, this, state), at);
    }

    public PuppetEntity spawnText(Location at, String text) {
        CorePuppet puppet = new CorePuppet(EntityType.TEXT, EntityType.TEXT.canonicalName(),
                world, at, this, 0);
        puppet.initNameTag(text); // the text must be set before the spawn relay carries it out
        return register(puppet, at);
    }

    /** Add a freshly built puppet to the roster and show it to everyone online (joiners get it in showAllTo). */
    private PuppetEntity register(CorePuppet puppet, Location at) {
        puppets.add(puppet);
        for (CorePlayer p : players.online()) {
            spawnPuppetTo(p.getConnection(), puppet);
        }
        LOGGER.info("Spawned puppet " + puppet.getEntityType().canonicalName() + " #" + puppet.getEntityId()
                + " at " + String.format(Locale.ROOT, "%.1f,%.1f,%.1f", at.x(), at.y(), at.z()));
        return puppet;
    }

    /**
     * Catch a joining connection up on everything already standing in the world — every puppet and
     * every hologram line. Existing players were shown each one as it spawned.
     */
    public void showAllTo(PlayerConnection connection) {
        for (CorePuppet puppet : puppets.all()) {
            spawnPuppetTo(connection, puppet);
        }
        for (CoreHologram hologram : holograms) {
            spawnHologramTo(connection, hologram);
        }
    }

    /**
     * Show one puppet to one connection. A {@link EntityType#PLAYER} puppet renders through the player-avatar
     * path (a tab / player-list entry + spawn-player), reusing exactly what real players use; a mob puppet
     * uses the spawn-entity path. Shared by the spawn broadcast and the join-time catch-up.
     */
    private void spawnPuppetTo(PlayerConnection conn, CorePuppet puppet) {
        Location loc = puppet.getLocation();
        if (puppet.getEntityType().isPlayer()) {
            conn.addToTab(puppet.getUniqueId(), puppet.getName()); // JE needs the tab entry before the avatar
            conn.showPlayer(puppet.getUniqueId(), puppet.getName(), puppet.getEntityId(),
                    loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch());
            return;
        }
        if (puppet.getEntityType().isItem()) {
            // A prop's body IS the item, so it spawns through its own packet on every edition.
            conn.spawnItemEntity(puppet.getEntityId(), puppet.getUniqueId(),
                    loc.x(), loc.y(), loc.z(), puppet.getItemState());
        } else if (puppet.getEntityType().isFallingBlock()) {
            conn.spawnFallingBlock(puppet.getEntityId(), puppet.getUniqueId(),
                    loc.x(), loc.y(), loc.z(), puppet.getItemState());
        } else if (puppet.getEntityType().isText()) {
            // A label's whole body is its text, so it spawns through the hologram-line path with the
            // name tag as the content — and the catch-up below would only re-send the same string.
            conn.spawnTextLine(puppet.getEntityId(), puppet.getUniqueId(),
                    loc.x(), loc.y(), loc.z(), ChatText.toLegacy(puppet.getNameTag()));
            return;
        } else {
            conn.spawnEntity(puppet.getEntityId(), puppet.getUniqueId(), puppet.getEntityType(),
                    loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch());
        }
        // Catch the newcomer up on the look the puppet has accumulated since it spawned.
        if (puppet.getFlags() != 0) {
            conn.setEntityFlags(puppet.getEntityId(), puppet.getFlags());
        }
        String nameTag = puppet.getNameTag();
        if (nameTag != null && !nameTag.isEmpty()) {
            conn.setEntityNameTag(puppet.getEntityId(), ChatText.toLegacy(nameTag));
        }
        // …and whatever it carries, so a newcomer sees a dressed statue rather than a bare mob.
        if (puppet.getHeldItem() != Blocks.AIR) {
            conn.showHeldItem(puppet.getEntityId(), puppet.getHeldItem());
        }
        if (puppet.hasArmor()) {
            showPuppetArmorTo(conn, puppet);
        }
    }

    /** Relay a puppet's floating text to every viewer. Called by {@link CorePuppet#setNameTag}. */
    public void relayNameTag(CorePuppet puppet) {
        if (puppet.getEntityType().isPlayer()) {
            return; // a player avatar's name is its player name on every edition — nothing to set
        }
        String nameTag = puppet.getNameTag();
        String rendered = nameTag == null ? "" : ChatText.toLegacy(nameTag);
        long entityId = puppet.getEntityId();
        for (CorePlayer p : players.online()) {
            p.getConnection().setEntityNameTag(entityId, rendered);
        }
    }

    /** Relay a puppet's whole flag set to every viewer. Called by {@link CorePuppet#setFlag}. */
    public void relayFlags(CorePuppet puppet) {
        long entityId = puppet.getEntityId();
        int flags = puppet.getFlags();
        for (CorePlayer p : players.online()) {
            p.getConnection().setEntityFlags(entityId, flags);
        }
    }

    /** Relay a puppet's arm swing to every viewer. Called by {@link CorePuppet#swing}. */
    public void relaySwing(CorePuppet puppet) {
        long entityId = puppet.getEntityId();
        for (CorePlayer p : players.online()) {
            p.getConnection().swingArm(entityId);
        }
    }

    /** Relay what a puppet holds to every viewer. Called by {@link CorePuppet#setHeldItem}. */
    public void relayHeldItem(CorePuppet puppet) {
        long entityId = puppet.getEntityId();
        int state = puppet.getHeldItem();
        for (CorePlayer p : players.online()) {
            p.getConnection().showHeldItem(entityId, state);
        }
    }

    /** Relay a puppet's worn armor to every viewer. Called by {@link CorePuppet#setArmor}. */
    public void relayArmor(CorePuppet puppet) {
        for (CorePlayer p : players.online()) {
            showPuppetArmorTo(p.getConnection(), puppet);
        }
    }

    /** Dress one puppet on one connection (the relay and the join-time catch-up share this). */
    private static void showPuppetArmorTo(PlayerConnection conn, CorePuppet puppet) {
        conn.showArmor(puppet.getEntityId(),
                puppet.getArmor(ArmorSlot.HELMET), puppet.getArmor(ArmorSlot.CHESTPLATE),
                puppet.getArmor(ArmorSlot.LEGGINGS), puppet.getArmor(ArmorSlot.BOOTS));
    }

    /** Relay a puppet's hurt flash to every viewer. Called by {@link CorePuppet#hurt}. */
    public void relayHurt(CorePuppet puppet) {
        long entityId = puppet.getEntityId();
        for (CorePlayer p : players.online()) {
            p.getConnection().playHurtAnimation(entityId);
        }
    }

    /** Relay a puppet's move to every viewer. Called by {@link CorePuppet#teleport}. */
    public void movePuppet(CorePuppet puppet, Location to) {
        boolean asPlayer = puppet.getEntityType().isPlayer();
        long entityId = puppet.getEntityId();
        for (CorePlayer p : players.online()) {
            if (asPlayer) {
                p.getConnection().moveAvatar(entityId, to.x(), to.y(), to.z(), to.yaw(), to.pitch());
            } else {
                p.getConnection().moveEntity(entityId, to.x(), to.y(), to.z(), to.yaw(), to.pitch());
            }
        }
    }

    /** Despawn a puppet from every viewer and drop it from the registry. Called by {@link CorePuppet#remove}. */
    public void removePuppet(CorePuppet puppet) {
        if (puppets.remove(puppet.getEntityId()) == null) {
            return; // already gone
        }
        boolean asPlayer = puppet.getEntityType().isPlayer();
        for (CorePlayer p : players.online()) {
            if (asPlayer) {
                p.getConnection().hidePlayer(puppet.getUniqueId(), puppet.getEntityId());
                p.getConnection().removeFromTab(puppet.getUniqueId());
            } else {
                p.getConnection().removeEntity(puppet.getEntityId());
            }
        }
    }

    /** The live puppet roster — used by the {@code /puppet} command to list / resolve puppets. */
    public PuppetRegistry getPuppets() {
        return puppets;
    }

    /** The puppet with this entity id, or {@code null} — how an attack resolves its target. */
    public CorePuppet getPuppet(long entityId) {
        return puppets.get(entityId);
    }

    // ===== Holograms (floating text) =====

    public Hologram spawnHologram(Location at, String... lines) {
        CoreHologram hologram = new CoreHologram(world, at, this, lines);
        holograms.add(hologram);
        for (CorePlayer p : players.online()) {
            spawnHologramTo(p.getConnection(), hologram);
        }
        LOGGER.info("Spawned hologram #" + hologram.getEntityId() + " (" + lines.length + " lines) at "
                + String.format(Locale.ROOT, "%.1f,%.1f,%.1f", at.x(), at.y(), at.z()));
        return hologram;
    }

    /** Show every line of one hologram to one connection. Shared by the spawn broadcast and the catch-up. */
    private void spawnHologramTo(PlayerConnection conn, CoreHologram hologram) {
        List<String> lines = hologram.getLines();
        long[] ids = hologram.getLineIds();
        for (int i = 0; i < lines.size(); i++) {
            Location at = hologram.lineLocation(i);
            conn.spawnTextLine(ids[i], hologram.getUniqueId(), at.x(), at.y(), at.z(),
                    ChatText.toLegacy(lines.get(i)));
        }
    }

    /** Relay one re-texted hologram line. Called by {@link CoreHologram#setLine}. */
    public void relayHologramLine(CoreHologram hologram, int index) {
        long id = hologram.getLineIds()[index];
        String rendered = ChatText.toLegacy(hologram.getLines().get(index));
        for (CorePlayer p : players.online()) {
            p.getConnection().setEntityNameTag(id, rendered);
        }
    }

    /** Move a hologram's whole stack. Called by {@link CoreHologram#teleport}. */
    public void moveHologram(CoreHologram hologram) {
        long[] ids = hologram.getLineIds();
        for (int i = 0; i < ids.length; i++) {
            Location at = hologram.lineLocation(i);
            for (CorePlayer p : players.online()) {
                p.getConnection().moveEntity(ids[i], at.x(), at.y(), at.z(), 0f, 0f);
            }
        }
    }

    /** Despawn {@code oldIds} and re-show the hologram — its line count changed ({@link CoreHologram#setLines}). */
    public void respawnHologram(CoreHologram hologram, long[] oldIds) {
        for (CorePlayer p : players.online()) {
            for (long id : oldIds) {
                p.getConnection().removeEntity(id);
            }
            spawnHologramTo(p.getConnection(), hologram);
        }
    }

    /** Despawn every line and drop the hologram. Called by {@link CoreHologram#remove}. */
    public void removeHologram(CoreHologram hologram) {
        if (!holograms.remove(hologram)) {
            return; // already gone
        }
        for (CorePlayer p : players.online()) {
            for (long id : hologram.getLineIds()) {
                p.getConnection().removeEntity(id);
            }
        }
    }

    /** The live holograms — used by the {@code /puppet} command to list / resolve them. */
    public List<CoreHologram> getHolograms() {
        return holograms;
    }
}
