package com.jedrock.api.config;

/**
 * The wire's own settings: the numbers the network layer runs on, which every other config deliberately
 * doesn't mention.
 *
 * <p>{@link ServerProperties} is what a server operator sets — a name, a port, a view distance. This is
 * what someone tuning a <em>pipeline</em> sets, and the two are separated because they fail differently.
 * Get {@code server.motd} wrong and the server list looks silly; get {@code max-inflated-batch-bytes}
 * wrong and a hostile client can decide how much memory the process uses. Every value here was a constant
 * compiled into the network module, chosen for a machine and a client that no longer have to be yours.
 *
 * <p>Being a record of records is the point: the file is nested, the record is nested, and a section that
 * an operator never touches has one default sitting in one place.
 *
 * @param netty     the transport itself — threads and socket options
 * @param java      the Java Edition listener
 * @param bedrock   the Bedrock listeners, one per era
 * @param guard     the wire-level safety limits (see the {@code PacketGuard} they feed)
 */
public record PipelineSettings(Netty netty, JavaEdition java, Bedrock bedrock, Guard guard) {

    /**
     * The Netty transport, shared by every listener.
     *
     * @param bossThreads    threads accepting connections; 1 is right until it isn't
     * @param workerThreads  threads doing the actual I/O; {@code 0} = Netty's default (2× cores)
     * @param tcpNoDelay     disable Nagle — on, because a server that batches for 40 ms feels laggy
     * @param soKeepAlive    ask the OS to notice a dead peer
     * @param reuseAddress   allow rebinding a port still in TIME_WAIT — what makes a fast restart work
     * @param backlog        pending-connection queue length for the Java listener
     */
    public record Netty(int bossThreads, int workerThreads, boolean tcpNoDelay, boolean soKeepAlive,
                        boolean reuseAddress, int backlog) {
        public static Netty defaults() {
            return new Netty(1, 0, true, true, true, 128);
        }
    }

    /**
     * The Java Edition listener.
     *
     * @param keepAliveSeconds how often a keep-alive is sent; the vanilla client disconnects at 30 s of
     *                         silence, so this is half that and there is little reason to raise it
     */
    public record JavaEdition(int keepAliveSeconds) {
        public static JavaEdition defaults() {
            return new JavaEdition(15);
        }
    }

    /**
     * Both Bedrock eras. They are separate settings rather than one shared block because they are separate
     * protocols that happen to share a shape — and because the interesting numbers differ: 0.14 streams
     * full 128-tall columns where 1.1.5 streams sections, so the radius one of them can afford is not the
     * radius the other can.
     */
    public record Bedrock(Era v1_1_5, Era v0_14) {
        public static Bedrock defaults() {
            return new Bedrock(Era.defaults115(), Era.defaults014());
        }
    }

    /**
     * One Bedrock era's knobs.
     *
     * @param maxViewRadius       the largest chunk radius this server will honour, whatever the client asks
     * @param sidebarRepaintTicks how often the borrowed HUD line is redrawn (Bedrock has no scoreboard, so
     *                            a sidebar is a popup that fades and must be repainted to stay)
     * @param maxParticleBurst    the most particle packets one {@code spawnParticle} may become
     * @param resyncDelayMillis   <b>1.1.5 only.</b> The trailing-edge delay before a chunk resend after a
     *                            block edit — the fix for that client ignoring its own UpdateBlock. Too
     *                            short and it fires mid-burst and shows a stale world; too long and a ghost
     *                            block lingers on screen.
     * @param announceDimension   <b>1.1.5 only.</b> Whether the dimension is put on the wire at all
     *                            (StartGame on join, ChangeDimension on travel). Off gives the destination's
     *                            blocks under the wrong sky, which is the failure that can't hang a client.
     */
    public record Era(int maxViewRadius, int sidebarRepaintTicks, int maxParticleBurst,
                      long resyncDelayMillis, boolean announceDimension) {
        public static Era defaults115() {
            return new Era(4, 20, 32, 180L, true);
        }

        public static Era defaults014() {
            return new Era(4, 20, 32, 0L, false);
        }
    }

    /**
     * Wire-level safety limits. Generous by design — far above anything a legitimate client sends — and
     * lowerable by anyone who would rather drop a suspicious client than serve it.
     *
     * @param maxInflatedBatchBytes most bytes one compressed batch may inflate to (the zip-bomb ceiling)
     * @param maxPacketsPerBatch    most inner packets in one batch
     * @param maxListEntries        most entries in any length-prefixed list read off the wire
     */
    public record Guard(int maxInflatedBatchBytes, int maxPacketsPerBatch, int maxListEntries) {
        public static Guard defaults() {
            return new Guard(2 * 1024 * 1024, 1024, 256);
        }
    }

    /** What the server runs on with no {@code pipeline.yml} at all — the constants this used to be. */
    public static PipelineSettings defaults() {
        return new PipelineSettings(Netty.defaults(), JavaEdition.defaults(),
                Bedrock.defaults(), Guard.defaults());
    }
}
