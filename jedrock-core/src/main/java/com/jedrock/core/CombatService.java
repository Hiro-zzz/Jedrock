package com.jedrock.core;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.DamageCause;
import com.jedrock.api.event.player.PlayerDamageEvent;
import com.jedrock.api.event.player.PlayerDeathEvent;
import com.jedrock.api.event.player.PlayerInteractEntityEvent;
import com.jedrock.api.event.player.PlayerRespawnEvent;
import com.jedrock.api.event.player.PuppetInteractEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.world.Location;
import com.jedrock.core.entity.CorePuppet;
import com.jedrock.core.entity.EntityDirector;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerBroadcast;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.utils.text.ChatText;

/**
 * Getting hurt: the one path from any source of damage to a player's health bar.
 *
 * <p>Fall, void and melee all funnel into {@link #hurt}, so the damage event, the invulnerability
 * window, the red flash relayed to onlookers and the silent respawn are written once. Nothing here
 * simulates anything — the void sweep is a coarse periodic check rather than physics, a fall is
 * whatever the client's descent added up to, and knockback is deliberately absent.
 */
public final class CombatService {

    private final PlayerRegistry players;
    private final CoreWorld world;
    private final EventBus events;
    private final PlayerBroadcast broadcast;
    private final EntityDirector entities;
    private final BlindJudge judge;
    /** Lets a custom item in the attacker's hand answer for a hit; null in tests that don't wire one. */
    private com.jedrock.core.item.ItemRegistry items;

    public CombatService(PlayerRegistry players, CoreWorld world, EventBus events,
                         PlayerBroadcast broadcast, EntityDirector entities, BlindJudge judge) {
        this.players = players;
        this.world = world;
        this.events = events;
        this.broadcast = broadcast;
        this.entities = entities;
        this.judge = judge;
    }

    /** Wired after construction, since the item registry and combat are built at the same moment. */
    public void setItems(com.jedrock.core.item.ItemRegistry items) {
        this.items = items;
    }

    /** Lets an attacker's strength / weakness answer for a hit; null in tests that don't wire one. */
    private com.jedrock.core.effect.EffectService effects;

    /** Wired after construction — the two services know about each other and are built together. */
    public void setEffects(com.jedrock.core.effect.EffectService effects) {
        this.effects = effects;
    }

    /** Interval (ticks) between environmental-damage sweeps — vanilla applies void damage every 10 ticks. */
    private static final int ENVIRONMENT_TICK_INTERVAL = 10;
    /** Void damage per sweep, in half-hearts (vanilla is 4 = two hearts). */
    private static final int VOID_DAMAGE = 4;

    /**
     * Periodic environmental damage. Today: the void — a survival player who has dropped past the finite
     * world's floor takes damage until they die (silent respawn at spawn). Runs on the game-loop thread;
     * {@link #hurt} and its packet sends are thread-safe. Other sources (lava, suffocation) would slot in
     * here.
     */
    public void environmentTick(long currentTick) {
        if (currentTick % ENVIRONMENT_TICK_INTERVAL != 0) {
            return;
        }
        for (CorePlayer player : players.online()) {
            if (player.getGameMode() == GameMode.SURVIVAL
                    && player.getCoreWorld().isInVoid(player.getLocation().y())) {
                hurt(player, VOID_DAMAGE, DamageCause.VOID,
                        "{gray}" + ChatText.escape(player.getName()) + " fell out of the world");
            }
        }
    }

    public void onFall(PlayerConnection connection, float fallDistance) {
        // Bedrock 1.1.5 reports its own falls (EntityFall) — apply the damage from the client's report.
        CorePlayer player = players.getByConnectionOrNull(connection);
        if (player != null) {
            applyFallDamage(player, fallDistance);
        }
    }

    /**
     * Turn a fall distance into vanilla fall damage (one point per block past the first three) and apply
     * it via {@link #hurt}. Shared by the Bedrock client's {@code EntityFall} report and the server-side
     * fall tracking used by editions with no fall-report packet (Java, PE 0.14).
     */
    public void applyFallDamage(CorePlayer player, double fallDistance) {
        int damage = (int) Math.floor(fallDistance) - 3;
        hurt(player, damage, DamageCause.FALL,
                "{gray}" + ChatText.escape(player.getName()) + " fell to their death");
    }

