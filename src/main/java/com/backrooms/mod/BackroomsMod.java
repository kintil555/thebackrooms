package com.backrooms.mod;

import com.backrooms.mod.block.ModBlocks;
import com.backrooms.mod.dimension.ModDimensions;
import com.backrooms.mod.event.NullZoneEventHandler;
import com.backrooms.mod.world.BackroomsChunkGeneratorType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(BackroomsMod.MOD_ID)
public class BackroomsMod {

    public static final String MOD_ID = "backrooms";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public BackroomsMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();

        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);

        BackroomsChunkGeneratorType.register(modEventBus);

        ModDimensions.init();

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);

        MinecraftForge.EVENT_BUS.register(new NullZoneEventHandler());
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("[Backrooms] Initialized. The humming begins...");
    }

    private void clientSetup(FMLClientSetupEvent event) {
        LOGGER.info("[Backrooms] Client ready.");
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
