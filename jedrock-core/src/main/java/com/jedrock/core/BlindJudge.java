package com.jedrock.core;

/**
 * The "blind judge" — lazy, allocation-free server-side validation at the points that matter, in
 * place of a full physics engine (see the project philosophy). Two cheap sanity checks:
 *
 * <ul>
 *   <li><b>Movement delta</b> — reject a position jump larger than {@code maxMoveDelta} between two
 *       movement reports, so the server never believes a blatant teleport / speed hack.</li>
 *   <li><b>Interaction sphere</b> — reject a block edit farther than {@code maxReach} from the player,
 *       measured from the head, so reach hacks can't touch distant blocks.</li>
 * </ul>
 *
 * <p>Thresholds are generous by design: the judge catches the egregious (teleporting across the map,
 * editing blocks half a chunk away), not marginal cases, and it does no per-tick simulation. Pure
 * functions of the configured limits — trivially testable and cheap enough for the network threads.
 */
public final class BlindJudge {

    /** Eye height above the feet, so reach is measured from the head the way the client aims. */
    private static final double EYE_HEIGHT = 1.62;

    private final boolean enabled;
    private final double maxMoveDeltaSq;
    private final double maxReachSq;

    public BlindJudge(boolean enabled, double maxReach, double maxMoveDelta) {
        this.enabled = enabled;
        this.maxReachSq = maxReach * maxReach;
        this.maxMoveDeltaSq = maxMoveDelta * maxMoveDelta;
    }

    /** @return whether moving from {@code (fx,fy,fz)} to {@code (tx,ty,tz)} is a believable step. */
    public boolean allowsMove(double fx, double fy, double fz, double tx, double ty, double tz) {
        if (!enabled) {
            return true;
        }
        double dx = tx - fx, dy = ty - fy, dz = tz - fz;
        return dx * dx + dy * dy + dz * dz <= maxMoveDeltaSq;
    }

    /** @return whether the block at {@code (bx,by,bz)} is within reach of a player at {@code (px,py,pz)}. */
    public boolean allowsInteraction(double px, double py, double pz, int bx, int by, int bz) {
        if (!enabled) {
            return true;
        }
        double dx = (bx + 0.5) - px;
        double dy = (by + 0.5) - (py + EYE_HEIGHT);
        double dz = (bz + 0.5) - pz;
        return dx * dx + dy * dy + dz * dz <= maxReachSq;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
