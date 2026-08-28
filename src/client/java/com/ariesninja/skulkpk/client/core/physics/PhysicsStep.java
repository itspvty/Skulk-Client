package com.ariesninja.skulkpk.client.core.physics;

import java.util.Objects;

/** Immutable state transition and the contacts that produced it. */
public record PhysicsStep(ParkourState state, CollisionManifold collisions) {
    public PhysicsStep {
        state = Objects.requireNonNull(state);
        collisions = Objects.requireNonNull(collisions);
    }
}
