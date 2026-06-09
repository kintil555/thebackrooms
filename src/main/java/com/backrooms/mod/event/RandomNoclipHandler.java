package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.block.GhostWallBlock;
import com.backrooms.mod.dimension.ModDimensions;
import com.backrooms.mod.network.ModNetwork;
import com.backrooms.mod.network.NoclipOverlayPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Event handler untuk random noclip — player di overworld bisa tiba-tiba
 * "tertelan" tanah dan masuk ke Backrooms.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * MEKANISME:
 *   Setiap tick, tiap player di overworld punya 1/CHANCE_PER_TICK peluang
 *   untuk di-trigger. Saat trigger:
 *
 *   1. Server kirim NoclipOverlayPacket ke client → overlay glitch muncul
 *   2. Player.noPhysics = true → player mulai tenggelam ke tanah
 *   3. Setelah DELAY_TICKS (60 tick = 3 detik), player diteleport ke Backrooms
 *   4. noPhysics direset, overlay selesai
 *
 * CATATAN:
 *   - Player yang sudah di-trigger tidak akan di-trigger lagi sampai selesai
 *   - Player yang sudah di Backrooms tidak akan di-trigger
 *   - Portal cooldown mencegah double-teleport
 * ═══════════════════════════════════════════════════════════════════════
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID)
public class RandomNoclipHandler {

    /**
     * Peluang noclip per-player per-tick.
     * 1/72000 = rata-rata 1 kali per jam gameplay (72000 tick = 1 jam).
     * Bisa disesuaikan: 24000 = rata-rata tiap 20 menit.
     */
    private static final int CHANCE_PER_TICK = 72000;

    /**
     * Durasi overlay dan sinking sebelum teleport (ticks).
     * 60 ticks = 3 detik.
     */
    private static final int DELAY_TICKS = 60;

    /**
     * Durasi overlay total yang dikirim ke client (sedikit lebih lama dari
     * delay agar masih tampil saat teleport terjadi, fade out setelah masuk).
     */
    private static final int OVERLAY_DURATION_TICKS = 80;

    /**
     * Kecepatan tenggelam per tick saat efek aktif.
     * Nilai negatif = ke bawah.
     */
    private static final double SINK_SPEED = -0.18;

    /**
     * Map player UUID → tick countdown sebelum teleport.
     * -1 = tidak aktif.
     */
    private static final Map<UUID, Integer> pendingTeleport = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        // Hanya di overworld
        if (!player.level().dimension().equals(Level.OVERWORLD)) return;
        // Skip kalau player tidak di darat / di bawah tanah
        // (tidak trigger kalau player sedang terbang / di air)
        if (player.isInWater() || player.isInLava() || player.getAbilities().flying) return;
        // Skip kalau player sudah di portal cooldown
        if (player.isOnPortalCooldown()) return;

        UUID uuid = player.getUUID();
        Integer countdown = pendingTeleport.get(uuid);

        if (countdown == null) {
            // ── Belum di-trigger — roll peluang ─────────────────────────
            if (player.getRandom().nextInt(CHANCE_PER_TICK) == 0) {
                triggerNoclip(player);
            }
        } else if (countdown > 0) {
            // ── Sedang dalam countdown — paksa player tenggelam ──────────
            applySinkEffect(player, countdown);
            pendingTeleport.put(uuid, countdown - 1);
        } else {
            // ── Countdown selesai — teleport ─────────────────────────────
            pendingTeleport.remove(uuid);
            player.noPhysics = false;
            executeNoclip(player);
        }
    }

    // ─── Trigger: mulai efek ───────────────────────────────────────────────────

    private static void triggerNoclip(ServerPlayer player) {
        UUID uuid = player.getUUID();
        pendingTeleport.put(uuid, DELAY_TICKS);

        // Aktifkan noPhysics agar player bisa tenggelam ke tanah
        player.noPhysics = true;

        // Kirim packet overlay ke client
        ModNetwork.CHANNEL.send(
                new NoclipOverlayPacket(OVERLAY_DURATION_TICKS),
                PacketDistributor.PLAYER.with(player)
        );

        BackroomsMod.LOGGER.info(
                "[Backrooms] ✦ Random noclip triggered for {} at ({},{},{})",
                player.getName().getString(),
                player.getBlockX(), player.getBlockY(), player.getBlockZ()
        );
    }

    // ─── Efek tenggelam selama countdown ──────────────────────────────────────

    private static void applySinkEffect(ServerPlayer player, int countdown) {
        // Paksa velocity ke bawah agar player tenggelam
        var vel = player.getDeltaMovement();

        // Makin mendalam makin cepat (akselerasi halus)
        double progress = 1.0 - (countdown / (double) DELAY_TICKS); // 0→1
        double targetSpeed = SINK_SPEED * (0.5 + progress * 0.5);
        double newY = Math.min(vel.y, targetSpeed);

        player.setDeltaMovement(vel.x * 0.5, newY, vel.z * 0.5);

        // Kurangi XZ movement agar player tidak kabur horizontal
        // (efek seperti tersedot ke bawah)
    }

    // ─── Eksekusi teleport ke Backrooms ───────────────────────────────────────

    private static void executeNoclip(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return;

        // Reset physics sebelum teleport
        player.noPhysics = false;

        GhostWallBlock.triggerBackroomsTransition(player, serverLevel);

        BackroomsMod.LOGGER.info(
                "[Backrooms] ✦ Random noclip completed for {} → Backrooms",
                player.getName().getString()
        );
    }

    /**
     * Bersihkan state saat player disconnect atau mati.
     * Dipanggil dari event handler lain jika diperlukan.
     */
    public static void clearPlayer(UUID uuid) {
        pendingTeleport.remove(uuid);
    }
}
