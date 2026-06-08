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
 * BackroomsChunkGenerator — Level 0
 *
 * LAYOUT VERTIKAL (sesuai build referensi):
 *   Y=0  Bedrock (alas)
 *   Y=1  Brown Wool (karpet lantai)
 *   Y=2  Cut Sandstone (baseboard bawah dinding)
 *   Y=3  Stripped Oak Log (dinding lapis 1)
 *   Y=4  Stripped Oak Log (dinding lapis 2)
 *   Y=5  Stripped Oak Log (dinding lapis 3)
 *   Y=6  Smooth Stone (ceiling)
 *   Y=7  Frog Light setiap 3 blok di X dan Z / Bedrock di tempat lain
 *   Y=8  Bedrock (atap solid)
 *
 * Player spawn di Y=2 (berdiri di atas karpet).
 * Lampu: Frog Light berjejer rapi tiap 3 blok (grid X%3==0 && Z%3==0).
 * Pilar: 2×2 Stripped Oak Log di ruangan luas (Y=2–5).
 * Maze: sel 8×8, 80% open, koneksi penuh antar chunk.
 */
public class BackroomsChunkGenerator extends ChunkGenerator {

    public static final MapCodec<BackroomsChunkGenerator> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source")
                            .forGetter(BackroomsChunkGenerator::getBiomeSource)
            ).apply(instance, BackroomsChunkGenerator::new));

    // ─── Y levels ─────────────────────────────────────────────────────────────
    private static final int Y_BEDROCK_FLOOR = 0;
    private static final int Y_CARPET        = 1;
    private static final int Y_BASEBOARD     = 2;
    private static final int Y_WALL_1        = 3;
    private static final int Y_WALL_2        = 4;
    private static final int Y_WALL_3        = 5;
    private static final int Y_CEILING       = 6;
    private static final int Y_LIGHT         = 7;
    private static final int Y_ROOF          = 8;

    // ─── Block states ─────────────────────────────────────────────────────────
    private static final BlockState BLK_BEDROCK   = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState BLK_CARPET    = Blocks.BROWN_WOOL.defaultBlockState();
    private static final BlockState BLK_BASE      = Blocks.CUT_SANDSTONE.defaultBlockState();
    private static final BlockState BLK_WALL      = Blocks.STRIPPED_OAK_LOG.defaultBlockState();
    private static final BlockState BLK_CEILING   = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState BLK_LIGHT     = Blocks.FROGLIGHT.defaultBlockState();
    private static final BlockState BLK_AIR       = Blocks.AIR.defaultBlockState();

    // ─── Maze ─────────────────────────────────────────────────────────────────
    private static final int CELL_SIZE       = 8;
    private static final int CELLS_PER_CHUNK = 2;

    public BackroomsChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender, RandomState randomState,
            StructureManager structureManager, ChunkAccess chunk) {

        int cx    = chunk.getPos().x;
        int cz    = chunk.getPos().z;
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();

        boolean[][] openMap = buildOpenMap(cx, cz);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                boolean open   = openMap[lx][lz];
                boolean pillar = open && isPillar(wx, wz, openMap, lx, lz);
                fillColumn(chunk, pos, lx, lz, wx, wz, open, pillar);
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // COLUMN
    // ══════════════════════════════════════════════════════════════════════════

    private void fillColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                            int lx, int lz, int wx, int wz,
                            boolean open, boolean pillar) {
        set(chunk, pos, lx, Y_BEDROCK_FLOOR, lz, BLK_BEDROCK);
        set(chunk, pos, lx, Y_ROOF,          lz, BLK_BEDROCK);

        if (!open || pillar) {
            // Dinding / pilar solid
            set(chunk, pos, lx, Y_CARPET,   lz, BLK_BASE);
            set(chunk, pos, lx, Y_BASEBOARD, lz, BLK_BASE);
            set(chunk, pos, lx, Y_WALL_1,   lz, BLK_WALL);
            set(chunk, pos, lx, Y_WALL_2,   lz, BLK_WALL);
            set(chunk, pos, lx, Y_WALL_3,   lz, BLK_WALL);
            set(chunk, pos, lx, Y_CEILING,  lz, BLK_WALL);
            set(chunk, pos, lx, Y_LIGHT,    lz, BLK_BEDROCK);
        } else {
            // Ruangan terbuka
            set(chunk, pos, lx, Y_CARPET,   lz, BLK_CARPET);
            set(chunk, pos, lx, Y_BASEBOARD, lz, BLK_AIR);
            set(chunk, pos, lx, Y_WALL_1,   lz, BLK_AIR);
            set(chunk, pos, lx, Y_WALL_2,   lz, BLK_AIR);
            set(chunk, pos, lx, Y_WALL_3,   lz, BLK_AIR);
            set(chunk, pos, lx, Y_CEILING,  lz, BLK_CEILING);
            // Frog Light tiap 3 blok (grid X%3==0 && Z%3==0)
            set(chunk, pos, lx, Y_LIGHT, lz,
                isLamp(wx, wz) ? BLK_LIGHT : BLK_BEDROCK);
        }
    }

    private void set(ChunkAccess c, BlockPos.MutableBlockPos p,
                     int lx, int y, int lz, BlockState s) {
        c.setBlockState(p.set(lx, y, lz), s, false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LAMP
    // ══════════════════════════════════════════════════════════════════════════

    /** Frog Light berjejer rapi tiap 3 blok di kedua arah. */
    private boolean isLamp(int wx, int wz) {
        return (Math.floorMod(wx, 3) == 0) && (Math.floorMod(wz, 3) == 0);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // OPEN MAP
    // ══════════════════════════════════════════════════════════════════════════

    private boolean[][] buildOpenMap(int cx, int cz) {
        boolean[][] map   = new boolean[16][16];
        boolean[][] self  = getCellGrid(cx,     cz);
        boolean[][] left  = getCellGrid(cx - 1, cz);
        boolean[][] right = getCellGrid(cx + 1, cz);
        boolean[][] top   = getCellGrid(cx,     cz - 1);
        boolean[][] bot   = getCellGrid(cx,     cz + 1);

        for (int sx = 0; sx < CELLS_PER_CHUNK; sx++) {
            for (int sz = 0; sz < CELLS_PER_CHUNK; sz++) {
                if (!self[sx][sz]) continue;
                int ox = sx * CELL_SIZE, oz = sz * CELL_SIZE;

                // Isi penuh
                for (int dx = 0; dx < CELL_SIZE; dx++)
                    for (int dz = 0; dz < CELL_SIZE; dz++)
                        map[ox + dx][oz + dz] = true;

                // Dinding kiri
                boolean cLeft = (sx > 0) ? self[sx-1][sz] : left[CELLS_PER_CHUNK-1][sz];
                if (!cLeft) for (int dz = 0; dz < CELL_SIZE; dz++) map[ox][oz+dz] = false;

                // Dinding atas
                boolean cTop = (sz > 0) ? self[sx][sz-1] : top[sx][CELLS_PER_CHUNK-1];
                if (!cTop) for (int dx = 0; dx < CELL_SIZE; dx++) map[ox+dx][oz] = false;

                // Dinding kanan (hanya sel paling kanan)
                if (sx == CELLS_PER_CHUNK - 1) {
                    boolean cRight = right[0][sz];
                    if (!cRight) for (int dz = 0; dz < CELL_SIZE; dz++) map[ox+CELL_SIZE-1][oz+dz] = false;
                }
                // Dinding bawah (hanya sel paling bawah)
                if (sz == CELLS_PER_CHUNK - 1) {
                    boolean cBot = bot[sx][0];
                    if (!cBot) for (int dx = 0; dx < CELL_SIZE; dx++) map[ox+dx][oz+CELL_SIZE-1] = false;
                }
            }
        }

        // Sambungan antar sel dalam chunk
        for (int sz = 0; sz < CELLS_PER_CHUNK; sz++)
            if (self[0][sz] && self[1][sz])
                for (int dz = 0; dz < CELL_SIZE; dz++) {
                    map[CELL_SIZE-1][sz*CELL_SIZE+dz] = true;
                    map[CELL_SIZE][sz*CELL_SIZE+dz]   = true;
                }
        for (int sx = 0; sx < CELLS_PER_CHUNK; sx++)
            if (self[sx][0] && self[sx][1])
                for (int dx = 0; dx < CELL_SIZE; dx++) {
                    map[sx*CELL_SIZE+dx][CELL_SIZE-1] = true;
                    map[sx*CELL_SIZE+dx][CELL_SIZE]   = true;
                }

        // Sambungan ke chunk tetangga
        for (int sz = 0; sz < CELLS_PER_CHUNK; sz++) {
            if (self[0][sz] && left[CELLS_PER_CHUNK-1][sz])
                for (int dz = 0; dz < CELL_SIZE; dz++) map[0][sz*CELL_SIZE+dz] = true;
            if (self[CELLS_PER_CHUNK-1][sz] && right[0][sz])
                for (int dz = 0; dz < CELL_SIZE; dz++) map[15][sz*CELL_SIZE+dz] = true;
        }
        for (int sx = 0; sx < CELLS_PER_CHUNK; sx++) {
            if (self[sx][0] && top[sx][CELLS_PER_CHUNK-1])
                for (int dx = 0; dx < CELL_SIZE; dx++) map[sx*CELL_SIZE+dx][0] = true;
            if (self[sx][CELLS_PER_CHUNK-1] && bot[sx][0])
                for (int dx = 0; dx < CELL_SIZE; dx++) map[sx*CELL_SIZE+dx][15] = true;
        }

        return map;
    }

    private boolean[][] getCellGrid(int cx, int cz) {
        boolean[][] g = new boolean[CELLS_PER_CHUNK][CELLS_PER_CHUNK];
        for (int sx = 0; sx < CELLS_PER_CHUNK; sx++)
            for (int sz = 0; sz < CELLS_PER_CHUNK; sz++)
                g[sx][sz] = (Math.abs(cellSeed(cx, cz, sx, sz) % 100) < 80);
        boolean any = false;
        for (int i = 0; i < CELLS_PER_CHUNK && !any; i++)
            for (int j = 0; j < CELLS_PER_CHUNK && !any; j++)
                if (g[i][j]) any = true;
        if (!any) g[0][0] = true;
        return g;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PILLAR
    // ══════════════════════════════════════════════════════════════════════════

    private boolean isPillar(int wx, int wz, boolean[][] openMap, int lx, int lz) {
        if (Math.floorMod(wx, 12) >= 2 || Math.floorMod(wz, 12) >= 2) return false;
        for (int dx = -2; dx <= 3; dx++)
            for (int dz = -2; dz <= 3; dz++) {
                int nx = lx + dx, nz = lz + dz;
                if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16) return false;
                if (!openMap[nx][nz]) return false;
            }
        return (Math.abs(pillarSeed(wx - Math.floorMod(wx, 12),
                                    wz - Math.floorMod(wz, 12)) % 3) != 2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SEEDS
    // ══════════════════════════════════════════════════════════════════════════

    private long cellSeed(int cx, int cz, int sx, int sz) {
        return ((long) cx * 341873128712L) ^ ((long) cz * 132897987541L)
             ^ ((long) sx * 0x9E3779B97F4A7C15L) ^ ((long) sz * 0x6C62272E07BB0142L)
             ^ 0xBADC0FFEE0DDF00DL;
    }

    private long pillarSeed(int wx, int wz) {
        return ((long) wx * 0x517CC1B727220A95L) ^ ((long) wz * 0xB492DFFE9FDEAD17L);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REQUIRED OVERRIDES
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void applyCarvers(WorldGenRegion r, long s, RandomState rs,
                             BiomeManager bm, StructureManager sm,
                             ChunkAccess c, GenerationStep.Carving v) {}
    @Override
    public void buildSurface(WorldGenRegion r, StructureManager s,
                             RandomState rs, ChunkAccess c) {}
    @Override
    public void spawnOriginalMobs(WorldGenRegion r) {}
    @Override public int getGenDepth() { return 64; }

    @Override
    public CompletableFuture<ChunkAccess> createBiomes(
            RandomState rs, Blender b, StructureManager sm, ChunkAccess c) {
        return CompletableFuture.completedFuture(c);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState rs, BlockPos pos) {
        info.add("Backrooms Level 0 | " + pos.toShortString());
    }

    @Override public int getSeaLevel() { return -1; }
    @Override public int getMinY()     { return Y_BEDROCK_FLOOR; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types t,
                             LevelHeightAccessor l, RandomState rs) { return Y_CEILING; }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor l, RandomState rs) {
        return new NoiseColumn(Y_BEDROCK_FLOOR, new BlockState[0]);
    }
}
