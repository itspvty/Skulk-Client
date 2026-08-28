package com.ariesninja.skulkpk.client;

import com.ariesninja.skulkpk.client.core.*;
import com.ariesninja.skulkpk.client.core.rendering.SelectionRenderer;
import com.ariesninja.skulkpk.client.core.utils.ModStateManager;
import com.ariesninja.skulkpk.client.license.LicenseInputScreen;
import com.ariesninja.skulkpk.client.license.LicenseManager;
import com.ariesninja.skulkpk.client.license.LicenseVerificationService;
import com.ariesninja.skulkpk.client.core.utils.ChatMessageUtil;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;

public class SkulkpkClient implements ClientModInitializer {
    private boolean isVerifyingLicense = false;

    @Override
    public void onInitializeClient() {
        Keybinds.register();
        SkulkCommands.register();
        SelectionRenderer.register();
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);

        // Register server join event to verify license
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // if their username starts with "Player", don't check the license.
            String username = client.getSession().getUsername();
            if (username.startsWith("Player")) {
                ChatMessageUtil.sendWarn(client, "Skipping license verification for test user: " + username);
                return;
            }
            verifyLicenseOnServerJoin(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            StepExecutor.getInstance().cancel(client, "Disconnected from world.");
            BlockSelector.clearSelectionSilent();
        });
    }

    private void onClientTick(MinecraftClient client) {
        // Check if mod is disabled before processing any functionality
        if (!ModStateManager.isModEnabled()) {
            StepExecutor.getInstance().cancel(client, "Mod disabled.");
            BlockSelector.clearSelectionSilent();
            return;
        }

        if (Keybinds.SELECT_KEY.wasPressed()) {
            if (StepExecutor.getInstance().isPlanning()) {
                StepExecutor.getInstance().cancel(client, "Planning cancelled for a new selection.");
            } else if (StepExecutor.getInstance().isExecuting()) {
                ChatMessageUtil.sendWarn(client, "Cancel the active jump before selecting another target.");
                return;
            }
            var cameraEntity = client.getCameraEntity();
            if (cameraEntity == null) return;

            var from = cameraEntity.getEyePos();
            var rotation = cameraEntity.getRotationVec(1.0f);
            var to = from.add(rotation.multiply(1000));

            var context = new RaycastContext(
                    from,
                    to,
                    RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE,
                    cameraEntity
            );
            var hit = client.world.raycast(context);

            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockSelector.selectBlock(hit, client);
                // Analysis is already handled inside BlockSelector.selectBlock()
            }
        }

        if (Keybinds.EXECUTE_KEY.wasPressed()) {
            StepExecutor.getInstance().executeSequence(client);
        }

        if (Keybinds.CLEAR_KEY.wasPressed()) {
            // If neither selection nor execution was active, show a general clear message
            if (BlockSelector.getSelectedBlock() == null && !StepExecutor.getInstance().isExecuting()) {
                if (client.player != null) {
                    ChatMessageUtil.sendInfo(client, "Nothing to clear");
                }
            }

            // Clear selection if present
            if (BlockSelector.getSelectedBlock() != null) {
                BlockSelector.clearSelection();
            }

            // Stop execution if active
            StepExecutor.getInstance().cancel(client, "Sequence execution cancelled.");
        }

        // Call tick methods for ongoing execution
        StepExecutor.getInstance().tick(client);
    }

    private void verifyLicenseOnServerJoin(MinecraftClient client) {
        if (isVerifyingLicense) return; // Prevent multiple verification attempts

        String storedLicense = LicenseManager.getStoredLicense();
        if (storedLicense == null) {
            // No license stored, show input screen
            client.execute(() -> client.setScreen(new LicenseInputScreen()));
            return;
        }

        // Verify the stored license with the API
        String username = client.getSession().getUsername();
        isVerifyingLicense = true;

        // Show verification message to player
        if (client.player != null) {
            ChatMessageUtil.sendWarn(client, "Verifying license...");
        }

        LicenseVerificationService.verifyLicense(username, storedLicense)
                .thenAccept(result -> {
                    client.execute(() -> {
                        isVerifyingLicense = false;

                        if (!result.isValid()) {
                            // License is invalid, show input screen with error and pre-filled license
                            if (client.player != null) {
                                ChatMessageUtil.sendError(client, "Stored license is invalid: " + result.getMessage());
                            }

                            // Show license input screen with error and pre-filled data
                            client.setScreen(new LicenseInputScreen("Automatic License Validation Failed", storedLicense));
                        } else {
                            // License is valid, allow continued play
                            if (client.player != null) {
                                ChatMessageUtil.sendSuccess(client, "License verified successfully!");
                            }
                        }
                    });
                })
                .exceptionally(throwable -> {
                    client.execute(() -> {
                        isVerifyingLicense = false;

                        if (client.player != null) {
                            ChatMessageUtil.sendError(client, "License verification error: " + throwable.getMessage());
                        }

                        // Show license input screen with error and pre-filled data for network errors too
                        client.setScreen(new LicenseInputScreen("License Verification Error", storedLicense));
                    });
                    return null;
                });
    }
}
