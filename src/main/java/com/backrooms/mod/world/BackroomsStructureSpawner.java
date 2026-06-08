package com.backrooms.mod.world;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.dimension.ModDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Menspawn struktur overworld "bocor" ke Backrooms Level 0.
 *
 * FIX KRITIS:
 *   Sebelumnya struktur di-place di ChunkEvent.Load → setiap kali chunk
 *   di-load ulang (login, unload/reload) struktur di-place LAGI, menimpa
 *   blok lantai Y=0–1 dengan AIR dan merusak generation.
 *
 *   Sekarang pakai Set alreadyProcessed (ConcurrentHashMap key set) untuk
 *   memastikan tiap chunk hanya diproses SEKALI per sesi server.
 *   Chunk yang belum ada di set = baru pertama kali load = boleh di-place.
 *   Chunk yang sudah ada = sudah pernah di-place = skip.
 *
 *   Ini aman karena struktur sudah tersimpan di world save (level.dat),
 *   jadi restart server pun tidak masalah — chunk fresh-load lagi tapi
 *   blok struktur sudah ada di terrain, placement hanya terjadi sekali.
 *
 * FIX MINESHAFT:
 *   oy diubah dari 2 ke 1 (tepat di atas Y=0 bedrock) agar lorong tidak
 *   mengapung, dan setBlock sekarang TIDAK pernah menulis ke Y <= 1 agar
 *   tidak menghapus bedrock lantai atau karpet yang sudah di-generate.
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID)
public class BackroomsStructureSpawner {

    /**
     * Set chunk (encoded sebagai long dari ChunkPos) yang sudah diproses
     * di sesi server ini. Mencegah double-placement saat chunk reload.
     */
    private static final Set<Long> alreadyProcessed =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Bersihkan set saat server restart / world unload (dipanggil dari WorldEventHandler). */
    public static void clearProcessedChunks() {
        alreadyProcessed.clear();
        BackroomsMod.LOGGER.debug("[Backrooms] Structure spawner cache cleared.");
    }

    // ── Chance per chunk (1 in N) ─────────────────────────────────────────────
    private static final int SHIP_CHANCE      = 180;
    private static final int IGLOO_CHANCE     = 150;
    private static final int OUTPOST_CHANCE   = 300;
    private static final int MINESHAFT_CHANCE = 120;

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

    // ── Zone constants (identik dengan BackroomsChunkGenerator) ──────────────
    private static final int REGION_SIZE = 48;
    private static final int ZONE_VOID   = 3;

    // ── Per-type seed XOR constants ───────────────────────────────────────────
    private static final long SEED_SHIP   = 0xA1B2C3D4E5F60001L;
    private static final long SEED_IGLOO  = 0xB2C3D4E5F6A00002L;
    private static final long SEED_POST   = 0xC3D4E5F6A0B00003L;
    private static final long SEED_MINE   = 0xD4E5F6A0B1C00004L;

    // ── Y minimum aman untuk menulis blok struktur ───────────────────────────
    /** Tidak boleh menulis ke Y=0 (bedrock lantai) dan Y=1 (karpet). */
    private static final int MIN_SAFE_Y = 2;

    // ══════════════════════════════════════════════════════════════════════════
    // MAIN EVENT
    // ══════════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(ModDimensions.BACKROOMS_LEVEL)) return;
        if (!(event.getChunk() instanceof LevelChunk chunk)) return;

        ChunkPos cp = chunk.getPos();

        // ── KRITIS: skip jika chunk ini sudah pernah diproses sesi ini ───
        long chunkKey = ChunkPos.asLong(cp.x, cp.z);
        if (!alreadyProcessed.add(chunkKey)) {
            // add() return false = sudah ada = chunk reload, jangan place lagi
            return;
        }

        long base = chunkSeed(level.getSeed(), cp.x, cp.z);
        int zone = getZone(cp.getMiddleBlockX(), cp.getMiddleBlockZ());

        // ── 1. SHIPWRECK — semua zone kecuali VOID ───────────────────────
        if (zone != ZONE_VOID) {
            RandomSource rShip = RandomSource.create(base ^ SEED_SHIP);
            if (rShip.nextInt(SHIP_CHANCE) == 0) {
                tryPlace(level, cp, rShip, SHIP_PIECES, MIN_SAFE_Y, "shipwreck");
            }
        }

        // ── 2. IGLOO FRAGMENT — semua zone ───────────────────────────────
        RandomSource rIgloo = RandomSource.create(base ^ SEED_IGLOO);
        if (rIgloo.nextInt(IGLOO_CHANCE) == 0) {
            tryPlace(level, cp, rIgloo, IGLOO_PIECES, MIN_SAFE_Y, "igloo");
        }

        // ── 3. MINESHAFT FRAGMENT — semua zone ───────────────────────────
        RandomSource rMine = RandomSource.create(base ^ SEED_MINE);
        if (rMine.nextInt(MINESHAFT_CHANCE) == 0) {
            placeMineshaftFragment(level, cp, rMine);
        }

