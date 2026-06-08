package com.backrooms.mod.world;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Mendaftarkan BackroomsChunkGenerator ke BuiltInRegistries.CHUNK_GENERATOR.
 *
 * PENTING: CHUNK_GENERATOR adalah vanilla registry, BUKAN Forge-wrapped registry.
 * DeferredRegister TIDAK bisa dipakai di sini karena Forge tidak memiliki
 * IForgeRegistry wrapper untuk registry ini. Harus pakai Registry.register()
 * langsung di FMLCommonSetupEvent via enqueueWork().
 *
 * Kalau pakai DeferredRegister, codec tidak pernah ter-register, JSON dimensi
 * gagal deserialize generator-nya, dan dimensi jadi void kosong.
 */
public class BackroomsChunkGeneratorType {

    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "backrooms_generator");

    public static void register(IEventBus modBus) {
        modBus.addListener(BackroomsChunkGeneratorType::onCommonSetup);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        // enqueueWork() karena registry write harus terjadi di main thread
        event.enqueueWork(() -> {
            net.minecraft.core.Registry.register(
                    BuiltInRegistries.CHUNK_GENERATOR,
                    ID,
                    BackroomsChunkGenerator.CODEC
            );
            BackroomsMod.LOGGER.info("[Backrooms] ChunkGenerator '{}' registered.", ID);
        });
    }
}
