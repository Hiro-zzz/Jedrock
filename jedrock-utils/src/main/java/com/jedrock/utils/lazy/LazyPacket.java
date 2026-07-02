package com.jedrock.utils.lazy;

import io.netty.buffer.ByteBuf;

import java.util.function.Function;

/**
 * Core primitive for Jedrock's lazy parsing philosophy.
 *
 * A LazyPacket holds:
 *  - packet ID (raw, protocol specific)
 *  - the *payload* bytes (everything after the packet ID) as a retained ByteBuf slice.
 *
 * Nothing is parsed until you explicitly call materialize(...) or access raw payload.
 *
 * Philosophy:
 * "Visible only what must be seen. Everything else stays as pure bytes."
 *
 * IMPORTANT: The caller (usually the decoder) is responsible for correct retain/release.
 * Always call release() when you are done with the packet (unless you passed ownership).
 */
public final class LazyPacket {

    private final int packetId;
    private final ByteBuf payload;           // retained slice of data AFTER packet ID
    private volatile Object materialized;

    /**
     * @param packetId protocol packet identifier
     * @param payload  ByteBuf containing ONLY the payload (after ID).
     *                 The LazyPacket takes ownership of this buffer.
     *                 Caller should have set up the refCnt appropriately (usually 1).
     */
    public LazyPacket(int packetId, ByteBuf payload) {
        this.packetId = packetId;
        // We take ownership of the passed buffer. The caller (usually the decoder)
        // must ensure the refCnt is correct for us to own it.
        // We do NOT call retain here.
        if (payload != null && payload.isReadable()) {
            this.payload = payload;
        } else {
            // Nothing usable to hold on to — release the buffer we were handed
            // instead of dropping the reference and leaking it.
            if (payload != null) {
                payload.release();
            }
            this.payload = null;
        }
    }

    public int getPacketId() {
        return packetId;
    }

    /**
     * Returns a duplicate view of the raw payload (no parsing).
     * You can read from it directly with ByteBufUtils without materializing an object.
     */
    public ByteBuf getPayload() {
        return payload != null ? payload.duplicate() : null;
    }

    /**
     * Returns a retained copy of the payload buffer.
     * Caller must release the returned buffer when finished.
     */
    public ByteBuf retainPayload() {
        return payload != null ? payload.retainedDuplicate() : null;
    }

    /**
     * Materialize a concrete packet object from the payload.
     * The parser receives a DUPLICATE of the payload so it can be read safely.
     * Result is cached — subsequent calls return the same instance.
     */
    @SuppressWarnings("unchecked")
    public <T> T materialize(Function<ByteBuf, T> parser) {
        if (materialized == null) {
            synchronized (this) {
                if (materialized == null && payload != null) {
                    materialized = parser.apply(payload.duplicate());
                }
            }
        }
        return (T) materialized;
    }

    /**
     * Release the internal payload buffer.
     * Must be called exactly once per LazyPacket after you no longer need the raw data.
     */
    public void release() {
        if (payload != null && payload.refCnt() > 0) {
            payload.release();
        }
    }

    public boolean isMaterialized() {
        return materialized != null;
    }

    /**
     * @return true if there is actual payload data
     */
    public boolean hasPayload() {
        return payload != null && payload.isReadable();
    }
}