    /**
     * Apply {@code amount} half-hearts of damage to a survival player from any source (fall, void, and
     * later PvP), push the new health to their HUD, and handle death. Death is deliberately primitive —
     * a silent respawn at spawn with full health, no death-screen handshake (a kept feature) — and
     * broadcasts {@code deathMessage}. A no-op outside survival or for a non-positive {@code amount}.
     */
    private void hurt(CorePlayer player, int amount, DamageCause cause, String deathMessage) {
        if (player.getGameMode() != GameMode.SURVIVAL || amount <= 0) {
            return;
        }
        // Worn armor gets its say first: protection softens everything, feather falling only a fall.
        // Read straight off the worn pieces rather than through an event, because armor is not a
        // listener's opinion — it is the state of the player being hit.
        amount = softenedByArmor(player, amount, cause);
        if (amount <= 0) {
            return;
        }
        // Let listeners veto or rescale the damage before it lands (invulnerability, a difficulty tweak).
        // Zeroing the amount is the same as cancelling.
        if (events.hasListeners(PlayerDamageEvent.class)) {
            PlayerDamageEvent event = events.post(new PlayerDamageEvent(player, cause, amount));
            if (event.isCancelled() || event.getAmount() <= 0) {
                return;
            }
            amount = event.getAmount();
        }
        // Show the hit to everyone else: the victim's avatar flashes red (its own client shows the hit
        // from its dropping health bar, so it doesn't need this). Covers every source — PvP, fall, void.
        broadcast.hurtAnimation(player);
        PlayerConnection connection = player.getConnection();
        if (player.damage(amount) <= 0) {
            // Death: a listener may restyle or suppress the announcement (null / empty = no broadcast).
            String message = deathMessage;
            if (events.hasListeners(PlayerDeathEvent.class)) {
                message = events.post(new PlayerDeathEvent(player, cause, deathMessage)).getDeathMessage();
            }
            player.setHealth(CorePlayer.MAX_HEALTH); // clamps + refreshes the client's health HUD
            // Where they respawn — the spawn of the world they died in by default (dying in the nether
            // does not silently move you home), but a listener may redirect it (a bed, a lobby).
            Location respawn = player.getWorld().getSpawnLocation();
            if (events.hasListeners(PlayerRespawnEvent.class)) {
                respawn = events.post(new PlayerRespawnEvent(player, respawn)).getRespawnLocation();
            }
            broadcast.teleport(player, respawn); // resets fall tracking too; not the eventful teleport (uncancellable)
            if (message != null && !message.isEmpty()) {
                broadcast.message(message, null);
            }
        } else {
            connection.setHealth(player.getHealth());
        }
    }

    /**
     * What worn armor takes off a hit. Vanilla's own arithmetic, kept coarse: each level of protection is
     * worth 4% off, capped at 80% so armour never makes anybody untouchable, and feather falling adds to
     * it on a fall alone. Nothing here simulates armour <em>points</em> — this server has no armour value,
     * only the enchantments people put on the pieces.
     */
    private int softenedByArmor(CorePlayer player, int amount, DamageCause cause) {
        int protection = player.armorEnchantmentLevel(com.jedrock.api.item.Enchantment.PROTECTION);
        if (cause == DamageCause.FALL) {
            protection += player.armorEnchantmentLevel(
                    com.jedrock.api.item.Enchantment.FEATHER_FALLING);
        }
        if (protection <= 0) {
            return amount;
        }
        double kept = Math.max(0.20, 1.0 - 0.04 * protection);
        // Round down, but never all the way to nothing: a hit that landed still lands.
        return Math.max(1, (int) Math.floor(amount * kept));
    }

    /** Bare-hand melee damage, in half-hearts (vanilla is 2 = one heart). */
    private static final int ATTACK_DAMAGE = 2;

