package com.ariesninja.skulkpk.client.core;

import com.ariesninja.skulkpk.client.core.analysis.JumpProblem;
import com.ariesninja.skulkpk.client.core.analysis.JumpProblemResult;
import com.ariesninja.skulkpk.client.core.analysis.MinecraftWorldView;
import com.ariesninja.skulkpk.client.core.analysis.PlayerSnapshot;
import com.ariesninja.skulkpk.client.core.rendering.SelectionRenderer;
import com.ariesninja.skulkpk.client.core.utils.ChatMessageUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

public final class BlockSelector {
    private static final JumpAnalyzer ANALYZER = new JumpAnalyzer();
    private static JumpProblem currentProblem;

    private BlockSelector() {}

    public static void selectBlock(BlockHitResult hitResult, MinecraftClient client) {
        if (hitResult == null || client.player == null || client.world == null) return;

        JumpProblemResult result = ANALYZER.analyzeProblem(
                new MinecraftWorldView(client.world),
                PlayerSnapshot.capture(client.player),
                hitResult.getBlockPos());
        storeProblemResult(result);

        if (result instanceof JumpProblemResult.Valid valid) {
            SelectionRenderer.showHighlights();
            ChatMessageUtil.sendSuccess(client, "Selected block at: " + valid.problem().selectedBlock().toShortString());
        } else if (result instanceof JumpProblemResult.Rejected rejected) {
            SelectionRenderer.hideAllHighlights();
            ChatMessageUtil.sendError(client, rejected.message());
        }
    }

    static void storeProblemResult(JumpProblemResult result) {
        currentProblem = result instanceof JumpProblemResult.Valid valid ? valid.problem() : null;
    }

    public static JumpProblemResult refreshProblem(MinecraftClient client) {
        if (currentProblem == null || client.player == null || client.world == null) {
            return new JumpProblemResult.Rejected(
                    com.ariesninja.skulkpk.client.core.analysis.JumpRejectionReason.NO_LANDING,
                    "No valid jump selected! Use SELECT first.");
        }
        JumpProblemResult result = ANALYZER.analyzeProblem(new MinecraftWorldView(client.world),
                PlayerSnapshot.capture(client.player), currentProblem.selectedBlock());
        storeProblemResult(result);
        return result;
    }

    public static Optional<JumpProblem> getCurrentProblem() { return Optional.ofNullable(currentProblem); }

    public static BlockPos getSelectedBlock() {
        return currentProblem == null ? null : currentProblem.selectedBlock();
    }

    public static void clearSelectionSilent() {
        currentProblem = null;
        SelectionRenderer.hideAllHighlights();
    }

    public static void clearSelection() {
        clearSelectionSilent();
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) ChatMessageUtil.sendWarn(client, "Selection cleared");
    }
}
