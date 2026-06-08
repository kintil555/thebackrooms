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
 *   tertanam di lantai karpet. Igloo yang mencuat dari bawah tanah. Menara
 *   pillager yang muncul di ruangan 40 blok tinggi tapi tanpa satu pun
 *   penghuninya. Semuanya misterius, tidak logis, dan membuat player bertanya.
 *
 * STRUKTUR:
 *   1. SHIPWRECK  — 1/180 chunk, semua zone kecuali VOID
 *      NBT verified: shipwreck/*.nbt ada di vanilla jar (Structure Block wiki)
 *      Y=1 (terbenam di lantai, hanya puncak yang kelihatan)
 *      Random rotation + mirror → tiap kapal orientasi berbeda
 *
 *   2. IGLOO FRAGMENT — 1/150 chunk, semua zone
 *      NBT verified: igloo/bottom.nbt, middle.nbt, top.nbt
 *      Y=1 (terbenam, hanya interior mencuat dari karpet)
 *      "bottom" punya secret room → paling misterius
 *
 *   3. PILLAGER OUTPOST — 1/300 chunk, HANYA ZONE_VOID
 *      NBT verified: semua piece pakai prefix "feature_" (bukan subdir /feature/)
 *      60% watchtower (menara penuh, dramatis di ruangan void 40 blok)
 *      40% feature saja (cage, tent, logs, dll)
 *      setIgnoreEntities=true → TIDAK spawn pillager apapun
 *
 * CATATAN PENTING — MINESHAFT:
 *   Mineshaft TIDAK punya NBT files. Generatenya secara procedural via Java
 *   (net.minecraft.world.level.levelgen.structure.structures.MineshaftStructure).
 *   Tidak bisa di-place via StructureTemplate. Diganti dengan igloo yang
 *   memberikan efek "reruntuhan overworld yang bocor" serupa.
 *
 * CATATAN PENTING — PILLAGER OUTPOST PIECE NAMES:
 *   BENAR:  minecraft:pillager_outpost/feature_cage1  (underscore, flat)
 *   SALAH:  minecraft:pillager_outpost/feature/cage1  (slash, subdir)
 *   Source: verified dari vanilla jar 1.14–1.21 (tidak berubah)
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID)
public class BackroomsStructureSpawner {

    // ── Chance per chunk (1 in N) ─────────────────────────────────────────────
    private static final int SHIP_CHANCE    = 180;
    private static final int IGLOO_CHANCE   = 150;
    private static final int OUTPOST_CHANCE = 300;

    // ── SHIPWRECK pieces (verified vanilla 1.21 NBT names) ───────────────────
    // Semua variant tersedia, dari rightsideup sampai upsidedown
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

    // ── IGLOO pieces (verified vanilla 1.21 NBT names) ───────────────────────
    // bottom = lantai dasar + secret lab room → paling dramatis
    // middle = dinding igloo
    // top    = kubah atas → visual unik mencuat dari karpet
    private static final String[] IGLOO_PIECES = {
        "minecraft:igloo/bottom",
        "minecraft:igloo/middle",
        "minecraft:igloo/top",
    };

    // ── PILLAGER OUTPOST pieces (verified vanilla 1.21 NBT names) ────────────
    // PENTING: prefix "feature_" bukan subdir "/feature/"
    // Semua ada di: data/minecraft/structures/pillager_outpost/*.nbt
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

    // ── Zone constants (identik dengan BackroomsChunkGenerator) ──────────────
    private static final int REGION_SIZE = 48;
    private static final int ZONE_VOID   = 3;

    // ── Per-type seed XOR constants ───────────────────────────────────────────
    private static final long SEED_SHIP   = 0xA1B2C3D4E5F60001L;
    private static final long SEED_IGLOO  = 0xB2C3D4E5F6A00002L;
    private static final long SEED_POST   = 0xC3D4E5F6A0B00003L;

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN EVENT
    // ══════════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        // Server-side only
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        // Backrooms dimension only
        if (!level.dimension().equals(ModDimensions.BACKROOMS_LEVEL)) return;
        // LevelChunk only (tidak proses proto-chunk atau empty chunk)
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        ChunkPos cp = chunk.getPos();
        long base = chunkSeed(level.getSeed(), cp.x, cp.z);

        // Zone check dari tengah chunk
        int zone = getZone(cp.getMiddleBlockX(), cp.getMiddleBlockZ());

        // ── 1. SHIPWRECK — semua zone kecuali VOID ───────────────────────
        if (zone != ZONE_VOID) {
            RandomSource rShip = RandomSource.create(base ^ SEED_SHIP);
            if (rShip.nextInt(SHIP_CHANCE) == 0) {
                tryPlace(level, cp, rShip, SHIP_PIECES, 1, "shipwreck");
            }
        }

        // ── 2. IGLOO FRAGMENT — semua zone ───────────────────────────────
        RandomSource rIgloo = RandomSource.create(base ^ SEED_IGLOO);
        if (rIgloo.nextInt(IGLOO_CHANCE) == 0) {
            tryPlace(level, cp, rIgloo, IGLOO_PIECES, 1, "igloo");
        }

        // ── 3. PILLAGER OUTPOST — HANYA ZONE_VOID ────────────────────────
        if (zone == ZONE_VOID) {
            RandomSource rPost = RandomSource.create(base ^ SEED_POST);
            if (rPost.nextInt(OUTPOST_CHANCE) == 0) {
                // 60% watchtower penuh (dramatis di void room 40 blok)
                // 40% feature saja (cage/tent/plate dll)
                String[] pool = rPost.nextFloat() < 0.60f ? OUTPOST_MAIN : OUTPOST_FEATURES;
                tryPlace(level, cp, rPost, pool, 2, "pillager_outpost");
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

        // Pilih piece acak dari pool
        String name = pieces[rng.nextInt(pieces.length)];
        ResourceLocation loc = ResourceLocation.parse(name);

        Optional<StructureTemplate> opt = mgr.get(loc);
        if (opt.isEmpty()) {
            // Tidak seharusnya terjadi kalau nama sudah diverifikasi
            BackroomsMod.LOGGER.warn("[Backrooms] NBT '{}' tidak ditemukan (cek nama piece)", name);
            return;
        }

        StructureTemplate tmpl = opt.get();

        // Posisi: acak dalam chunk, beri margin 1 blok dari tepi
        int wx = cp.getMinBlockX() + rng.nextInt(14) + 1;
        int wz = cp.getMinBlockZ() + rng.nextInt(14) + 1;
        BlockPos origin = new BlockPos(wx, baseY, wz);

        // Random rotation + mirror → tiap instance orientasi beda
        Rotation rot    = Rotation.values()[rng.nextInt(4)];
        Mirror   mirror = Mirror.values()[rng.nextInt(3)];

        StructurePlaceSettings cfg = new StructurePlaceSettings()
            .setRotation(rot)
            .setMirror(mirror)
            .setIgnoreEntities(true)       // KRITIS: tidak spawn mob apapun
            .setFinalizeEntities(false);   // Tidak finalize entity data

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
    // ZONE — identik dengan BackroomsChunkGenerator.getZone()
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
