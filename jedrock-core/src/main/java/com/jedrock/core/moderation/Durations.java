package com.jedrock.core.moderation;

import java.util.Locale;

/**
 * Reading {@code 30m}, {@code 2d}, {@code perm} — the one thing that makes a temporary ban a temporary ban
 * without a second command for it. {@code /ban alice 2d spam} rather than {@code /tempban}.
 */
public final class Durations {

    private Durations() {}

    /** Returned when the text is not a duration at all — which is how a command tells it apart from a reason. */
    public static final long NOT_A_DURATION = -1L;
    /** Returned for {@code perm} / {@code forever} — and what an omitted duration means. */
    public static final long PERMANENT = 0L;

    /**
     * Parse a duration into milliseconds.
     *
     * <p>A unit is required: {@code 30} on its own is {@link #NOT_A_DURATION}, not thirty of something.
     * A moderator typing {@code /ban alice 30 spam} means the reason starts at {@code 30}, and guessing a
     * unit there would silently ban somebody for a length nobody chose.
     *
     * @return milliseconds, {@link #PERMANENT}, or {@link #NOT_A_DURATION}
     */
    public static long parse(String text) {
        if (text == null || text.isBlank()) {
            return NOT_A_DURATION;
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.equals("perm") || value.equals("permanent") || value.equals("forever")) {
            return PERMANENT;
        }
        int split = 0;
        while (split < value.length() && Character.isDigit(value.charAt(split))) {
            split++;
        }
        if (split == 0 || split == value.length()) {
            return NOT_A_DURATION; // no number, or no unit
        }
        long unit = switch (value.substring(split)) {
            case "s", "sec", "secs" -> 1_000L;
            case "m", "min", "mins" -> 60_000L;
            case "h", "hr", "hrs", "hour", "hours" -> 3_600_000L;
            case "d", "day", "days" -> 86_400_000L;
            case "w", "week", "weeks" -> 604_800_000L;
            default -> NOT_A_DURATION;
        };
        if (unit == NOT_A_DURATION) {
            return NOT_A_DURATION;
        }
        try {
            long count = Long.parseLong(value.substring(0, split));
            if (count <= 0) {
                return NOT_A_DURATION; // "0d" is not "forever"; say perm if you mean it
            }
            return Math.multiplyExact(count, unit);
        } catch (ArithmeticException overflow) {
            // Someone typed a number of weeks that doesn't fit in a long. They meant forever.
            return PERMANENT;
        } catch (NumberFormatException e) {
            return NOT_A_DURATION;
        }
    }

    /** Render a remaining time the way a person reads it: {@code 2d 3h}, {@code 45s}, {@code permanent}. */
    public static String describe(long millis) {
        if (millis < 0) {
            return "permanent";
        }
        if (millis < 1000) {
            return "moments";
        }
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append("d ");
        }
        if (hours > 0) {
            out.append(hours).append("h ");
        }
        // Minutes and seconds are noise next to days, so they stop once something bigger is being shown.
        if (minutes > 0 && days == 0) {
            out.append(minutes).append("m ");
        }
        if (secs > 0 && days == 0 && hours == 0) {
            out.append(secs).append("s");
        }
        return out.toString().trim();
    }
}
