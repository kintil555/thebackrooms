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
 * LAYOUT VERTIKAL:
 *   Y=0  Bedrock
 *   Y=1  Brown Wool (karpet)
 *   Y=2  Cut Sandstone (baseboard dinding)   ← AIR jika open
 *   Y=3  Stripped Oak Log (dinding)           ← AIR jika open
 *   Y=4  Stripped Oak Log (dinding)           ← AIR jika open
 *   Y=5  Stripped Oak Log (dinding)           ← AIR jika open
 *   Y=6  Smooth Stone / Ochre Froglight (ceiling)
 *   Y=7  Bedrock (atap)
 *
 * MAZE SYSTEM — "Office Partition" style seperti referensi:
 *   - Grid global 4×4 blok per sel (lebih halus dari 8×8)
 *   - Tembok TIPIS 1 blok — bukan sel solid besar
 *   - Pilar 1×1 di titik persimpangan grid
 *   - Ruangan luas di antara partisi
 *   - Deterministik per world seed
 *
 * Cara kerjanya:
 *   Setiap blok world (wx, wz) di-cek apakah dia ada di "garis partisi".
 *   Garis partisi muncul di interval GRID_SIZE blok (setiap 4 blok).
 *   Di tiap garis, ada "pintu" (gap) yang dibuka/tutup berdasarkan seed.
 *   Pilar 1×1 ada di setiap titik persilangan garis X dan Z.
 */
public class BackroomsChunkGenerator extends ChunkGenerator {

