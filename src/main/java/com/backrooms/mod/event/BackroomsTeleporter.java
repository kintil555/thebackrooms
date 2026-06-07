package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Utility class for safe teleportation into the Backrooms dimension.
 * ITeleporter was removed in 1.21.1; teleportation now uses DimensionTransition directly.
 */
public class BackroomsTeleporter {

    /**
     * Carves out a 3x2x3 breathable space at the landing location
     * so the player isn't teleported inside a solid block.
     */
    public static void ensureSafeRoom(ServerLevel level, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
                    if (!level.getBlockState(pos).isAir()) {
                        if (dy > 0 || level.getBlockState(pos.below()).isAir()) {
                            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
        }
        BackroomsMod.LOGGER.debug("[Backrooms] Safe room carved at {},{},{}", x, y, z);
    }
}
