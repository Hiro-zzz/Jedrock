package com.jedrock.core.plugin;

import com.jedrock.api.player.ArmorSlot;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;

import java.util.List;
import java.util.UUID;

/**
 * The {@code player} object a script sees. Every method here is one a plugin is <em>meant</em> to call;
 * nothing else on the real player object is reachable from JavaScript.
 *
 * <p>That last sentence is the whole point of this class. Rhino reflects an object's <b>runtime</b> class,
 * not the interface it was declared as — a fact confirmed by experiment, since Rhino's {@code staticType}
 * only narrows the surface when reflection outright fails. So handing a script the core's own player
 * object handed it every public method that object happened to have, and the {@code api} module — whose
 * entire job is to be the contract — described none of it. A script could reach the connection and write
 * raw packets to it; renaming an internal method would silently break plugins, with nothing to compile
 * against and no test to fail.
 *
 * <p>So the contract is a class now, not a promise. Anything a script may do to a player is a method
 * here, delegating to the api {@link Player}; anything absent is absent by decision. The names match the
 * api exactly, so a plugin written against the old surface keeps working — the one thing deliberately
 * dropped is {@code getConnection()}, the door into the network layer, replaced by {@link #getVersion()}
 * for the one thing scripts used it for.
 *
 * <p>Instances are handed out by {@link ScriptWrapFactory}, which substitutes one wherever a player
 * crosses into JavaScript — a global, an event getter, a command argument, a nearest-player query — and
 * caches them per player, so {@code e.getPlayer() === somePlayer} holds the way a script expects.
 */
public final class ScriptPlayer {

    private final Player player;
    /** The way to the server-wide services a player method needs (effects); null in a bare test wrap. */
    private final PluginManager manager;

    ScriptPlayer(Player player) {
        this(player, null);
    }

    ScriptPlayer(Player player, PluginManager manager) {
        this.player = player;
        this.manager = manager;
    }

    /** The wrapped player, for the core's own use — not reachable from a script. */
    Player unwrap() {
        return player;
    }

    // ===== Identity =====

    public UUID getUniqueId() {
        return player.getUniqueId();
    }

    /** The player's real, client-supplied name. */
    public String getName() {
        return player.getName();
    }

    /** The name shown in chat — a script-set nickname if there is one, else the real name. */
    public String getDisplayName() {
        return player.getDisplayName();
    }

    public void setDisplayName(String displayName) {
        player.setDisplayName(displayName);
    }

    /** The chat prefix from the player's permission group, or an empty string. */
    public String getPrefix() {
        return player.getPrefix();
    }

    /** The id this player's avatar carries on the wire — what a packet tap sees. */
    public long getEntityId() {
        return player.getEntityId();
    }

    /** Which edition and version this player is on, e.g. {@code "1.12.2"} or {@code "0.14"}. */
    public String getVersion() {
        return player.getConnection().getProtocolVersion().getVersionName();
    }

    public String getAddress() {
        return player.getAddress();
    }

    /** Round-trip latency in milliseconds, or -1 while unknown. */
    public int getPing() {
        return player.getPing();
    }

    public boolean isOnline() {
        return player.isOnline();
    }

    // ===== Permissions =====

    public boolean isOp() {
        return player.isOp();
    }

    public boolean hasPermission(String node) {
        return player.hasPermission(node);
    }

    // ===== Position =====

    public World getWorld() {
        return player.getWorld();   // the factory wraps it as the script world
    }

    public Location getLocation() {
        return player.getLocation();
    }

    public double getX() {
        return player.getLocation().x();
    }

    public double getY() {
        return player.getLocation().y();
    }

    public double getZ() {
        return player.getLocation().z();
    }

    public void teleport(Location location) {
        player.teleport(location);
    }

    public void teleport(double x, double y, double z) {
        Location at = player.getLocation();
        player.teleport(new Location(player.getWorld(), x, y, z, at.yaw(), at.pitch()));
    }

    public void teleport(double x, double y, double z, double yaw, double pitch) {
        player.teleport(new Location(player.getWorld(), x, y, z, (float) yaw, (float) pitch));
    }

