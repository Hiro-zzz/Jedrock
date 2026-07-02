package com.jedrock.network.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;

import java.util.List;

/**
 * Decodes Minecraft-style length-prefixed packets.
 *
 * Inbound bytes:
 *   [VarInt length] [length bytes of data...]
 *
 * Output: a single ByteBuf containing exactly the data after the length
 * (i.e. packet ID + payload), passed further down the pipeline.
 *
 * The VarInt length is read incrementally so a partial header simply waits for
 * more bytes instead of throwing — no exceptions on the normal path.
 */
public class VarintFrameDecoder extends ByteToMessageDecoder {

    private static final int MAX_PACKET_SIZE = 2 * 1024 * 1024; // 2 MiB safety (1.12 era was smaller)
    private static final int MAX_VARINT_BYTES = 5;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!in.isReadable()) {
            return;
        }

        in.markReaderIndex();

        int length = 0;
        int read = 0;
        byte current;
        do {
            if (!in.isReadable()) {
                // Incomplete length header — wait for more bytes.
                in.resetReaderIndex();
                return;
            }
            current = in.readByte();
            length |= (current & 0x7F) << (read * 7);
            read++;
            if (read > MAX_VARINT_BYTES) {
                throw new CorruptedFrameException("length VarInt too big");
            }
        } while ((current & 0x80) != 0);

        if (length < 0 || length > MAX_PACKET_SIZE) {
            throw new CorruptedFrameException("packet length out of bounds: " + length);
        }

        if (in.readableBytes() < length) {
            // Body not fully arrived yet.
            in.resetReaderIndex();
            return;
        }

        // Extract exactly 'length' bytes: the raw packet content (id + payload).
        out.add(in.readRetainedSlice(length));
    }
}
