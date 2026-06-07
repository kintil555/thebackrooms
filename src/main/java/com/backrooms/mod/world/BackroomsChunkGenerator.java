package com.backrooms.mod.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * BackroomsChunkGenerator — Level 0 "The Lobby"
 *
 * Rebuilt to match Kane Pixels / screenshot reference:
 *
 * MATERIALS:
 *   Floor   → Oak Planks (warm brown wood)
 *   Wall    → Sandstone (yellowish plaster) + Birch Planks wainscoting border
 *   Ceiling → Gray Concrete tiles (office suspended ceiling)
 *   Light   → Ochre Froglight strips (warm fluorescent glow)
 *   Hidden  → Glowstone hidden above froglight (actual light source)
 *   Pillars → Sandstone pillars at room corners/centers
 *   Trim    → Smooth Sandstone (wall base trim / wainscoting)
 *
 * VERTICAL LAYOUT (room height = 5 blocks — more open, less claustrophobic):
 *   Y=0    Bedrock (void floor)
 *   Y=1    Sandstone sub-floor
 *   Y=2    Oak Planks (floor)
 *   Y=3-6  AIR — 4 blocks of walkable room height
 *   Y=7    Ceiling: Gray Concrete tile grid OR Ochre Froglight strip
 *   Y=8    Glowstone (at light strips) or Bedrock (opaque roof)
 *
 * CEILING DETAIL:
 *   The ceiling alternates Gray Concrete (tiles) and Ochre Froglight strips
 *   every 4 blocks along X-axis, replicating fluorescent office strips.
 *   Froglight goes at Y=7, Glowstone at Y=8 above them for actual illumination.
 *
 * ROOM LAYOUT — using 8x8 cell size per room, 2 rooms per chunk side:
 *   Rooms are 8x8 blocks wide, corridors are 4 blocks wide.
 *   This gives spacious rooms that look like the screenshot.
 *   Pillars placed at inner corners of room intersections.
 *
 * MAZE ALGORITHM:
 *   Uses a room-based approach with per-room-edge corridor decisions.
 *   Each chunk has 2x2 "macro rooms" (8x8 each).
 *   Corridors connect rooms along shared edges.
 *   Pillars generated at room grid intersections (every 8 blocks).
 */
public class BackroomsChunkGenerator extends ChunkGenerator {

