package com.jedrock.network.je.packet;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Decode of Use Entity (JE): target + type prefix, shared by 1.12.2 (0x0A) and 1.8 (0x02). */
class ServerboundUseEntityTest {

    @Test
    void decodesAttackTargetAndType() {
        ByteBuf buf = Unpooled.buffer();
        ByteBufUtils.writeVarInt(buf, 1000);                         // target avatar entity id
        ByteBufUtils.writeVarInt(buf, ServerboundUseEntity.TYPE_ATTACK);

        ServerboundUseEntity use = ServerboundUseEntity.fromBuffer(buf);
        assertEquals(1000, use.target);
        assertEquals(ServerboundUseEntity.TYPE_ATTACK, use.type);
        buf.release();
    }

    @Test
    void readsOnlyTheTargetTypePrefixOfAnInteractAt() {
        // An interact-at (type 2) carries trailing floats + a hand VarInt; we read only the prefix and
        // leave the rest, so a non-attack type is classified correctly without needing the tail.
        ByteBuf buf = Unpooled.buffer();
        ByteBufUtils.writeVarInt(buf, 7);
        ByteBufUtils.writeVarInt(buf, ServerboundUseEntity.TYPE_INTERACT_AT);
        buf.writeFloat(0.1f).writeFloat(0.2f).writeFloat(0.3f);       // target-at vector (unread)
        ByteBufUtils.writeVarInt(buf, 0);                            // hand (unread)

        ServerboundUseEntity use = ServerboundUseEntity.fromBuffer(buf);
        assertEquals(7, use.target);
        assertEquals(ServerboundUseEntity.TYPE_INTERACT_AT, use.type);
        buf.release();
    }
}
