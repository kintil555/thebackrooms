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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Random;

/**
 * Handler utama untuk sistem Null Zone.
 *
 * ═══════════════════════════════════════════════════════════════
 * PENDEKATAN FINAL — tidak ubah terrain sama sekali.
 *
 * Null Zone = area 1×∞×1 (x, semua Y, z) yang "tipis realitasnya".
 * Terrain TIDAK DIUBAH — blok aslinya tetap ada secara normal.
 *
 * Efek noclip diimplementasi di server:
 *   Saat player memasuki kolom null zone (XZ sama), player.setNoClip(true)
 *   diaktifkan → player menembus semua blok di kolom itu.
 *   Gravity tetap berlaku → player jatuh ke bawah menembus terrain.
 *   Saat player mencapai Y bedrock → teleport ke backrooms.
 *
 * Catatan: noClip di Forge 1.21.1 = player.noPhysics field (server-side).
 * Kita set true saat masuk kolom, false saat keluar (atau setelah teleport).
 *
 * ═══════════════════════════════════════════════════════════════
 * MENGAPA TIDAK UBAH BLOK:
 *   Setiap blok yang diganti dengan ghost block (noOcclusion) merusak
 *   face culling tetangga → lubang hitam / void visual di terrain.
 *   Solusi: biarkan blok asli, pakai noPhysics untuk player saja.
 */
public class NullZoneEventHandler {

    /** 1 dari N chunk mendapat null zone (~6.7%). */
    private static final int NULL_ZONE_SPAWN_CHANCE = 15;

    /** Y trigger teleport — player sudah jauh di bawah bedrock. */
    private static final int TELEPORT_TRIGGER_Y = -62;

    /** Seberapa jauh player harus berada dari titik null zone (radius XZ). */
    private static final double NULL_ZONE_RADIUS = 0.6;

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

        if (rng.nextInt(NULL_ZONE_SPAWN_CHANCE) != 0) return;

        int localX = rng.nextInt(16);
        int localZ = rng.nextInt(16);
        int worldX = chunkPos.getMinBlockX() + localX;
        int worldZ = chunkPos.getMinBlockZ() + localZ;

        // Hanya catat posisi — TIDAK ubah blok apapun
        NullZoneManager.register(serverLevel.dimension(), new BlockPos(worldX, 0, worldZ));

        BackroomsMod.LOGGER.debug(
                "[Backrooms] ✦ Null Zone registered at X={} Z={} chunk {}",
                worldX, worldZ, chunkPos);
    }

    // ─── Player Tick ──────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (!player.level().dimension().equals(Level.OVERWORLD)) return;

        int playerY = player.getBlockY();

        // ── Cek apakah player ada di XZ kolom null zone ──────────────────
        boolean inNullZone = isPlayerInNullZoneColumn(player);

        if (inNullZone) {
            // Aktifkan noPhysics → player jatuh nembus blok
            if (!player.noPhysics) {
                player.noPhysics = true;
                BackroomsMod.LOGGER.debug(
                        "[Backrooms] Player {} entered null zone at X={} Z={}, noPhysics ON",
                        player.getName().getString(), player.getBlockX(), player.getBlockZ());
            }

            // Pastikan player tetap jatuh (gravity) meski noPhysics
            // noPhysics menghilangkan collision tapi gravity masih berlaku
            // Namun gravity butuh collision untuk berhenti — kita tambah downward velocity
            Vec3 vel = player.getDeltaMovement();
            if (vel.y > -0.5) {
                // Tambahkan gravitasi manual agar player terus jatuh
                player.setDeltaMovement(vel.x * 0.3, Math.min(vel.y - 0.08, -0.1), vel.z * 0.3);
            }

            // ── Trigger teleport saat sudah sangat dalam ────────────────
            if (playerY <= TELEPORT_TRIGGER_Y && player.tickCount % 5 == 0) {
                if (player.level() instanceof ServerLevel sl) {
                    player.noPhysics = false; // Reset sebelum teleport
                    GhostWallBlock.triggerBackroomsTransition(player, sl);
                }
            }

        } else {
            // Player keluar dari kolom null zone → kembalikan physics normal
            if (player.noPhysics && player.tickCount % 3 == 0) {
                player.noPhysics = false;
            }
        }
    }

    // ─── Helper: cek apakah player di dalam radius kolom null zone ────────────

    private boolean isPlayerInNullZoneColumn(ServerPlayer player) {
        double px = player.getX();
        double pz = player.getZ();

        // Cek blok-blok terdekat di radius (null zone hanya 1×1 XZ)
        int bx = player.getBlockX();
        int bz = player.getBlockZ();

        // Cek exact position dan 1 blok sekitarnya untuk toleransi
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = bx + dx;
                int cz = bz + dz;
                if (!NullZoneManager.isNullZone(player.level().dimension(), cx, cz)) continue;

                // Hitung jarak dari center blok null zone ke player
                double centerX = cx + 0.5;
                double centerZ = cz + 0.5;
                double distSq = (px - centerX) * (px - centerX) + (pz - centerZ) * (pz - centerZ);

                if (distSq <= NULL_ZONE_RADIUS * NULL_ZONE_RADIUS) {
                    return true;
                }
            }
        }
        return false;
    }
}
