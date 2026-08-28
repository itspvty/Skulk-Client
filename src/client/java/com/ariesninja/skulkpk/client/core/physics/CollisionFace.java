package com.ariesninja.skulkpk.client.core.physics;

import net.minecraft.util.math.Vec3d;

/** Outward normal of the obstacle face contacted by the player. */
public enum CollisionFace {
    WEST(new Vec3d(-1, 0, 0)),
    EAST(new Vec3d(1, 0, 0)),
    DOWN(new Vec3d(0, -1, 0)),
    UP(new Vec3d(0, 1, 0)),
    NORTH(new Vec3d(0, 0, -1)),
    SOUTH(new Vec3d(0, 0, 1));

    private final Vec3d normal;

    CollisionFace(Vec3d normal) { this.normal = normal; }

    public Vec3d normal() { return normal; }

    public boolean headContact() { return this == DOWN; }
    public boolean supportContact() { return this == UP; }
    public boolean sideContact() { return this != UP && this != DOWN; }
}
