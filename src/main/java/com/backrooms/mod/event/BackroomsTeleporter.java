package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Utility teleport untuk Backrooms.
 *
 * Layout Y generator:
 *   Y=1  Brown Wool (lantai/karpet)   ← solid, player berdiri di atasnya
 *   Y=2  AIR                          ← kaki player
 *   Y=3  AIR                          ← kepala player
 *   Y=4  AIR
 *   Y=5  AIR
 *   Y=6  Smooth Stone / Froglight     ← ceiling
 *
 * Player di-spawn di Y=2.0 (berdiri di atas karpet Y=1).
 * TIDAK perlu ensureSafeRoom — generator sudah handle layout dengan benar.
 * Fungsi ini hanya dipakai jika chunk belum ter-generate saat teleport terjadi.
 */
public class BackroomsTeleporter {

    /**
     * Cek apakah posisi spawn aman (Y=2 dan Y=3 harus udara, Y=1 harus solid).
     * Kalau tidak aman (misal chunk belum gen), paksa clear area minimal.
     * TIDAK membuat blok solid baru di area udara.
     */
    public static void ensureSafeRoom(ServerLevel level, int x, int floorY, int z) {
        // floorY = Y=1 (karpet). Player spawn di floorY+1 = Y=2.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                // Pastikan lantai ada (Y=1)
                BlockPos floor = new BlockPos(x + dx, floorY, z + dz);
                BlockState floorState = level.getBlockState(floor);
                if (floorState.isAir()) {
                    // Chunk belum gen — pasang lantai darurat
                    level.setBlockAndUpdate(floor, Blocks.BROWN_WOOL.defaultBlockState());
                }

                // Bersihkan Y=2,3,4,5 — HANYA jika ada blok solid (pilar, dinding salah gen)
                // Jangan sentuh jika sudah udara (kondisi normal)
                for (int dy = 1; dy <= 4; dy++) {
                    BlockPos airPos = new BlockPos(x + dx, floorY + dy, z + dz);
                    BlockState st = level.getBlockState(airPos);
                    // Hanya bersihkan jika bukan udara DAN bukan ceiling (Y=6)
                    if (!st.isAir() && (floorY + dy) < 6) {
                        level.setBlockAndUpdate(airPos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        BackroomsMod.LOGGER.debug("[Backrooms] Spawn zone checked at {},{},{}", x, floorY, z);
    }
}
