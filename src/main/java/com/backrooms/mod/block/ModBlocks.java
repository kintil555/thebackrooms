package com.backrooms.mod.block;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.function.Supplier;

public class ModBlocks {

    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, BackroomsMod.MOD_ID);

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BackroomsMod.MOD_ID);

    /**
     * Null Zone Ghost Block — terlihat PERSIS seperti blok asli yang digantikannya,
     * tapi tidak punya collision (player bisa menembus / noclip).
     *
     * Visual dirender oleh NullZoneBlockEntityRenderer menggunakan originalBlockState
     * yang tersimpan di NullZoneBlockEntity.
     *
     * Properties:
     * - strength(-1, 3600000) = tidak bisa dihancurkan oleh player normal
     * - noCollission()        = no physical collision → player nembus
     * - noOcclusion()         = tidak memblokir cahaya / rendering neighbor
     * - dynamicShape()        = shape dihitung per-BlockEntity (untuk outline box)
     * - isSuffocating false   = player tidak tercekik saat di dalam block
     * - isViewBlocking false  = kamera tidak terblokir
     */
    public static final RegistryObject<Block> GHOST_WALL = registerBlock("ghost_wall",
            () -> new GhostWallBlock(BlockBehaviour.Properties.of()
                    .strength(-1.0f, 3600000.0f)
                    .noCollission()
                    .noOcclusion()
                    .sound(SoundType.EMPTY)
                    .isSuffocating((state, level, pos) -> false)
                    .isViewBlocking((state, level, pos) -> false)
            ));

    /**
     * Null Zone Marker — marker tak terlihat di server untuk tracking posisi null zone.
     * Tidak punya collision, tidak punya shape, sepenuhnya invisible.
     */
    public static final RegistryObject<Block> NULL_ZONE_MARKER = registerBlock("null_zone_marker",
            () -> new NullZoneMarkerBlock(BlockBehaviour.Properties.of()
                    .strength(-1.0f, 3600000.0f)
                    .noCollission()
                    .noOcclusion()
                    .sound(SoundType.EMPTY)
            ));

    private static <T extends Block> RegistryObject<T> registerBlock(String name, Supplier<T> block) {
        RegistryObject<T> toReturn = BLOCKS.register(name, block);
        registerBlockItem(name, toReturn);
        return toReturn;
    }

    private static <T extends Block> void registerBlockItem(String name, RegistryObject<T> block) {
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }
}
