package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.block.GhostWallBlock;
import com.backrooms.mod.network.ModNetwork;
import com.backrooms.mod.network.NoclipOverlayPacket;
import com.backrooms.mod.world.NullZoneManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Handler untuk sistem Null Zone — player jatuh menembus realita ke Backrooms.
 *
 * ALUR LENGKAP (sama persis dengan RandomNoclipHandler):
 *   1. Player menginjak kolom null zone di Overworld
 *   2. Kirim NoclipOverlayPacket ke client → overlay glitch 4 detik muncul
 *   3. noPhysics = true → player mulai tenggelam ke dalam tanah
 *   4. Setelah 80 ticks (4 detik) → teleport ke Backrooms
 *   5. Player kena fall damage saat landing di Backrooms
 *
 * Null Zone = kolom XZ tipis di Overworld yang ditentukan saat chunk load.
 * Terrain TIDAK DIUBAH — blok aslinya tetap, hanya noPhysics yang aktif.
 */
public class NullZoneEventHandler {

    /** 1 dari N chunk mendapat null zone (~6.7%). */
    private static final int NULL_ZONE_SPAWN_CHANCE = 15;

    /** Radius XZ player harus masuk agar trigger null zone. */
    private static final double NULL_ZONE_RADIUS = 0.6;

    /** 80 ticks = 4 detik — sama persis dengan OVERLAY_DURATION_TICKS. */
    private static final int DELAY_TICKS = 80;
    private static final int OVERLAY_DURATION_TICKS = 80;

    /** Kecepatan tenggelam (negatif = ke bawah). */
    private static final double SINK_SPEED = -0.18;

    /** Map countdown per player: null = belum trigger, >0 = sedang countdown. */
    private static final Map<UUID, Integer> pendingTeleport = new HashMap<>();

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
        if (player.getAbilities().flying) return;
        if (player.isInWater() || player.isInLava()) return;
        if (player.isOnPortalCooldown()) return;

        UUID uuid = player.getUUID();
        Integer countdown = pendingTeleport.get(uuid);

        if (countdown == null) {
            // Cek apakah player baru masuk null zone
            if (isPlayerInNullZoneColumn(player)) {
                triggerNullZoneNoclip(player);
            }
        } else if (countdown > 0) {
            // Sedang countdown: sink effect + kurangi timer
            applySinkEffect(player, countdown);
            pendingTeleport.put(uuid, countdown - 1);
        } else {
            // Countdown habis → teleport ke Backrooms
            pendingTeleport.remove(uuid);
            player.noPhysics = false;
            executeNoclip(player);
        }
    }

    // ─── Trigger ─────────────────────────────────────────────────────────────

    private void triggerNullZoneNoclip(ServerPlayer player) {
        pendingTeleport.put(player.getUUID(), DELAY_TICKS);
        player.noPhysics = true;

        // Kirim overlay packet ke client — ini yang bikin glitch 4 detik muncul
        ModNetwork.CHANNEL.send(
                new NoclipOverlayPacket(OVERLAY_DURATION_TICKS),
                PacketDistributor.PLAYER.with(player)
        );

        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§7§o..."));

        BackroomsMod.LOGGER.info(
                "[Backrooms] ✦ Null zone noclip triggered for {} at X={} Z={}",
                player.getName().getString(), player.getBlockX(), player.getBlockZ());
    }

    // ─── Sink Effect ──────────────────────────────────────────────────────────

    private void applySinkEffect(ServerPlayer player, int countdown) {
        Vec3 vel = player.getDeltaMovement();
        double progress = 1.0 - (countdown / (double) DELAY_TICKS);
        double targetSpeed = SINK_SPEED * (0.5 + progress * 0.5);
        player.setDeltaMovement(vel.x * 0.5, Math.min(vel.y, targetSpeed), vel.z * 0.5);
    }

    // ─── Execute Noclip ───────────────────────────────────────────────────────

    private void executeNoclip(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        player.noPhysics = false;
        GhostWallBlock.triggerBackroomsTransition(player, serverLevel);
        BackroomsMod.LOGGER.info("[Backrooms] ✦ Null zone noclip completed for {} → Backrooms",
                player.getName().getString());
    }

    // ─── Clear State ──────────────────────────────────────────────────────────

    public static void clearPlayer(UUID uuid) {
        pendingTeleport.remove(uuid);
    }

    // ─── Helper: cek apakah player di dalam radius kolom null zone ────────────

    private boolean isPlayerInNullZoneColumn(ServerPlayer player) {
        // Hanya trigger sekali — jika sudah ada countdown, skip
        if (pendingTeleport.containsKey(player.getUUID())) return false;

        double px = player.getX();
        double pz = player.getZ();
        int bx = player.getBlockX();
        int bz = player.getBlockZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = bx + dx;
                int cz = bz + dz;
                if (!NullZoneManager.isNullZone(player.level().dimension(), cx, cz)) continue;

                double centerX = cx + 0.5;
                double centerZ = cz + 0.5;
                double distSq = (px - centerX) * (px - centerX)
                              + (pz - centerZ) * (pz - centerZ);

                if (distSq <= NULL_ZONE_RADIUS * NULL_ZONE_RADIUS) {
                    return true;
                }
            }
        }
        return false;
    }
}
