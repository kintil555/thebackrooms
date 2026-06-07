package com.backrooms.mod.world;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.resources.ResourceLocation;

/**
 * Constants and resource locations for the Backrooms world generation data.
 * The actual world generation is handled by JSON data files in:
 *   resources/data/backrooms/dimension/backrooms.json
 *   resources/data/backrooms/dimension_type/backrooms_type.json
 *   resources/data/backrooms/worldgen/biome/backrooms_biome.json
 *   resources/data/backrooms/worldgen/noise_settings/backrooms_noise.json
 */
public class BackroomsWorldGenData {

    public static final ResourceLocation BACKROOMS_DIMENSION =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "backrooms");

    public static final ResourceLocation BACKROOMS_BIOME =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "backrooms_biome");

    public static final ResourceLocation BACKROOMS_NOISE_SETTINGS =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "backrooms_noise");

    /** Ceiling height of the Backrooms rooms (in blocks above floor). */
    public static final int ROOM_CEILING_HEIGHT = 5;

    /** Floor Y level in the Backrooms dimension. */
    public static final int FLOOR_Y = 0;

    /** Carpet/floor color block used in the Backrooms. */
    public static final String FLOOR_BLOCK = "minecraft:yellow_carpet";

    /** Wall material — sandstone approximates the aged yellow wallpaper look. */
    public static final String WALL_BLOCK = "minecraft:sandstone";

    /** Ceiling material — dark gray tiles like office drop ceilings. */
    public static final String CEILING_BLOCK = "minecraft:chiseled_stone_bricks";

    /** Light block for the fluorescent ceiling lights. */
    public static final String LIGHT_BLOCK = "minecraft:glowstone";
}
