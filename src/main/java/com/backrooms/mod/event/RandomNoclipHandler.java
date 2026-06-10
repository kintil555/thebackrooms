package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.block.GhostWallBlock;
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

@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID)
public class RandomNoclipHandler {

    private static final int DELAY_TICKS            = 60;
    private static final int OVERLAY_DURATION_TICKS = 80;
    private static final double SINK_SPEED          = -0.18;

    private static final Map<UUID, Integer> pendingTeleport = new HashMap<>();

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (!player.level().dimension().equals(Level.OVERWORLD)) return;
        if (player.isInWater() || player.isInLava() || player.getAbilities().flying) return;
        if (player.isOnPortalCooldown()) return;

        UUID uuid = player.getUUID();
        Integer countdown = pendingTeleport.get(uuid);

        if (countdown == null) {
            // Roll chance menggunakan multiplier dari NoclipChanceManager
            int denom = NoclipChanceManager.getEffectiveChanceDenominator(uuid);
            if (denom > 0 && player.getRandom().nextInt(denom) == 0) {
                triggerNoclip(player);
            }
        } else if (countdown > 0) {
            applySinkEffect(player, countdown);
            pendingTeleport.put(uuid, countdown - 1);
        } else {
            pendingTeleport.remove(uuid);
            player.noPhysics = false;
            executeNoclip(player);
        }
    }

    private static void triggerNoclip(ServerPlayer player) {
        pendingTeleport.put(player.getUUID(), DELAY_TICKS);
        player.noPhysics = true;
        NoclipChanceManager.incrementMultiplier(player.getUUID());
        ModNetwork.CHANNEL.send(
                new NoclipOverlayPacket(OVERLAY_DURATION_TICKS),
                PacketDistributor.PLAYER.with(player)
        );
        BackroomsMod.LOGGER.info("[Backrooms] ✦ Random noclip triggered for {} ({}x multiplier)",
                player.getName().getString(),
                NoclipChanceManager.getMultiplier(player.getUUID()));
    }

    private static void applySinkEffect(ServerPlayer player, int countdown) {
        var vel = player.getDeltaMovement();
        double progress = 1.0 - (countdown / (double) DELAY_TICKS);
        double targetSpeed = SINK_SPEED * (0.5 + progress * 0.5);
        player.setDeltaMovement(vel.x * 0.5, Math.min(vel.y, targetSpeed), vel.z * 0.5);
    }

    private static void executeNoclip(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) return;
        player.noPhysics = false;
        GhostWallBlock.triggerBackroomsTransition(player, serverLevel);
        BackroomsMod.LOGGER.info("[Backrooms] ✦ Random noclip completed for {} → Backrooms",
                player.getName().getString());
    }

    public static void clearPlayer(UUID uuid) {
        pendingTeleport.remove(uuid);
        // Tidak hapus multiplier saat logout — disimpan per sesi ini saja
        // Uncomment baris berikut jika ingin reset tiap logout:
        // NoclipChanceManager.clearPlayer(uuid);
    }
}