    public static final MapCodec<BackroomsChunkGenerator> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source")
                            .forGetter(BackroomsChunkGenerator::getBiomeSource)
            ).apply(instance, BackroomsChunkGenerator::new));

    // ─── Y Levels ─────────────────────────────────────────────────────────────
    private static final int Y_BEDROCK     = 0;
    private static final int Y_SUBFLOOR    = 1;
    private static final int Y_FLOOR       = 2;
    private static final int Y_ROOM_BOTTOM = 3;
    private static final int Y_ROOM_TOP    = 6;  // 4 air blocks (Y 3,4,5,6)
    private static final int Y_CEILING     = 7;
    private static final int Y_ABOVE_CEIL  = 8;

    // ─── Block States ─────────────────────────────────────────────────────────
    private static final BlockState BEDROCK_BLK     = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState SUBFLOOR_BLK    = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState FLOOR_BLK       = Blocks.OAK_PLANKS.defaultBlockState();

    // Walls — sandstone as main wall plaster, smooth sandstone wainscoting trim
    private static final BlockState WALL_PLASTER     = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState WALL_WAINSCOT    = Blocks.SMOOTH_SANDSTONE.defaultBlockState();

    // Ceiling — gray concrete tiles + froglight strips
    private static final BlockState CEIL_TILE        = Blocks.GRAY_CONCRETE.defaultBlockState();
    private static final BlockState CEIL_FROGLIGHT   = Blocks.OCHRE_FROGLIGHT.defaultBlockState();
    private static final BlockState GLOWSTONE_BLK    = Blocks.GLOWSTONE.defaultBlockState();

    // Pillars — sandstone column
    private static final BlockState PILLAR_BLK       = Blocks.CHISELED_SANDSTONE.defaultBlockState();

    private static final BlockState AIR_BLK          = Blocks.AIR.defaultBlockState();

    // ─── Room/Grid settings ───────────────────────────────────────────────────
    /**
     * Room cell size in blocks. 8 = 2 rooms per 16-block chunk.
     * Rooms are 6 blocks of open interior (8 - 2 walls on sides).
     */
    private static final int ROOM_SIZE  = 8;
    private static final int ROOMS_PER_CHUNK = 2; // 16 / 8

    /**
     * Corridor width (open gap between rooms).
     * 4 blocks wide → matches the spacious corridors in screenshot.
     */
    private static final int CORRIDOR_HALF = 2; // ±2 from center = 4 wide

    public BackroomsChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // ─── Main fill ────────────────────────────────────────────────────────────

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk) {

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                fillColumn(chunk, pos, lx, lz, wx, wz);
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    // ─── Column fill ──────────────────────────────────────────────────────────

    private void fillColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                             int lx, int lz, int wx, int wz) {

        ColumnType colType = classifyColumn(wx, wz);

        // Y=0: bedrock void floor
        chunk.setBlockState(pos.set(lx + chunk.getPos().getMinBlockX(), Y_BEDROCK,
                lz + chunk.getPos().getMinBlockZ()), BEDROCK_BLK, false);

        // Y=1: sub-floor
        chunk.setBlockState(pos.setY(Y_SUBFLOOR), SUBFLOOR_BLK, false);

        if (colType == ColumnType.AIR) {
            // Floor
            chunk.setBlockState(pos.setY(Y_FLOOR), FLOOR_BLK, false);
            // Open room space
            for (int y = Y_ROOM_BOTTOM; y <= Y_ROOM_TOP; y++) {
                chunk.setBlockState(pos.setY(y), AIR_BLK, false);
            }
            // Ceiling tile or froglight strip
            boolean isFroglight = isFroglightStrip(wx, wz);
            chunk.setBlockState(pos.setY(Y_CEILING), isFroglight ? CEIL_FROGLIGHT : CEIL_TILE, false);
            // Above ceiling: glowstone at froglight (hidden light), bedrock elsewhere
            chunk.setBlockState(pos.setY(Y_ABOVE_CEIL), isFroglight ? GLOWSTONE_BLK : BEDROCK_BLK, false);

        } else if (colType == ColumnType.PILLAR) {
            // Chiseled sandstone pillar — full height
            chunk.setBlockState(pos.setY(Y_FLOOR), PILLAR_BLK, false);
            for (int y = Y_ROOM_BOTTOM; y <= Y_ROOM_TOP; y++) {
                chunk.setBlockState(pos.setY(y), PILLAR_BLK, false);
            }
            chunk.setBlockState(pos.setY(Y_CEILING), PILLAR_BLK, false);
            chunk.setBlockState(pos.setY(Y_ABOVE_CEIL), BEDROCK_BLK, false);

        } else {
            // WALL — wainscoting trim at bottom 2 blocks, plaster above
            chunk.setBlockState(pos.setY(Y_FLOOR), WALL_WAINSCOT, false);
            chunk.setBlockState(pos.setY(Y_ROOM_BOTTOM), WALL_WAINSCOT, false);   // Y=3 wainscoting
            for (int y = Y_ROOM_BOTTOM + 1; y <= Y_ROOM_TOP; y++) {
                chunk.setBlockState(pos.setY(y), WALL_PLASTER, false);
            }
            chunk.setBlockState(pos.setY(Y_CEILING), WALL_PLASTER, false);
            chunk.setBlockState(pos.setY(Y_ABOVE_CEIL), BEDROCK_BLK, false);
        }
    }

    // ─── Column Classification ─────────────────────────────────────────────────

    private enum ColumnType { AIR, WALL, PILLAR }

    /**
     * Classify each world column as open air, wall, or pillar.
     *
     * ROOM GRID:
     *   Rooms are on an 8-block grid. Within each room:
     *   - The outer 1 block on each side is wall
     *   - The inner 6×6 is open
     *
     * CORRIDORS:
     *   At room boundaries (every 8 blocks), if a corridor is open between
     *   two rooms, the central 4 blocks of that boundary are open.
     *
     * PILLARS:
     *   At every intersection of room grid lines (every 8 blocks in both X and Z),
     *   place a 2×2 pillar block (classic backrooms column look).
     */
    private ColumnType classifyColumn(int wx, int wz) {
        // Position within current room cell (0-7)
        int rx = Math.floorMod(wx, ROOM_SIZE);
        int rz = Math.floorMod(wz, ROOM_SIZE);

        // Room cell coordinate
        int cellX = Math.floorDiv(wx, ROOM_SIZE);
        int cellZ = Math.floorDiv(wz, ROOM_SIZE);

        // ── PILLAR CHECK ──
        // Pillars go at room grid intersections: rx ∈ {0,1} AND rz ∈ {0,1}
        // But only where BOTH walls would have been (inner corner style)
        if (rx <= 1 && rz <= 1) {
            // This is a corner intersection. Place pillar if it's "inner" corner
            // (i.e. where two room walls meet, not on outer edge of corridor)
            return ColumnType.PILLAR;
        }

        // ── WALL ON X-BOUNDARY ──
        // rx == 0 or rx == 1 means we're in the 2-block wall on the -X side of cell
        // Unless a corridor is open here (rz in center range)
        if (rx <= 1) {
            // Check corridor between cellX and cellX-1
            if (isCorridorOpen(cellX, cellZ, false)) {
                // Corridor is open: rz must be in the open passage zone
                // Open zone: rz in [2, 5] (center 4 of 8)
                if (rz >= 2 && rz <= 5) {
                    return ColumnType.AIR;
                }
            }
            return ColumnType.WALL;
        }

        if (rz <= 1) {
            // Wall on -Z boundary of room
            if (isCorridorOpen(cellX, cellZ, true)) {
                if (rx >= 2 && rx <= 5) {
                    return ColumnType.AIR;
                }
            }
            return ColumnType.WALL;
        }

        // ── INTERIOR OF ROOM ── always open
        return ColumnType.AIR;
    }

    // ─── Corridor Logic ───────────────────────────────────────────────────────

    /**
     * Decide if there's a corridor open between two adjacent room cells.
     *
     * @param cellX       current room cell X
     * @param cellZ       current room cell Z
     * @param isZEdge     true = checking Z-direction edge (between cellZ and cellZ-1)
     *                    false = checking X-direction edge (between cellX and cellX-1)
     */
    private boolean isCorridorOpen(int cellX, int cellZ, boolean isZEdge) {
        long seed;
        if (isZEdge) {
            // Edge between row cellZ-1 and row cellZ
            seed = edgeSeed(cellX, cellZ, 1);
        } else {
            // Edge between column cellX-1 and column cellX
            seed = edgeSeed(cellX, cellZ, 0);
        }
        // ~80% corridors open → more interconnected, less claustrophobic
        return (Math.abs(seed % 10) < 8);
    }

    private long edgeSeed(int cx, int cz, int axis) {
        return ((long) cx * 1234567891L)
                ^ ((long) cz * 9876543217L)
                ^ ((long) axis * 0xABCDEF1234L)
                ^ 0xB4CKR00MSL3V3LL;
    }

    // ─── Ceiling Light Strips ─────────────────────────────────────────────────

    /**
     * Froglight strip pattern — simulating fluorescent office lights.
     *
     * Pattern: horizontal strips running along Z-axis,
     * spaced every 6 blocks in X, 2 blocks wide.
     * This creates the "long tube light" look from the screenshot.
     */
    private boolean isFroglightStrip(int wx, int wz) {
        int mx = Math.floorMod(wx, 6);
        // Strip occupies mx = 2 and mx = 3 (2 blocks wide)
        return (mx == 2 || mx == 3);
    }

    // ─── Required abstract overrides ──────────────────────────────────────────

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
        // No caves in the backrooms
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                             RandomState randomState, ChunkAccess chunk) {
        // Handled in fillFromNoise
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // Backrooms starts empty
    }

    @Override
    public int getGenDepth() {
        return 64;
    }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState randomState,
            Blender blender,
            StructureManager structureManager,
            ChunkAccess chunk) {
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState state, BlockPos pos) {
        info.add("Backrooms Level 0 — The Lobby | " + pos.toShortString());
    }

    @Override
    public int getSeaLevel() { return -1; }

    @Override
    public int getMinY() { return Y_BEDROCK; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type,
                             LevelHeightAccessor level, RandomState state) {
        return Y_CEILING;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level,
                                     RandomState state) {
        return new NoiseColumn(Y_BEDROCK, new BlockState[0]);
    }
}
