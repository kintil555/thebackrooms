package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.block.ModBlocks;
import com.backrooms.mod.dimension.ModDimensions;
import com.backrooms.mod.world.NullZoneManager;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraft.world.level.portal.DimensionTransition;

import java.util.*;

/**
 * Core event handler for The Backrooms mod.
 *
 * Responsibilities:
 *  1. When a chunk loads in the Overworld, randomly decide if it gets a "null zone"
 *     — a 1-block-wide vertical column of ghost blocks from surface to bedrock (y=-64).
 *  2. Each server tick, check if any player is falling through the void below a null zone.
 *  3. If a player's Y drops below -64 (void) while in the Overworld, teleport them to
 *     The Backrooms dimension.
 *  4. Track which players are inside the Backrooms; handle their return.
 */
public class NullZoneEventHandler {

    // ─── Tuning knobs ────────────────────────────────────────────────────────────

    /** 1 in N chance that a freshly loaded overworld chunk gets a null zone. */
    private static final int NULL_ZONE_SPAWN_CHANCE = 12;

    /** Additional nearby-player triggered null zone: 1 in N per player tick. */
    private static final int NULL_ZONE_RANDOM_NEARBY_CHANCE = 60;

    /** How many ghost-block columns appear per null zone chunk (1 column = 1 x-z pos). */
    private static final int COLUMNS_PER_NULL_ZONE = 1;

    /** Min Y where ghost blocks start (overworld surface - 5 for visual effect). */
    private static final int GHOST_START_Y = 100;

    /** Bottom Y of ghost column (bedrock level). */
    private static final int GHOST_BOTTOM_Y = -64;

    /** Y level at which the player is considered to have "fallen into the void". */
    private static final int VOID_THRESHOLD_Y = -65;

    // ─── State tracking ──────────────────────────────────────────────────────────

    /** Players currently in the process of being teleported (cooldown to avoid double-tp). */
    private final Set<UUID> teleportCooldown = new HashSet<>();

    /** Ticks since we last did a nearby-null-zone check. */
    private int tickCounter = 0;

    // ─── Chunk Load: Place Null Zones ─────────────────────────────────────────────

    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        // Only generate null zones in the Overworld
        if (!serverLevel.dimension().equals(Level.OVERWORLD)) return;

        LevelChunk chunk = (LevelChunk) event.getChunk();
        ChunkPos chunkPos = chunk.getPos();

        // Deterministic random based on chunk seed so null zones are stable between reloads
        Random chunkRandom = new Random(serverLevel.getSeed()
                ^ ((long) chunkPos.x * 341873128712L)
                ^ ((long) chunkPos.z * 132897987541L));

