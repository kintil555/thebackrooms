package com.backrooms.mod.dimension;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * Registers the dimension resource keys for the Backrooms dimension.
 * The actual generation is defined in JSON data files (dimension/*.json,
 * dimension_type/*.json, worldgen/biome/*.json).
 */
public class ModDimensions {

    /** The dimension key used to reference the Backrooms world level. */
    public static final ResourceKey<Level> BACKROOMS_LEVEL =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "backrooms"));

    /** The dimension type key. */
    public static final ResourceKey<DimensionType> BACKROOMS_DIM_TYPE =
            ResourceKey.create(Registries.DIMENSION_TYPE,
                    ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "backrooms_type"));

    public static void init() {
        // Keys are created statically — nothing else needed at this stage.
        BackroomsMod.LOGGER.info("[Backrooms] Dimension keys registered.");
    }
}
