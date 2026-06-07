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
 * Custom ChunkGenerator untuk dimensi Backrooms — Level 0.
 *
 * Menghasilkan labirin ruangan dan koridor infinite dengan block vanilla,
 * persis seperti referensi Kane Pixels / screenshot:
 *
 * MATERIAL (semua vanilla):
 *   Lantai  → Oak Planks (kayu coklat — seperti screenshot)
 *   Dinding → Birch Planks + Sandstone (alternasi panel kayu & plester kuning)
 *   Langit  → Stone Bricks + Chiseled Stone Bricks
 *   Lampu   → Glowstone tersembunyi di atas langit-langit
 *   Atap    → Bedrock
 *
 * LAYOUT VERTIKAL:
 *   Y=0  bedrock floor
 *   Y=1  sandstone sub-floor
 *   Y=2  oak planks (lantai)
 *   Y=3-6 air (4 blok tinggi ruangan)
 *   Y=7  stone bricks langit-langit
 *   Y=8  bedrock atap (glowstone di sini pada titik lampu)
 *
 * MAZE ALGORITHM:
 *   Grid 4×4 sel per chunk (tiap sel = 4×4 blok).
 *   75% sel terbuka, 25% dinding solid.
 *   Koridor 2-blok lebar menghubungkan sel yang bersebelahan.
 *   Seed deterministik → same world seed = same layout setiap kali.
 */
public class BackroomsChunkGenerator extends ChunkGenerator {

