package com.jedrock.network.pipeline;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Prepends a VarInt length in front of outgoing packets.
 *
 * Input: ByteBuf containing the full packet data (packet ID + payload)
 * Output: [VarInt length][original data]
 */
public class VarintLengthEncoder extends MessageToByteEncoder<ByteBuf> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int length = msg.readableBytes();
        ByteBufUtils.writeVarInt(out, length);
        // Copy the body without consuming the source's readerIndex.
        out.writeBytes(msg, msg.readerIndex(), length);
    }
}
