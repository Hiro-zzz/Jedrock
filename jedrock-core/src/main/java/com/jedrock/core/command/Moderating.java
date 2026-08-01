package com.jedrock.core.command;

import com.jedrock.core.moderation.Durations;
import com.jedrock.core.moderation.Punishment;

/**
 * The two things every punishment command has to do the same way: read {@code [duration] [reason]} off the
 * end of a command line, and say how long something is for.
 *
 * <p>Written once because the alternative is three commands that disagree about whether
 * {@code /ban alice 30 spam} means thirty of something. It does not: a duration needs a unit, and a second
 * argument without one is simply the first word of the reason.
 */
final class Moderating {

    private Moderating() {}

    /** A parsed tail: how long, and why. */
    record Split(long durationMillis, String reason) {}

    /**
     * Read {@code args} from {@code from} as an optional duration followed by an optional reason.
     *
     * @return {@link Durations#PERMANENT} and {@link Punishment#NO_REASON} when neither was given
     */
    static Split splitDurationAndReason(String[] args, int from) {
        int index = from;
        long duration = Durations.PERMANENT;
        if (index < args.length) {
            long parsed = Durations.parse(args[index]);
            if (parsed != Durations.NOT_A_DURATION) {
                duration = parsed;
                index++;
            }
        }
        String reason = index < args.length
                ? String.join(" ", java.util.Arrays.copyOfRange(args, index, args.length))
                : Punishment.NO_REASON;
        return new Split(duration, reason);
    }

    /** {@code "permanently"} or {@code "for 2d"} — the phrase a confirmation line needs. */
    static String forHowLong(long durationMillis) {
        return durationMillis == Durations.PERMANENT
                ? "permanently" : "for " + Durations.describe(durationMillis);
    }
}
