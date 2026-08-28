package com.ariesninja.skulkpk.client.core.execution;

import com.ariesninja.skulkpk.client.core.physics.ParkourState;

import java.util.Objects;

/** Player state observed after Minecraft has processed the identified command epoch. */
public record MovementObservation(ParkourState state, long observedCommandEpoch) {
    public MovementObservation {
        state = Objects.requireNonNull(state);
        if (observedCommandEpoch < 0) throw new IllegalArgumentException("Command epochs cannot be negative.");
    }
}
