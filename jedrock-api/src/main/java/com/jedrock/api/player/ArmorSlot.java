package com.jedrock.api.player;

/**
 * The four armor pieces a player can wear. Armor is <b>visual</b> in Jedrock's illusionist model: it
 * shows on the wearer's avatar for everyone, cross-edition, but the server simulates no protection —
 * damage is what the damage path says it is.
 *
 * <p>The declaration order (head to feet) is the order every wire format that takes all four at once
 * uses (both Bedrock eras' {@code MobArmorEquipment}), and matches the backing inventory slots.
 */
public enum ArmorSlot {

    HELMET(36),
    CHESTPLATE(37),
    LEGGINGS(38),
    BOOTS(39);

    private final int inventorySlot;

    ArmorSlot(int inventorySlot) {
        this.inventorySlot = inventorySlot;
    }

    /**
     * The backing slot in the player's inventory model — past the 36 storage slots, which is why the
     * storage-slot inventory API (0-35) can't reach armor and {@link Player#setArmor} exists.
     */
    public int inventorySlot() {
        return inventorySlot;
    }
}
