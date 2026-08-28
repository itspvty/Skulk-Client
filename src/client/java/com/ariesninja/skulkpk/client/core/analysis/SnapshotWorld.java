package com.ariesninja.skulkpk.client.core.analysis;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Immutable, spatially indexed shapes/properties. Search never touches a Minecraft world. */
public final class SnapshotWorld implements WorldView {
    private static final int MAX_CELLS = 131_072;
    private final Box bounds;
    private final int minX, minY, minZ, sizeY, sizeZ;
    private final Cell[] cells;
    private final List<List<Box>> buckets;
    private final int top;

    private SnapshotWorld(Capture capture) {
        bounds = capture.bounds;
        minX = capture.minX; minY = capture.minY; minZ = capture.minZ;
        sizeY = capture.sizeY; sizeZ = capture.sizeZ;
        top = capture.top;
        cells = capture.cells.clone();
        List<List<Box>> indexed = new ArrayList<>(cells.length);
        for (int i = 0; i < cells.length; i++) indexed.add(new ArrayList<>());
        LinkedHashSet<Box> shapes = new LinkedHashSet<>();
        for (Cell cell : cells) shapes.addAll(cell.collisions());
        for (Box shape : shapes) {
            for (int x = Math.max(minX, floor(shape.minX)); x < Math.min(bounds.maxX, Math.ceil(shape.maxX)); x++)
                for (int y = Math.max(minY, floor(shape.minY)); y < Math.min(bounds.maxY, Math.ceil(shape.maxY)); y++)
                    for (int z = Math.max(minZ, floor(shape.minZ)); z < Math.min(bounds.maxZ, Math.ceil(shape.maxZ)); z++)
                        indexed.get(index(x, y, z)).add(shape);
        }
        buckets = indexed.stream().map(List::copyOf).toList();
    }

    public static Capture capture(WorldView source, Box region) { return new Capture(source, region); }

    @Override public boolean contains(Box region) {
        return region.minX >= bounds.minX && region.maxX <= bounds.maxX
                && region.minY >= bounds.minY && region.maxY <= bounds.maxY
                && region.minZ >= bounds.minZ && region.maxZ <= bounds.maxZ;
    }

    @Override public List<Box> collisionBoxes(Box region) {
        // Unknown geometry cannot be used as a clear staging corridor. Physics independently
        // rejects a transition crossing the captured boundary, never treating unknown as air.
        if (!contains(region)) return List.of(region);
        List<Box> result = new ArrayList<>();
        for (int x = floor(region.minX); x < Math.ceil(region.maxX); x++)
            for (int y = floor(region.minY); y < Math.ceil(region.maxY); y++)
                for (int z = floor(region.minZ); z < Math.ceil(region.maxZ); z++)
                    for (Box shape : buckets.get(index(x, y, z)))
                        if (shape.intersects(region) && !result.contains(shape)) result.add(shape);
        return result;
    }

    private int index(int x, int y, int z) { return ((x - minX) * sizeY + y - minY) * sizeZ + z - minZ; }
    private Cell cell(BlockPos pos) {
        if (pos.getX() < bounds.minX || pos.getX() >= bounds.maxX
                || pos.getY() < bounds.minY || pos.getY() >= bounds.maxY
                || pos.getZ() < bounds.minZ || pos.getZ() >= bounds.maxZ)
            throw new com.ariesninja.skulkpk.client.core.physics.ParkourPhysics.UnsupportedPhysicsStateException(
                    "outside_captured_world");
        return cells[index(pos.getX(), pos.getY(), pos.getZ())];
    }
    private static int floor(double value) { return (int) Math.floor(value); }
    @Override public Cell captureCell(BlockPos pos) { return cell(pos); }
    @Override public boolean isSolid(BlockPos pos) { return cell(pos).solid(); }
    @Override public boolean isAir(BlockPos pos) { return cell(pos).air(); }
    @Override public boolean isLadder(BlockPos pos) { return cell(pos).ladder(); }
    @Override public boolean isClimbable(BlockPos pos) { return cell(pos).climbable(); }
    @Override public boolean hasFluid(BlockPos pos) { return cell(pos).fluid(); }
    @Override public double slipperiness(BlockPos pos) { return cell(pos).slipperiness(); }
    @Override public double jumpMultiplier(BlockPos pos) { return cell(pos).jumpMultiplier(); }
    @Override public boolean hasCollision(Box region) { return !collisionBoxes(region).isEmpty(); }
    @Override public int topY() { return top; }

