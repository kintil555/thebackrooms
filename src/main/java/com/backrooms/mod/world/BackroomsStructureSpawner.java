package com.backrooms.mod.world;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.dimension.ModDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Optional;

/**
 * Menspawn struktur overworld "bocor" ke Backrooms Level 0.
 *
 * FILOSOFI:
 *   Struktur-struktur ini tidak "seharusnya" ada di sini. Kapal kargo yang
 *   tertanam di lantai karpet. Potongan mineshaft yang tiba-tiba berhenti
 *   di tengah ruangan. Menara pillager yang muncul di ruangan 40 blok tinggi
 *   tapi tanpa satu pun penghuninya. Semuanya misterius, tidak logis, dan
 *   membuat player bertanya-tanya.
 *
 * STRUKTUR:
 *   1. SHIPWRECK FRAGMENT  — 1/180 chunk, semua zone kecuali VOID
 *      Y=1 (terbenam di lantai, hanya puncaknya kelihatan)
 *      Random rotation + mirror → tiap kapal orientasi berbeda
 *
 *   2. MINESHAFT PIECE     — 1/120 chunk, semua zone
 *      Y=2 (kaki tembok mencuat dari lantai, terpotong batas chunk)
 *
 *   3. PILLAGER OUTPOST    — 1/400 chunk, HANYA ZONE_VOID
 *      Y=2, NO mob spawning (setIgnoreEntities=true)
 *      60% spawn base_plate (blacksmith) saja, 40% feature lain
 *
 * IMPLEMENTASI:
 *   ChunkEvent.Load → seed per-chunk → roll → StructureTemplate.placeInWorld()
 *   Tidak menggunakan vanilla StructureStart/StructureManager — langsung place NBT.
 *   Struktur akan terpotong oleh batas chunk secara alami (efek misterius).
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID)
public class BackroomsStructureSpawner {

    // ── Chance per chunk (1 in N) ─────────────────────────────────────────────
    private static final int SHIP_CHANCE      = 180;
    private static final int MINESHAFT_CHANCE = 120;
    private static final int OUTPOST_CHANCE   = 400;

    // ── Struktur NBT pieces ───────────────────────────────────────────────────
    private static final String[] SHIP_PIECES = {
        "minecraft:shipwreck/with_mast",
        "minecraft:shipwreck/sideways_full",
        "minecraft:shipwreck/rightsideup_full",
        "minecraft:shipwreck/rightsideup_full_degraded",
        "minecraft:shipwreck/sideways_fronthalf",
        "minecraft:shipwreck/rightsideup_fronthalf",
    };

    private static final String[] MINESHAFT_PIECES = {
        "minecraft:mineshaft/corridor",
        "minecraft:mineshaft/cross",
        "minecraft:mineshaft/room",
        "minecraft:mineshaft/stairs",
    };

    // Outpost: index 0 = base_plate (blacksmith area, sering spawn)
    // index 1-3 = feature lain (tower, logs, targets)
    private static final String[] OUTPOST_BASE   = { "minecraft:pillager_outpost/base_plate" };
    private static final String[] OUTPOST_EXTRAS = {
        "minecraft:pillager_outpost/feature/guard_tower",
        "minecraft:pillager_outpost/feature/logs",
        "minecraft:pillager_outpost/feature/targets_miss",
    };

    // ── Zone constants (identik dengan BackroomsChunkGenerator) ──────────────
    private static final int REGION_SIZE = 48;
    private static final int ZONE_VOID   = 3;

    // ── Seed XOR constants (valid hex only: 0-9, A-F) ────────────────────────
    private static final long SEED_MINE   = 0xA1B2C3D4E5F60001L;
    private static final long SEED_SHIP   = 0xB2C3D4E5F6A00002L;
    private static final long SEED_POST   = 0xC3D4E5F6A0B00003L;

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN EVENT
    // ══════════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(ModDimensions.BACKROOMS_LEVEL)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        ChunkPos cp = chunk.getPos();
        long base = chunkSeed(level.getSeed(), cp.x, cp.z);

        // Tengah chunk — untuk cek zone
        int mx = cp.getMiddleBlockX();
        int mz = cp.getMiddleBlockZ();
        int zone = getZone(mx, mz);

        // ── 1. MINESHAFT — semua zone ─────────────────────────────────────
        RandomSource rMine = RandomSource.create(base ^ SEED_MINE);
        if (rMine.nextInt(MINESHAFT_CHANCE) == 0) {
            tryPlace(level, cp, rMine, MINESHAFT_PIECES, 2, "mineshaft");
        }

        // ── 2. SHIPWRECK — semua zone kecuali VOID ───────────────────────
        if (zone != ZONE_VOID) {
            RandomSource rShip = RandomSource.create(base ^ SEED_SHIP);
            if (rShip.nextInt(SHIP_CHANCE) == 0) {
                tryPlace(level, cp, rShip, SHIP_PIECES, 1, "shipwreck");
            }
        }

        // ── 3. PILLAGER OUTPOST — HANYA zone VOID ────────────────────────
        if (zone == ZONE_VOID) {
            RandomSource rPost = RandomSource.create(base ^ SEED_POST);
            if (rPost.nextInt(OUTPOST_CHANCE) == 0) {
                // 60% base_plate (blacksmith), 40% random feature
                String[] pieces = rPost.nextFloat() < 0.60f ? OUTPOST_BASE : OUTPOST_EXTRAS;
                tryPlace(level, cp, rPost, pieces, 2, "pillager_outpost");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PLACE
    // ══════════════════════════════════════════════════════════════════════════

    private static void tryPlace(ServerLevel level, ChunkPos cp,
                                 RandomSource rng, String[] pieces,
                                 int baseY, String tag) {
        StructureTemplateManager mgr = level.getStructureManager();

        // Pilih piece acak
        String name = pieces[rng.nextInt(pieces.length)];
        ResourceLocation loc = ResourceLocation.parse(name);

        Optional<StructureTemplate> opt = mgr.get(loc);
        if (opt.isEmpty()) {
            BackroomsMod.LOGGER.debug("[Backrooms] NBT '{}' tidak ditemukan, skip", name);
            return;
        }

        StructureTemplate tmpl = opt.get();

        // Posisi: acak dalam chunk
        int wx = cp.getMinBlockX() + rng.nextInt(14) + 1; // +1 biar tidak di tepi
        int wz = cp.getMinBlockZ() + rng.nextInt(14) + 1;
        BlockPos origin = new BlockPos(wx, baseY, wz);

        // Random rotation + mirror → tiap struktur beda orientasi
        Rotation rot    = Rotation.values()[rng.nextInt(4)];
        Mirror   mirror = Mirror.values()[rng.nextInt(3)];

        StructurePlaceSettings cfg = new StructurePlaceSettings()
            .setRotation(rot)
            .setMirror(mirror)
            .setIgnoreEntities(true)     // TIDAK spawn mob apapun!
            .setFinalizeEntities(false); // Tidak finalize entity data

        try {
            tmpl.placeInWorld(level, origin, origin, cfg, rng, 2);
            BackroomsMod.LOGGER.debug(
                "[Backrooms] ✦ {} '{}' → ({},{},{})", tag, name, wx, baseY, wz);
        } catch (Exception e) {
            BackroomsMod.LOGGER.warn(
                "[Backrooms] Gagal place {} '{}': {}", tag, name, e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ZONE HELPER — sama dengan BackroomsChunkGenerator
    // ══════════════════════════════════════════════════════════════════════════

    private static int getZone(int wx, int wz) {
        int rx = Math.floorDiv(wx, REGION_SIZE);
        int rz = Math.floorDiv(wz, REGION_SIZE);
        long s  = ((long) rx * 0xD1B54A32D192ED03L)
                ^ ((long) rz * 0x9E3779B97F4A7C15L)
                ^ 0xBAC0D00D0000000L;
        int r = (int) Math.abs(s % 10);
        if (r < 1) return ZONE_VOID;
        if (r < 3) return 0; // CORRIDOR
        if (r < 5) return 4; // COMPLEX
        if (r < 8) return 2; // OPEN
        return 1;             // OFFICE
    }

    private static long chunkSeed(long worldSeed, int cx, int cz) {
        return worldSeed
             ^ ((long) cx * 341873128712L)
             ^ ((long) cz * 132897987541L);
    }
}
