package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.world.NullZoneManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Handles world-level events — mainly clearing the NullZoneManager when a world
 * is unloaded so we don't carry stale data across sessions.
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID)
public class WorldEventHandler {

    @SubscribeEvent
    public static void onWorldUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            if (serverLevel.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
                NullZoneManager.clear();
                BackroomsMod.LOGGER.info("[Backrooms] World unloaded. Null zone data cleared.");
            }
        }
    }
}
