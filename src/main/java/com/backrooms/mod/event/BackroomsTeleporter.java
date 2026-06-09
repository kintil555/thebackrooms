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
     * Cari posisi spawn yang aman — cek apakah titik XZ ini solid (bisa berdiri).
     * Kalau posisi itu di dalam lubang (pitfall), geser ke jembatan terdekat.
     * TIDAK pernah menulis blok baru ke world — menghormati generation yang sudah ada.
     *
     * Return: Y lantai yang aman untuk berdiri (player di-spawn di Y+1).
     */
    public static int findSafeFloorY(ServerLevel level, int x, int z) {
        // Coba posisi asli dulu — cek apakah Y=4 solid (lantai pitfalls)
        // atau Y=1 solid (lantai zone lain)
        for (int floorY : new int[]{4, 1}) {
            BlockPos floor = new BlockPos(x, floorY, z);
            if (!level.getBlockState(floor).isAir()) {
                return floorY;
            }
        }

        // Posisi asli adalah lubang — spiral outward sampai ketemu lantai solid
        for (int r = 1; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    for (int floorY : new int[]{4, 1}) {
                        BlockPos floor = new BlockPos(x + dx, floorY, z + dz);
                        if (!level.getBlockState(floor).isAir()) {
                            BackroomsMod.LOGGER.debug(
                                "[Backrooms] Safe floor found at {},{},{} (offset {},{})",
                                x + dx, floorY, z + dz, dx, dz);
                            return floorY;
                        }
                    }
                }
            }
        }

        // Fallback — kembalikan Y=1 dan biarkan generator handle
        return 1;
    }

    /**
     * Legacy method — masih dipanggil dari BackroomsCommand.
     * Sekarang tidak menulis blok apapun, hanya log.
     */
    public static void ensureSafeRoom(ServerLevel level, int x, int floorY, int z) {
        // Tidak melakukan apa-apa — generator sudah handle dengan benar.
        // Menulis blok di sini bisa merusak lubang pitfalls atau struktur lain.
        BackroomsMod.LOGGER.debug("[Backrooms] ensureSafeRoom skipped (no-op) at {},{},{}", x, floorY, z);
    }
}
