package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Utility untuk memastikan area pendaratan aman di Backrooms.
 *
 * Backrooms generator membuat ruangan, tapi player mungkin landing
 * di posisi yang kebetulan ada dinding. Method ini membersihkan
 * area 3×3 di landing point dan memastikan lantai ada.
 */
public class BackroomsTeleporter {

    /**
     * Memastikan area 3×3 di sekitar (x, y, z) clear dari block solid.
     * Lantai di Y-1 dipastikan solid (oak planks = lantai backrooms).
     */
    public static void ensureSafeRoom(ServerLevel level, int x, int y, int z) {
        // Pastikan lantai solid
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos floor = new BlockPos(x + dx, y - 1, z + dz);
                if (level.getBlockState(floor).isAir()) {
                    level.setBlockAndUpdate(floor, Blocks.OAK_PLANKS.defaultBlockState());
                }
                // Bersihkan 2 blok ke atas untuk ruang bernafas
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        BackroomsMod.LOGGER.debug("[Backrooms] Landing zone cleared at {},{},{}", x, y, z);
    }
}
