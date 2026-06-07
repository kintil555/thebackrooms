package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Utility untuk teleportasi aman ke dimensi Backrooms.
 *
 * DimensionTransition digunakan langsung (ITeleporter dihapus di 1.21.1).
 * Class ini hanya bertanggung jawab untuk memastikan area pendaratan aman.
 */
public class BackroomsTeleporter {

    /**
     * Membuat ruang aman di titik pendaratan agar player tidak masuk ke block solid.
     * Lantai juga dipastikan solid — pakai sandstone (cocok dengan estetika backrooms).
     */
    public static void ensureSafeRoom(ServerLevel level, int x, int y, int z) {
        // Pastikan lantai solid (sandstone = wallpaper backrooms)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos floor = new BlockPos(x + dx, y - 1, z + dz);
                if (level.getBlockState(floor).isAir()) {
                    level.setBlockAndUpdate(floor, Blocks.SANDSTONE.defaultBlockState());
                }
                // Bersihkan ruang 3 blok ke atas
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
                    if (!level.getBlockState(pos).isAir()) {
                        level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        BackroomsMod.LOGGER.debug("[Backrooms] Safe landing zone carved at {},{},{}", x, y, z);
    }
}
