package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.util.ITeleporter;

import java.util.function.Function;

/**
 * Custom teleporter that places the player safely inside the Backrooms dimension.
 * If the target area is not yet generated (first visit), it carves out a small
 * room so the player doesn't get stuck inside blocks.
 */
public class BackroomsTeleporter implements ITeleporter {

    private final int targetX;
    private final int targetY;
    private final int targetZ;

    public BackroomsTeleporter(int x, int y, int z) {
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }

    @Override
    public Entity placeEntity(Entity entity, ServerLevel currentWorld,
                              ServerLevel destWorld, float yaw,
                              Function<Boolean, Entity> repositionEntity) {
        Entity repositionedEntity = repositionEntity.apply(false);

        // Ensure the landing zone has breathable space
        if (destWorld != null) {
            ensureSafeRoom(destWorld, targetX, targetY, targetZ);
        }

        // Teleport the entity
        repositionedEntity.teleportTo(targetX + 0.5, targetY, targetZ + 0.5);
        repositionedEntity.setYRot(yaw);

        return repositionedEntity;
    }

    /**
     * Carves out a 3x3x3 breathable space at the landing location
     * so the player isn't teleported inside a solid block.
     */
    private void ensureSafeRoom(ServerLevel level, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                // Clear 2 blocks of head + body space
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
                    if (!level.getBlockState(pos).isAir()) {
                        // Only clear non-floor blocks (preserve the carpet/planks floor at y-1)
                        if (dy > 0 || level.getBlockState(pos.below()).isAir()) {
                            level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                        }
                    }
                }
            }
        }
        BackroomsMod.LOGGER.debug("[Backrooms] Safe room carved at {},{},{}", x, y, z);
    }

    /**
     * We handle position ourselves, so no portal search/creation.
     */
    @Override
    public boolean isVanilla() {
        return false;
    }
}
