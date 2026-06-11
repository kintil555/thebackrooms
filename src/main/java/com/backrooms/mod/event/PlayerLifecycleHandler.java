package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.event.BackroomsNoclipReturnHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Bersihkan state RandomNoclipHandler saat player log out atau mati,
 * supaya tidak ada countdown yang stuck atau noPhysics yang tidak di-reset.
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID)
public class PlayerLifecycleHandler {

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        player.noPhysics = false;
        RandomNoclipHandler.clearPlayer(player.getUUID());
        BackroomsNoclipReturnHandler.clearPlayer(player.getUUID());
    }

    @SubscribeEvent
    public static void onPlayerDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        player.noPhysics = false;
        RandomNoclipHandler.clearPlayer(player.getUUID());
        BackroomsNoclipReturnHandler.clearPlayer(player.getUUID());
    }
}
