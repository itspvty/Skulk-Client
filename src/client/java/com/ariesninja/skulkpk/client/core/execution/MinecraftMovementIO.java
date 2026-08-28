package com.ariesninja.skulkpk.client.core.execution;

import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.physics.ParkourState;
import com.ariesninja.skulkpk.client.core.planning.ControlFrame;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;

/** Live key/yaw adapter. Observation N exposes the command written at END_CLIENT_TICK N-1. */
public final class MinecraftMovementIO implements MovementIO {
    private static final float MAX_YAW_CHANGE = 12;
    private long appliedEpoch;

    @Override
    public MovementObservation observe(MinecraftClient client) {
        if (client == null || client.player == null) {
            throw new IllegalStateException("The live player is unavailable.");
        }
        return new MovementObservation(ParkourState.capture(PlayerSnapshot.capture(client.player))
                .withCollisions(client.player.horizontalCollision, client.player.verticalCollision,
                        client.player.collidedSoftly),
                appliedEpoch);
    }

    @Override
    public long apply(MinecraftClient client, ControlFrame frame, boolean targetSupported) {
        if (client == null || client.player == null || client.options == null) {
            throw new IllegalStateException("Minecraft movement input is unavailable.");
        }
        PlayerEntity player = client.player;
        float delta = MathHelper.wrapDegrees(frame.desiredYaw() - player.getYaw());
        player.setYaw(player.getYaw() + MathHelper.clamp(delta, -MAX_YAW_CHANGE, MAX_YAW_CHANGE));
        boolean ladder = !player.isOnGround() && player.isClimbing()
                && player.getWorld().getBlockState(player.getBlockPos()).getBlock() instanceof net.minecraft.block.LadderBlock;
        boolean sneak = frame.allowsSneak(player.isOnGround(), targetSupported, ladder);
        client.options.forwardKey.setPressed(frame.forward() > 0.01);
        client.options.backKey.setPressed(frame.forward() < -0.01);
        client.options.leftKey.setPressed(frame.strafe() > 0.01);
        client.options.rightKey.setPressed(frame.strafe() < -0.01);
        client.options.sprintKey.setPressed(frame.sprint());
        client.options.jumpKey.setPressed(frame.jump()
                && (frame.phase() != com.ariesninja.skulkpk.client.core.planning.ControlPhase.LADDER || ladder));
        client.options.sneakKey.setPressed(sneak);
        return ++appliedEpoch;
    }

    @Override
    public void release(MinecraftClient client) {
        if (client == null || client.options == null) return;
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.sneakKey.setPressed(false);
    }
}