    public void onAttack(PlayerConnection connection, long targetEntityId) {
        CorePlayer attacker = players.getByConnectionOrNull(connection);
        if (attacker == null) {
            return;
        }
        // Let listeners veto the interaction before anything acts on it — no damage, no puppet callback.
        if (events.hasListeners(PlayerInteractEntityEvent.class)
                && events.post(new PlayerInteractEntityEvent(attacker, targetEntityId)).isCancelled()) {
            return;
        }
        CorePlayer victim = players.getByEntityIdOrNull(targetEntityId);
        if (victim == null) {
            // Not a player — maybe a puppet. Fire its interaction hook (the seam the API subscribes to)
            // and, as a visible demo, flash the puppet red on every client. A puppet has no health/damage.
            CorePuppet puppet = entities.getPuppet(targetEntityId);
            if (puppet != null) {
                // The resolved counterpart of the event above: that one carries an entity id and fires for
                // players too, so a script watching puppets it did not spawn had nothing to hook. Cancelling
                // this stops the puppet's own callback, which is how one script overrules another's NPC.
                if (events.hasListeners(PuppetInteractEvent.class)
                        && events.post(new PuppetInteractEvent(attacker, puppet)).isCancelled()) {
                    return;
                }
                puppet.fireInteract(attacker);
                entities.relayHurt(puppet);
            }
            return;
        }
        if (victim == attacker) {
            return; // a self-hit — nothing to do
        }
        // Reach check (the blind judge): reject a hit from implausibly far — a reach hack — measured to
        // the victim's cell. The attacker's own arm swing is relayed separately via onSwingArm.
        Location a = attacker.getLocation();
        Location v = victim.getLocation();
        if (!judge.allowsInteraction(a.x(), a.y(), a.z(),
                (int) Math.floor(v.x()), (int) Math.floor(v.y()), (int) Math.floor(v.z()))) {
            return;
        }
        // Invulnerability frames: drop a hit landing inside the victim's half-second window, so a
        // click-spamming attacker can't deal damage faster than vanilla.
        if (victim.isOnHurtCooldown()) {
            return;
        }
        // A custom item in the attacker's hand gets to answer for the hit. Called straight rather than
        // through a listener because no event carries both sides — PlayerDamageEvent knows who was hurt,
        // not who did it. Returning true means the item handled it, so no ordinary damage follows.
        if (items != null && items.onHit(attacker, victim)) {
            return;
        }
        // Strength and weakness belong to whoever is swinging, and no damage event carries that — the
        // event knows who was hurt, not who did it — so this is the one place they can be applied. What
        // the victim is under (resistance) is handled on the event, where it belongs.
        int damage = ATTACK_DAMAGE + sharpnessBonus(attacker);
        damage = effects == null ? damage : effects.scaleOutgoing(attacker, damage);
        if (damage <= 0) {
            return; // weakness can take a bare-handed hit down to nothing, as it does in vanilla
        }
        hurt(victim, damage, DamageCause.ATTACK, "{gray}" + ChatText.escape(victim.getName())
                + " was slain by " + ChatText.escape(attacker.getName()));

        // Thorns answers back. Applied after the hit rather than instead of it, and through the same
        // funnel, so the attacker's own armour and effects get their say on the way in — and so a thorns
        // kill reads like any other death.
        int thorns = victim.armorEnchantmentLevel(com.jedrock.api.item.Enchantment.THORNS);
        if (thorns > 0) {
            hurt(attacker, Math.max(1, thorns / 2), DamageCause.MAGIC,
                    "{gray}" + ChatText.escape(attacker.getName()) + " was killed trying to hurt "
                            + ChatText.escape(victim.getName()));
        }
    }

    /**
     * What the weapon in the attacker's hand adds. Sharpness, smite and bane of arthropods are all read
     * here and all worth the same, because the two that discriminate have nothing to discriminate
     * against: every target in this server is a player, and puppets take no damage at all.
     */
    private static int sharpnessBonus(CorePlayer attacker) {
        com.jedrock.api.item.Enchantments held = attacker.getHeldEnchantments();
        int level = Math.max(held.level(com.jedrock.api.item.Enchantment.SHARPNESS),
                Math.max(held.level(com.jedrock.api.item.Enchantment.SMITE),
                        held.level(com.jedrock.api.item.Enchantment.BANE_OF_ARTHROPODS)));
        return level;   // vanilla's sharpness is roughly a half-heart a level; one point is exactly that
    }

    /**
     * Kill a survival player through the normal damage path — a silent respawn at spawn with a death
     * message, exactly like a lethal fall. A no-op in creative (which takes no damage); the caller is
     * expected to tell the player so.
     *
     * @return {@code true} if the player was killed (survival), {@code false} if immune (creative)
     */
    public boolean kill(CorePlayer player) {
        if (player.getGameMode() != GameMode.SURVIVAL) {
            return false;
        }
        hurt(player, CorePlayer.MAX_HEALTH, DamageCause.KILL,
                "{gray}" + ChatText.escape(player.getName()) + " died");
        return true;
    }

    /**
     * Heal a survival player by {@code points} half-hearts, clamped at full. What instant health does —
     * the partial counterpart of {@link #heal}, which restores everything.
     */
    public void healBy(CorePlayer player, int points) {
        if (player.getGameMode() != GameMode.SURVIVAL || points <= 0) {
            return;
        }
        player.setHealth(Math.min(CorePlayer.MAX_HEALTH, player.getHealth() + points));
    }

    /**
     * Hurt a survival player by {@code points} half-hearts from an effect (instant damage). Goes through
     * the same {@link #hurt} path as everything else, so it fires the damage event, flashes the avatar
     * and can kill — an effect is not a special kind of damage, only a different source of it.
     */
    public void hurtByEffect(CorePlayer player, int points) {
        hurt(player, points, DamageCause.MAGIC,
                "{gray}" + ChatText.escape(player.getName()) + " was killed by magic");
    }

    /**
     * Restore a survival player to full health and push it to their HUD. A no-op in creative (which has
     * no health to restore); the caller is expected to say so.
     *
     * @return {@code true} if the player was healed (survival), {@code false} if not applicable (creative)
     */
    public boolean heal(CorePlayer player) {
        if (player.getGameMode() != GameMode.SURVIVAL) {
            return false;
        }
        player.setHealth(CorePlayer.MAX_HEALTH); // clamps + refreshes the client's health HUD
        return true;
    }
}
