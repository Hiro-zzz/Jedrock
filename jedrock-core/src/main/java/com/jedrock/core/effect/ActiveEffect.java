package com.jedrock.core.effect;

import com.jedrock.api.entity.Effect;

/**
 * One effect on one player: how strong it is, when it runs out, and whether the client draws its
 * particles.
 *
 * <p>The deadline is a wall-clock moment rather than a countdown, which is what makes an effect free:
 * nothing has to tick it down. Whoever asks works out what is left, and the periodic pass in
 * {@link EffectService} only exists to tell the client when something has quietly lapsed.
 */
public record ActiveEffect(Effect effect, int amplifier, long expiresAtMillis, boolean particles) {

    /** Whether it has run out at {@code now}. */
    public boolean isExpired(long now) {
        return now >= expiresAtMillis;
    }

    /** What is left, in ticks — what a client is told when it has to be told again. */
    public int remainingTicks(long now) {
        long left = expiresAtMillis - now;
        return left <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, left / 50L);
    }

    /** What is left, in whole seconds — what a person is told. */
    public int remainingSeconds(long now) {
        long left = expiresAtMillis - now;
        return left <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (left + 999L) / 1000L);
    }
}
