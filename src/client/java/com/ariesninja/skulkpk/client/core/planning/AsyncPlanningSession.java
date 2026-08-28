package com.ariesninja.skulkpk.client.core.planning;

import com.ariesninja.skulkpk.client.core.analysis.SnapshotWorld;
import com.ariesninja.skulkpk.client.core.analysis.WorldView;
import net.minecraft.util.math.Box;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Client-thread snapshot capture, isolated bounded worker, nonblocking result handoff. */
public final class AsyncPlanningSession implements PlanningSession {
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), task -> {
                Thread thread = new Thread(task, "Skulk trajectory search");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
    private final PlanningRequest request;
    private final SnapshotWorld.Capture capture;
    private final long deadline;
    private Future<Outcome> future;
    private volatile boolean cancelled;
    private SnapshotWorld snapshot;
    private PlanningTickResult terminal;

    public AsyncPlanningSession(PlanningRequest request, Box bounds) {
        this.request = request;
        capture = SnapshotWorld.capture(request.world(), bounds);
        // Search retains its policy limit. Capture/queueing is also bounded independently.
        deadline = System.nanoTime() + request.policy().maximumWallNanos() + 500_000_000L;
    }

    @Override public PlanningTickResult tick(long budgetNanos) {
        if (terminal != null) return terminal;
        if (cancelled) return reject("Planning was cancelled.");
        if (System.nanoTime() >= deadline) {
            cancel();
            return reject("Planning exceeded its bounded capture/search deadline.");
        }
        try {
            if (future == null) {
                if (!capture.tick(Math.min(budgetNanos, 2_000_000L))) return new PlanningTickResult.Planning(0);
                WORKER.purge();
                future = WORKER.submit(() -> {
                    SnapshotWorld frozen = capture.finish();
                    SearchPlanningSession search = new SearchPlanningSession(new PlanningRequest(frozen,
                            request.player(), request.target(), request.policy(), request.problem()));
                    PlanningTickResult result;
                    do {
                        if (cancelled || Thread.currentThread().isInterrupted()) {
                            search.cancel();
                            throw new java.util.concurrent.CancellationException();
                        }
                        result = search.tick(10_000_000L);
                    } while (result instanceof PlanningTickResult.Planning);
                    return new Outcome(frozen, result);
                });
            }
            if (!future.isDone()) return new PlanningTickResult.Planning(0);
            Outcome result = future.get(); // isDone above: never waits on the render thread.
            snapshot = result.world;
            terminal = result.result;
            return terminal;
        } catch (Exception exception) {
            cancel();
            org.slf4j.LoggerFactory.getLogger("Skulk/Trajectory").warn("Snapshot search failed", exception);
            return reject("The isolated trajectory search could not complete safely.");
        }
    }

    public WorldView executionWorld() {
        if (snapshot == null) throw new IllegalStateException("No completed snapshot.");
        return snapshot.validatedBy(request.world());
    }
    @Override public void cancel() {
        cancelled = true;
        if (future != null) future.cancel(true);
        WORKER.purge();
    }
    private PlanningTickResult reject(String message) {
        terminal = new PlanningTickResult.Rejected(PlanRejectionReason.SEARCH_TIMEOUT, message, 0);
        return terminal;
    }
    private record Outcome(SnapshotWorld world, PlanningTickResult result) {}
}
