package com.backrooms.mod;

import com.backrooms.mod.block.ModBlocks;
import com.backrooms.mod.client.NoclipOverlayRenderer;
import com.backrooms.mod.sound.ModSounds;
import com.backrooms.mod.command.BackroomsCommand;
import com.backrooms.mod.command.BackroomsLocateCommand;
import com.backrooms.mod.dimension.ModDimensions;
import com.backrooms.mod.event.NullZoneEventHandler;
import com.backrooms.mod.network.ModNetwork;
import com.backrooms.mod.world.BackroomsChunkGeneratorType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
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
        ModSounds.SOUNDS.register(modEventBus);

        BackroomsChunkGeneratorType.register(modEventBus);

        ModDimensions.init();

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        modEventBus.addListener(this::onRegisterGuiOverlays);

        MinecraftForge.EVENT_BUS.register(new NullZoneEventHandler());
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        BackroomsCommand.register(event.getDispatcher());
        BackroomsLocateCommand.register(event.getDispatcher());
        LOGGER.info("[Backrooms] Commands registered.");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(ModNetwork::register);
        LOGGER.info("[Backrooms] Initialized. The humming begins...");
    }

    private void clientSetup(FMLClientSetupEvent event) {
        LOGGER.info("[Backrooms] Client ready.");
    }

    /**
     * Register overlay Noclip ke Forge overlay pipeline.
     * Harus via mod event bus — bukan MinecraftForge.EVENT_BUS.
     * Dirender di atas semua overlay lain (above all).
     */
    private void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("noclip_overlay", NoclipOverlayRenderer.createOverlay());
        LOGGER.info("[Backrooms] Noclip overlay registered.");
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
