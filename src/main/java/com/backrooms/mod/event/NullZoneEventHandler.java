package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.block.ModBlocks;
import com.backrooms.mod.world.NullZoneManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Random;

/**
 * Handles null zone generation in the Overworld.
 *
 * When a chunk loads, there's a 1-in-N chance it gets a null zone:
 * a single 1×1 column of ghost blocks from surface down to just above bedrock.
 * The ghost blocks are invisible and have no collision — players fall straight
 * through. When they reach bedrock level, GhostWallBlock.entityInside()
 * triggers and teleports them to The Backrooms.
 */
public class NullZoneEventHandler {

    /** 1 in N chance a loaded overworld chunk gets a null zone. */
    private static final int NULL_ZONE_SPAWN_CHANCE = 10;

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (!serverLevel.dimension().equals(Level.OVERWORLD)) return;
        // Don't run on client side
        if (serverLevel.isClientSide()) return;

        LevelChunk chunk = (LevelChunk) event.getChunk();
        ChunkPos chunkPos = chunk.getPos();

        // Deterministic per-chunk random — same seed = same null zone positions always
        Random rng = new Random(serverLevel.getSeed()
                ^ ((long) chunkPos.x * 341873128712L)
                ^ ((long) chunkPos.z * 132897987541L));

        if (rng.nextInt(NULL_ZONE_SPAWN_CHANCE) == 0) {
            placeNullZone(serverLevel, chunkPos, rng);
        }
    }

    /**
     * Places a null zone column in the chunk:
     * - Pick a random X,Z within the 16x16 chunk
     * - Replace every block from surface down to Y=-63 (just above bedrock at -64)
     *   with ghost_wall blocks (invisible, no collision)
     * - Leave bedrock at Y=-64 intact (triggers teleport when player reaches it)
     */
    private void placeNullZone(ServerLevel level, ChunkPos chunkPos, Random rng) {
        int localX = rng.nextInt(16);
        int localZ = rng.nextInt(16);
        int worldX = chunkPos.getMinBlockX() + localX;
        int worldZ = chunkPos.getMinBlockZ() + localZ;

        // Find surface height
        int surfaceY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                worldX, worldZ);

        // Get the ghost block state once
        BlockState ghostState = ModBlocks.GHOST_WALL.get().defaultBlockState();
        BlockState bedrockState = Blocks.BEDROCK.defaultBlockState();

        // Replace entire column from surface down to Y=-63
        // Y=-64 is bedrock — leave it, that's what triggers the teleport
        for (int y = surfaceY; y >= -63; y--) {
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            BlockState existing = level.getBlockState(pos);

            // Skip if already ghost or bedrock
            if (existing.is(ModBlocks.GHOST_WALL.get())) continue;
            if (existing.is(Blocks.BEDROCK)) continue;

            level.setBlock(pos, ghostState, 2); // flag 2 = send to client, no block update cascade
        }

        // Make sure bedrock is there at the bottom
        level.setBlock(new BlockPos(worldX, -64, worldZ), bedrockState, 2);

        NullZoneManager.register(level.dimension(), new BlockPos(worldX, 0, worldZ));

        BackroomsMod.LOGGER.debug("[Backrooms] Null zone at X={} Z={} (chunk {})",
                worldX, worldZ, chunkPos);
    }
}
