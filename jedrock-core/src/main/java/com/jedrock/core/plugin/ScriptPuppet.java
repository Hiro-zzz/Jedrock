package com.jedrock.core.plugin;

import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.entity.PuppetFlag;
import com.jedrock.api.player.ArmorSlot;
import com.jedrock.api.world.Location;
import org.mozilla.javascript.Function;

import java.util.Locale;

/**
 * A server-owned puppet as scripts see it — what {@code server.spawnPuppet(...)} hands back.
 *
 * <p>The plugin-owned counterpart is {@link ScriptEntity}, which this deliberately is not: an entity from
 * the {@code entities} global belongs to its plugin and dies with it, while a puppet spawned through the
 * server outlives a hot reload and answers to nothing but {@code remove()}. What they share is that
 * neither lets a script touch the implementation — see {@link ScriptWrapFactory} for why a wrapper, and
 * not the api interface, is what enforces that.
 *
 * <p>One thing does change with a reload, and must: the interaction callback. It is a function inside a
 * scope that is about to be thrown away, so it is dispatched through the plugin that registered it —
 * under the script lock, on a real Rhino context, and cleared when that plugin goes. Left as a bare
 * lambda on the puppet (which is what a raw {@code onInteract} was) it would keep firing into a dead
 * scope, off the script lock, for as long as the server ran.
 */
public final class ScriptPuppet {

    private final PluginManager manager;
    private final ScriptPlugin plugin;
    private final PuppetEntity puppet;

    ScriptPuppet(PluginManager manager, ScriptPlugin plugin, PuppetEntity puppet) {
        this.manager = manager;
        this.plugin = plugin;
        this.puppet = puppet;
    }

    // ===== Identity =====

    public long getEntityId() {
        return puppet.getEntityId();
    }

    /** The canonical type name, e.g. {@code 'zombie'} or {@code 'item'}. */
    public String getType() {
        return puppet.getType();
    }

    public boolean isAlive() {
        return puppet.isAlive();
    }

    // ===== Where it is =====

    public Location getLocation() {
        return puppet.getLocation();
    }

    public double getX() {
        return puppet.getLocation().x();
    }

    public double getY() {
        return puppet.getLocation().y();
    }

    public double getZ() {
        return puppet.getLocation().z();
    }

    public void teleport(Location to) {
        puppet.teleport(to);
    }

    public void moveTo(double x, double y, double z) {
        Location at = puppet.getLocation();
        puppet.teleport(new Location(at.world(), x, y, z, at.yaw(), at.pitch()));
    }

    /** Turn in place (degrees). */
    public void setRotation(double yaw, double pitch) {
        puppet.setRotation((float) yaw, (float) pitch);
    }

    /** Turn to face a point, a player or another entity — the cheapest illusion of attention there is. */
    public void lookAt(Object target) {
        Location at = ScriptEntity.locationOf(target);
        if (at != null) {
            puppet.lookAt(at);
        }
    }

    // ===== Looks =====

    /** Floating text above it, in the unified {@code {color}} markup; null or empty removes it. */
    public void setNameTag(String nameTag) {
        puppet.setNameTag(nameTag);
    }

    public String getNameTag() {
        return puppet.getNameTag();
    }

    public void setFlag(PuppetFlag flag, boolean on) {
        puppet.setFlag(flag, on);
    }

    /** As {@link #setFlag(PuppetFlag, boolean)} by name: {@code 'on_fire'}, {@code 'invisible'}, {@code 'sneaking'}. */
    public void setFlag(String flag, boolean on) {
        puppet.setFlag(parseFlag(flag), on);
    }

    public boolean hasFlag(PuppetFlag flag) {
        return puppet.hasFlag(flag);
    }

    public boolean hasFlag(String flag) {
        return puppet.hasFlag(parseFlag(flag));
    }

    public void setHeldItem(int state) {
        puppet.setHeldItem(state);
    }

    public int getHeldItem() {
        return puppet.getHeldItem();
    }

    public void setArmor(ArmorSlot slot, int state) {
        puppet.setArmor(slot, state);
    }

    /** As {@link #setArmor(ArmorSlot, int)} by name: {@code 'helmet'} / {@code 'chestplate'} / … */
    public void setArmor(String slot, int state) {
        puppet.setArmor(parseArmorSlot(slot), state);
    }

    public int getArmor(ArmorSlot slot) {
        return puppet.getArmor(slot);
    }

    public int getArmor(String slot) {
        return puppet.getArmor(parseArmorSlot(slot));
    }

    // ===== Acting =====

    public void swing() {
        puppet.swing();
    }

    /** Play the red damage flash — the look of being hit, with no health behind it. */
    public void hurt() {
        puppet.hurt();
    }

    /** Run {@code fn(player)} when someone hits this puppet. Passing {@code null} clears it. */
    public void onInteract(Function fn) {
        if (fn == null) {
            puppet.onInteract(null);
            return;
        }
        puppet.onInteract(player -> manager.callInteract(plugin, fn, player));
    }

    /** Take it out of the world. Its interaction callback goes with it. */
    public void remove() {
        puppet.remove();
    }

    private static PuppetFlag parseFlag(String name) {
        try {
            return PuppetFlag.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("unknown entity flag '" + name + "' — one of: "
                    + java.util.Arrays.toString(PuppetFlag.values()));
        }
    }

    private static ArmorSlot parseArmorSlot(String name) {
        try {
            return ArmorSlot.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("unknown armor slot '" + name + "' — one of: "
                    + java.util.Arrays.toString(ArmorSlot.values()));
        }
    }

    @Override
    public String toString() {
        return "Puppet(" + puppet.getType() + "#" + puppet.getEntityId() + ")";
    }
}
