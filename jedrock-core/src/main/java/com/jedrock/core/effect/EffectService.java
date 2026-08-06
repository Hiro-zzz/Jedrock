package com.jedrock.core.effect;

import com.jedrock.api.entity.Effect;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.EventPriority;
import com.jedrock.api.event.player.PlayerDamageEvent;
import com.jedrock.api.event.player.PlayerEffectEvent;
import com.jedrock.core.CombatService;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerTracker;
import com.jedrock.core.player.PlayerRegistry;

import java.util.EnumMap;
import java.util.Map;

/**
 * Who is under what, and for how long.
 *
 * <p>An effect is <b>scenery</b> in the same sense the weather and the clock are: the server says
 * "speed II for thirty seconds" once and the client draws the swirl, tints the screen and — because
 * movement here is client-authoritative — actually moves faster. Nothing on this side ticks to make any
 * of that happen. What is kept here is only what the server needs its own answers for: who has what, so
 * it can be taken away again, re-sent to a client that has just arrived somewhere, and consulted at the
 * two points where the core already owns a decision.
 *
 * <p>Those two points, and no others:
 * <ul>
 *   <li><b>Movement.</b> A sped-up player really does move further between reports, and the blind judge
 *       would call that a speed hack. The allowance is widened to match — which is not a feature so much
 *       as the bug that shipping speed without it would be.</li>
 *   <li><b>Damage.</b> Resistance and weakness scale what a hit does, through the very
 *       {@link PlayerDamageEvent} the core already routes every point of damage through; strength is
 *       applied where the attacker is known (see {@link CombatService}).</li>
 * </ul>
 *
 * <p>Everything else — night vision, nausea, water breathing, the particles — is the client's own
 * rendering and this class does nothing about it. <b>Poison, regeneration and wither are deliberately
 * cosmetic</b>: ticking a player's health would be exactly the server-side simulation this project
 * doesn't do.
 *
 * <p>Expiry costs nothing when nobody is affected. There is no sweep over the roster — a player's own
 * map is consulted when it is read, and the periodic pass visits only players who actually hold
 * something. Effects are not persisted, like the weather and the time.
 */
public final class EffectService {

    /** How often the expiry pass runs. A second's granularity is plenty for something a client animates. */
    private static final int EXPIRY_INTERVAL_TICKS = 20;

    /** The vanilla step per speed level, as a fraction of ordinary movement. */
    private static final double SPEED_PER_LEVEL = 0.20;
    /** The same for jump boost, which mostly buys height rather than distance. */
    private static final double JUMP_PER_LEVEL = 0.10;

    private final PlayerRegistry players;
    private final PlayerTracker tracker;
    private final EventBus events;

    public EffectService(PlayerRegistry players, PlayerTracker tracker, EventBus events) {
        this.players = players;
        this.tracker = tracker;
        this.events = events;
        // Resistance and weakness are applied by listening to the event the core already posts for every
        // point of damage, rather than by CombatService learning about effects. HIGH so a script at
        // HIGHEST still gets the last word, the way region enforcement works.
        events.register(PlayerDamageEvent.class, EventPriority.HIGH, event -> {
            if (!(event.getPlayer() instanceof CorePlayer player)) {
                return;
            }
            event.setAmount(scaleIncoming(player, event.getAmount()));
        });
    }

    /** Wired after construction: instant health and damage are a health change, which combat owns. */
    private CombatService combat;

    public void setCombat(CombatService combat) {
        this.combat = combat;
    }

    // ===== Applying =====

    /**
     * Put an effect on a player for {@code durationSeconds}, replacing any of the same kind. A listener
     * may refuse it, or change the level and the length before it lands.
     *
     * <p>An {@linkplain Effect#isInstant() instant} effect is applied rather than stored — it is a change
     * to a number, not a state to hold — and still sent, so the client draws it.
     *
     * @return {@code true} if it was applied, {@code false} if a listener cancelled it
     */
    public boolean apply(CorePlayer player, Effect effect, int amplifier, int durationSeconds,
                         boolean particles) {
        if (player == null || effect == null) {
            return false;
        }
        int level = Math.max(0, Math.min(255, amplifier));
        int seconds = Math.max(0, durationSeconds);

        if (events.hasListeners(PlayerEffectEvent.class)) {
            boolean reapplied = has(player, effect);
            PlayerEffectEvent event = events.post(
                    new PlayerEffectEvent(player, effect, level, seconds, reapplied));
            if (event.isCancelled()) {
                return false;
            }
            level = Math.max(0, event.getAmplifier());
            seconds = Math.max(0, event.getDurationSeconds());
        }

        if (effect.isInstant()) {
            applyInstant(player, effect, level);
            player.getConnection().sendEffect(effect, level, 1, particles);
            return true;
        }

        long expiry = System.currentTimeMillis() + seconds * 1000L;
        player.getEffects().put(effect, new ActiveEffect(effect, level, expiry, particles));
        player.getConnection().sendEffect(effect, level, seconds * 20, particles);
        if (effect == Effect.INVISIBILITY) {
            tracker.setInvisible(player, true);
        }
        return true;
    }

    /** Instant health and damage: the server owns health, so these are simply done. */
    private void applyInstant(CorePlayer player, Effect effect, int amplifier) {
        if (combat == null) {
            return;
        }
        // Vanilla's own arithmetic: four half-hearts, doubling with the level.
        int points = (int) (4 * Math.pow(2, amplifier));
        if (effect == Effect.INSTANT_HEALTH) {
            combat.healBy(player, points);
        } else {
            combat.hurtByEffect(player, points);
        }
    }