    // ===== State =====

    public GameMode getGameMode() {
        return player.getGameMode();
    }

    public void setGameMode(GameMode mode) {
        player.setGameMode(mode);
    }

    /** As {@link #setGameMode(GameMode)} by name: {@code 'survival'}, {@code 'creative'}, … */
    public void setGameMode(String mode) {
        GameMode parsed = GameMode.fromString(mode);
        if (parsed != null) {
            player.setGameMode(parsed);
        }
    }

    public int getHealth() {
        return player.getHealth();
    }

    public int getMaxHealth() {
        return player.getMaxHealth();
    }

    public void setHealth(int health) {
        player.setHealth(health);
    }

    public boolean isSneaking() {
        return player.isSneaking();
    }

    public boolean isSprinting() {
        return player.isSprinting();
    }

    public boolean isUsingItem() {
        return player.isUsingItem();
    }

    public void kick(String reason) {
        player.kick(reason);
    }

    // ===== Status effects =====
    //
    // Mostly scenery: the client draws and applies these itself, and the server keeps only what it needs
    // its own answers from. Levels are written the way a person says them — 1 is Speed I — and converted
    // to the wire's zero-based amplifier here rather than in a script author's head.

    /**
     * Put this player under an effect: {@code player.addEffect('speed', 30, 2)} is Speed II for thirty
     * seconds. The level defaults to 1 and the duration to 30 seconds. Fires {@code PlayerEffect}, so
     * another plugin may refuse or rescale it.
     *
     * @return whether it landed
     */
    public boolean addEffect(String effect, int seconds, int level) {
        com.jedrock.api.entity.Effect kind = requireEffect(effect);
        com.jedrock.core.effect.EffectService effects = effects();
        return effects != null && player instanceof com.jedrock.core.player.CorePlayer core
                && effects.apply(core, kind, Math.max(1, level) - 1, Math.max(0, seconds), true);
    }

    public boolean addEffect(String effect, int seconds) {
        return addEffect(effect, seconds, 1);
    }

    public boolean addEffect(String effect) {
        return addEffect(effect, 30, 1);
    }

    /** Take one off. @return whether they were under it. */
    public boolean removeEffect(String effect) {
        com.jedrock.api.entity.Effect kind = requireEffect(effect);
        com.jedrock.core.effect.EffectService effects = effects();
        return effects != null && player instanceof com.jedrock.core.player.CorePlayer core
                && effects.remove(core, kind);
    }

    /** Whether they are under it right now. */
    public boolean hasEffect(String effect) {
        return getEffectLevel(effect) > 0;
    }

    /** The level they are under, the way a person counts it (1 = Speed I), or {@code 0} for none. */
    public int getEffectLevel(String effect) {
        com.jedrock.api.entity.Effect kind = requireEffect(effect);
        com.jedrock.core.effect.EffectService effects = effects();
        if (effects == null || !(player instanceof com.jedrock.core.player.CorePlayer core)) {
            return 0;
        }
        int amplifier = effects.amplifierOf(core, kind);
        return amplifier < 0 ? 0 : amplifier + 1;
    }

    /** How many seconds are left of it, or {@code 0} if they aren't under it. */
    public int getEffectSeconds(String effect) {
        com.jedrock.api.entity.Effect kind = requireEffect(effect);
        com.jedrock.core.effect.EffectService effects = effects();
        if (effects == null || !(player instanceof com.jedrock.core.player.CorePlayer core)) {
            return 0;
        }
        com.jedrock.core.effect.ActiveEffect active = effects.active(core).get(kind);
        return active == null ? 0 : active.remainingSeconds(System.currentTimeMillis());
    }

    /** The names of everything they are under right now. */
    public String[] getEffects() {
        com.jedrock.core.effect.EffectService effects = effects();
        if (effects == null || !(player instanceof com.jedrock.core.player.CorePlayer core)) {
            return new String[0];
        }
        return effects.active(core).keySet().stream()
                .map(com.jedrock.api.entity.Effect::getKey)
                .toArray(String[]::new);
    }

