package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.world.BackroomsStructureSpawner;
import com.backrooms.mod.world.NullZoneManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID)
public class WorldEventHandler {

    @SubscribeEvent
    public static void onWorldUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (serverLevel.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
            NullZoneManager.clear();
            // Reset structure spawner cache — sesi baru, chunk dianggap fresh
            BackroomsStructureSpawner.clearProcessedChunks();
            BackroomsMod.LOGGER.info("[Backrooms] World unloaded. Null zone + structure cache cleared.");
        }
    }
}
