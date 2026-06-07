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
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Random;

/**
 * Handler untuk:
 *
 * 1. CHUNK LOAD — Saat chunk overworld dimuat, ada peluang 1-dari-N chunk
 *    mendapat kolom Null Zone: ghost block dari permukaan sampai Y=-63.
 *    Berdasarkan lore Kane Pixels: Null Zone muncul secara acak dan tiba-tiba.
 *
 * 2. PLAYER TICK — Fallback check setiap 10 tick.
 *    Jika player berada di dalam ghost block dan Y <= threshold → teleport.
 *    Ini diperlukan karena entityInside() tidak selalu reliable di 1.21.1
 *    untuk block dengan Shapes.empty() collision.
 */
public class NullZoneEventHandler {

    /** 1 dari N chunk mendapat Null Zone (~6.7% chunk). */
    private static final int NULL_ZONE_SPAWN_CHANCE = 15;

    /** Y threshold teleport — cukup dekat dengan bedrock di Y=-64. */
    private static final int TELEPORT_THRESHOLD_Y = -58;

    // ─── Chunk Load ───────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.dimension().equals(Level.OVERWORLD)) return;
        if (serverLevel.isClientSide()) return;

        ChunkAccess chunkAccess = event.getChunk();
        if (!(chunkAccess instanceof LevelChunk chunk)) return;

        ChunkPos chunkPos = chunk.getPos();

        long chunkSeed = serverLevel.getSeed()
                ^ ((long) chunkPos.x * 341873128712L)
                ^ ((long) chunkPos.z * 132897987541L);
        Random rng = new Random(chunkSeed);

        if (rng.nextInt(NULL_ZONE_SPAWN_CHANCE) == 0) {
            // Jadwalkan di next server tick — aman dari crash saat chunk loading
            serverLevel.getServer().execute(() -> {
                if (!serverLevel.hasChunk(chunkPos.x, chunkPos.z)) return;
                placeNullZone(serverLevel, chunkPos, rng);
            });
        }
    }

    // ─── Player Tick Fallback ─────────────────────────────────────────────────

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (!player.level().dimension().equals(Level.OVERWORLD)) return;
        if (player.tickCount % 10 != 0) return;
        if (player.getBlockY() > TELEPORT_THRESHOLD_Y) return;

        BlockPos feetPos = player.blockPosition();
        boolean inGhost = player.level().getBlockState(feetPos).is(ModBlocks.GHOST_WALL.get())
                || player.level().getBlockState(feetPos.above()).is(ModBlocks.GHOST_WALL.get());

        boolean inNullZoneCol = NullZoneManager.isNullZone(
                player.level().dimension(), player.getBlockX(), player.getBlockZ());

        if (inGhost || inNullZoneCol) {
            if (player.level() instanceof ServerLevel sl) {
                GhostWallBlock.triggerBackroomsTransition(player, sl);
            }
        }
    }

    // ─── Null Zone Placement ──────────────────────────────────────────────────

    private void placeNullZone(ServerLevel level, ChunkPos chunkPos, Random rng) {
        int localX = rng.nextInt(16);
        int localZ = rng.nextInt(16);
        int worldX = chunkPos.getMinBlockX() + localX;
        int worldZ = chunkPos.getMinBlockZ() + localZ;

        // Surface Y — posisi awal ghost block column
        int surfaceY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG,
                worldX, worldZ);

        if (surfaceY < -60 || surfaceY > 320) surfaceY = 64;

        BlockState ghostState = ModBlocks.GHOST_WALL.get().defaultBlockState();

        // Ghost block dari permukaan sampai Y=-63
        // Y=-64 = bedrock → dibiarkan sebagai trigger
        for (int y = surfaceY; y >= -63; y--) {
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            BlockState existing = level.getBlockState(pos);
            if (existing.is(ModBlocks.GHOST_WALL.get())) continue;
            if (existing.is(Blocks.BEDROCK)) continue;
            level.setBlock(pos, ghostState, 2 | 16);
        }

        // Pastikan bedrock ada di Y=-64
        BlockPos bedrockPos = new BlockPos(worldX, -64, worldZ);
        if (!level.getBlockState(bedrockPos).is(Blocks.BEDROCK)) {
            level.setBlock(bedrockPos, Blocks.BEDROCK.defaultBlockState(), 2);
        }

        NullZoneManager.register(level.dimension(), new BlockPos(worldX, 0, worldZ));

        BackroomsMod.LOGGER.debug("[Backrooms] ✦ Null Zone at X={} Z={} (surface Y={}) chunk {}",
                worldX, worldZ, surfaceY, chunkPos);
    }
}