    public static final MapCodec<BackroomsChunkGenerator> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source")
                            .forGetter(BackroomsChunkGenerator::getBiomeSource)
            ).apply(instance, BackroomsChunkGenerator::new));

    // ─── Y levels ─────────────────────────────────────────────────────────────
    private static final int Y_BEDROCK_FLOOR = 0;
    private static final int Y_SUBFLOOR      = 1;
    private static final int Y_FLOOR         = 2;
    private static final int Y_ROOM_LOW      = 3;
    private static final int Y_ROOM_HIGH     = 6;
    private static final int Y_CEILING       = 7;
    private static final int Y_ROOF          = 8;

    // ─── Block states ─────────────────────────────────────────────────────────
    private static final BlockState BEDROCK      = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState SUBFLOOR_BLK = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState FLOOR_BLK    = Blocks.OAK_PLANKS.defaultBlockState();
    private static final BlockState WALL_A       = Blocks.BIRCH_PLANKS.defaultBlockState();
    private static final BlockState WALL_B       = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState CEIL_NORMAL  = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState CEIL_LIGHT   = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
    private static final BlockState GLOWSTONE    = Blocks.GLOWSTONE.defaultBlockState();
    private static final BlockState AIR          = Blocks.AIR.defaultBlockState();

    // ─── Maze settings ────────────────────────────────────────────────────────
    /** Ukuran tiap sel maze dalam blok (4→ 4×4 sel per chunk 16×16). */
    private static final int CELL_SIZE = 4;
    /** Jumlah sel per sisi chunk. */
    private static final int GRID_CELLS = 4; // 16 / 4

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
        long seed   = chunkSeed(chunkX, chunkZ);

        boolean[][] open = buildMazeGrid(chunkX, chunkZ, seed);

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                fillColumn(chunk, lx, lz, open, chunkX, chunkZ, seed);
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    // ─── Column fill ──────────────────────────────────────────────────────────

    private void fillColumn(ChunkAccess chunk, int lx, int lz,
                             boolean[][] open, int chunkX, int chunkZ, long seed) {

        int cellX  = lx / CELL_SIZE;
        int cellZ  = lz / CELL_SIZE;
        int locX   = lx % CELL_SIZE;  // position within cell (0-3)
        int locZ   = lz % CELL_SIZE;

        boolean isOpen = open[cellX][cellZ];
        boolean onEdgeX = (locX == 0);
        boolean onEdgeZ = (locZ == 0);

        // Apakah kolom ini bagian dari ruang terbuka atau koridor?
        boolean isAir = resolveIsAir(isOpen, onEdgeX, onEdgeZ, locX, locZ, cellX, cellZ, open);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(
                chunk.getPos().getMinBlockX() + lx, 0,
                chunk.getPos().getMinBlockZ() + lz);

        // Y=0: bedrock floor
        chunk.setBlockState(pos.setY(Y_BEDROCK_FLOOR), BEDROCK, false);
        // Y=1: sandstone sub-floor
        chunk.setBlockState(pos.setY(Y_SUBFLOOR), SUBFLOOR_BLK, false);

        if (isAir) {
            // Lantai
            chunk.setBlockState(pos.setY(Y_FLOOR), FLOOR_BLK, false);
            // Ruang udara
            for (int y = Y_ROOM_LOW; y <= Y_ROOM_HIGH; y++) {
                chunk.setBlockState(pos.setY(y), AIR, false);
            }
            // Langit-langit — normal atau chiseled di titik lampu
            boolean lamp = isLampPosition(lx, lz, chunkX, chunkZ);
            chunk.setBlockState(pos.setY(Y_CEILING), lamp ? CEIL_LIGHT : CEIL_NORMAL, false);
            // Atap — glowstone di titik lampu, bedrock di tempat lain
            chunk.setBlockState(pos.setY(Y_ROOF), lamp ? GLOWSTONE : BEDROCK, false);
        } else {
            // Kolom dinding solid
            BlockState wallMat = wallMaterial(lx, lz, chunkX, chunkZ);
            chunk.setBlockState(pos.setY(Y_FLOOR),   wallMat, false);
            for (int y = Y_ROOM_LOW; y <= Y_ROOM_HIGH; y++) {
                chunk.setBlockState(pos.setY(y), wallMat, false);
            }
            chunk.setBlockState(pos.setY(Y_CEILING), wallMat, false);
            chunk.setBlockState(pos.setY(Y_ROOF),    BEDROCK, false);
        }
    }

    // ─── Air/Wall resolve ─────────────────────────────────────────────────────

    /**
     * Tentukan apakah kolom ini harus jadi udara (ruang terbuka / koridor).
     *
     * Aturan:
     *  - Sel open, bukan tepi → udara (interior ruangan)
     *  - Sel open, tepi menuju sel open lain → udara (pintu 2-blok lebar)
     *  - Sel closed, tepat di batas dengan sel open → koridor 2-blok lebar
     *  - Sisanya → dinding solid
     */
    private boolean resolveIsAir(boolean isOpen, boolean onEdgeX, boolean onEdgeZ,
                                  int locX, int locZ, int cellX, int cellZ, boolean[][] open) {
        if (isOpen) {
            if (!onEdgeX && !onEdgeZ) return true; // interior penuh

            // Tepi X (locX==0): cek sel sebelah kiri (cellX-1)
            if (onEdgeX && cellX > 0 && open[cellX - 1][cellZ]) {
                // Buka 2 blok tengah (locZ 1 dan 2) sebagai pintu
                if (locZ == 1 || locZ == 2) return true;
            }
            // Tepi Z (locZ==0): cek sel sebelah atas (cellZ-1)
            if (onEdgeZ && cellZ > 0 && open[cellX][cellZ - 1]) {
                if (locX == 1 || locX == 2) return true;
            }
            // Interior (tidak di tepi sama sekali)
            if (!onEdgeX && !onEdgeZ) return true;
            // Open tapi di sudut/tepi tanpa neighbor open → dinding
            return false;
        } else {
            // Sel closed: hanya jadi udara jika di tepi menuju open (koridor)
            if (onEdgeX && cellX > 0 && open[cellX - 1][cellZ]) {
                if (locZ == 1 || locZ == 2) return true;
            }
            if (onEdgeZ && cellZ > 0 && open[cellX][cellZ - 1]) {
                if (locX == 1 || locX == 2) return true;
            }
            return false;
        }
    }

    // ─── Maze generation ──────────────────────────────────────────────────────

    /**
     * Buat boolean grid 4×4 untuk chunk ini.
     * true = sel terbuka (ruang), false = sel tertutup (dinding).
     * 75% probabilitas terbuka → maze cukup padat tapi bisa dijelajahi.
     */
    private boolean[][] buildMazeGrid(int chunkX, int chunkZ, long seed) {
        boolean[][] grid = new boolean[GRID_CELLS][GRID_CELLS];
        for (int cx = 0; cx < GRID_CELLS; cx++) {
            for (int cz = 0; cz < GRID_CELLS; cz++) {
                long s = seed ^ ((long) cx * 0x9E3779B97F4A7C15L) ^ ((long) cz * 0x6C62272E07BB0142L);
                grid[cx][cz] = (Math.abs(s % 100) < 75);
            }
        }
        // Pastikan ada setidaknya satu koridor di tiap sisi chunk (konektivitas)
        ensureConnectivity(grid);
        return grid;
    }

    private void ensureConnectivity(boolean[][] g) {
        boolean n = false, s = false, w = false, e = false;
        for (int i = 0; i < GRID_CELLS; i++) {
            if (g[i][0]) n = true;
            if (g[i][GRID_CELLS - 1]) s = true;
            if (g[0][i]) w = true;
            if (g[GRID_CELLS - 1][i]) e = true;
        }
        if (!n) g[1][0] = true;
        if (!s) g[1][GRID_CELLS - 1] = true;
        if (!w) g[0][1] = true;
        if (!e) g[GRID_CELLS - 1][1] = true;
    }

    // ─── Lamp positions ───────────────────────────────────────────────────────

    /**
     * Tentukan apakah posisi dunia (wx, wz) ini punya lampu tersembunyi.
     * Pola: strip horizontal tiap 6 blok X, tiap 4 blok Z.
     * Mensimulasikan "fluorescent strip lights" khas backrooms.
     */
    private boolean isLampPosition(int lx, int lz, int chunkX, int chunkZ) {
        int wx = chunkX * 16 + lx;
        int wz = chunkZ * 16 + lz;
        int mx = Math.floorMod(wx, 6);
        int mz = Math.floorMod(wz, 4);
        return (mz == 2) && (mx >= 1 && mx <= 4);
    }

    /**
     * Material dinding: alternasi Birch Planks dan Sandstone.
     * Birch Planks = panel kayu (warna terang kekuningan).
     * Sandstone    = plester/dinding kapur (kuning).
     * Alternasi tiap 2 blok → tampak seperti panel dinding backrooms.
     */
    private BlockState wallMaterial(int lx, int lz, int chunkX, int chunkZ) {
        int wx = chunkX * 16 + lx;
        // Alternasi per-2-blok di arah X
        return (Math.floorMod(wx, 4) < 2) ? WALL_A : WALL_B;
    }

    // ─── Seed ────────────────────────────────────────────────────────────────

    private long chunkSeed(int cx, int cz) {
        return ((long) cx * 341873128712L) ^ ((long) cz * 132897987541L) ^ 0xDEADCAFEBEEFL;
    }

    // ─── Required abstract overrides ─────────────────────────────────────────

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
        // Tidak ada gua di backrooms
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                             RandomState randomState, ChunkAccess chunk) {
        // Sudah di-handle di fillFromNoise
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // Backrooms awalnya sepi — tidak ada mob spawn
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

    /** Diperlukan di 1.21.1 — menampilkan info di F3 debug screen. */
    @Override
    public void addDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add("Backrooms Level 0 | " + pos.toShortString());
    }

    @Override
    public int getSeaLevel() {
        return -1;
    }

    @Override
    public int getMinY() {
        return Y_BEDROCK_FLOOR;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmapType,
                             LevelHeightAccessor level, RandomState randomState) {
        return Y_CEILING;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level,
                                     RandomState randomState) {
        return new NoiseColumn(Y_BEDROCK_FLOOR, new BlockState[0]);
    }
}
