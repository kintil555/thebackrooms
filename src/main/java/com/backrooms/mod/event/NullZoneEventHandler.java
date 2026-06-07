package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.block.GhostWallBlock;
import com.backrooms.mod.world.NullZoneManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Random;

/**
 * Handler untuk efek "noclip" backrooms — sesuai lore Kane Pixels.
 *
 * PENDEKATAN BARU (tidak merusak terrain overworld):
 *
 * 1. CHUNK LOAD — Saat chunk overworld dimuat, ada peluang 1-dari-N chunk
 *    menjadi "null zone" (hanya dicatat di NullZoneManager sebagai data, TIDAK
 *    ada penempatan block di terrain). Terrain overworld sama sekali tidak diubah.
 *
 * 2. PLAYER TICK — Setiap 5 tick, cek apakah player di overworld berada di:
 *    a) Posisi null zone DAN sedang berada di Y rendah (jatuh ke void/bedrock),
 *    b) Atau menyentuh/berdiri di bedrock floor (Y=-64 atau Y=-63) di mana pun.
 *
 *    Trigger: jika kondisi terpenuhi → teleport ke backrooms dimension.
 *
 *    Ini mensimulasikan: player berjalan normal → tanpa sadar masuk area
 *    null zone → jatuh tembus bedrock → "noclip out of reality".
 *
 * MENGAPA TIDAK PAKAI GHOST BLOCK DI TERRAIN:
 *   Menempatkan ghost block menggantikan grass/dirt/stone merusak terrain
 *   overworld secara visual (blok melayang, terrain bolong).
 *   Lore backrooms tidak memerlukan kerusakan terrain — cukup deteksi
 *   posisi dan trigger teleport saat player "menembus" batas realitas.
 */
public class NullZoneEventHandler {

    /**
     * 1 dari N chunk punya null zone (~6.7% chunk).
     * Null zone = zona tak kasat mata tempat realitas "tipis".
     */
    private static final int NULL_ZONE_SPAWN_CHANCE = 15;

    /**
     * Saat player di bawah Y ini di null zone → trigger teleport.
     * Biasanya terjadi saat player jatuh ke void atau mine ke bedrock.
     */
    private static final int NULL_ZONE_TRIGGER_Y = -58;

    /**
     * Player yang menyentuh bedrock di posisi MANAPUN (termasuk di luar null zone)
     * punya peluang kecil noclip — sesuai lore "berlari dan tiba-tiba tembus".
     */
    private static final int BEDROCK_TRIGGER_Y = -62;

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

        // Hanya catat null zone — TIDAK ubah block apapun di terrain
        if (rng.nextInt(NULL_ZONE_SPAWN_CHANCE) == 0) {
            int localX = rng.nextInt(16);
            int localZ = rng.nextInt(16);
            int worldX = chunkPos.getMinBlockX() + localX;
            int worldZ = chunkPos.getMinBlockZ() + localZ;

            NullZoneManager.register(serverLevel.dimension(),
                    new BlockPos(worldX, 0, worldZ));

            BackroomsMod.LOGGER.debug(
                    "[Backrooms] ✦ Null Zone registered at X={} Z={} chunk {}",
                    worldX, worldZ, chunkPos);
        }
    }

    // ─── Player Tick ──────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (!player.level().dimension().equals(Level.OVERWORLD)) return;
        if (player.tickCount % 5 != 0) return; // Cek setiap 5 tick

        int playerY = player.getBlockY();

        // Kondisi 1: Player sangat dekat dengan bedrock (jatuh ke void)
        // Ini terjadi saat player mine ke bedrock atau jatuh ke void.
        if (playerY <= BEDROCK_TRIGGER_Y) {
            if (player.level() instanceof ServerLevel sl) {
                BackroomsMod.LOGGER.debug(
                        "[Backrooms] Player {} reached bedrock floor at Y={} → noclip trigger",
                        player.getName().getString(), playerY);
                GhostWallBlock.triggerBackroomsTransition(player, sl);
            }
            return;
        }

        // Kondisi 2: Player di null zone area DAN cukup jauh ke bawah
        if (playerY <= NULL_ZONE_TRIGGER_Y) {
            boolean inNullZone = NullZoneManager.isNullZone(
                    player.level().dimension(), player.getBlockX(), player.getBlockZ());

            if (inNullZone && player.level() instanceof ServerLevel sl) {
                BackroomsMod.LOGGER.debug(
                        "[Backrooms] Player {} in null zone at Y={} → noclip trigger",
                        player.getName().getString(), playerY);
                GhostWallBlock.triggerBackroomsTransition(player, sl);
            }
        }
    }
}
