package com.backrooms.mod.block;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
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
     * The "Ghost Block" — visually identical to a sandstone-colored wall block,
     * but has NO collision shape (VoxelShape.empty). Players pass straight through.
     * When used in a "null zone" column, falling through leads to the void
     * and triggers teleport to the Backrooms dimension.
     */
    public static final RegistryObject<Block> GHOST_WALL = registerBlock("ghost_wall",
            () -> new GhostWallBlock(BlockBehaviour.Properties.of()
                    .strength(-1.0f, 3600000.0f)    // unbreakable
                    .noCollission()                  // no collision = noclip/ghost
                    .noOcclusion()                   // renders as transparent
                    .lightLevel(state -> 0)
                    .sound(net.minecraft.world.level.block.SoundType.SAND)
            ));

    /**
     * Invisible air-like marker block used internally to flag null zone columns.
     * Not visible to players, used server-side to track which column is a null zone.
     */
    public static final RegistryObject<Block> NULL_ZONE_MARKER = registerBlock("null_zone_marker",
            () -> new NullZoneMarkerBlock(BlockBehaviour.Properties.of()
                    .strength(-1.0f, 3600000.0f)
                    .noCollission()
                    .noOcclusion()
                    .air()                           // treated as air, invisible
            ));

    // ─── helpers ────────────────────────────────────────────────────────────────

    private static <T extends Block> RegistryObject<T> registerBlock(String name, Supplier<T> block) {
        RegistryObject<T> toReturn = BLOCKS.register(name, block);
        registerBlockItem(name, toReturn);
        return toReturn;
    }

    private static <T extends Block> void registerBlockItem(String name, RegistryObject<T> block) {
        ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }
}
