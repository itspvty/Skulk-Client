package com.ariesninja.skulkpk.client.mixin;

import com.ariesninja.skulkpk.client.core.Keybinds;
import com.ariesninja.skulkpk.client.core.StepExecutor;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public abstract class KeyboardMixin {
    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void skulk$cancelOnKeyboardPress(long window, int key, int scanCode,
                                           int action, int modifiers, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        // Real key-down events only: not simulated movement bindings, key releases,
        // mouse input, or repeats from the H press that started the jump.
        if (action != GLFW.GLFW_PRESS || window != client.getWindow().getHandle()) return;
        StepExecutor executor = StepExecutor.getInstance();
        if (!executor.isExecuting()) return;

        executor.cancel(client, "Jump cancelled by keyboard input.");
        // Don't let the cancelling H/G press immediately start another operation.
        // Other keys continue through vanilla normally, handing control to the player.
        if (Keybinds.EXECUTE_KEY.matchesKey(key, scanCode)
                || Keybinds.SELECT_KEY.matchesKey(key, scanCode)) ci.cancel();
    }
}