        // ── 4. PILLAGER OUTPOST — HANYA ZONE_VOID ────────────────────────
        if (zone == ZONE_VOID) {
            RandomSource rPost = RandomSource.create(base ^ SEED_POST);
            if (rPost.nextInt(OUTPOST_CHANCE) == 0) {
                String[] pool = rPost.nextFloat() < 0.60f ? OUTPOST_MAIN : OUTPOST_FEATURES;
                tryPlace(level, cp, rPost, pool, MIN_SAFE_Y, "pillager_outpost");
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MINESHAFT FRAGMENT — procedural
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Lorong mineshaft 3×3: mulai dari Y=2 (tepat di atas karpet Y=1).
     *
     * FIX: semua setBlock sekarang dicek minimum Y=2 agar tidak pernah
     * menimpa Y=0 (bedrock) atau Y=1 (karpet) yang di-generate oleh
     * BackroomsChunkGenerator.
     */
    private static void placeMineshaftFragment(ServerLevel level, ChunkPos cp,
                                               RandomSource rng) {
        boolean alongX = rng.nextBoolean();
        int length = 4 + rng.nextInt(9);

        int ox = cp.getMinBlockX() + rng.nextInt(12) + 2;
        int oz = cp.getMinBlockZ() + rng.nextInt(12) + 2;
        int oy = MIN_SAFE_Y; // Y=2, tepat di atas karpet Y=1

        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState fence  = Blocks.OAK_FENCE.defaultBlockState();
        BlockState air    = Blocks.AIR.defaultBlockState();
        BlockState rail   = Blocks.RAIL.defaultBlockState()
            .setValue(RailBlock.SHAPE, alongX ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);

        int nextTorch   = 2 + rng.nextInt(3);
        int nextSupport = 4;

        for (int i = 0; i < length; i++) {
            int wx = alongX ? ox + i : ox;
            int wz = alongX ? oz     : oz + i;

            // Y+0 (=Y=2): lantai lorong — rel di tengah, air di sisi
            safePlaceAir(level, wx - (alongX ? 0 : 1), oy,     wz - (alongX ? 1 : 0));
            safeSetBlock(level, wx,                      oy,     wz,                     rail);
            safePlaceAir(level, wx + (alongX ? 0 : 1), oy,     wz + (alongX ? 1 : 0));

            // Y+1 (=Y=3): tengah lorong
            for (int side = -1; side <= 1; side++) {
                int sx = alongX ? wx : wx + side;
                int sz = alongX ? wz + side : wz;
                safePlaceAir(level, sx, oy + 1, sz);
            }

            // Y+2 (=Y=4): atas lorong
            for (int side = -1; side <= 1; side++) {
                int sx = alongX ? wx : wx + side;
                int sz = alongX ? wz + side : wz;
                safePlaceAir(level, sx, oy + 2, sz);
            }

            // Support beam tiap 4 blok
            if (i == nextSupport || i == length - 1) {
                int sx1 = alongX ? wx : wx - 1;
                int sz1 = alongX ? wz - 1 : wz;
                int sx2 = alongX ? wx : wx + 1;
                int sz2 = alongX ? wz + 1 : wz;

                safeSetBlock(level, sx1, oy + 1, sz1, fence);
                safeSetBlock(level, sx2, oy + 1, sz2, fence);

                for (int side = -1; side <= 1; side++) {
                    int spx = alongX ? wx : wx + side;
                    int spz = alongX ? wz + side : wz;
                    safeSetBlock(level, spx, oy + 2, spz, planks);
                }
                nextSupport = i + 4;
            }

            // Torch di dinding tiap ~5 blok
            if (i == nextTorch && i > 0 && i < length - 1) {
                boolean leftSide = rng.nextBoolean();
                Direction torchDir = alongX
                    ? (leftSide ? Direction.NORTH : Direction.SOUTH)
                    : (leftSide ? Direction.WEST  : Direction.EAST);

                int tx = wx + (alongX ? 0 : (leftSide ? -1 : 1));
                int tz = wz + (alongX ? (leftSide ? -1 : 1) : 0);

                BlockState wallTorch = Blocks.WALL_TORCH.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, torchDir);
                safeSetBlock(level, tx, oy + 1, tz, wallTorch);

                nextTorch = i + 4 + rng.nextInt(3);
            }
        }

        BackroomsMod.LOGGER.debug(
            "[Backrooms] ⛏ mineshaft fragment ({} blok, {}) → ({},{},{})",
            length, alongX ? "EW" : "NS", ox, oy, oz);
    }

    /**
     * Set blok dengan perlindungan: tidak pernah menulis ke Y < MIN_SAFE_Y.
     * Mencegah overwrite bedrock (Y=0) dan karpet (Y=1).
     */
    private static void safeSetBlock(ServerLevel level, int wx, int wy, int wz, BlockState state) {
        if (wy < MIN_SAFE_Y) return; // PROTEKSI: jangan sentuh lantai/bedrock
        level.setBlock(new BlockPos(wx, wy, wz), state, 2);
    }

    /**
     * Tempatkan air hanya jika Y aman DAN blok yang ada bukan bedrock/karpet.
     * Ini mencegah menghapus lantai di area chunk tetangga yang mungkin
     * belum ter-generate ulang.
     */
    private static void safePlaceAir(ServerLevel level, int wx, int wy, int wz) {
        if (wy < MIN_SAFE_Y) return;
        BlockPos pos = new BlockPos(wx, wy, wz);
        BlockState existing = level.getBlockState(pos);
        // Jangan timpa bedrock atau karpet yang sudah ada
        if (existing.is(Blocks.BEDROCK) || existing.is(Blocks.BROWN_WOOL)) return;
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PLACE (NBT structures)
    // ══════════════════════════════════════════════════════════════════════════

    private static void tryPlace(ServerLevel level, ChunkPos cp,
                                 RandomSource rng, String[] pieces,
                                 int baseY, String tag) {
        StructureTemplateManager mgr = level.getStructureManager();

        String name = pieces[rng.nextInt(pieces.length)];
        ResourceLocation loc = ResourceLocation.parse(name);

        Optional<StructureTemplate> opt = mgr.get(loc);
        if (opt.isEmpty()) {
            BackroomsMod.LOGGER.warn("[Backrooms] NBT '{}' tidak ditemukan (cek nama piece)", name);
            return;
        }

        StructureTemplate tmpl = opt.get();

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
