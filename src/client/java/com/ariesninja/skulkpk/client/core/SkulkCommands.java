package com.ariesninja.skulkpk.client.core;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.function.BiPredicate;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** Camera-independent selection; Fabric handles these commands locally, not on the server. */
public final class SkulkCommands {
    private SkulkCommands() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                register(dispatcher, (source, target) -> BlockSelector.selectBlock(target, source.getClient())));
    }

    static void register(CommandDispatcher<FabricClientCommandSource> dispatcher,
                         BiPredicate<FabricClientCommandSource, BlockPos> select) {
        dispatcher.register(literal("skulk")
                .executes(context -> {
                    context.getSource().sendFeedback(Text.literal(
                            "Skulk > /skulk select <x> <y> <z> selects a landing block. Then press H to execute; C cancels."));
                    return 1;
                })
                .then(literal("select")
                        .then(argument("x", integer(-30_000_000, 30_000_000))
                                .then(argument("y", integer())
                                        .then(argument("z", integer(-30_000_000, 30_000_000))
                                                .executes(context -> select.test(context.getSource(), new BlockPos(
                                                        getInteger(context, "x"), getInteger(context, "y"),
                                                        getInteger(context, "z"))) ? 1 : 0))))));
    }
}
