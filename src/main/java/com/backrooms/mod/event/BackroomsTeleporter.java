package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Utility landing zone untuk backrooms.
 * Y=1 = karpet brown wool (lantai), player spawn di Y=2.
 */
public class BackroomsTeleporter {

    public static void ensureSafeRoom(ServerLevel level, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos floor = new BlockPos(x + dx, y, z + dz);
                if (level.getBlockState(floor).isAir())
                    level.setBlockAndUpdate(floor, Blocks.BROWN_WOOL.defaultBlockState());
                for (int dy = 1; dy <= 4; dy++) {
                    BlockPos ap = new BlockPos(x + dx, y + dy, z + dz);
                    if (!level.getBlockState(ap).isAir())
                        level.setBlockAndUpdate(ap, Blocks.AIR.defaultBlockState());
                }
            }
        }
        BackroomsMod.LOGGER.debug("[Backrooms] Landing zone cleared at {},{},{}", x, y, z);
    }
}