    /** Take everything off. @return how many there were. */
    public int clearEffects() {
        com.jedrock.core.effect.EffectService effects = effects();
        return effects != null && player instanceof com.jedrock.core.player.CorePlayer core
                ? effects.clear(core) : 0;
    }

    /** An effect by name, or a thrown error a script can actually read. */
    private static com.jedrock.api.entity.Effect requireEffect(String name) {
        com.jedrock.api.entity.Effect effect = com.jedrock.api.entity.Effect.fromString(name);
        if (effect == null) {
            throw new IllegalArgumentException("no such effect: '" + name + "'");
        }
        return effect;
    }

    private com.jedrock.core.effect.EffectService effects() {
        return manager == null ? null : manager.effects();
    }

    // ===== Inventory =====

    public int getInventorySize() {
        return player.getInventorySize();
    }

    public int getItem(int slot) {
        return player.getItem(slot);
    }

    public int getItemCount(int slot) {
        return player.getItemCount(slot);
    }

    public void setItem(int slot, int state, int count) {
        player.setItem(slot, state, count);
    }

    public boolean giveItem(int state) {
        return player.giveItem(state);
    }

    public int giveItem(int state, int count) {
        return player.giveItem(state, count);
    }

    public int removeItem(int state, int count) {
        return player.removeItem(state, count);
    }

    public int countItem(int state) {
        return player.countItem(state);
    }

    public boolean hasItem(int state) {
        return player.hasItem(state);
    }

    public void clearInventory() {
        player.clearInventory();
    }

    public int getHeldItem() {
        return player.getHeldItem();
    }

    public int getHeldItemSlot() {
        return player.getHeldItemSlot();
    }

    // ===== Armor =====

    public int getArmor(ArmorSlot slot) {
        return player.getArmor(slot);
    }

    /** As {@link #getArmor(ArmorSlot)} by name: {@code 'helmet'} / {@code 'chestplate'} / … */
    public int getArmor(String slot) {
        ArmorSlot parsed = armorSlot(slot);
        return parsed == null ? 0 : player.getArmor(parsed);
    }

    public void setArmor(ArmorSlot slot, int state) {
        player.setArmor(slot, state);
    }

    /** As {@link #setArmor(ArmorSlot, int)} by name — the spelling {@code entity.setArmor} already takes. */
    public void setArmor(String slot, int state) {
        ArmorSlot parsed = armorSlot(slot);
        if (parsed != null) {
            player.setArmor(parsed, state);
        }
    }

    public void clearArmor() {
        player.clearArmor();
    }

    private static ArmorSlot armorSlot(String name) {
        if (name == null) {
            return null;
        }
        for (ArmorSlot slot : ArmorSlot.values()) {
            if (slot.name().equalsIgnoreCase(name)) {
                return slot;
            }
        }
        return null;
    }

    // ===== What the player sees =====

    public void sendMessage(String message) {
        player.sendMessage(message);
    }

    public void sendTitle(String title, String subtitle) {
        player.sendTitle(title, subtitle, 10, 70, 20);
    }

    public void sendTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
    }

    public void sendActionBar(String text) {
        player.sendActionBar(text);
    }

    public void clearTitle() {
        player.clearTitle();
    }

    public void setSidebar(String title, List<String> lines) {
        player.setSidebar(title, lines);
    }

    public void clearSidebar() {
        player.clearSidebar();
    }

    public void setBossBar(String title, double progress) {
        player.setBossBar(title, (float) progress, null);
    }

    public void setBossBar(String title, double progress, String color) {
        player.setBossBar(title, (float) progress, color);
    }

    public void clearBossBar() {
        player.clearBossBar();
    }

    /** Play a sound to this player alone (a private ding), by name — see the {@code Sound} enum. */
    public void playSound(String sound) {
        playSound(sound, 1.0, 1.0);
    }

    public void playSound(String sound, double volume, double pitch) {
        player.playSound(ScriptWorld.parse(com.jedrock.api.world.Sound.class, sound),
                (float) volume, (float) pitch);
    }

    @Override
    public String toString() {
        return "Player(" + player.getName() + ")";
    }
}
