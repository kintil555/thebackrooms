package com.backrooms.mod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Ghost Wall Block — looks like a wall but players walk right through it.
 * It is rendered as a full block (so it looks real from a distance),
 * but its collision shape is empty, making it passable.
 *
 * Used in "null zone" columns — a 1-block wide vertical strip from the
 * surface all the way down to bedrock (y=-64) where these ghost blocks sit.
 * When a player falls into the void below, the event handler teleports them
 * to the Backrooms dimension.
 */
public class GhostWallBlock extends Block {

    public GhostWallBlock(Properties properties) {
        super(properties);
    }

    /**
     * Returns an empty shape — players and entities pass right through.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level,
                                        BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    /**
     * Still render as a full cube visually (so it looks like a real wall).
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level,
                               BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    /**
     * Prevent occlusion so adjacent blocks still render their faces.
     */
    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }
}
