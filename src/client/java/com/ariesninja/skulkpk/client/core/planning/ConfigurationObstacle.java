package com.ariesninja.skulkpk.client.core.planning;

import net.minecraft.util.math.Box;

import java.util.Objects;

/** Collision shape represented as forbidden player-feet space. */
public record ConfigurationObstacle(String id, Box collisionShape, Box forbiddenFeet) {
    public ConfigurationObstacle {
        id = Objects.requireNonNull(id);
        collisionShape = Objects.requireNonNull(collisionShape);
        forbiddenFeet = Objects.requireNonNull(forbiddenFeet);
    }
}
