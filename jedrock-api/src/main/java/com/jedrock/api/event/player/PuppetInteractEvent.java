package com.jedrock.api.event.player;

import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.player.Player;

/**
 * A player hit a {@linkplain PuppetEntity puppet} — a mob, an NPC, a prop.
 *
 * <p>A puppet has always been able to answer for itself through {@code onInteract}, but only the script
 * that spawned it can set that, which leaves nothing for a script that wants to watch <em>every</em>
 * puppet: an NPC framework, a protection rule, a log. {@link PlayerInteractEntityEvent} does fire for
 * this, and cancelling it already stops the callback — but it carries a raw entity id, so a listener has
 * to resolve the id itself and then work out whether it was a puppet at all. This is the resolved version:
 * it fires only for puppets, and it hands over the puppet.
 *
 * <p>Ordering: {@code PlayerInteractEntity} first (it is the wire event, and cancelling it stops
 * everything), then this, then the puppet's own {@code onInteract}. <b>Cancelling this</b> stops that
 * callback — which is how a script overrules an NPC somebody else installed.
 */
public final class PuppetInteractEvent extends CancellablePlayerEvent {

    private final PuppetEntity puppet;

    public PuppetInteractEvent(Player player, PuppetEntity puppet) {
        super(player);
        this.puppet = puppet;
    }

    /** The puppet that was hit. */
    public PuppetEntity getPuppet() {
        return puppet;
    }
}
