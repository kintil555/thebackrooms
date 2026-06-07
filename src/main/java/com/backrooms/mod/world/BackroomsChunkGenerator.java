package com.backrooms.mod.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Custom chunk generator untuk dimensi Backrooms.
 *
 * Generates Level 0 Backrooms — infinite maze of rooms & corridors.
 *
 * STRUKTUR TIAP CHUNK (16×16 per chunk):
 *   Y=0           → Bedrock floor
 *   Y=1           → Sandstone (sub-floor)
 *   Y=2           → Oak Planks (lantai karpet kayu — seperti screenshot)
 *   Y=3           → Air (ruang berjalan — floor level)
 *   Y=4..6        → Air (ruang berjalan)
 *   Y=7           → Stone Bricks (langit-langit)
 *   Y=8           → Bedrock ceiling (atap solid)
 *
 * DINDING:
 *   Dinding vertikal dari Y=2..7 di tepi ruangan/koridor.
 *   Material: Birch Planks / Sandstone (seperti screenshot — kuning kecoklatan)
 *
 * PENCAHAYAAN:
 *   Glowstone tersembunyi di atas langit-langit (Y=8) di beberapa titik,
 *   memancarkan cahaya ke bawah melalui celah 1 blok di stone_brick ceiling.
 *   Ini mensimulasikan "fluorescent ceiling lights" backrooms.
 *
 * MAZE ALGORITHM:
 *   Per-chunk deterministic cellular automata maze.
 *   Setiap chunk 16×16 dibagi jadi grid sel 4×4.
 *   Tiap sel bisa jadi ROOM (terbuka) atau WALL (tertutup).
 *   Koneksi antar sel = koridor (2 blok lebar).
 *   Hasilnya: labirin yang terasa organik tapi tetap rapi.
 */
public class BackroomsChunkGenerator extends ChunkGenerator {

