package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.WorldView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** A vanilla feet-block attachment volume, not a solid surface or a magnetic grab radius. */
public record LadderColumn(Box attachment, Vec3d intoWall) {
    public static List<LadderColumn> discover(WorldView world, Box region) {
        List<LadderColumn> columns = new ArrayList<>();
        for (int x = (int) Math.floor(region.minX); x < Math.ceil(region.maxX); x++)
            for (int z = (int) Math.floor(region.minZ); z < Math.ceil(region.maxZ); z++)
                for (int y = (int) Math.floor(region.minY); y < Math.ceil(region.maxY); y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.isLadder(pos)) continue;
                    Vec3d inward = wallDirection(world, pos);
                    if (inward == null) continue;
                    int bottom = y;
                    while (y + 1 < Math.ceil(region.maxY)
                            && inward.equals(wallDirection(world, new BlockPos(x, y + 1, z)))) y++;
                    columns.add(new LadderColumn(
                            new Box(x, bottom, z, x + 1, y + 1, z + 1), inward));
                }
        return List.copyOf(columns);
    }

    private static Vec3d wallDirection(WorldView world, BlockPos pos) {
        if (!world.isLadder(pos)) return null;
        Box rung = world.collisionBoxes(new Box(pos)).stream()
                .filter(box -> box.intersects(new Box(pos)))
                .filter(box -> Math.min(box.getLengthX(), box.getLengthZ()) < 0.25)
                .findFirst().orElse(null);
        if (rung == null) return null;
        Vec3d center = rung.getCenter().subtract(Vec3d.ofCenter(pos));
        Vec3d inward = rung.getLengthX() < rung.getLengthZ()
                ? new Vec3d(Math.signum(center.x), 0, 0) : new Vec3d(0, 0, Math.signum(center.z));
        return inward.lengthSquared() > 0 ? inward : null;
    }

    public Vec3d entry(double y) {
        return new Vec3d((attachment.minX + attachment.maxX) * 0.5,
                Math.clamp(y, attachment.minY + 0.05, attachment.maxY - 0.05),
                (attachment.minZ + attachment.maxZ) * 0.5);
    }

    public Vec3d exit(LandingZone landing) {
        return landing.preferredAnchors().stream().min(java.util.Comparator.comparingDouble(anchor ->
                anchor.feet().subtract(entry(anchor.feet().y)).horizontalLengthSquared()))
                .orElseThrow().feet();
    }

    public boolean contains(Vec3d feet) {
        return feet.x >= attachment.minX && feet.x < attachment.maxX
                && feet.z >= attachment.minZ && feet.z < attachment.maxZ
                && feet.y >= attachment.minY && feet.y < attachment.maxY;
    }

    public boolean supportsExit(com.ariesninja.skulkpk.client.core.physics.ParkourState state) {
        return state.onGround() && Math.abs(state.feetPosition().y - attachment.maxY) < 1.0E-5
                && contains(state.feetPosition().add(0, -1.0E-6, 0));
    }
}
