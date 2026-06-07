package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.block.GhostWallBlock;
import com.backrooms.mod.block.ModBlocks;
import com.backrooms.mod.world.NullZoneManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Random;

/**
 * Menangani dua hal:
 *
 * 1. CHUNK LOAD — Saat chunk overworld dimuat, ada peluang 1-dari-N chunk mendapat
 *    satu kolom Null Zone: kolom ghost block dari permukaan sampai Y=-63.
 *    Bedrock di Y=-64 dibiarkan utuh; ketika player jatuh sampai sana, mereka
 *    ter-teleport ke The Backrooms (sesuai lore Kane Pixels — Null Zone = area
 *    di mana gelombang elektromagnetik saling meniadakan, membuat portal sementara).
 *
 * 2. PLAYER TICK — Setiap tick, cek apakah player berada di dalam ghost block
 *    DAN posisi Y mereka sudah sangat rendah. Ini adalah fallback yang penting
 *    karena entityInside() tidak selalu terpanggil reliably untuk block tanpa
 *    collision di semua versi Forge.
 */
public class NullZoneEventHandler {

    /**
     * 1 dari N chunk overworld mendapat Null Zone.
     * Nilai kecil = lebih banyak null zone. Default: 15 (sekitar 6-7% chunk).
     */
    private static final int NULL_ZONE_SPAWN_CHANCE = 15;

    /** Y threshold — jika player ada di ghost block dan Y <= ini, teleport. */
    private static final int TELEPORT_THRESHOLD_Y = -58;

    // ─── Chunk Load Handler ───────────────────────────────────────────────────

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        // Hanya di server-side Overworld
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.dimension().equals(Level.OVERWORLD)) return;
        if (serverLevel.isClientSide()) return;

        ChunkAccess chunkAccess = event.getChunk();
        // Hanya proses LevelChunk yang sudah fully generated, bukan ProtoChunk
        if (!(chunkAccess instanceof LevelChunk chunk)) return;

        ChunkPos chunkPos = chunk.getPos();

        // Seed deterministik per-chunk: null zone selalu di tempat yang sama
        // untuk world seed yang sama (konsisten setiap load/unload)
        long chunkSeed = serverLevel.getSeed()
                ^ ((long) chunkPos.x * 341873128712L)
                ^ ((long) chunkPos.z * 132897987541L);
        Random rng = new Random(chunkSeed);

        if (rng.nextInt(NULL_ZONE_SPAWN_CHANCE) == 0) {
            // Jadwalkan placement di next tick agar tidak crash saat chunk sedang loading
            serverLevel.getServer().execute(() -> {
                // Cek apakah level masih valid
                if (!serverLevel.hasChunk(chunkPos.x, chunkPos.z)) return;
                placeNullZone(serverLevel, chunkPos, rng);
            });
        }
    }

    // ─── Player Tick Fallback Handler ─────────────────────────────────────────

    /**
     * Fallback tick handler — cek setiap 10 tick (0.5 detik) apakah player
     * sedang berada di dalam ghost block dan Y-nya sudah sangat rendah.
     *
     * Ini memastikan teleport terjadi bahkan jika entityInside() tidak terpanggil.
     */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (!player.level().dimension().equals(Level.OVERWORLD)) return;

        // Cek setiap 10 tick untuk performa
        if (player.tickCount % 10 != 0) return;

        // Apakah player sudah cukup dalam dan berada di area null zone?
        if (player.getBlockY() > TELEPORT_THRESHOLD_Y) return;

        // Cek apakah block di posisi player adalah ghost block
        BlockPos feetPos = player.blockPosition();
        BlockState feetBlock = player.level().getBlockState(feetPos);
        BlockState headBlock = player.level().getBlockState(feetPos.above());

        boolean inGhostBlock = feetBlock.is(ModBlocks.GHOST_WALL.get())
                || headBlock.is(ModBlocks.GHOST_WALL.get());

        // Juga teleport jika player ada di null zone column (sudah ditrack)
        // bahkan jika ghost block belum di-set (race condition saat chunk load)
        boolean inNullZoneColumn = NullZoneManager.isNullZone(
                player.level().dimension(), player.getBlockX(), player.getBlockZ());

        if (inGhostBlock || (inNullZoneColumn && player.getBlockY() <= TELEPORT_THRESHOLD_Y)) {
            if (player.level() instanceof ServerLevel serverLevel) {
                GhostWallBlock.triggerBackroomsTransition(player, serverLevel);
            }
        }
    }

    // ─── Null Zone Placement ──────────────────────────────────────────────────

    /**
     * Menempatkan kolom Null Zone di chunk:
     *
     * - Pilih satu titik X,Z acak dalam chunk 16×16
     * - Ganti semua block dari permukaan sampai Y=-63 dengan ghost_wall
     *   (invisible, no collision — player bisa tembus / noclip)
     * - Bedrock di Y=-64 dibiarkan utuh → trigger teleport
     *
     * Konsep dari Kane Pixels:
     *   "Null Zones adalah area di mana seseorang bisa menembus,
     *    terutama jatuh dari dunia nyata ke The Backrooms."
     */
    private void placeNullZone(ServerLevel level, ChunkPos chunkPos, Random rng) {
        int localX = rng.nextInt(16);
        int localZ = rng.nextInt(16);
        int worldX = chunkPos.getMinBlockX() + localX;
        int worldZ = chunkPos.getMinBlockZ() + localZ;

        // Cari tinggi permukaan (WORLD_SURFACE = block teratas yang bukan udara)
        int surfaceY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                worldX, worldZ);

        // Pastikan surfaceY masuk akal
        if (surfaceY < -60 || surfaceY > 320) {
            surfaceY = 64; // fallback ke sea level jika aneh
        }

        BlockState ghostState = ModBlocks.GHOST_WALL.get().defaultBlockState();

        // Ganti seluruh kolom dari permukaan sampai Y=-63 dengan ghost block
        // Y=-64 adalah bedrock — dibiarkan agar trigger teleport
        for (int y = surfaceY; y >= -63; y--) {
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            BlockState existing = level.getBlockState(pos);

            // Skip jika sudah ghost block atau bedrock
            if (existing.is(ModBlocks.GHOST_WALL.get())) continue;
            if (existing.is(Blocks.BEDROCK)) continue;

            // Flag 2+16 = kirim ke client, update lighting
            level.setBlock(pos, ghostState, 2 | 16);
        }

        // Pastikan bedrock ada di dasar null zone
        BlockPos bedrockPos = new BlockPos(worldX, -64, worldZ);
        if (!level.getBlockState(bedrockPos).is(Blocks.BEDROCK)) {
            level.setBlock(bedrockPos, Blocks.BEDROCK.defaultBlockState(), 2);
        }

        // Register ke NullZoneManager untuk tracking
        NullZoneManager.register(level.dimension(), new BlockPos(worldX, 0, worldZ));

        BackroomsMod.LOGGER.debug(
                "[Backrooms] ✦ Null Zone placed at X={} Z={} (surface Y={}) in chunk {}",
                worldX, worldZ, surfaceY, chunkPos);
    }
}