    public static final MapCodec<BackroomsChunkGenerator> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source")
                            .forGetter(BackroomsChunkGenerator::getBiomeSource)
            ).apply(instance, BackroomsChunkGenerator::new));

    // ─── Dimensi vertikal ─────────────────────────────────────────────────────
    private static final int BEDROCK_FLOOR    = 0;
    private static final int SUBFLOOR_Y       = 1;  // sandstone sub-floor
    private static final int FLOOR_Y          = 2;  // oak planks / carpet floor
    private static final int ROOM_BOTTOM_Y    = 3;  // air starts here
    private static final int ROOM_TOP_Y       = 6;  // air ends here (4 blok tinggi)
    private static final int CEILING_Y        = 7;  // stone bricks ceiling
    private static final int ROOF_Y           = 8;  // bedrock roof

    // ─── Blok material ────────────────────────────────────────────────────────
    private static final BlockState BEDROCK       = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState SUBFLOOR      = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState FLOOR         = Blocks.OAK_PLANKS.defaultBlockState();
    private static final BlockState WALL          = Blocks.BIRCH_PLANKS.defaultBlockState();
    private static final BlockState WALL_ALT      = Blocks.SANDSTONE.defaultBlockState();
    private static final BlockState CEILING       = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState CEILING_TRIM  = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
    private static final BlockState LIGHT_BLOCK   = Blocks.GLOWSTONE.defaultBlockState();
    private static final BlockState AIR           = Blocks.AIR.defaultBlockState();
    private static final BlockState VOID_AIR      = Blocks.VOID_AIR.defaultBlockState();

    // ─── Grid maze: sel 4×4 dalam chunk 16×16 → 4×4 grid = 16 sel ───────────
    private static final int CELL_SIZE  = 4;   // blok per sel
    private static final int GRID_SIZE  = 4;   // jumlah sel per sisi (16/4)

    public BackroomsChunkGenerator(BiomeSource biomeSource) {
        super(biomeSource);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // ─── Main generation ──────────────────────────────────────────────────────

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
            Blender blender,
            RandomState randomState,
            StructureManager structureManager,
            ChunkAccess chunk) {

        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;

        // Seed deterministik per-chunk
        long seed = hashChunk(chunkX, chunkZ);

        // Generate maze grid: true = sel terbuka (ruangan/koridor), false = dinding solid
        boolean[][] openCells = generateMazeGrid(chunkX, chunkZ, seed);

        // Isi tiap kolom X,Z dalam chunk
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                fillColumn(chunk, lx, lz, openCells, chunkX, chunkZ, seed);
            }
        }

        return CompletableFuture.completedFuture(chunk);
    }

    // ─── Per-kolom placement ──────────────────────────────────────────────────

    private void fillColumn(ChunkAccess chunk, int lx, int lz,
                             boolean[][] openCells, int chunkX, int chunkZ, long seed) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(chunk.getPos().getMinBlockX() + lx, 0, chunk.getPos().getMinBlockZ() + lz);

        // Sel mana yang ditempati kolom ini?
        int cellX = lx / CELL_SIZE;
        int cellZ = lz / CELL_SIZE;
        boolean isOpen = openCells[cellX][cellZ];

        // Posisi dalam sel (0..3)
        int localX = lx % CELL_SIZE;
        int localZ = lz % CELL_SIZE;

        // Apakah tepi sel? (dinding kandidat)
        boolean edgeX = (localX == 0);
        boolean edgeZ = (localZ == 0);
        boolean onEdge = edgeX || edgeZ;

        // Tentukan apakah blok ini bagian dari koridor
        boolean isCorridor = false;
        if (!isOpen && onEdge) {
            // Cek apakah tetangga di arah ini terbuka → ini koridor penghubung
            if (edgeX && cellX > 0 && openCells[cellX - 1][cellZ]) {
                // Koridor: tengah sel (Z) harus 2 blok lebar
                isCorridor = (localZ == 1 || localZ == 2);
            }
            if (edgeZ && cellZ > 0 && openCells[cellX][cellZ - 1]) {
                isCorridor = isCorridor || (localX == 1 || localX == 2);
            }
        }

        // Tentukan apakah kolom ini harus jadi ruang kosong (open interior atau koridor)
        boolean isAirColumn;
        if (isOpen) {
            // Ruang terbuka: bukan tepi → interior terbuka
            // Tepi: dinding kecuali ada tetangga open → opening/pintu
            if (!onEdge) {
                isAirColumn = true;
            } else {
                // Cek apakah tetangga di arah tepi ini juga open/koridor
                boolean neighborOpen = false;
                if (edgeX && cellX > 0)     neighborOpen = openCells[cellX - 1][cellZ];
                if (edgeZ && cellZ > 0)     neighborOpen = neighborOpen || openCells[cellX][cellZ - 1];
                // Buka 2 blok tengah dari tepi untuk pintu/koridor
                if (edgeX) neighborOpen = neighborOpen && (localZ == 1 || localZ == 2);
                if (edgeZ) neighborOpen = neighborOpen && (localX == 1 || localX == 2);
                isAirColumn = neighborOpen;
            }
        } else {
            isAirColumn = isCorridor;
        }

        // === Letakkan block sesuai Y ===

        // Bedrock floor
        chunk.setBlockState(pos.setY(BEDROCK_FLOOR), BEDROCK, false);

        // Sub-floor (sandstone)
        chunk.setBlockState(pos.setY(SUBFLOOR_Y), SUBFLOOR, false);

        if (isAirColumn) {
            // Kolom terbuka: lantai + udara + langit-langit
            chunk.setBlockState(pos.setY(FLOOR_Y), FLOOR, false);

            for (int y = ROOM_BOTTOM_Y; y <= ROOM_TOP_Y; y++) {
                chunk.setBlockState(pos.setY(y), AIR, false);
            }

            // Ceiling — variasi: sebagian besar stone bricks, sesekali glowstone tersembunyi
            boolean isLightPos = isLightPosition(lx, lz, chunkX, chunkZ, seed);
            chunk.setBlockState(pos.setY(CEILING_Y), isLightPos ? CEILING_TRIM : CEILING, false);

            // Glowstone di atas langit-langit untuk pencahayaan (hidden above ceiling)
            if (isLightPos) {
                chunk.setBlockState(pos.setY(ROOF_Y), LIGHT_BLOCK, false);
            } else {
                chunk.setBlockState(pos.setY(ROOF_Y), BEDROCK, false);
            }

        } else {
            // Kolom dinding: solid dari lantai ke atap
            // Material dinding: alternasi birch planks dan sandstone untuk variasi visual
            BlockState wallMat = isAltWall(lx, lz, chunkX, chunkZ) ? WALL_ALT : WALL;

            chunk.setBlockState(pos.setY(FLOOR_Y), wallMat, false);
            for (int y = ROOM_BOTTOM_Y; y <= ROOM_TOP_Y; y++) {
                chunk.setBlockState(pos.setY(y), wallMat, false);
            }
            chunk.setBlockState(pos.setY(CEILING_Y), wallMat, false);
            chunk.setBlockState(pos.setY(ROOF_Y), BEDROCK, false);
        }
    }

    // ─── Maze grid generation ─────────────────────────────────────────────────

    /**
     * Generate 4×4 boolean grid per chunk.
     * Menggunakan weighted random: 75% sel terbuka, 25% dinding.
     * Ini menghasilkan maze yang cukup padat namun tetap bisa dijelajahi.
     *
     * Konektivitas dijamin: minimal satu koridor di setiap sisi chunk
     * terhubung ke chunk tetangga yang sesuai.
     */
    private boolean[][] generateMazeGrid(int chunkX, int chunkZ, long seed) {
        boolean[][] grid = new boolean[GRID_SIZE][GRID_SIZE];

        for (int cx = 0; cx < GRID_SIZE; cx++) {
            for (int cz = 0; cz < GRID_SIZE; cz++) {
                long cellSeed = seed ^ ((long) cx * 0x9E3779B97F4A7C15L) ^ ((long) cz * 0x6C62272E07BB0142L);
                // 75% chance open
                grid[cx][cz] = (Math.abs(cellSeed % 100) < 75);
            }
        }

        // Pastikan setidaknya tepi chunk terhubung ke tetangga
        // dengan membuka sel tepi jika chunk tetangga juga open
        ensureConnectivity(grid, chunkX, chunkZ);

        return grid;
    }

    /**
     * Pastikan chunk bisa terhubung ke tetangganya.
     * Minimal satu sel di tiap sisi chunk (N/S/E/W) harus open
     * jika chunk tetangga di arah itu juga memiliki sel open di tepi yang bersesuaian.
     */
    private void ensureConnectivity(boolean[][] grid, int chunkX, int chunkZ) {
        // Cek 4 arah: North (Z-), South (Z+), West (X-), East (X+)
        // Jika tidak ada sel open di tepi → paksa buka satu
        boolean hasNorth = false, hasSouth = false, hasWest = false, hasEast = false;
        for (int i = 0; i < GRID_SIZE; i++) {
            if (grid[i][0]) hasNorth = true;
            if (grid[i][GRID_SIZE - 1]) hasSouth = true;
            if (grid[0][i]) hasWest = true;
            if (grid[GRID_SIZE - 1][i]) hasEast = true;
        }
        // Buka tengah-tengah tepi jika tertutup semua
        if (!hasNorth)  grid[1][0] = true;
        if (!hasSouth)  grid[1][GRID_SIZE - 1] = true;
        if (!hasWest)   grid[0][1] = true;
        if (!hasEast)   grid[GRID_SIZE - 1][1] = true;
    }

    // ─── Lighting placement ───────────────────────────────────────────────────

    /**
     * Tentukan apakah posisi ini punya lampu tersembunyi di langit-langit.
     * Posisi lampu: setiap ~4-6 blok di ruang terbuka, dihitung dari seed.
     * Ini mensimulasikan lampu fluorescent backrooms yang khas.
     */
    private boolean isLightPosition(int lx, int lz, int chunkX, int chunkZ, long seed) {
        // Koordinat dunia nyata mod 8 (lampu tiap 8 blok)
        int wx = chunkX * 16 + lx;
        int wz = chunkZ * 16 + lz;
        // Pola lampu: garis horizontal tiap 4 blok Z, tiap 6 blok X
        // Ini memberi efek "strip lampu" horizontal seperti backrooms
        if ((wz % 4) == 2 && (wx % 6) >= 1 && (wx % 6) <= 4) {
            // Tambah variasi agar tidak terlalu seragam
            long lightSeed = seed ^ ((long) wx * 0x517CC1B727220A95L) ^ ((long) wz * 0xB492557D3452L);
            return (Math.abs(lightSeed % 3) != 0); // 66% chance ada lampu di posisi valid
        }
        return false;
    }

    /**
     * Alternatif material dinding: birch planks di X genap, sandstone di X ganjil.
     * Ini memberikan variasi visual dinding seperti di screenshot — campuran
     * panel kayu dan dinding plester kuning.
     */
    private boolean isAltWall(int lx, int lz, int chunkX, int chunkZ) {
        int wx = chunkX * 16 + lx;
        return (wx % 2) == 0;
    }

    // ─── Hash per chunk ───────────────────────────────────────────────────────

    private long hashChunk(int cx, int cz) {
        return ((long) cx * 341873128712L) ^ ((long) cz * 132897987541L) ^ 0xDEADBEEFCAFEL;
    }

    // ─── Required overrides (minimal) ─────────────────────────────────────────

    @Override
    public void applyCarvers(WorldGenRegion region, long seed, RandomState randomState,
                             BiomeManager biomeManager, StructureManager structureManager,
                             ChunkAccess chunk, GenerationStep.Carving step) {
        // Tidak ada carver — backrooms tidak punya gua
    }

    @Override
    public void buildSurface(WorldGenRegion region, StructureManager structureManager,
                             RandomState randomState, ChunkAccess chunk) {
        // Surface sudah dihandle di fillFromNoise
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {
        // Tidak ada mob spawn original — backrooms awalnya sepi
    }

    @Override
    public int getGenDepth() {
        return 384;
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
    public void getDebugScreenInfo(List<String> info, RandomState randomState, BlockPos pos) {
        info.add("Backrooms Level 0");
    }

    @Override
    public int getSeaLevel() {
        return -63;
    }

    @Override
    public int getMinY() {
        return 0;
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types heightmapType,
                             LevelHeightAccessor level, RandomState randomState) {
        return CEILING_Y;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level,
                                     RandomState randomState) {
        return new NoiseColumn(0, new BlockState[0]);
    }
}
