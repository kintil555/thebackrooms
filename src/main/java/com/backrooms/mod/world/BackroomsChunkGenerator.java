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
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * BackroomsChunkGenerator — Level 0 "Habitable Zone"
 *
 * LAYOUT VERTIKAL (ruangan normal):
 *   Y=0  Bedrock
 *   Y=1  Brown Wool (karpet)
 *   Y=2  Cut Sandstone (baseboard)
 *   Y=3  Stripped Oak Log (dinding)
 *   Y=4  Stripped Oak Log
 *   Y=5  Stripped Oak Log
 *   Y=6  Smooth Stone / Ochre Froglight (ceiling)
 *   Y=7  Bedrock (atap)
 *
 * LAYOUT VERTIKAL (ZONE_VOID — ruangan mega-tinggi):
 *   Y=0  Bedrock
 *   Y=1  Brown Wool
 *   Y=2–39 AIR
 *   Y=40 Smooth Stone / Ochre Froglight (ceiling void)
 *   Y=41–63 Bedrock (atap solid, biar aman di batas height=64)
 *
 * ZONE SYSTEM — 5 tipe zona dipilih per makro-region 48×48 blok:
 *   ZONE_CORRIDOR — koridor sempit, grid 4 blok, ~60% pintu
 *   ZONE_OFFICE   — ruangan standar, grid 6 blok, ~70% pintu (default)
 *   ZONE_OPEN     — ruangan luas, grid 12 blok, ~85% pintu
 *   ZONE_VOID     — ruangan masif tanpa partisi, langit-langit Y=40
 *   ZONE_COMPLEX  — pola dinding L/T/U organik (dual-grid overlay)
 */
public class BackroomsChunkGenerator extends ChunkGenerator {