    public static final MapCodec<BackroomsChunkGenerator> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source")
                            .forGetter(BackroomsChunkGenerator::getBiomeSource)
            ).apply(instance, BackroomsChunkGenerator::new));

    // ─── Y levels ─────────────────────────────────────────────────────────────
    private static final int Y_BASE     = 0;
    private static final int Y_CARPET   = 1;
    private static final int Y_BASE2    = 2;   // cut sandstone baseboard
    private static final int Y_WALL_1   = 3;
    private static final int Y_WALL_2   = 4;
    private static final int Y_WALL_3   = 5;
    private static final int Y_CEILING  = 6;
    private static final int Y_ROOF     = 7;

    // ─── Blocks ───────────────────────────────────────────────────────────────
    private static final BlockState BLK_BEDROCK  = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState BLK_CARPET   = Blocks.BROWN_WOOL.defaultBlockState();
    private static final BlockState BLK_BASE     = Blocks.CUT_SANDSTONE.defaultBlockState();
    private static final BlockState BLK_WALL     = Blocks.STRIPPED_OAK_LOG.defaultBlockState();
    private static final BlockState BLK_CEIL     = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState BLK_LAMP     = Blocks.OCHRE_FROGLIGHT.defaultBlockState();
    private static final BlockState BLK_AIR      = Blocks.AIR.defaultBlockState();

    // ─── Maze config ──────────────────────────────────────────────────────────
    /**
     * Jarak antar garis partisi (blok).
     * 6 = ruangan 5 blok lebar + 1 blok tembok — cukup lega, mirip referensi.
     */
    private static final int GRID    = 6;

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

        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                boolean solid = isSolid(wx, wz);
                fillColumn(chunk, pos, lx, lz, wx, wz, solid);
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MAZE LOGIC — isSolid
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Menentukan apakah blok di (wx, wz) adalah dinding/pilar.
     *
     * Prinsip:
     *   - Garis X terjadi di wx % GRID == 0
     *   - Garis Z terjadi di wz % GRID == 0
     *   - Persilangan (wx%GRID==0 && wz%GRID==0) → selalu PILAR 1×1
     *   - Segmen di garis X (wz%GRID==0) → dinding tipis, dengan gap (pintu) acak
     *   - Segmen di garis Z (wx%GRID==0) → dinding tipis, dengan gap (pintu) acak
     *   - Bukan garis → selalu OPEN (udara/karpet)
     */
    private boolean isSolid(int wx, int wz) {
        int gx = Math.floorMod(wx, GRID);
        int gz = Math.floorMod(wz, GRID);

        boolean onLineX = (gx == 0); // blok ini ada di "garis partisi vertikal"
        boolean onLineZ = (gz == 0); // blok ini ada di "garis partisi horizontal"

        if (!onLineX && !onLineZ) return false; // interior ruangan → open

        // Koordinat "sel" grid tempat blok ini berada
        int cellX = Math.floorDiv(wx, GRID); // indeks sel X
        int cellZ = Math.floorDiv(wz, GRID); // indeks sel Z

        if (onLineX && onLineZ) {
            // ── Titik persilangan → selalu pilar solid ──────────────────────
            return true;
        }

        if (onLineX) {
            // ── Garis vertikal (partisi di arah Z) ─────────────────────────
            // Segmen ini ada di antara sel (cellX-1, cellZ) dan (cellX, cellZ)
            // Cek apakah ada "pintu" di posisi gz ini dalam segmen ini
            // Setiap segmen panjang (GRID-1) blok (antara 2 pilar)
            // Pintu lebar 2 blok di tengah segmen (gz=2,3 dari 5)
            return !isDoor(cellX, cellZ, false, gz);
        }

        // onLineZ:
        // ── Garis horizontal (partisi di arah X) ───────────────────────────
        return !isDoor(cellX, cellZ, true, gx);
    }

    /**
     * Apakah ada "pintu" (gap terbuka) di posisi ini?
     *
     * @param cellX  indeks sel X
     * @param cellZ  indeks sel Z
     * @param isHoriz true = garis horizontal (arah X), false = vertikal (arah Z)
     * @param offset  posisi dalam segmen (1..GRID-1)
     */
    private boolean isDoor(int cellX, int cellZ, boolean isHoriz, int offset) {
        // Pintu hanya di blok tengah segmen: offset == GRID/2 atau GRID/2+1
        // Untuk GRID=6: offset 3 dan 4 → pintu lebar 2
        int mid = GRID / 2;
        if (offset != mid && offset != mid + 1) return false;

        // Seed untuk segmen ini — apakah segmen ini punya pintu terbuka?
        long s = isHoriz
                ? wallSeed(cellX, cellZ, 1)   // partisi horizontal antara cellZ-1 dan cellZ
                : wallSeed(cellX, cellZ, 0);  // partisi vertikal antara cellX-1 dan cellX

        // ~70% segmen punya pintu terbuka → maze tetap terhubung, tidak terlalu padat
        return (Math.abs(s % 10) < 7);
    }

    /** Seed deterministik per segmen tembok. */
    private long wallSeed(int cellX, int cellZ, int axis) {
        return ((long) cellX * 0x9E3779B97F4A7C15L)
             ^ ((long) cellZ * 0x6C62272E07BB0142L)
             ^ ((long) axis  * 0xD1B54A32D192ED03L)
             ^ 0xBADC0FFEE0DDF00DL;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // COLUMN FILL
    // ══════════════════════════════════════════════════════════════════════════

    private void fillColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                            int lx, int lz, int wx, int wz, boolean solid) {
        // Y=0 bedrock selalu
        set(chunk, pos, lx, Y_BASE,  lz, BLK_BEDROCK);
        // Y=7 bedrock atap selalu
        set(chunk, pos, lx, Y_ROOF,  lz, BLK_BEDROCK);

        if (solid) {
            // ── DINDING / PILAR ─────────────────────────────────────────────
            set(chunk, pos, lx, Y_CARPET, lz, BLK_BASE);   // baseboard turun ke lantai
            set(chunk, pos, lx, Y_BASE2,  lz, BLK_BASE);   // cut sandstone
            set(chunk, pos, lx, Y_WALL_1, lz, BLK_WALL);
            set(chunk, pos, lx, Y_WALL_2, lz, BLK_WALL);
            set(chunk, pos, lx, Y_WALL_3, lz, BLK_WALL);
            set(chunk, pos, lx, Y_CEILING,lz, BLK_WALL);   // sambung ke ceiling
        } else {
            // ── RUANGAN TERBUKA ─────────────────────────────────────────────
            set(chunk, pos, lx, Y_CARPET, lz, BLK_CARPET);
            set(chunk, pos, lx, Y_BASE2,  lz, BLK_AIR);
            set(chunk, pos, lx, Y_WALL_1, lz, BLK_AIR);
            set(chunk, pos, lx, Y_WALL_2, lz, BLK_AIR);
            set(chunk, pos, lx, Y_WALL_3, lz, BLK_AIR);
            // Ceiling: froglight tiap 3 blok, smooth stone lainnya
            set(chunk, pos, lx, Y_CEILING, lz,
                isLamp(wx, wz) ? BLK_LAMP : BLK_CEIL);
        }
    }

    private void set(ChunkAccess c, BlockPos.MutableBlockPos p,
                     int lx, int y, int lz, BlockState s) {
        c.setBlockState(p.set(lx, y, lz), s, false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LAMP — froglight tiap 3 blok
    // ══════════════════════════════════════════════════════════════════════════

    private boolean isLamp(int wx, int wz) {
        return (Math.floorMod(wx, 3) == 0) && (Math.floorMod(wz, 3) == 0);
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
    @Override public int getMinY()     { return Y_BASE; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types t,
                             LevelHeightAccessor l, RandomState rs) { return Y_CEILING; }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor l, RandomState rs) {
        return new NoiseColumn(Y_BASE, new BlockState[0]);
    }
}