    /** Main-thread execution uses fast immutable queries plus live invalidation, never stale physics. */
    public WorldView validatedBy(WorldView live) {
        return new WorldView() {
            @Override public boolean contains(Box region) { return SnapshotWorld.this.contains(region); }
            @Override public List<Box> collisionBoxes(Box region) { return SnapshotWorld.this.collisionBoxes(region); }
            @Override public boolean isSolid(BlockPos pos) { return SnapshotWorld.this.isSolid(pos); }
            @Override public boolean isAir(BlockPos pos) { return SnapshotWorld.this.isAir(pos); }
            @Override public boolean isLadder(BlockPos pos) { return SnapshotWorld.this.isLadder(pos); }
            @Override public boolean isClimbable(BlockPos pos) { return SnapshotWorld.this.isClimbable(pos); }
            @Override public boolean hasFluid(BlockPos pos) { return SnapshotWorld.this.hasFluid(pos); }
            @Override public double slipperiness(BlockPos pos) { return SnapshotWorld.this.slipperiness(pos); }
            @Override public double jumpMultiplier(BlockPos pos) { return SnapshotWorld.this.jumpMultiplier(pos); }
            @Override public boolean hasCollision(Box region) { return SnapshotWorld.this.hasCollision(region); }
            @Override public int topY() { return top; }
            @Override public long fingerprint(Box region) { return live.fingerprint(region); }
            @Override public Object identityToken() { return live.identityToken(); }
        };
    }

    /** Bounded client-thread capture; indexing is deferred to the search worker. */
    public static final class Capture {
        private final WorldView source;
        private final Box bounds;
        private final int minX, minY, minZ, sizeY, sizeZ, top;
        private final Cell[] cells;
        private int cursor;
        private Capture(WorldView source, Box region) {
            this.source = source;
            // One block halo includes support probes/neighbor-extended fence shapes.
            bounds = new Box(floor(region.minX) - 1, floor(region.minY) - 1, floor(region.minZ) - 1,
                    Math.ceil(region.maxX) + 1, Math.ceil(region.maxY) + 1, Math.ceil(region.maxZ) + 1);
            minX = floor(bounds.minX); minY = floor(bounds.minY); minZ = floor(bounds.minZ);
            sizeY = (int) bounds.getLengthY(); sizeZ = (int) bounds.getLengthZ();
            long count = (long) bounds.getLengthX() * sizeY * sizeZ;
            if (count <= 0 || count > MAX_CELLS) throw new IllegalArgumentException("Planning region is too large to capture safely.");
            cells = new Cell[(int) count];
            top = source.topY();
        }
        public boolean tick(long budgetNanos) {
            long deadline = System.nanoTime() + Math.max(1, budgetNanos);
            do {
                int x = minX + cursor / (sizeY * sizeZ);
                int y = minY + cursor / sizeZ % sizeY;
                int z = minZ + cursor % sizeZ;
                cells[cursor++] = source.captureCell(new BlockPos(x, y, z));
            } while (cursor < cells.length && System.nanoTime() < deadline);
            return cursor == cells.length;
        }
        public SnapshotWorld finish() {
            if (cursor != cells.length) throw new IllegalStateException("Incomplete world capture.");
            return new SnapshotWorld(this);
        }
    }
}