    public static final MapCodec<BackroomsChunkGenerator> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    BiomeSource.CODEC.fieldOf("biome_source")
                            .forGetter(BackroomsChunkGenerator::getBiomeSource)
            ).apply(instance, BackroomsChunkGenerator::new));

    // ─── Y levels normal ──────────────────────────────────────────────────────
    private static final int Y_BASE    = 0;
    private static final int Y_CARPET  = 1;
    private static final int Y_BASE2   = 2;
    private static final int Y_WALL_1  = 3;
    private static final int Y_WALL_2  = 4;
    private static final int Y_WALL_3  = 5;
    private static final int Y_CEIL    = 6;
    private static final int Y_ROOF    = 7;

    // ─── Y levels void room ───────────────────────────────────────────────────
    private static final int Y_VOID_CEIL = 40;
    private static final int Y_VOID_ROOF = 41; // dari sini sampai 63 → bedrock

    // ─── Blocks ───────────────────────────────────────────────────────────────
    private static final BlockState BLK_BEDROCK = Blocks.BEDROCK.defaultBlockState();
    private static final BlockState BLK_CARPET  = Blocks.BROWN_WOOL.defaultBlockState();
    private static final BlockState BLK_BASE    = Blocks.CUT_SANDSTONE.defaultBlockState();
    private static final BlockState BLK_WALL    = Blocks.STRIPPED_OAK_LOG.defaultBlockState();
    private static final BlockState BLK_CEIL    = Blocks.SMOOTH_STONE.defaultBlockState();
    private static final BlockState BLK_LAMP    = Blocks.OCHRE_FROGLIGHT.defaultBlockState();
    private static final BlockState BLK_AIR     = Blocks.AIR.defaultBlockState();

    // ─── Zone types ───────────────────────────────────────────────────────────
    private static final int ZONE_CORRIDOR = 0;
    private static final int ZONE_OFFICE   = 1;
    private static final int ZONE_OPEN     = 2;
    private static final int ZONE_VOID     = 3;
    private static final int ZONE_COMPLEX  = 4;

    private static final String[] ZONE_NAMES = {"CORRIDOR", "OFFICE", "OPEN", "VOID", "COMPLEX"};

    /** Ukuran makro-region dalam blok. Zone dipilih satu per region ini. */
    private static final int REGION_SIZE = 48;

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
                fillColumn(chunk, pos, lx, lz, wx, wz);
            }
        }
        return CompletableFuture.completedFuture(chunk);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ZONE — tentukan tipe zona untuk posisi world (wx, wz)
    // ══════════════════════════════════════════════════════════════════════════

    private int getZone(int wx, int wz) {
        int rx = Math.floorDiv(wx, REGION_SIZE);
        int rz = Math.floorDiv(wz, REGION_SIZE);
        long s = regionSeed(rx, rz);
        int r = (int) Math.abs(s % 10);
        // Distribusi: VOID=10%, CORRIDOR=20%, COMPLEX=20%, OPEN=25%, OFFICE=25%
        if (r < 1) return ZONE_VOID;
        if (r < 3) return ZONE_CORRIDOR;
        if (r < 5) return ZONE_COMPLEX;
        if (r < 8) return ZONE_OPEN;
        return ZONE_OFFICE;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // COLUMN FILL
    // ══════════════════════════════════════════════════════════════════════════

    private void fillColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                            int lx, int lz, int wx, int wz) {
        int zone = getZone(wx, wz);

        if (zone == ZONE_VOID) {
            fillVoidColumn(chunk, pos, wx, wz);
            return;
        }

        // Kalau zone ini bukan void tapi berbatasan langsung dengan pintu void,
        // ikuti ketinggian void agar lorong tidak ketutup di bawah.
        if (isAdjacentToVoidDoor(wx, wz)) {
            fillVoidColumn(chunk, pos, wx, wz);
            return;
        }

        boolean solid = isSolid(wx, wz, zone);

        // Lantai & atap selalu ada
        set(chunk, pos, wx, Y_BASE, wz, BLK_BEDROCK);
        set(chunk, pos, wx, Y_ROOF, wz, BLK_BEDROCK);

        if (solid) {
            // ── DINDING / PILAR ────────────────────────────────────────────
            set(chunk, pos, wx, Y_CARPET, wz, BLK_BASE);
            set(chunk, pos, wx, Y_BASE2,  wz, BLK_BASE);
            set(chunk, pos, wx, Y_WALL_1, wz, BLK_WALL);
            set(chunk, pos, wx, Y_WALL_2, wz, BLK_WALL);
            set(chunk, pos, wx, Y_WALL_3, wz, BLK_WALL);
            set(chunk, pos, wx, Y_CEIL,   wz, BLK_WALL);
        } else {
            // ── RUANGAN TERBUKA ────────────────────────────────────────────
            set(chunk, pos, wx, Y_CARPET, wz, BLK_CARPET);
            set(chunk, pos, wx, Y_BASE2,  wz, BLK_AIR);
            set(chunk, pos, wx, Y_WALL_1, wz, BLK_AIR);
            set(chunk, pos, wx, Y_WALL_2, wz, BLK_AIR);
            set(chunk, pos, wx, Y_WALL_3, wz, BLK_AIR);
            set(chunk, pos, wx, Y_CEIL,   wz, isLamp(wx, wz) ? BLK_LAMP : BLK_CEIL);
        }
    }

    /**
     * Cek apakah kolom (wx,wz) berada di zona transisi dengan ZONE_VOID di region sebelah.
     * Digunakan agar lorong pintu antara ZONE_VOID dan zone lain ikut tinggi void
     * sehingga tidak ketutup di bawah.
     *
     * Logika: jika posisi ini bukan ZONE_VOID, tapi berada di tepian region
     * yang berbatasan langsung dengan region ZONE_VOID, DAN posisi ini
     * adalah posisi "pintu void" di region tetangga, kembalikan true.
     */
    private boolean isAdjacentToVoidDoor(int wx, int wz) {
        // Cek 4 region tetangga: barat, timur, utara, selatan
        // Tepian barat region saat ini (lx == 0) → berbatasan dengan region rx-1 (tepian timur lx==47)
        int lx = Math.floorMod(wx, REGION_SIZE);
        int lz = Math.floorMod(wz, REGION_SIZE);
        int rx = Math.floorDiv(wx, REGION_SIZE);
        int rz = Math.floorDiv(wz, REGION_SIZE);

        // Cek apakah kita di 2 blok paling timur region (lx==46,47) → berbatasan dengan region rx+1
        if (lx >= REGION_SIZE - 2) {
            int nrx = rx + 1;
            if (getZoneByRegion(nrx, rz) == ZONE_VOID) {
                // Cek apakah lz ini adalah posisi pintu di region tetangga
                int mid = REGION_SIZE / 2;
                if (lz == mid || lz == mid - 1 || lz == mid + 1 || lz == mid - 2) {
                    long s = wallSeed(nrx, rz, 0, REGION_SIZE); // sisi barat region tetangga
                    if (Math.abs(s % 100) < 80) return true; // ada pintu
                }
            }
        }
        // Cek apakah kita di 2 blok paling barat region (lx==0,1) → berbatasan dengan region rx-1
        if (lx <= 1) {
            int nrx = rx - 1;
            if (getZoneByRegion(nrx, rz) == ZONE_VOID) {
                int mid = REGION_SIZE / 2;
                if (lz == mid || lz == mid - 1 || lz == mid + 1 || lz == mid - 2) {
                    long s = wallSeed(nrx, rz, 1, REGION_SIZE); // sisi timur region tetangga
                    if (Math.abs(s % 100) < 80) return true;
                }
            }
        }
        // Cek apakah kita di 2 blok paling selatan region (lz==46,47) → berbatasan dengan region rz+1
        if (lz >= REGION_SIZE - 2) {
            int nrz = rz + 1;
            if (getZoneByRegion(rx, nrz) == ZONE_VOID) {
                int mid = REGION_SIZE / 2;
                if (lx == mid || lx == mid - 1 || lx == mid + 1 || lx == mid - 2) {
                    long s = wallSeed(rx, nrz, 2, REGION_SIZE); // sisi utara region tetangga
                    if (Math.abs(s % 100) < 80) return true;
                }
            }
        }
        // Cek apakah kita di 2 blok paling utara region (lz==0,1) → berbatasan dengan region rz-1
        if (lz <= 1) {
            int nrz = rz - 1;
            if (getZoneByRegion(rx, nrz) == ZONE_VOID) {
                int mid = REGION_SIZE / 2;
                if (lx == mid || lx == mid - 1 || lx == mid + 1 || lx == mid - 2) {
                    long s = wallSeed(rx, nrz, 3, REGION_SIZE); // sisi selatan region tetangga
                    if (Math.abs(s % 100) < 80) return true;
                }
            }
        }
        return false;
    }

    /** Versi getZone yang menerima indeks region langsung (bukan koordinat world). */
    private int getZoneByRegion(int rx, int rz) {
        long s = regionSeed(rx, rz);
        int r = (int) Math.abs(s % 10);
        if (r < 1) return ZONE_VOID;
        if (r < 3) return ZONE_CORRIDOR;
        if (r < 5) return ZONE_COMPLEX;
        if (r < 8) return ZONE_OPEN;
        return ZONE_OFFICE;
    }

    /**
     * ZONE_VOID: ruangan masif — dinding tetap ada (pakai grid OFFICE 6 blok)
     * tapi tingginya penuh dari Y=0 sampai Y=40. Interior yang tidak solid
     * = udara penuh 40 blok. Ini yang menciptakan ruangan "cathedral" yang
     * super tinggi dengan dinding normal di pinggirnya.
     *
     * Layout kolom:
     *   Solid (dinding/pilar):
     *     Y=0       Bedrock
     *     Y=1       Cut Sandstone (baseboard)
     *     Y=2–39    Stripped Oak Log (dinding penuh)
     *     Y=40      Smooth Stone (ceiling void)
     *     Y=41–63   Bedrock
     *
     *   Non-solid (interior):
     *     Y=0       Bedrock (lantai)
     *     Y=1       Brown Wool (karpet)
     *     Y=2–39    AIR (ruang terbuka 38 blok tinggi)
     *     Y=40      Smooth Stone / Froglight (ceiling)
     *     Y=41–63   Bedrock
     */
    private void fillVoidColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                int wx, int wz) {
        boolean solid = isSolidVoid(wx, wz);

        // Lantai bedrock selalu ada
        set(chunk, pos, wx, Y_BASE, wz, BLK_BEDROCK);

        if (solid) {
            // ── DINDING PENUH 40 BLOK ─────────────────────────────────────
            set(chunk, pos, wx, Y_CARPET, wz, BLK_BASE);
            for (int y = Y_BASE2; y < Y_VOID_CEIL; y++) {
                set(chunk, pos, wx, y, wz, BLK_WALL);
            }
            set(chunk, pos, wx, Y_VOID_CEIL, wz, BLK_CEIL);
        } else {
            // ── INTERIOR VOID — udara penuh 38 blok ──────────────────────
            set(chunk, pos, wx, Y_CARPET, wz, BLK_CARPET);
            for (int y = Y_BASE2; y < Y_VOID_CEIL; y++) {
                set(chunk, pos, wx, y, wz, BLK_AIR);
            }
            boolean voidLamp = (Math.floorMod(wx, 8) == 0) && (Math.floorMod(wz, 8) == 0);
            set(chunk, pos, wx, Y_VOID_CEIL, wz, voidLamp ? BLK_LAMP : BLK_CEIL);
        }

        // Y=41 ke atas → bedrock solid
        for (int y = Y_VOID_ROOF; y < 64; y++) {
            set(chunk, pos, wx, y, wz, BLK_BEDROCK);
        }
    }

    /**
     * Solid check khusus ZONE_VOID.
     *
     * Logika baru: interior region 48×48 = KOSONG TOTAL.
     * Dinding hanya tumbuh di TEPIAN region (1 blok paling luar di tiap sisi).
     * Tidak ada grid internal, tidak ada pilar di tengah.
     *
     * Tepian region: blok pertama dan terakhir di sumbu X dan Z dalam region.
     * Misalnya region rx=0 → X: 0..47. Tepian = X==0 atau X==47.
     * Pada tepian, dinding penuh Y=0..40. Interior = lantai + langit-langit saja.
     *
     * Pintu (gap 2 blok) dibuka di tengah tiap sisi agar bisa masuk/keluar
     * ke region tetangga (chance 80% → lebih banyak pintu, ruangan lebih terhubung).
     */
    private boolean isSolidVoid(int wx, int wz) {
        // Hitung posisi dalam region 48×48
        int rx  = Math.floorDiv(wx, REGION_SIZE);
        int rz  = Math.floorDiv(wz, REGION_SIZE);
        int lx  = Math.floorMod(wx, REGION_SIZE);   // 0..47
        int lz  = Math.floorMod(wz, REGION_SIZE);   // 0..47

        // Dinding void 2 blok tebal agar tidak ada celah antar region:
        // Sisi barat:  lx == 0 atau lx == 1
        // Sisi timur:  lx == REGION_SIZE-1 atau lx == REGION_SIZE-2
        // Sisi utara:  lz == 0 atau lz == 1
        // Sisi selatan: lz == REGION_SIZE-1 atau lz == REGION_SIZE-2
        boolean onWestEdge  = (lx == 0 || lx == 1);
        boolean onEastEdge  = (lx == REGION_SIZE - 1 || lx == REGION_SIZE - 2);
        boolean onNorthEdge = (lz == 0 || lz == 1);
        boolean onSouthEdge = (lz == REGION_SIZE - 1 || lz == REGION_SIZE - 2);

        // Gunakan lx==0/lx==REGION_SIZE-1 sebagai "tepian luar" untuk logika pintu
        boolean onOuterWest  = (lx == 0);
        boolean onOuterEast  = (lx == REGION_SIZE - 1);
        boolean onOuterNorth = (lz == 0);
        boolean onOuterSouth = (lz == REGION_SIZE - 1);

        boolean onEdgeX = onWestEdge  || onEastEdge;
        boolean onEdgeZ = onNorthEdge || onSouthEdge;

        // Interior: bukan di tepian → kosong
        if (!onEdgeX && !onEdgeZ) return false;

        // Sudut (pertemuan dua tepian) → selalu solid (pilar sudut)
        if (onEdgeX && onEdgeZ) return true;

        // Tepian X (dinding barat/timur): cek apakah ada pintu di posisi lz ini
        if (onEdgeX) {
            // Pintu selebar 4 blok di tengah sisi agar muat 2 blok dinding tebal
            int mid = REGION_SIZE / 2;
            boolean isDoorPos = (lz == mid || lz == mid - 1 || lz == mid + 1 || lz == mid - 2);
            if (!isDoorPos) return true;
            // Seed berdasarkan tepian luar saja agar kedua layer dinding sinkron
            int side = (onOuterWest || onWestEdge) ? 0 : 1;
            // Sinkronkan seed: pakai rx dari region yang "memiliki" dinding ini
            // Dinding barat (lx==0,1) → seed dari region ini
            // Dinding timur (lx==46,47) → seed dari region ini juga
            int seedRx = onWestEdge ? rx : rx;
            long s = wallSeed(seedRx, rz, side, REGION_SIZE);
            return (Math.abs(s % 100) >= 80); // 80% ada pintu → 20% solid
        }

        // Tepian Z (dinding utara/selatan): cek apakah ada pintu di posisi lx ini
        if (onEdgeZ) {
            int mid = REGION_SIZE / 2;
            boolean isDoorPos = (lx == mid || lx == mid - 1 || lx == mid + 1 || lx == mid - 2);
            if (!isDoorPos) return true;
            int side = (onOuterNorth || onNorthEdge) ? 2 : 3;
            long s = wallSeed(rx, rz, side, REGION_SIZE);
            return (Math.abs(s % 100) >= 80);
        }

        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SOLID CHECK — tiap zone punya algoritma sendiri
    // ══════════════════════════════════════════════════════════════════════════

    private boolean isSolid(int wx, int wz, int zone) {
        switch (zone) {
            case ZONE_CORRIDOR: return isSolidCorridor(wx, wz);
            case ZONE_OPEN:     return isSolidOpen(wx, wz);
            case ZONE_COMPLEX:  return isSolidComplex(wx, wz);
            default:            return isSolidOffice(wx, wz);
        }
    }

    /**
     * ZONE_CORRIDOR — grid 4 blok, koridor sempit.
     * 60% segmen punya pintu → labirin rapat tapi tetap traversable.
     */
    private boolean isSolidCorridor(int wx, int wz) {
        final int G = 4;
        int gx = Math.floorMod(wx, G);
        int gz = Math.floorMod(wz, G);
        boolean onX = (gx == 0);
        boolean onZ = (gz == 0);
        if (!onX && !onZ) return false;
        if (onX && onZ) return true; // pilar persimpangan
        int cellX = Math.floorDiv(wx, G);
        int cellZ = Math.floorDiv(wz, G);
        if (onX) return !isDoor(cellX, cellZ, false, gz, G, 0.60);
        return      !isDoor(cellX, cellZ, true,  gx, G, 0.60);
    }

    /**
     * ZONE_OFFICE — grid 6 blok, ruangan standar backrooms.
     * 70% segmen punya pintu.
     */
    private boolean isSolidOffice(int wx, int wz) {
        final int G = 6;
        int gx = Math.floorMod(wx, G);
        int gz = Math.floorMod(wz, G);
        boolean onX = (gx == 0);
        boolean onZ = (gz == 0);
        if (!onX && !onZ) return false;
        if (onX && onZ) return true;
        int cellX = Math.floorDiv(wx, G);
        int cellZ = Math.floorDiv(wz, G);
        if (onX) return !isDoor(cellX, cellZ, false, gz, G, 0.70);
        return      !isDoor(cellX, cellZ, true,  gx, G, 0.70);
    }

    /**
     * ZONE_OPEN — grid 12 blok, ruangan sangat luas.
     * 85% segmen terbuka → hampir tanpa tembok, open plan lebar.
     */
    private boolean isSolidOpen(int wx, int wz) {
        final int G = 12;
        int gx = Math.floorMod(wx, G);
        int gz = Math.floorMod(wz, G);
        boolean onX = (gx == 0);
        boolean onZ = (gz == 0);
        if (!onX && !onZ) return false;
        if (onX && onZ) return true; // pilar 1×1 di persimpangan
        int cellX = Math.floorDiv(wx, G);
        int cellZ = Math.floorDiv(wz, G);
        if (onX) return !isDoor(cellX, cellZ, false, gz, G, 0.85);
        return      !isDoor(cellX, cellZ, true,  gx, G, 0.85);
    }

    /**
     * ZONE_COMPLEX — pola dinding organik L/T/U seperti screenshot ke-2.
     * Dicapai dengan overlay dua grid berbeda: G1=6 (normal) + G2=9 (offset 3 blok).
     * Hasilnya: sudut-sudut yang tidak simetris, jog, relung, L-shape alami.
     */
    private boolean isSolidComplex(int wx, int wz) {
        // Grid primer 6 blok
        final int G1 = 6;
        int gx1 = Math.floorMod(wx, G1);
        int gz1 = Math.floorMod(wz, G1);

        // Grid sekunder 9 blok, dioffset +3 agar tidak overlap persis
        final int G2 = 9;
        int wx2 = wx + 3;
        int wz2 = wz + 3;
        int gx2 = Math.floorMod(wx2, G2);
        int gz2 = Math.floorMod(wz2, G2);

        boolean solid1 = false;
        boolean solid2 = false;

        // Cek grid primer
        boolean on1X = (gx1 == 0);
        boolean on1Z = (gz1 == 0);
        if (on1X || on1Z) {
            if (on1X && on1Z) {
                solid1 = true;
            } else {
                int cx = Math.floorDiv(wx, G1);
                int cz = Math.floorDiv(wz, G1);
                // Grid primer: 55% pintu → tembok lebih banyak dari OFFICE
                if (on1X) solid1 = !isDoor(cx, cz, false, gz1, G1, 0.55);
                else      solid1 = !isDoor(cx, cz, true,  gx1, G1, 0.55);
            }
        }

        // Cek grid sekunder (lebih jarang pintu → bentuk L/T muncul alami)
        boolean on2X = (gx2 == 0);
        boolean on2Z = (gz2 == 0);
        if (on2X || on2Z) {
            if (on2X && on2Z) {
                solid2 = true;
            } else {
                int cx2 = Math.floorDiv(wx2, G2);
                int cz2 = Math.floorDiv(wz2, G2);
                // Grid sekunder: 50% pintu → lebih solid, menciptakan dead-end
                if (on2X) solid2 = !isDoor(cx2, cz2, false, gz2, G2, 0.50);
                else      solid2 = !isDoor(cx2, cz2, true,  gx2, G2, 0.50);
            }
        }

        // Solid jika ada di salah satu grid → L/T/U shape muncul di persimpangan
        return solid1 || solid2;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DOOR — apakah segmen ini punya bukaan (pintu/gap)?
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * @param cellX      indeks sel grid X
     * @param cellZ      indeks sel grid Z
     * @param isHoriz    true = partisi horizontal, false = vertikal
     * @param offset     posisi dalam segmen (1 .. grid-1)
     * @param grid       ukuran grid
     * @param doorChance probabilitas pintu terbuka (0.0–1.0)
     */
    private boolean isDoor(int cellX, int cellZ, boolean isHoriz,
                           int offset, int grid, double doorChance) {
        // Pintu selebar 2 blok di tengah segmen
        int mid = grid / 2;
        if (offset != mid && offset != mid - 1) return false;
        long s = wallSeed(cellX, cellZ, isHoriz ? 1 : 0, grid);
        return (Math.abs(s % 100) < (long)(doorChance * 100));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LAMP
    // ══════════════════════════════════════════════════════════════════════════

    private boolean isLamp(int wx, int wz) {
        return (Math.floorMod(wx, 3) == 0) && (Math.floorMod(wz, 3) == 0);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SEEDS
    // ══════════════════════════════════════════════════════════════════════════

    private long regionSeed(int rx, int rz) {
        return ((long) rx * 0xD1B54A32D192ED03L)
             ^ ((long) rz * 0x9E3779B97F4A7C15L)
             ^ 0xBAC0D00D0000000L;
    }

    private long wallSeed(int cellX, int cellZ, int axis, int grid) {
        return ((long) cellX * 0x9E3779B97F4A7C15L)
             ^ ((long) cellZ * 0x6C62272E07BB0142L)
             ^ ((long) axis  * 0xD1B54A32D192ED03L)
             ^ ((long) grid  * 0x517CC1B727220A95L)
             ^ 0xBADC0FFEE0DDF00DL;
    }

    private void set(ChunkAccess c, BlockPos.MutableBlockPos p,
                     int wx, int y, int wz, BlockState s) {
        c.setBlockState(p.set(wx, y, wz), s, false);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // REQUIRED OVERRIDES
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Dipanggil SEKALI saat chunk pertama kali di-generate (fase FEATURES).
     * Ini tempat yang benar untuk menaruh struktur — tidak dipanggil saat reload.
     */
    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk,
                                     StructureManager structureManager) {
        // Ambil world seed dari ServerLevel
        long seed = level.getSeed();
        BackroomsStructureSpawner.decorate(level, chunk, seed);
    }

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
        int zone = getZone(pos.getX(), pos.getZ());
        info.add("Backrooms Level 0 | Zone: " + ZONE_NAMES[zone]
                + " | " + pos.toShortString());
    }

    @Override public int getSeaLevel() { return -1; }
    @Override public int getMinY()     { return Y_BASE; }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types t,
                             LevelHeightAccessor l, RandomState rs) {
        return getZone(x, z) == ZONE_VOID ? Y_VOID_CEIL : Y_CEIL;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor l, RandomState rs) {
        return new NoiseColumn(Y_BASE, new BlockState[0]);
    }
}
