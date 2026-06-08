package com.backrooms.mod.world;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

import java.util.Optional;

/**
 * Menspawn struktur overworld "bocor" ke Backrooms Level 0.
 *
 * PENTING — CARA YANG BENAR (hasil riset):
 *   Semua placement struktur harus dilakukan dari applyBiomeDecoration()
 *   di BackroomsChunkGenerator, bukan dari ChunkEvent.Load.
 *
 *   ChunkEvent.Load dipanggil SETIAP KALI chunk di-load (termasuk reload),
 *   dan memanggil level.setBlock() di sana menyebabkan:
 *     - Chunk corruption (blok lantai/bedrock tertimpa)
 *     - Chunk "kedip" (muncul/hilang saat didekati)
 *     - World save lama karena chunk terus di-mark dirty
 *
 *   applyBiomeDecoration() dipanggil HANYA SEKALI saat chunk pertama kali
 *   di-generate (ChunkStatus FEATURES), tidak pernah saat reload.
 *   Ini adalah pipeline resmi Minecraft untuk decoration/struktur.
 *
 * STRUKTUR:
 *   1. SHIPWRECK  — 1/180 chunk, semua zone kecuali VOID, baseY=2
 *   2. IGLOO      — 1/150 chunk, semua zone, baseY=2
 *   3. PILLAGER OUTPOST — 1/300 chunk, HANYA ZONE_VOID, baseY=2
 *
 * Mineshaft prosedural DIHAPUS — terlalu invasif, bisa corrupt Y=0-1.
 */
public class BackroomsStructureSpawner {

    // ── Chance per chunk (1 in N) ─────────────────────────────────────────────
    private static final int SHIP_CHANCE    = 180;
    private static final int IGLOO_CHANCE   = 150;
    private static final int OUTPOST_CHANCE = 300;

    /** Y minimum untuk placement — tidak boleh menyentuh bedrock Y=0 atau karpet Y=1 */
    private static final int BASE_Y = 2;

    // ── SHIPWRECK pieces ─────────────────────────────────────────────────────
    private static final String[] SHIP_PIECES = {
        "minecraft:shipwreck/with_mast",
        "minecraft:shipwreck/with_mast_degraded",
        "minecraft:shipwreck/rightsideup_full",
        "minecraft:shipwreck/rightsideup_full_degraded",
        "minecraft:shipwreck/rightsideup_fronthalf",
        "minecraft:shipwreck/rightsideup_fronthalf_degraded",
        "minecraft:shipwreck/sideways_full",
        "minecraft:shipwreck/sideways_full_degraded",
        "minecraft:shipwreck/sideways_fronthalf",
        "minecraft:shipwreck/upsidedown_full",
        "minecraft:shipwreck/upsidedown_fronthalf",
    };

    // ── IGLOO pieces ─────────────────────────────────────────────────────────
    private static final String[] IGLOO_PIECES = {
        "minecraft:igloo/bottom",
        "minecraft:igloo/middle",
        "minecraft:igloo/top",
    };

    // ── PILLAGER OUTPOST pieces ───────────────────────────────────────────────
    private static final String[] OUTPOST_MAIN = {
        "minecraft:pillager_outpost/watchtower",
        "minecraft:pillager_outpost/watchtower_overgrown",
    };
    private static final String[] OUTPOST_FEATURES = {
        "minecraft:pillager_outpost/base_plate",
        "minecraft:pillager_outpost/feature_cage1",
        "minecraft:pillager_outpost/feature_cage2",
        "minecraft:pillager_outpost/feature_logs",
        "minecraft:pillager_outpost/feature_plate",
        "minecraft:pillager_outpost/feature_targets",
        "minecraft:pillager_outpost/feature_tent1",
        "minecraft:pillager_outpost/feature_tent2",
    };

    // ── Zone constants ────────────────────────────────────────────────────────
    private static final int REGION_SIZE = 48;
    private static final int ZONE_VOID   = 3;

    // ── Per-type seed XOR constants ───────────────────────────────────────────
    private static final long SEED_SHIP   = 0xA1B2C3D4E5F60001L;
    private static final long SEED_IGLOO  = 0xB2C3D4E5F6A00002L;
    private static final long SEED_POST   = 0xC3D4E5F6A0B00003L;

