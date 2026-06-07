package com.backrooms.mod.blockentity;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.block.ModBlocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, BackroomsMod.MOD_ID);

    /**
     * BlockEntity untuk menyimpan originalBlockState dari blok yang
     * digantikan null zone ghost block.
     */
    public static final RegistryObject<BlockEntityType<NullZoneBlockEntity>> NULL_ZONE_BE =
            BLOCK_ENTITIES.register("null_zone_be", () ->
                    BlockEntityType.Builder
                            .of(NullZoneBlockEntity::new, ModBlocks.GHOST_WALL.get())
                            .build(null));

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }
}