    /** Take one effect away. @return whether the player actually had it. */
    public boolean remove(CorePlayer player, Effect effect) {
        if (player == null || effect == null || player.getEffects().remove(effect) == null) {
            return false;
        }
        player.getConnection().removeEffect(effect);
        if (effect == Effect.INVISIBILITY) {
            tracker.setInvisible(player, false);
        }
        return true;
    }

    /** Take them all away. @return how many there were. */
    public int clear(CorePlayer player) {
        if (player == null) {
            return 0;
        }
        int had = 0;
        for (Effect effect : Effect.values()) {
            if (remove(player, effect)) {
                had++;
            }
        }
        return had;
    }

    // ===== Reading =====

    /** What this player currently holds, expired entries already dropped. */
    public Map<Effect, ActiveEffect> active(CorePlayer player) {
        Map<Effect, ActiveEffect> held = player.getEffects();
        if (held.isEmpty()) {
            return Map.of();
        }
        long now = System.currentTimeMillis();
        Map<Effect, ActiveEffect> live = new EnumMap<>(Effect.class);
        for (ActiveEffect a : held.values()) {
            if (!a.isExpired(now)) {
                live.put(a.effect(), a);
            }
        }
        return live;
    }

    /** The level of one effect, or {@code -1} if the player isn't under it. */
    public int amplifierOf(CorePlayer player, Effect effect) {
        ActiveEffect a = player.getEffects().get(effect);
        return a == null || a.isExpired(System.currentTimeMillis()) ? -1 : a.amplifier();
    }

    public boolean has(CorePlayer player, Effect effect) {
        return amplifierOf(player, effect) >= 0;
    }

    // ===== The two places the core listens =====

    /**
     * How much further than usual this player may plausibly have moved — {@code 1.0} for somebody under
     * nothing, more for speed and jump boost. The blind judge multiplies its limit by it, so a legitimately
     * fast player isn't snapped back as a cheat.
     *
     * <p>Deliberately generous, like every threshold that judge uses: it is there to catch somebody
     * crossing the map, not to model movement.
     */
    public double moveAllowance(CorePlayer player) {
        if (player == null || player.getEffects().isEmpty()) {
            return 1.0;  // the common case, and it costs one field read
        }
        double allowance = 1.0;
        int speed = amplifierOf(player, Effect.SPEED);
        if (speed >= 0) {
            allowance += SPEED_PER_LEVEL * (speed + 1);
        }
        int jump = amplifierOf(player, Effect.JUMP_BOOST);
        if (jump >= 0) {
            allowance += JUMP_PER_LEVEL * (jump + 1);
        }
        return allowance;
    }

    /** Damage taken, after resistance softens it and weakness leaves it alone (weakness is dealt, not taken). */
    private int scaleIncoming(CorePlayer player, int amount) {
        int resistance = amplifierOf(player, Effect.RESISTANCE);
        if (resistance < 0 || amount <= 0) {
            return amount;
        }
        // Vanilla: each level takes off a fifth, and level 5 makes you immune.
        double factor = Math.max(0.0, 1.0 - 0.20 * (resistance + 1));
        return (int) Math.round(amount * factor);
    }

    /**
     * Damage dealt by this attacker, after strength adds to it and weakness takes away — the half of the
     * arithmetic that needs to know who is swinging, which no damage event carries.
     */
    public int scaleOutgoing(CorePlayer attacker, int amount) {
        if (attacker == null || attacker.getEffects().isEmpty()) {
            return amount;
        }
        int strength = amplifierOf(attacker, Effect.STRENGTH);
        if (strength >= 0) {
            amount += 3 * (strength + 1);   // vanilla adds 3 per level to a melee hit
        }
        int weakness = amplifierOf(attacker, Effect.WEAKNESS);
        if (weakness >= 0) {
            amount -= 4 * (weakness + 1);
        }
        return Math.max(0, amount);
    }

    // ===== Keeping clients in step =====

    /**
     * Re-state everything a player holds to their client. Called when a client has just been given a new
     * world to look at (a travel, a rejoin) — the same moment the weather and the time are re-sent, and
     * for the same reason: the client is the one holding the state, so anything it may have dropped has
     * to be said again.
     */
    public void resend(CorePlayer player) {
        Map<Effect, ActiveEffect> live = active(player);
        if (live.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ActiveEffect a : live.values()) {
            player.getConnection().sendEffect(a.effect(), a.amplifier(), a.remainingTicks(now),
                    a.particles());
        }
    }

    /**
     * Retire what has run out. Runs on the game loop, gated to a coarse interval, and visits only players
     * who are actually holding something — an unaffected server pays one empty-map check per player per
     * second, and one that has nobody online pays nothing at all.
     */
    public void expiryTick(long currentTick) {
        if (currentTick % EXPIRY_INTERVAL_TICKS != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (CorePlayer player : players.online()) {
            Map<Effect, ActiveEffect> held = player.getEffects();
            if (held.isEmpty()) {
                continue;
            }
            for (ActiveEffect a : held.values().toArray(new ActiveEffect[0])) {
                if (a.isExpired(now)) {
                    // Through remove(), so the client is told and invisibility puts the avatar back.
                    remove(player, a.effect());
                }
            }
        }
    }
}
