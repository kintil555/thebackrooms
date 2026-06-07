package com.backrooms.mod.block;

import com.backrooms.mod.dimension.ModDimensions;
import com.backrooms.mod.event.BackroomsTeleporter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Null Zone Block — completely invisible and has no collision.
 * Players fall straight through it.
 *
 * When a player enters this block (entityInside), if the block below is
 * bedrock (Y = -64), they get teleported to The Backrooms.
 *
 * Placed in a 1-block-wide vertical column from surface down to just above
 * bedrock. Player noclips down, hits bedrock level → teleport.
 */
public class GhostWallBlock extends Block {

    public GhostWallBlock(Properties properties) {
        super(properties);
    }

    /** No collision — player falls straight through. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level,
                                        BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    /** No visual shape either — completely invisible. */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level,
                               BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    /** Allow light through. */
    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    /**
     * Called every tick while an entity is inside this block.
     * If the entity is a player and the block directly below is bedrock,
     * teleport them to The Backrooms.
     */
    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos,
                              Entity entity) {
        if (level.isClientSide()) return;
        if (!(entity instanceof ServerPlayer player)) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        // Only trigger when player reaches the bedrock layer
        BlockPos below = pos.below();
        if (!serverLevel.getBlockState(below).is(net.minecraft.world.level.block.Blocks.BEDROCK)) return;

        // Cooldown: use player's portal cooldown to avoid double-teleport
        if (player.portalCooldown > 0) return;

        // Teleport to Backrooms
        ServerLevel backroomsLevel = serverLevel.getServer().getLevel(ModDimensions.BACKROOMS_LEVEL);
        if (backroomsLevel == null) return;

        player.portalCooldown = 200; // 10 second cooldown

        int spawnX = player.getBlockX();
        int spawnZ = player.getBlockZ();
        int spawnY = findSafeY(backroomsLevel, spawnX, spawnZ);

        BackroomsTeleporter.ensureSafeRoom(backroomsLevel, spawnX, spawnY, spawnZ);

        DimensionTransition transition = new DimensionTransition(
                backroomsLevel,
                new net.minecraft.world.phys.Vec3(spawnX + 0.5, spawnY, spawnZ + 0.5),
                net.minecraft.world.phys.Vec3.ZERO,
                player.getYRot(),
                player.getXRot(),
                DimensionTransition.DO_NOTHING
        );
        player.changeDimension(transition);

        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§e§lYou have noclipped out of reality..."));
        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§7§oThe warm hum of fluorescent lights surrounds you."));
    }

    private int findSafeY(ServerLevel level, int x, int z) {
        for (int y = 3; y < 10; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()) {
                return y;
            }
        }
        return 2;
    }
}
