package com.backrooms.mod.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Invisible server-side marker for the top of a null zone column.
 * Placed at the surface-level position of a null zone column.
 * The NullZoneEventHandler reads this marker to know where null zones are located.
 * This block is completely invisible and passable — it's just a tag.
 */
public class NullZoneMarkerBlock extends Block {

    public NullZoneMarkerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level,
                                        BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level,
                               BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }
}
