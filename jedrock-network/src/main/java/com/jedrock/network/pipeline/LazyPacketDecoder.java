package com.jedrock.network.pipeline;

import com.jedrock.utils.ByteBufUtils;
import com.jedrock.utils.lazy.LazyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.util.ReferenceCountUtil;

import java.util.List;

/**
 * Second stage decoder.
 *
 * Takes a framed packet buffer (from VarintFrameDecoder):
 *    [VarInt packetId] [remaining payload bytes]
 *
 * Produces a LazyPacket that:
 *   - Stores only the packetId
 *   - Stores a retained slice of the *payload only*
 *
 * No object allocation or parsing of payload happens here.
 * This is the heart of "lazy".
 */
public class LazyPacketDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        // Compensate for MessageToMessageDecoder's automatic release of the input.
        // We want full control over when the frame buffer is released.
        ReferenceCountUtil.retain(msg);

        if (!msg.isReadable()) {
            msg.release();
            return;
        }

        int packetId;

        try {
            packetId = ByteBufUtils.readVarInt(msg);
        } catch (RuntimeException ex) {
            // Malformed — drop
            msg.release();
            return;
        }

        // Everything that remains is the payload (may be zero length)
        ByteBuf payload = null;
        if (msg.isReadable()) {
            // readRetainedSlice gives us refCnt=1 for this slice.
            // LazyPacket will take ownership (we no longer auto-retain inside it).
            payload = msg.readRetainedSlice(msg.readableBytes());
        }

        // We are responsible for releasing the framed buffer now.
        msg.release();

        LazyPacket lazy = new LazyPacket(packetId, payload);
        out.add(lazy);
    }
}
