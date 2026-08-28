package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.physics.*;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Short-horizon attachment controller shared by search and recovery. Never teleports a grab. */
public final class LadderContinuation {
    private LadderContinuation() {}

    public static ControlFrame choose(PhysicsWorld world, ParkourPhysics physics, ParkourState state,
                                      LadderColumn column, Vec3d exit) {
        boolean attached = column.contains(state.feetPosition()) && !state.onGround();
        if (!attached) {
            ControlFrame toward = towards(state, exit, false, state.sprinting());
            return column.supportsExit(state) ? new ControlFrame(toward.forward(), toward.strafe(),
                    false, false, false, toward.desiredYaw(), ControlPhase.LADDER_EXIT) : toward;
        }
        Vec3d center = column.entry(state.feetPosition().y);
        Vec3d side = ControlInput.strafeDirection(column.intoWall());
        double lateral = state.feetPosition().subtract(center).dotProduct(side);
        double drift = state.velocity().dotProduct(side);
        // Keep the feet in the attachment cell while climbing; pressure into the backing
        // shape is allowed, and jump can climb without pressure (not a second jump impulse).
        Vec3d goal = center.add(column.intoWall().multiply(0.30))
                .subtract(side.multiply(drift * 4 + lateral));
        if (state.feetPosition().y >= exit.y - 0.02) goal = exit;
        List<ControlFrame> actions = new ArrayList<>();
        float heading = (float) Math.toDegrees(Math.atan2(-column.intoWall().x, column.intoWall().z));
        float yaw = state.yaw() + MathHelper.clamp(MathHelper.wrapDegrees(heading - state.yaw()), -12, 12);
        for (int forward = -1; forward <= 1; forward++) for (int strafe = -1; strafe <= 1; strafe++) {
            actions.add(new ControlFrame(forward, strafe, forward > 0, true, false, yaw, ControlPhase.LADDER));
        }
        actions.add(new ControlFrame(0, 0, false, false, true, yaw, ControlPhase.LADDER));
        Vec3d desired = goal;
        return actions.stream().min(Comparator.comparingDouble(frame -> {
            ParkourState next = state;
            try {
                for (int tick = 0; tick < 3; tick++) {
                    boolean onLadder = column.contains(next.feetPosition());
                    next = physics.tickState(world, next, new ControlInput(frame.forward(), frame.strafe(), frame.sprint(),
                            onLadder && frame.jump(), onLadder && frame.sneak(), frame.desiredYaw()));
                    if (next.onGround()) break;
                    if (!column.contains(next.feetPosition()) && next.feetPosition().y < exit.y) return 1.0E6;
                }
            } catch (RuntimeException ignored) { return Double.MAX_VALUE; }
            Vec3d delta = next.feetPosition().subtract(desired);
            return delta.x * delta.x * 20 + delta.z * delta.z * 20
                    + Math.max(0, exit.y - next.feetPosition().y) * 3
                    + Math.abs(next.velocity().dotProduct(side)) * 2 + (frame.sneak() ? 0.05 : 0);
        })).orElseThrow();
    }

    public static ControlFrame towards(ParkourState state, Vec3d destination, boolean jump, boolean sprint) {
        Vec3d direction = destination.subtract(state.feetPosition()).multiply(1, 0, 1).normalize();
        float desired = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float camera = state.yaw() + MathHelper.clamp(MathHelper.wrapDegrees(desired - state.yaw()), -12, 12);
        double radians = Math.toRadians(camera);
        Vec3d heading = new Vec3d(-Math.sin(radians), 0, Math.cos(radians));
        double forward = direction.dotProduct(heading), strafe = direction.dotProduct(ControlInput.strafeDirection(heading));
        return new ControlFrame(Math.abs(forward) > 0.38 ? (float) Math.signum(forward) : 0,
                Math.abs(strafe) > 0.38 ? (float) Math.signum(strafe) : 0, sprint, jump, false,
                camera, state.onGround() ? ControlPhase.TAKEOFF : ControlPhase.AIRBORNE);
    }
}
