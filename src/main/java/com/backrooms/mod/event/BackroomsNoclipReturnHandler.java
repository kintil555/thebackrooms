package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.block.GhostWallBlock;
import com.backrooms.mod.dimension.ModDimensions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handler noclip return dari Backrooms → Overworld.
 *
 * Mekanisme:
 * - Saat player berada di Backrooms, ada chance kecil noclip terjadi kembali.
 * - Saat trigger: player mulai "melayang" ke atas (noPhysics, velocity ke atas).
 * - Setelah DELAY_TICKS, player di-teleport ke Overworld di posisi permukaan yang aman.
 * - Tidak ada overlay glitch (return lebih halus — semakin banyak cahaya, lalu tiba-tiba dunia nyata).
 *
 * FIX masalah lama:
 * - Return dulu pakai Y_SPAWN = 2.0 hardcoded → bisa suffocate atau jatuh dari langit.
 * - Sekarang pakai GhostWallBlock.triggerOverworldReturn() yang mencari surface yang aman.
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID)
public class BackroomsNoclipReturnHandler {

    // 1 in 6000 ticks per tick (~5 menit rata-rata) — lebih jarang dari masuk
    // Tapi tetap bisa terjadi, memberi harapan bagi player yang terjebak
    private static final int RETURN_CHANCE_DENOM = 6000;

    // 60 ticks = 3 detik animasi naik sebelum teleport
    private static final int DELAY_TICKS = 60;

    // Kecepatan naik saat animasi noclip return
    private static final double RISE_SPEED = 0.15;

    private static final Map<UUID, Integer> pendingReturn = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        // Hanya aktif di Backrooms
        if (!player.level().dimension().equals(ModDimensions.BACKROOMS_LEVEL)) return;

        // Abaikan jika player terbang, di air, atau portal cooldown
        if (player.getAbilities().flying) return;
        if (player.isInWater() || player.isInLava()) return;
        if (player.isOnPortalCooldown()) return;

        UUID uuid = player.getUUID();
        Integer countdown = pendingReturn.get(uuid);

        if (countdown == null) {
            // Roll chance return noclip
            if (player.getRandom().nextInt(RETURN_CHANCE_DENOM) == 0) {
                triggerReturn(player);
            }
        } else if (countdown > 0) {
            applyRiseEffect(player, countdown);
            pendingReturn.put(uuid, countdown - 1);
        } else {
            // Countdown habis → teleport ke overworld
            pendingReturn.remove(uuid);
            player.noPhysics = false;
            executeReturn(player);
        }
    }

    private static void triggerReturn(ServerPlayer player) {
        pendingReturn.put(player.getUUID(), DELAY_TICKS);
        player.noPhysics = true;
        BackroomsMod.LOGGER.info("[Backrooms] ✦ Noclip return triggered for {}",
                player.getName().getString());

        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§b§oReality seems to flicker..."));
    }

    private static void applyRiseEffect(ServerPlayer player, int countdown) {
        // Player bergerak ke atas secara halus (terbalik dari sinkEffect masuk)
        var vel = player.getDeltaMovement();
        double progress = 1.0 - (countdown / (double) DELAY_TICKS);
        double targetSpeed = RISE_SPEED * (0.3 + progress * 0.7);
        player.setDeltaMovement(vel.x * 0.3, Math.max(vel.y, targetSpeed), vel.z * 0.3);
    }

    private static void executeReturn(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        player.noPhysics = false;
        GhostWallBlock.triggerOverworldReturn(player, serverLevel);
        BackroomsMod.LOGGER.info("[Backrooms] ✦ Noclip return completed for {} → Overworld",
                player.getName().getString());
    }

    public static void clearPlayer(UUID uuid) {
        pendingReturn.remove(uuid);
    }
}
