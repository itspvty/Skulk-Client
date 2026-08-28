package com.ariesninja.skulkpk.client.core;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkulkCommandsTest {
    @Test void selectsExactSignedBlockCoordinatesWithoutACamera() throws Exception {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        List<BlockPos> selected = new ArrayList<>();
        SkulkCommands.register(dispatcher, (source, target) -> selected.add(target));

        assertEquals(1, dispatcher.execute("skulk select 76 -59 42", null));
        assertEquals(1, dispatcher.execute("skulk select -12 100 -4", null));
        assertEquals(List.of(new BlockPos(76, -59, 42), new BlockPos(-12, 100, -4)), selected);
    }

    @Test void invalidCoordinatesNeverInvokeSelection() {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        SkulkCommands.register(dispatcher, (source, target) -> fail("Malformed command reached selection."));
        for (String command : List.of("skulk select 1 2", "skulk select 1.5 2 3",
                "skulk select ~ ~ ~", "skulk select 30000001 0 0", "skulk select 0 0 -30000001",
                "skulk select 0 2147483648 0", "skulk select 0 0 0 trailing")) {
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute(command, null), command);
        }
    }

    @Test void rejectedSelectionReturnsFailureWithoutReportingSuccess() throws Exception {
        var dispatcher = new CommandDispatcher<FabricClientCommandSource>();
        SkulkCommands.register(dispatcher, (source, target) -> false);
        assertEquals(0, dispatcher.execute("skulk select 1 2 3", null));
    }
}
