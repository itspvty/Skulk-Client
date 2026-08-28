package com.ariesninja.skulkpk.client.core.physics;

import com.ariesninja.skulkpk.client.core.analysis.WorldView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryPhysicsWorld implements WorldView {
    private final List<Box> boxes = new ArrayList<>();
    private long fingerprint = 11;
    private final java.util.Set<BlockPos> ladders = new java.util.HashSet<>();

    public InMemoryPhysicsWorld ladder(BlockPos pos) { ladders.add(pos.toImmutable()); return this; }

    public InMemoryPhysicsWorld floor(int x, int z, double topY) {
        boxes.add(new Box(x, topY - 1, z, x + 1, topY, z + 1));
        return this;
    }

    public InMemoryPhysicsWorld box(Box box) { boxes.add(box); return this; }
    public List<Box> boxes() { return List.copyOf(boxes); }

    @Override public List<Box> collisionBoxes(Box region) {
        return boxes.stream().filter(box -> box.intersects(region)).toList();
    }
    @Override public boolean isSolid(BlockPos pos) {
        Box block = new Box(pos);
        return boxes.stream().anyMatch(box -> box.equals(block));
    }
    @Override public boolean isAir(BlockPos pos) { return !isSolid(pos); }
    @Override public boolean isLadder(BlockPos pos) { return ladders.contains(pos); }
    @Override public boolean hasCollision(Box box) { return !collisionBoxes(box).isEmpty(); }
    @Override public int topY() { return 320; }
    @Override public long fingerprint(Box region) { return fingerprint; }
    public InMemoryPhysicsWorld fingerprint(long value) { fingerprint = value; return this; }
}