    // ══════════════════════════════════════════════════════════════════════════
    // ENTRY POINT — dipanggil dari BackroomsChunkGenerator.applyBiomeDecoration()
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Dipanggil dari BackroomsChunkGenerator#applyBiomeDecoration().
     * WorldGenLevel adalah WorldGenRegion — safe untuk setBlock karena ini
     * fase FEATURES (setelah fillFromNoise, sebelum chunk di-mark FULL).
     */
    public static void decorate(WorldGenLevel level, ChunkAccess chunk, long worldSeed) {
        // StructureTemplateManager hanya tersedia dari ServerLevel
        if (!(level.getLevel() instanceof ServerLevel serverLevel)) return;

        ChunkPos cp = chunk.getPos();
        long base   = chunkSeed(worldSeed, cp.x, cp.z);
        int  zone   = getZone(cp.getMiddleBlockX(), cp.getMiddleBlockZ());

        StructureTemplateManager mgr = serverLevel.getStructureManager();

        // ── 1. SHIPWRECK — semua zone kecuali VOID ───────────────────────
        if (zone != ZONE_VOID) {
            RandomSource rShip = RandomSource.create(base ^ SEED_SHIP);
            if (rShip.nextInt(SHIP_CHANCE) == 0) {
                tryPlace(level, cp, rShip, mgr, SHIP_PIECES, BASE_Y, "shipwreck");
            }
        }

        // ── 2. IGLOO FRAGMENT — semua zone ───────────────────────────────
        RandomSource rIgloo = RandomSource.create(base ^ SEED_IGLOO);
        if (rIgloo.nextInt(IGLOO_CHANCE) == 0) {
            tryPlace(level, cp, rIgloo, mgr, IGLOO_PIECES, BASE_Y, "igloo");
        }

        // ── 3. PILLAGER OUTPOST — HANYA ZONE_VOID ────────────────────────
        if (zone == ZONE_VOID) {
            RandomSource rPost = RandomSource.create(base ^ SEED_POST);
            if (rPost.nextInt(OUTPOST_CHANCE) == 0) {
                String[] pool = rPost.nextFloat() < 0.60f ? OUTPOST_MAIN : OUTPOST_FEATURES;
                tryPlace(level, cp, rPost, mgr, pool, BASE_Y, "pillager_outpost");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PLACE (NBT structures)
    // ══════════════════════════════════════════════════════════════════════════

    private static void tryPlace(WorldGenLevel level, ChunkPos cp,
                                 RandomSource rng, StructureTemplateManager mgr,
                                 String[] pieces, int baseY, String tag) {
        String name = pieces[rng.nextInt(pieces.length)];
        ResourceLocation loc = ResourceLocation.parse(name);

        Optional<StructureTemplate> opt = mgr.get(loc);
        if (opt.isEmpty()) {
            BackroomsMod.LOGGER.warn("[Backrooms] NBT '{}' tidak ditemukan", name);
            return;
        }

        StructureTemplate tmpl = opt.get();

        // Posisi acak dalam chunk, margin 1 blok dari tepi
        int wx = cp.getMinBlockX() + rng.nextInt(14) + 1;
        int wz = cp.getMinBlockZ() + rng.nextInt(14) + 1;
        BlockPos origin = new BlockPos(wx, baseY, wz);

        Rotation rot    = Rotation.values()[rng.nextInt(4)];
        Mirror   mirror = Mirror.values()[rng.nextInt(3)];

        StructurePlaceSettings cfg = new StructurePlaceSettings()
            .setRotation(rot)
            .setMirror(mirror)
            .setIgnoreEntities(true)
            .setFinalizeEntities(false);

        try {
            tmpl.placeInWorld(level, origin, origin, cfg, rng, 2);
            BackroomsMod.LOGGER.debug(
                "[Backrooms] ✦ {} '{}' → ({},{},{})", tag, name, wx, baseY, wz);
        } catch (Exception e) {
            BackroomsMod.LOGGER.warn(
                "[Backrooms] Gagal place {} '{}' di ({},{},{}): {}",
                tag, name, wx, baseY, wz, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private static int getZone(int wx, int wz) {
        int rx = Math.floorDiv(wx, REGION_SIZE);
        int rz = Math.floorDiv(wz, REGION_SIZE);
        long s  = ((long) rx * 0xD1B54A32D192ED03L)
                ^ ((long) rz * 0x9E3779B97F4A7C15L)
                ^ 0xBAC0D00D0000000L;
        int r = (int) Math.abs(s % 10);
        if (r < 1) return ZONE_VOID;
        if (r < 3) return 0;
        if (r < 5) return 4;
        if (r < 8) return 2;
        return 1;
    }

    private static long chunkSeed(long worldSeed, int cx, int cz) {
        return worldSeed
             ^ ((long) cx * 341873128712L)
             ^ ((long) cz * 132897987541L);
    }
}
