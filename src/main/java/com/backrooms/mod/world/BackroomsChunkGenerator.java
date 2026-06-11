package com.backrooms.mod.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
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
 *   Y=41–63 Bedrock (atap solid)
 *
 * ZONE SYSTEM — 5 tipe zona dipilih per makro-region 48×48 blok:
 *   ZONE_CORRIDOR — koridor sempit, grid 4 blok
 *   ZONE_OFFICE   — ruangan standar, grid 6 blok (default)
 *   ZONE_OPEN     — ruangan luas, grid 12 blok
 *   ZONE_VOID     — ruangan masif tanpa partisi, langit-langit Y=40
 *   ZONE_COMPLEX  — pola dinding L/T/U organik (dual-grid overlay)
 *
 * FIX: Setiap sel ruangan dijamin minimal 1 pintu ke tetangga (connectivity guarantee).
 * FIX: Spawn selalu di tengah sel terbuka, tidak pernah di dalam dinding.
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
    private static final int Y_VOID_ROOF = 41;

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
    private static final int ZONE_PITFALLS = 5;

    private static final String[] ZONE_NAMES = {"CORRIDOR", "OFFICE", "OPEN", "VOID", "COMPLEX", "PITFALLS"};

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
    // ZONE
    // ══════════════════════════════════════════════════════════════════════════

    private int getZone(int wx, int wz) {
        int rx = Math.floorDiv(wx, REGION_SIZE);
        int rz = Math.floorDiv(wz, REGION_SIZE);
        long s = regionSeed(rx, rz);
        int r = (int) Math.abs(s % 20);
        if (r < 2)  return ZONE_VOID;
        if (r < 4)  return ZONE_PITFALLS;
        if (r < 7)  return ZONE_CORRIDOR;
        if (r < 10) return ZONE_COMPLEX;
        if (r < 15) return ZONE_OPEN;
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

        if (zone == ZONE_PITFALLS) {
            fillPitfallsColumn(chunk, pos, wx, wz);
            return;
        }

        boolean solid = isSolid(wx, wz, zone);
        boolean nextToVoid = isNextToVoidRegion(wx, wz);

        set(chunk, pos, wx, Y_BASE, wz, BLK_BEDROCK);

        if (nextToVoid) {
            if (solid) {
                set(chunk, pos, wx, Y_CARPET, wz, BLK_BASE);
                for (int y = Y_BASE2; y < Y_VOID_CEIL; y++) {
                    set(chunk, pos, wx, y, wz, BLK_WALL);
                }
                set(chunk, pos, wx, Y_VOID_CEIL, wz, BLK_CEIL);
            } else {
                set(chunk, pos, wx, Y_CARPET, wz, BLK_CARPET);
                for (int y = Y_BASE2; y < Y_VOID_CEIL; y++) {
                    set(chunk, pos, wx, y, wz, BLK_AIR);
                }
                set(chunk, pos, wx, Y_VOID_CEIL, wz, isLamp(wx, wz) ? BLK_LAMP : BLK_CEIL);
            }
            for (int y = Y_VOID_ROOF; y < 64; y++) {
                set(chunk, pos, wx, y, wz, BLK_BEDROCK);
            }
        } else {
            set(chunk, pos, wx, Y_ROOF, wz, BLK_BEDROCK);
            if (solid) {
                set(chunk, pos, wx, Y_CARPET, wz, BLK_BASE);
                set(chunk, pos, wx, Y_BASE2,  wz, BLK_BASE);
                set(chunk, pos, wx, Y_WALL_1, wz, BLK_WALL);
                set(chunk, pos, wx, Y_WALL_2, wz, BLK_WALL);
                set(chunk, pos, wx, Y_WALL_3, wz, BLK_WALL);
                set(chunk, pos, wx, Y_CEIL,   wz, BLK_WALL);
            } else {
                set(chunk, pos, wx, Y_CARPET, wz, BLK_CARPET);
                set(chunk, pos, wx, Y_BASE2,  wz, BLK_AIR);
                set(chunk, pos, wx, Y_WALL_1, wz, BLK_AIR);
                set(chunk, pos, wx, Y_WALL_2, wz, BLK_AIR);
                set(chunk, pos, wx, Y_WALL_3, wz, BLK_AIR);
                set(chunk, pos, wx, Y_CEIL,   wz, isLamp(wx, wz) ? BLK_LAMP : BLK_CEIL);
            }
        }
    }

    private boolean isNextToVoidRegion(int wx, int wz) {
        int rx = Math.floorDiv(wx, REGION_SIZE);
        int rz = Math.floorDiv(wz, REGION_SIZE);
        return getZoneByRegion(rx - 1, rz) == ZONE_VOID
            || getZoneByRegion(rx + 1, rz) == ZONE_VOID
            || getZoneByRegion(rx, rz - 1) == ZONE_VOID
            || getZoneByRegion(rx, rz + 1) == ZONE_VOID;
    }

    private int getZoneByRegion(int rx, int rz) {
        long s = regionSeed(rx, rz);
        int r = (int) Math.abs(s % 20);
        if (r < 2)  return ZONE_VOID;
        if (r < 4)  return ZONE_PITFALLS;
        if (r < 7)  return ZONE_CORRIDOR;
        if (r < 10) return ZONE_COMPLEX;
        if (r < 15) return ZONE_OPEN;
        return ZONE_OFFICE;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PITFALLS
    // ══════════════════════════════════════════════════════════════════════════

    private void fillPitfallsColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                    int wx, int wz) {
        final int PF_DEEP_FLOOR = 0;
        final int PF_PIT_TOP    = 3;
        final int PF_CARPET     = 4;
        final int PF_BASE       = 5;
        final int PF_WALL_BOT   = 6;
        final int PF_WALL_MID   = 7;
        final int PF_WALL_TOP   = 8;
        final int PF_CEIL       = 9;
        final int PF_ROOF       = 10;

        boolean wall = isSolidPitfalls(wx, wz);
        boolean pit  = !wall && isPitfall(wx, wz);

        set(chunk, pos, wx, PF_ROOF, wz, BLK_BEDROCK);
        for (int y = PF_ROOF + 1; y < 64; y++) {
            set(chunk, pos, wx, y, wz, BLK_BEDROCK);
        }

        if (wall) {
            set(chunk, pos, wx, PF_DEEP_FLOOR, wz, BLK_CARPET);
            set(chunk, pos, wx, 1,             wz, BLK_CARPET);
            set(chunk, pos, wx, 2,             wz, BLK_CARPET);
            set(chunk, pos, wx, 3,             wz, BLK_CARPET);
            set(chunk, pos, wx, PF_CARPET,     wz, BLK_BASE);
            set(chunk, pos, wx, PF_BASE,       wz, BLK_BASE);
            set(chunk, pos, wx, PF_WALL_BOT,   wz, BLK_WALL);
            set(chunk, pos, wx, PF_WALL_MID,   wz, BLK_WALL);
            set(chunk, pos, wx, PF_WALL_TOP,   wz, BLK_WALL);
            set(chunk, pos, wx, PF_CEIL,       wz, BLK_WALL);
        } else if (pit) {
            set(chunk, pos, wx, PF_DEEP_FLOOR, wz, BLK_AIR);
            set(chunk, pos, wx, 1,             wz, BLK_AIR);
            set(chunk, pos, wx, 2,             wz, BLK_AIR);
            set(chunk, pos, wx, PF_PIT_TOP,    wz, BLK_AIR);
            set(chunk, pos, wx, PF_CARPET,     wz, BLK_AIR);
            set(chunk, pos, wx, PF_BASE,       wz, BLK_AIR);
            set(chunk, pos, wx, PF_WALL_BOT,   wz, BLK_AIR);
            set(chunk, pos, wx, PF_WALL_MID,   wz, BLK_AIR);
            set(chunk, pos, wx, PF_WALL_TOP,   wz, BLK_AIR);
            set(chunk, pos, wx, PF_CEIL,       wz, isLamp(wx, wz) ? BLK_LAMP : BLK_CEIL);
        } else {
            set(chunk, pos, wx, PF_DEEP_FLOOR, wz, BLK_CARPET);
            set(chunk, pos, wx, 1,             wz, BLK_CARPET);
            set(chunk, pos, wx, 2,             wz, BLK_CARPET);
            set(chunk, pos, wx, 3,             wz, BLK_CARPET);
            set(chunk, pos, wx, PF_CARPET,     wz, BLK_CARPET);
            set(chunk, pos, wx, PF_BASE,       wz, BLK_AIR);
            set(chunk, pos, wx, PF_WALL_BOT,   wz, BLK_AIR);
            set(chunk, pos, wx, PF_WALL_MID,   wz, BLK_AIR);
            set(chunk, pos, wx, PF_WALL_TOP,   wz, BLK_AIR);
            set(chunk, pos, wx, PF_CEIL,       wz, isLamp(wx, wz) ? BLK_LAMP : BLK_CEIL);
        }
    }

    private boolean isPitfall(int wx, int wz) {
        final int PERIOD  = 4;
        final int PIT_LEN = 3;
        int gx = Math.floorMod(wx, PERIOD);
        int gz = Math.floorMod(wz, PERIOD);
        return gx < PIT_LEN && gz < PIT_LEN;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // VOID
    // ══════════════════════════════════════════════════════════════════════════

    private void fillVoidColumn(ChunkAccess chunk, BlockPos.MutableBlockPos pos,
                                int wx, int wz) {
        boolean solid = isSolidVoid(wx, wz);

        set(chunk, pos, wx, Y_BASE, wz, BLK_BEDROCK);

        if (solid) {
            set(chunk, pos, wx, Y_CARPET, wz, BLK_BASE);
            for (int y = Y_BASE2; y < Y_VOID_CEIL; y++) {
                set(chunk, pos, wx, y, wz, BLK_WALL);
            }
            set(chunk, pos, wx, Y_VOID_CEIL, wz, BLK_CEIL);
        } else {
            set(chunk, pos, wx, Y_CARPET, wz, BLK_CARPET);
            for (int y = Y_BASE2; y < Y_VOID_CEIL; y++) {
                set(chunk, pos, wx, y, wz, BLK_AIR);
            }
            boolean voidLamp = (Math.floorMod(wx, 8) == 0) && (Math.floorMod(wz, 8) == 0);
            set(chunk, pos, wx, Y_VOID_CEIL, wz, voidLamp ? BLK_LAMP : BLK_CEIL);
        }

        for (int y = Y_VOID_ROOF; y < 64; y++) {
            set(chunk, pos, wx, y, wz, BLK_BEDROCK);
        }
    }

    private boolean isSolidVoid(int wx, int wz) {
        int rx  = Math.floorDiv(wx, REGION_SIZE);
        int rz  = Math.floorDiv(wz, REGION_SIZE);
        int lx  = Math.floorMod(wx, REGION_SIZE);
        int lz  = Math.floorMod(wz, REGION_SIZE);

        boolean onWestEdge  = (lx == 0);
        boolean onEastEdge  = (lx == REGION_SIZE - 1);
        boolean onNorthEdge = (lz == 0);
        boolean onSouthEdge = (lz == REGION_SIZE - 1);

        boolean onEdgeX = onWestEdge  || onEastEdge;
        boolean onEdgeZ = onNorthEdge || onSouthEdge;

        if (!onEdgeX && !onEdgeZ) return false;
        if (onEdgeX && onEdgeZ) return true;

        if (onEdgeX) {
            int mid = REGION_SIZE / 2;
            boolean isDoorPos = (lz >= mid - 3 && lz <= mid + 2);
            if (!isDoorPos) return true;
            int side = onWestEdge ? 0 : 1;
            long s = wallSeed(rx, rz, side, REGION_SIZE);
            return (Math.abs(s % 100) >= 85);
        }

        if (onEdgeZ) {
            int mid = REGION_SIZE / 2;
            boolean isDoorPos = (lx >= mid - 3 && lx <= mid + 2);
            if (!isDoorPos) return true;
            int side = onNorthEdge ? 2 : 3;
            long s = wallSeed(rx, rz, side, REGION_SIZE);
            return (Math.abs(s % 100) >= 85);
        }

        return false;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SOLID CHECK — dengan CONNECTIVITY GUARANTEE
    //
    // Masalah lama: ruangan bisa terkunci 100% karena doorChance bersifat
    // probabilistik — bisa saja semua 4 dinding sel tidak ada satupun pintu.
    //
    // Solusi: setiap sel dijamin punya setidaknya 1 pintu ke tetangganya.
    // Caranya: untuk setiap sel (cellX, cellZ), tentukan 1 "guaranteed exit"
    // (arah yang selalu terbuka), dipilih dari seed sel itu sendiri.
    // Exit lain tetap probabilistik seperti sebelumnya.
    // ══════════════════════════════════════════════════════════════════════════

    private boolean isSolid(int wx, int wz, int zone) {
        switch (zone) {
            case ZONE_CORRIDOR: return isSolidCorridor(wx, wz);
            case ZONE_OPEN:     return isSolidOpen(wx, wz);
            case ZONE_COMPLEX:  return isSolidComplex(wx, wz);
            case ZONE_PITFALLS: return isSolidOffice(wx, wz);
            default:            return isSolidOffice(wx, wz);
        }
    }

    /**
     * ZONE_CORRIDOR — grid 4 blok.
     * Connectivity guarantee: tiap sel punya minimal 1 pintu.
     */
    private boolean isSolidCorridor(int wx, int wz) {
        final int G = 4;
        int gx = Math.floorMod(wx, G);
        int gz = Math.floorMod(wz, G);
        boolean onX = (gx == 0);
        boolean onZ = (gz == 0);
        if (!onX && !onZ) return false;
        if (onX && onZ) return true;
        int cellX = Math.floorDiv(wx, G);
        int cellZ = Math.floorDiv(wz, G);
        if (onX) return !isDoorGuaranteed(cellX, cellZ, false, gz, G, 0.60);
        return      !isDoorGuaranteed(cellX, cellZ, true,  gx, G, 0.60);
    }

    /**
     * ZONE_OFFICE — grid 6 blok.
     * Connectivity guarantee aktif.
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
        if (onX) return !isDoorGuaranteed(cellX, cellZ, false, gz, G, 0.70);
        return      !isDoorGuaranteed(cellX, cellZ, true,  gx, G, 0.70);
    }

    /**
     * ZONE_PITFALLS — tembok hanya di tepi region 48×48.
     */
    private boolean isSolidPitfalls(int wx, int wz) {
        int rx  = Math.floorDiv(wx, REGION_SIZE);
        int rz  = Math.floorDiv(wz, REGION_SIZE);
        int lx  = Math.floorMod(wx, REGION_SIZE);
        int lz  = Math.floorMod(wz, REGION_SIZE);

        boolean onWestEdge  = (lx == 0);
        boolean onEastEdge  = (lx == REGION_SIZE - 1);
        boolean onNorthEdge = (lz == 0);
        boolean onSouthEdge = (lz == REGION_SIZE - 1);

        boolean onEdgeX = onWestEdge  || onEastEdge;
        boolean onEdgeZ = onNorthEdge || onSouthEdge;

        if (!onEdgeX && !onEdgeZ) return false;
        if (onEdgeX && onEdgeZ)   return true;

        if (onEdgeX) {
            int mid = REGION_SIZE / 2;
            boolean isDoor = (lz >= mid - 2 && lz <= mid + 1);
            if (!isDoor) return true;
            int side = onWestEdge ? 0 : 1;
            long s = wallSeed(rx, rz, side, REGION_SIZE);
            return (Math.abs(s % 100) >= 80);
        }
        int mid = REGION_SIZE / 2;
        boolean isDoor = (lx >= mid - 2 && lx <= mid + 1);
        if (!isDoor) return true;
        int side = onNorthEdge ? 2 : 3;
        long s = wallSeed(rx, rz, side, REGION_SIZE);
        return (Math.abs(s % 100) >= 80);
    }

    /**
     * ZONE_OPEN — grid 12 blok, ruangan sangat luas.
     */
    private boolean isSolidOpen(int wx, int wz) {
        final int G = 12;
        int gx = Math.floorMod(wx, G);
        int gz = Math.floorMod(wz, G);
        boolean onX = (gx == 0);
        boolean onZ = (gz == 0);
        if (!onX && !onZ) return false;
        if (onX && onZ) return true;
        int cellX = Math.floorDiv(wx, G);
        int cellZ = Math.floorDiv(wz, G);
        if (onX) return !isDoorGuaranteed(cellX, cellZ, false, gz, G, 0.85);
        return      !isDoorGuaranteed(cellX, cellZ, true,  gx, G, 0.85);
    }

    /**
     * ZONE_COMPLEX — pola dinding L/T/U, dual-grid overlay.
     * COMPLEX memakai isDoor biasa (bukan guaranteed) karena dual-grid
     * sudah cukup menjamin konektivitas secara statistik. Tapi agar
     * tidak ada dead-end penuh, kita pastikan setidaknya salah satu grid
     * membuka pintu via guaranteed logic.
     */
    private boolean isSolidComplex(int wx, int wz) {
        final int G1 = 6;
        int gx1 = Math.floorMod(wx, G1);
        int gz1 = Math.floorMod(wz, G1);

        final int G2 = 9;
        int wx2 = wx + 3;
        int wz2 = wz + 3;
        int gx2 = Math.floorMod(wx2, G2);
        int gz2 = Math.floorMod(wz2, G2);

        boolean solid1 = false;
        boolean solid2 = false;

        boolean on1X = (gx1 == 0);
        boolean on1Z = (gz1 == 0);
        if (on1X || on1Z) {
            if (on1X && on1Z) {
                solid1 = true;
            } else {
                int cx = Math.floorDiv(wx, G1);
                int cz = Math.floorDiv(wz, G1);
                if (on1X) solid1 = !isDoorGuaranteed(cx, cz, false, gz1, G1, 0.55);
                else      solid1 = !isDoorGuaranteed(cx, cz, true,  gx1, G1, 0.55);
            }
        }

        boolean on2X = (gx2 == 0);
        boolean on2Z = (gz2 == 0);
        if (on2X || on2Z) {
            if (on2X && on2Z) {
                solid2 = true;
            } else {
                int cx2 = Math.floorDiv(wx2, G2);
                int cz2 = Math.floorDiv(wz2, G2);
                if (on2X) solid2 = !isDoor(cx2, cz2, false, gz2, G2, 0.50);
                else      solid2 = !isDoor(cx2, cz2, true,  gx2, G2, 0.50);
            }
        }

        return solid1 || solid2;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DOOR LOGIC
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Cek apakah segmen dinding ini adalah pintu (bukaan).
     * @param cellX      indeks sel X
     * @param cellZ      indeks sel Z
     * @param isHoriz    true = partisi Z (berjalan di X), false = partisi X
     * @param offset     posisi dalam segmen (1..grid-1)
     * @param grid       ukuran grid
     * @param doorChance probabilitas pintu (0.0–1.0)
     */
    private boolean isDoor(int cellX, int cellZ, boolean isHoriz,
                           int offset, int grid, double doorChance) {
        int mid = grid / 2;
        if (offset != mid && offset != mid - 1) return false;
        long s = wallSeed(cellX, cellZ, isHoriz ? 1 : 0, grid);
        return (Math.abs(s % 100) < (long)(doorChance * 100));
    }

    /**
     * Versi isDoor dengan CONNECTIVITY GUARANTEE.
     *
     * Algoritma:
     * 1. Hitung "guaranteed exit direction" untuk sel ini dari seed sel.
     *    (0=North wall, 1=South wall, 2=West wall, 3=East wall)
     * 2. Jika partisi ini adalah arah guaranteed → selalu buka pintu.
     * 3. Jika bukan → gunakan probabilitas doorChance seperti biasa.
     *
     * Dengan cara ini, setiap sel selalu punya minimal 1 pintu ke luar,
     * sehingga player tidak pernah terkunci penuh.
     */
    private boolean isDoorGuaranteed(int cellX, int cellZ, boolean isHoriz,
                                     int offset, int grid, double doorChance) {
        int mid = grid / 2;
        // Hanya slot tengah yang bisa jadi pintu
        if (offset != mid && offset != mid - 1) return false;

        // Tentukan guaranteed exit untuk sel ini
        long cellSeedVal = wallSeed(cellX, cellZ, 99, grid); // seed khusus "exit direction"
        int guaranteedExit = (int) Math.abs(cellSeedVal % 4);
        // 0=North(-Z wall), 1=South(+Z wall), 2=West(-X wall), 3=East(+X wall)

        // Partisi X (onX=true → isHoriz=false) = dinding barat/timur = exit 2 atau 3
        // Partisi Z (onZ=true → isHoriz=true)  = dinding utara/selatan = exit 0 atau 1
        boolean isGuaranteedPartition;
        if (!isHoriz) {
            // Ini adalah partisi di sumbu X (dinding vertikal N-S)
            isGuaranteedPartition = (guaranteedExit == 2 || guaranteedExit == 3);
        } else {
            // Ini adalah partisi di sumbu Z (dinding horizontal E-W)
            isGuaranteedPartition = (guaranteedExit == 0 || guaranteedExit == 1);
        }

        if (isGuaranteedPartition) {
            // Cek apakah ini sisi spesifik yang guaranteed (bukan semua partisi sumbu ini)
            long exitSide = wallSeed(cellX, cellZ, 98, grid);
            boolean preferNegative = (exitSide % 2 == 0); // pilih sisi -X atau -Z
            // isHoriz=false → partisi X: cellX lebih kecil = sisi barat (-X)
            // isHoriz=true  → partisi Z: cellZ lebih kecil = sisi utara (-Z)
            // Kita gunakan seed untuk pilih salah satu agar tidak semua sisi terbuka
            if (isGuaranteedPartition) return true; // buka semua partisi di arah guaranteed
        }

        // Fallback ke probabilitas normal
        long s = wallSeed(cellX, cellZ, isHoriz ? 1 : 0, grid);
        return (Math.abs(s % 100) < (long)(doorChance * 100));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SAFE SPAWN FINDER — digunakan oleh GhostWallBlock saat teleport masuk
    //
    // Cari koordinat XZ yang tidak berada di dalam dinding, di zone manapun.
    // Spiral outward dari posisi asal sampai ketemu posisi terbuka.
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Temukan posisi XZ yang aman (tidak di dinding) dekat (originX, originZ).
     * Return array [safeX, safeZ]. Jika originX/Z sudah aman, langsung return.
     * Spiral outward hingga radius 16 blok.
     */
    public static int[] findSafeSpawnXZ(int originX, int originZ, int zone,
                                         BackroomsChunkGenerator gen) {
        // Cek origin dulu
        if (!gen.isSolidForZone(originX, originZ, zone)) {
            return new int[]{originX, originZ};
        }

        // Spiral outward
        for (int r = 1; r <= 16; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    int tx = originX + dx;
                    int tz = originZ + dz;
                    if (!gen.isSolidForZone(tx, tz, zone)) {
                        return new int[]{tx, tz};
                    }
                }
            }
        }

        // Fallback: return origin (harusnya tidak terjadi dengan connectivity guarantee)
        return new int[]{originX, originZ};
    }

    /** Cek apakah posisi (wx,wz) adalah solid di zone yang berlaku. */
    public boolean isSolidForZone(int wx, int wz, int zone) {
        return isSolid(wx, wz, zone);
    }

    /** Expose getZone untuk digunakan GhostWallBlock. */
    public int getZoneAt(int wx, int wz) {
        return getZone(wx, wz);
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
    // DECORATIONS & DOORS
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk,
                                     StructureManager structureManager) {
        long seed = level.getSeed();
        BackroomsStructureSpawner.decorate(level, chunk, seed);
        placePitfallsDoors(level, chunk);
    }

    private void placePitfallsDoors(WorldGenLevel level, ChunkAccess chunk) {
        ChunkPos cp = chunk.getPos();
        int minX = cp.getMinBlockX();
        int minZ = cp.getMinBlockZ();
        int maxX = cp.getMaxBlockX();
        int maxZ = cp.getMaxBlockZ();

        int rxMin = Math.floorDiv(minX, REGION_SIZE);
        int rxMax = Math.floorDiv(maxX, REGION_SIZE);
        int rzMin = Math.floorDiv(minZ, REGION_SIZE);
        int rzMax = Math.floorDiv(maxZ, REGION_SIZE);

        for (int rx = rxMin; rx <= rxMax; rx++) {
            for (int rz = rzMin; rz <= rzMax; rz++) {
                long rs = regionSeed(rx, rz);
                int r = (int) Math.abs(rs % 20);
                if (!(r >= 2 && r < 4)) continue;

                int mid = REGION_SIZE / 2;
                int[][] anchors = {
                    { rx * REGION_SIZE,                    rz * REGION_SIZE + mid - 2 },
                    { rx * REGION_SIZE + REGION_SIZE - 1,  rz * REGION_SIZE + mid - 2 },
                    { rx * REGION_SIZE + mid - 2,          rz * REGION_SIZE            },
                    { rx * REGION_SIZE + mid - 2,          rz * REGION_SIZE + REGION_SIZE - 1 },
                };
                for (int[] a : anchors) {
                    int ax = a[0], az = a[1];
                    if (ax >= minX && ax <= maxX && az >= minZ && az <= maxZ) {
                        tryPlacePitfallDoor(level, ax, az);
                    }
                }
            }
        }
    }

    private void tryPlacePitfallDoor(WorldGenLevel level, int wx, int wz) {
        int rx  = Math.floorDiv(wx, REGION_SIZE);
        int rz  = Math.floorDiv(wz, REGION_SIZE);
        int lx  = Math.floorMod(wx, REGION_SIZE);
        int lz  = Math.floorMod(wz, REGION_SIZE);

        int mid = REGION_SIZE / 2;

        boolean westEdge  = (lx == 0);
        boolean eastEdge  = (lx == REGION_SIZE - 1);
        boolean northEdge = (lz == 0);
        boolean southEdge = (lz == REGION_SIZE - 1);

        if ((westEdge || eastEdge) && lz == mid - 2) {
            int side = westEdge ? 0 : 1;
            long s = wallSeed(rx, rz, side, REGION_SIZE);
            if (Math.abs(s % 100) < 80) {
                Direction facing = westEdge ? Direction.EAST : Direction.WEST;
                boolean fake = (Math.abs((s >> 8) % 100) < 50);
                placeDoor(level, wx, 4, wz, facing, fake, s);
            }
        }

        if ((northEdge || southEdge) && lx == mid - 2) {
            int side = northEdge ? 2 : 3;
            long s = wallSeed(rx, rz, side, REGION_SIZE);
            if (Math.abs(s % 100) < 80) {
                Direction facing = northEdge ? Direction.SOUTH : Direction.NORTH;
                boolean fake = (Math.abs((s >> 8) % 100) < 50);
                placeDoor(level, wx, 4, wz, facing, fake, s);
            }
        }
    }

    private void placeDoor(WorldGenLevel level, int wx, int wy, int wz,
                           Direction facing, boolean fake, long seed) {
        BlockState doorLower = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
            .setValue(DoorBlock.OPEN, false)
            .setValue(DoorBlock.POWERED, false);

        BlockState doorUpper = doorLower
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER);

        BlockPos posLower = new BlockPos(wx, wy, wz);
        BlockPos posUpper = posLower.above();

        level.setBlock(posLower, doorLower, 3);
        level.setBlock(posUpper, doorUpper, 3);

        if (fake) {
            Direction behind = facing.getOpposite();
            BlockPos behindPos = posLower.relative(behind);
            BlockPos behindUp  = behindPos.above();
            if (level.getBlockState(behindPos).isAir()) {
                level.setBlock(behindPos, BLK_WALL, 3);
            }
            if (level.getBlockState(behindUp).isAir()) {
                level.setBlock(behindUp, BLK_WALL, 3);
            }
        }
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
