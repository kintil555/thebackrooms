package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.block.GhostWallBlock;
import com.backrooms.mod.block.ModBlocks;
import com.backrooms.mod.blockentity.NullZoneBlockEntity;
import com.backrooms.mod.world.NullZoneManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Random;

/**
 * Handler utama untuk sistem Null Zone.
 *
 * ═══════════════════════════════════════════════════════════════
 * KONSEP (sesuai lore Kane Pixels):
 *
 *   Null Zone = kolom tak kasatmata dimana fabric of reality menipis.
 *   Blok yang ada di kolom ini masih TERLIHAT normal (rendering asli),
 *   tapi sudah tidak punya collision — player bisa menembus (noclip).
 *
 *   Kolom null zone: dari surface Y sampai Y_MIN, satu blok lebarnya.
 *   Bedrocknya tetap di tempat TAPI ditandai sebagai trigger teleport.
 *
 * ═══════════════════════════════════════════════════════════════
 * MEKANISME:
 *
 * 1. CHUNK LOAD (~6.7% chunk mendapat null zone):
 *    - Pilih 1 titik acak (x, z) di dalam chunk
 *    - Dari surface Y ke bawah sampai bedrock:
 *        * Simpan originalBlockState di NullZoneBlockEntity
 *        * Ganti dengan GhostWallBlock (no collision, render seperti asli)
 *        * Bedrock dibiarkan TETAP sebagai bedrock (trigger teleport)
 *    - Catat posisi di NullZoneManager
 *
 * 2. PLAYER TICK (setiap 5 tick):
 *    - Cek apakah player menyentuh bedrock DI DALAM null zone area
 *    - Jika ya → trigger teleport ke backrooms dimension
 *
 * ═══════════════════════════════════════════════════════════════
 * MENGAPA TIDAK MENYENTUH SEMUA BLOK:
 *   Hanya 1 kolom per ~15 chunk → tidak merusak terrain secara visual
 *   Blok sekitarnya tetap normal
 */
public class NullZoneEventHandler {

    /** 1 dari N chunk mendapat null zone (~6.7%). Naikkan N untuk lebih jarang. */
    private static final int NULL_ZONE_SPAWN_CHANCE = 15;

    /** Y dimana bedrock berada (trigger teleport jika di null zone). */
    private static final int BEDROCK_Y = -64;

    /** Player dianggap "menyentuh bedrock" jika Y <= threshold ini. */
    private static final int TELEPORT_TRIGGER_Y = -62;

    // ─── Chunk Load ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.dimension().equals(Level.OVERWORLD)) return;
        if (serverLevel.isClientSide()) return;

        ChunkAccess chunkAccess = event.getChunk();
        if (!(chunkAccess instanceof LevelChunk chunk)) return;

        ChunkPos chunkPos = chunk.getPos();

        // Seed deterministik per-chunk: world seed + koordinat chunk
        long chunkSeed = serverLevel.getSeed()
                ^ ((long) chunkPos.x * 341873128712L)
                ^ ((long) chunkPos.z * 132897987541L);
        Random rng = new Random(chunkSeed);

        if (rng.nextInt(NULL_ZONE_SPAWN_CHANCE) != 0) return;

        // Jadwalkan di server tick berikutnya — aman saat chunk loading
        final int localX = rng.nextInt(16);
        final int localZ = rng.nextInt(16);

        serverLevel.getServer().execute(() -> {
            if (!serverLevel.hasChunk(chunkPos.x, chunkPos.z)) return;
            // Cek apakah sudah ada null zone di sini (dari sesi sebelumnya)
            int worldX = chunkPos.getMinBlockX() + localX;
            int worldZ = chunkPos.getMinBlockZ() + localZ;
            if (NullZoneManager.isNullZone(serverLevel.dimension(), worldX, worldZ)) return;
            placeNullZone(serverLevel, worldX, worldZ);
        });
    }

    // ─── Place Null Zone Column ───────────────────────────────────────────────

    /**
     * Ganti satu kolom vertikal (x, z) dari surface ke Y_MIN dengan GhostWallBlock.
     * Setiap ghost block menyimpan originalBlockState di BlockEntity-nya.
     * Bedrock dibiarkan — dipakai sebagai trigger teleport.
     */
    private void placeNullZone(ServerLevel level, int worldX, int worldZ) {
        // Cari surface Y (posisi tertinggi blok solid)
        int surfaceY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;

        // Batasi: jangan buat null zone di void atau sangat tinggi
        if (surfaceY < -60 || surfaceY > 320) {
            surfaceY = 64;
        }

        BlockState ghostState = ModBlocks.GHOST_WALL.get().defaultBlockState();
        int ghostCount = 0;

        // Dari surface ke bedrock (tidak termasuk bedrock itu sendiri)
        for (int y = surfaceY; y > BEDROCK_Y; y--) {
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            BlockState existing = level.getBlockState(pos);

            // Skip: sudah jadi ghost block
            if (existing.is(ModBlocks.GHOST_WALL.get())) continue;
            // Skip: bedrock — biarkan jadi trigger teleport
            if (existing.is(Blocks.BEDROCK)) continue;
            // Skip: air dan void — tidak perlu ghost block di sini
            if (existing.isAir() && y < 0) continue;

            // Simpan originalState dan ganti dengan ghost block
            BlockState originalState = existing;
            level.setBlock(pos, ghostState, 2 | 16); // flag 2=update client, 16=no neighbor update

            // Simpan originalState ke BlockEntity
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof NullZoneBlockEntity nullZoneBE) {
                nullZoneBE.setOriginalBlockState(originalState);
                level.sendBlockUpdated(pos, ghostState, ghostState, 2);
            }

            ghostCount++;
        }

        // Catat posisi null zone
        NullZoneManager.register(level.dimension(), new BlockPos(worldX, 0, worldZ));

        BackroomsMod.LOGGER.info(
                "[Backrooms] ✦ Null Zone placed at X={} Z={} (surface Y={}, {} blocks converted)",
                worldX, worldZ, surfaceY, ghostCount);
    }

    // ─── Player Tick: Deteksi Teleport ────────────────────────────────────────

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (!player.level().dimension().equals(Level.OVERWORLD)) return;
        if (player.tickCount % 5 != 0) return;

        int playerY = player.getBlockY();

        // Player harus cukup rendah (dekat bedrock) untuk trigger
        if (playerY > TELEPORT_TRIGGER_Y) return;

        // Cek apakah player berada di dalam kolom null zone
        boolean inNullZone = NullZoneManager.isNullZone(
                player.level().dimension(), player.getBlockX(), player.getBlockZ());

        if (!inNullZone) return;

        // Player di null zone dan dekat bedrock → noclip trigger!
        if (player.level() instanceof ServerLevel sl) {
            BackroomsMod.LOGGER.info(
                    "[Backrooms] Player {} noclipped at null zone X={} Z={} Y={}",
                    player.getName().getString(),
                    player.getBlockX(), player.getBlockZ(), playerY);
            GhostWallBlock.triggerBackroomsTransition(player, sl);
        }
    }
}
