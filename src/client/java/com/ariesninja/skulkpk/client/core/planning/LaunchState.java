package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.physics.ControlInput;
import com.ariesninja.skulkpk.client.core.physics.ParkourState;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** A physically generated state immediately before the one allowed jump transition. */
public record LaunchState(
        ParkourState initialState,
        ParkourState state,
        LaunchLane lane,
        Vec3d stagingPosition,
        double runUpLength,
        int jumpTick,
        List<ControlInput> groundPrefix,
        boolean startsFromCurrentState,
        RouteMode approachMode
) {
    public LaunchState {
        groundPrefix = List.copyOf(groundPrefix);
        if (approachMode == null) approachMode = RouteMode.DIRECT;
    }

    public LaunchState(ParkourState initialState, ParkourState state, LaunchLane lane,
                       Vec3d stagingPosition, double runUpLength, int jumpTick,
                       List<ControlInput> groundPrefix, boolean startsFromCurrentState) {
        this(initialState, state, lane, stagingPosition, runUpLength, jumpTick,
                groundPrefix, startsFromCurrentState, RouteMode.DIRECT);
    }
}
