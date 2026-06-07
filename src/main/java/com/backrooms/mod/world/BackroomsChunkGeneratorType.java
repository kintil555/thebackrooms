package com.backrooms.mod.world;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Registry;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.NewRegistryEvent;
import net.minecraftforge.registries.RegisterEvent;

/**
 * Mendaftarkan BackroomsChunkGenerator ke registry Minecraft
 * agar bisa direferensikan dari dimension JSON.
 */
public class BackroomsChunkGeneratorType {

    // DeferredRegister untuk ChunkGenerator codec
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> CHUNK_GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, BackroomsMod.MOD_ID);

    public static final RegistryObject<MapCodec<? extends ChunkGenerator>> BACKROOMS_GENERATOR =
            CHUNK_GENERATORS.register("backrooms_generator",
                    () -> BackroomsChunkGenerator.CODEC);

    public static void register(IEventBus modBus) {
        CHUNK_GENERATORS.register(modBus);
    }
}
