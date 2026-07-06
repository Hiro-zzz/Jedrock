package com.jedrock.network.pe;

/**
 * Wire-level safety limits — the blind judge extended down to the packet layer. Bedrock game packets
 * arrive as a zlib-compressed batch of length-prefixed inner packets, several of which carry
 * wire-driven counts (batch size, inventory actions, can-place/destroy lists). A hostile client can
 * weaponise any of these to crash or stall the server:
 *
 * <ul>
 *   <li>a tiny "zip bomb" batch that inflates to gigabytes → out-of-memory,</li>
 *   <li>a batch claiming millions of empty inner packets → a spinning dispatch loop,</li>
 *   <li>an item or transaction claiming a huge list length → a spinning parse loop.</li>
 * </ul>
 *
 * <p>These constants bound each of those so a malformed batch is rejected instead of taking the
 * server down. They are generous — far above anything a legitimate 1.1.5 client sends.
 */
final class PacketGuard {

    private PacketGuard() {}

    /** Max bytes one {@code 0xFE} batch may inflate to — stops a "zip bomb" from OOMing the server. */
    static final int MAX_INFLATED_BATCH = 2 * 1024 * 1024; // 2 MiB

    /** Max inner packets in one batch — a flood of empty packets can't spin the dispatch loop. */
    static final int MAX_PACKETS_PER_BATCH = 1024;

    /** Max entries for a wire-driven loop (inventory actions, can-place-on / can-destroy string lists). */
    static final int MAX_LIST_ENTRIES = 256;

    /** @return whether a list length read off the wire is sane (non-negative and not hostile). */
    static boolean saneCount(int count) {
        return count >= 0 && count <= MAX_LIST_ENTRIES;
    }
}