        if (chunkRandom.nextInt(NULL_ZONE_SPAWN_CHANCE) == 0) {
            placeNullZone(serverLevel, chunkPos, chunkRandom);
        }
    }

    /**
     * Places a null zone column inside the given chunk.
     * The column consists of ghost wall blocks from the surface down to bedrock.
     */
    private void placeNullZone(ServerLevel level, ChunkPos chunkPos, Random random) {
        for (int c = 0; c < COLUMNS_PER_NULL_ZONE; c++) {
            // Pick a random X,Z within the 16×16 chunk
            int localX = random.nextInt(16);
            int localZ = random.nextInt(16);
            int worldX = chunkPos.getMinBlockX() + localX;
            int worldZ = chunkPos.getMinBlockZ() + localZ;

            // Find the surface Y (highest non-air block)
            int surfaceY = level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                    worldX, worldZ);

            int startY = Math.min(GHOST_START_Y, surfaceY);

            // Place ghost wall blocks from startY down to GHOST_BOTTOM_Y
            for (int y = startY; y >= GHOST_BOTTOM_Y; y--) {
                BlockPos pos = new BlockPos(worldX, y, worldZ);
                BlockState existingState = level.getBlockState(pos);

                // Only replace solid non-bedrock blocks (don't replace air, fluid, bedrock)
                if (!existingState.isAir()
                        && !existingState.getFluidState().isEmpty()
                        && !existingState.is(Blocks.BEDROCK)) {
                    level.setBlockAndUpdate(pos,
                            ModBlocks.GHOST_WALL.get().defaultBlockState());
                }
            }

            // Place an invisible marker at the top of the column for tracking
            BlockPos markerPos = new BlockPos(worldX, startY + 1, worldZ);
            level.setBlockAndUpdate(markerPos,
                    ModBlocks.NULL_ZONE_MARKER.get().defaultBlockState());

            // Register this column in our manager
            NullZoneManager.register(level.dimension(), new BlockPos(worldX, 0, worldZ));

            BackroomsMod.LOGGER.debug("[Backrooms] Null zone column placed at X={} Z={} in chunk {}",
                    worldX, worldZ, chunkPos);
        }
    }

    // ─── Server Tick: Detect falling players and spawn random nearby null zones ──

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter++;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) return;

        for (ServerPlayer player : overworld.players()) {
            // ── 1. Check if player has fallen into the void ──────────────────────
            if (player.getY() < VOID_THRESHOLD_Y && !teleportCooldown.contains(player.getUUID())) {
                teleportToBackrooms(player, server);
            }

            // ── 2. Random nearby null zone spawning ──────────────────────────────
            if (tickCounter % NULL_ZONE_RANDOM_NEARBY_CHANCE == 0) {
                Random rand = new Random();
                if (rand.nextInt(20) == 0) {  // extra rarity filter
                    spawnRandomNearbyNullZone(overworld, player, rand);
                }
            }
        }

        // Clean cooldown list
        if (tickCounter % 100 == 0) {
            cleanCooldowns(server);
        }
    }

    /**
     * Spawns a new null zone column at a random position within 32 blocks of the player.
     * Creates the eerie sense that the world is "glitching" around you.
     */
    private void spawnRandomNearbyNullZone(ServerLevel level, ServerPlayer player, Random rand) {
        int offsetX = rand.nextInt(65) - 32;
        int offsetZ = rand.nextInt(65) - 32;

        int worldX = (int) player.getX() + offsetX;
        int worldZ = (int) player.getZ() + offsetZ;

        ChunkPos chunkPos = new ChunkPos(new BlockPos(worldX, 0, worldZ));

        // Only spawn if chunk is already loaded (don't force-load)
        if (!level.isLoaded(new BlockPos(worldX, 64, worldZ))) return;

        int surfaceY = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                worldX, worldZ);

        int startY = Math.min(GHOST_START_Y, surfaceY);

        for (int y = startY; y >= GHOST_BOTTOM_Y; y--) {
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            BlockState existing = level.getBlockState(pos);
            if (!existing.isAir()
                    && existing.getFluidState().isEmpty()
                    && !existing.is(Blocks.BEDROCK)
                    && !existing.is(ModBlocks.GHOST_WALL.get())) {
                level.setBlockAndUpdate(pos, ModBlocks.GHOST_WALL.get().defaultBlockState());
            }
        }

        NullZoneManager.register(level.dimension(), new BlockPos(worldX, 0, worldZ));
        BackroomsMod.LOGGER.debug("[Backrooms] Random null zone spawned near player {} at X={} Z={}",
                player.getName().getString(), worldX, worldZ);
    }

    // ─── Teleport to Backrooms ────────────────────────────────────────────────────

    /**
     * Teleports a player from the Overworld void into The Backrooms dimension.
     * Finds a safe flat spot in the Backrooms to land the player.
     */
    private void teleportToBackrooms(ServerPlayer player, MinecraftServer server) {
        ServerLevel backroomsLevel = server.getLevel(ModDimensions.BACKROOMS_LEVEL);
        if (backroomsLevel == null) {
            BackroomsMod.LOGGER.error("[Backrooms] Backrooms dimension not found! " +
                    "Make sure dimension JSON data is loaded.");
            return;
        }

        // Start cooldown to prevent double-teleport
        teleportCooldown.add(player.getUUID());

        // Find a safe Y to spawn the player in the Backrooms (2 blocks above floor)
        int spawnX = player.getBlockX();
        int spawnZ = player.getBlockZ();
        int spawnY = findSafeY(backroomsLevel, spawnX, spawnZ);

        // Do the actual dimension teleport
        // In 1.21.1+, changeDimension takes a DimensionTransition; ITeleporter was removed
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

        BackroomsMod.LOGGER.info("[Backrooms] Player {} noclipped into The Backrooms at {},{},{}",
                player.getName().getString(), spawnX, spawnY, spawnZ);

        // Send a message to the player for flavor
        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal(
                        "§e§lYou have noclipped out of reality..."
                )
        );
        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal(
                        "§7§oThe warm hum of fluorescent lights surrounds you."
                )
        );
    }

    /**
     * Finds the first safe Y position above the floor in the Backrooms.
     * The Backrooms is a flat dimension, so the floor is at a fixed Y.
     */
    private int findSafeY(ServerLevel level, int x, int z) {
        // Backrooms floor is at Y=0 in our dimension, ceiling at Y=5
        // Player spawns at Y=2 (standing on the carpet floor)
        for (int y = 3; y < 10; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockPos above = pos.above();
            if (level.getBlockState(pos).isAir() && level.getBlockState(above).isAir()) {
                return y;
            }
        }
        return 2; // fallback default
    }

    /** Remove players from cooldown if they are no longer near void. */
    private void cleanCooldowns(MinecraftServer server) {
        teleportCooldown.removeIf(uuid -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            return p == null || p.getY() > VOID_THRESHOLD_Y + 10;
        });
    }
}
