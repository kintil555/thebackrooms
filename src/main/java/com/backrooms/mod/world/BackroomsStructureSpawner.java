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
    private static final int SHIP_CHANCE      = 180;
    private static final int IGLOO_CHANCE     = 150;
    private static final int OUTPOST_CHANCE   = 300;
    private static final int MINESHAFT_CHANCE = 120;

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
    private static final long SEED_MINE   = 0xD4E5F6A0B1C00004L;

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

        // ── 3. MINESHAFT FRAGMENT — semua zone ───────────────────────────
        // Procedural: lorong 3×3 blok, panjang 4–12 blok, arah N/S/E/W acak
        // Dibuat manual karena minecraft:mineshaft tidak punya NBT
        RandomSource rMine = RandomSource.create(base ^ SEED_MINE);
        if (rMine.nextInt(MINESHAFT_CHANCE) == 0) {
            placeMineshaftFragment(level, cp, rMine);
        }

        // ── 5. PILLAGER OUTPOST — HANYA ZONE_VOID ────────────────────────
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
    // MINESHAFT FRAGMENT — procedural, tidak pakai NBT
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Tempat lorong mineshaft sepotong: 3 blok lebar × 3 blok tinggi, panjang 4–12 blok.
     *
     * STRUKTUR per cross-section:
     *   Y+2  [planks][air  ][planks]   ← ceiling support (tiap 4 blok)
     *   Y+1  [air   ][air  ][air   ]
     *   Y+0  [air   ][rail ][air   ]   ← lantai + rel di tengah
     *
     * Torch ditempel di dinding tiap ~5 blok (acak 4–6).
     * Support (oak fence vertikal + planks horizontal) tiap 4 blok.
     *
     * baseY = 2 (tepat di atas karpet Y=1, jadi ujung lorong "muncul" dari lantai)
     */
    private static void placeMineshaftFragment(ServerLevel level, ChunkPos cp,
                                               RandomSource rng) {
        // Pilih arah: NORTH/SOUTH (along Z) atau EAST/WEST (along X)
        boolean alongX = rng.nextBoolean();

        // Panjang lorong: 4–12 blok
        int length = 4 + rng.nextInt(9);

        // Origin: acak dalam chunk dengan margin
        int ox = cp.getMinBlockX() + rng.nextInt(12) + 2;
        int oz = cp.getMinBlockZ() + rng.nextInt(12) + 2;
        int oy = 2; // tepat di atas karpet

        // Blok yang dipakai
        BlockState planks  = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState fence   = Blocks.OAK_FENCE.defaultBlockState();
        BlockState torch   = Blocks.WALL_TORCH.defaultBlockState();
        BlockState air     = Blocks.AIR.defaultBlockState();

        // Arah rel dan arah torch ke dinding
        BlockState rail = Blocks.RAIL.defaultBlockState()
            .setValue(RailBlock.SHAPE, alongX ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);

        int nextTorch  = 2 + rng.nextInt(3); // torch pertama di blok ke-2/3/4
        int nextSupport = 4;                  // support tiap 4 blok

        for (int i = 0; i < length; i++) {
            // Hitung posisi world berdasarkan arah
            int wx = alongX ? ox + i : ox;
            int wz = alongX ? oz     : oz + i;

            // ── Lantai: gali air (hapus blok yang ada) ───────────────────
            // Y+0: samping kiri, tengah (rel), samping kanan
            setBlock(level, wx, oy,     wz,     alongX ? -1 : 0,  0, alongX ? 0 : -1, air);
            setBlock(level, wx, oy,     wz,     0,                 0, 0,                rail);
            setBlock(level, wx, oy,     wz,     alongX ? 1 : 0,   0, alongX ? 0 : 1,  air);

            // Y+1: tiga blok lebar, kosong
            for (int side = -1; side <= 1; side++) {
                int sx = alongX ? wx : wx + side;
                int sz = alongX ? wz + side : wz;
                level.setBlock(new BlockPos(sx, oy + 1, sz), air, 2);
            }

            // Y+2: tiga blok lebar, kosong (kecuali support)
            for (int side = -1; side <= 1; side++) {
                int sx = alongX ? wx : wx + side;
                int sz = alongX ? wz + side : wz;
                level.setBlock(new BlockPos(sx, oy + 2, sz), air, 2);
            }

            // ── Support beam tiap 4 blok ──────────────────────────────────
            if (i == nextSupport || i == length - 1) {
                // Dua tiang fence di sisi lorong
                int sx1 = alongX ? wx : wx - 1;
                int sz1 = alongX ? wz - 1 : wz;
                int sx2 = alongX ? wx : wx + 1;
                int sz2 = alongX ? wz + 1 : wz;

                level.setBlock(new BlockPos(sx1, oy + 1, sz1), fence, 2);
                level.setBlock(new BlockPos(sx2, oy + 1, sz2), fence, 2);

                // Planks di atas tiang dan di tengah
                for (int side = -1; side <= 1; side++) {
                    int spx = alongX ? wx : wx + side;
                    int spz = alongX ? wz + side : wz;
                    level.setBlock(new BlockPos(spx, oy + 2, spz), planks, 2);
                }
                nextSupport = i + 4;
            }

            // ── Torch di dinding tiap ~5 blok ────────────────────────────
            if (i == nextTorch && i > 0 && i < length - 1) {
                // Torch di sisi kiri atau kanan secara acak
                boolean leftSide = rng.nextBoolean();
                Direction torchDir = alongX
                    ? (leftSide ? Direction.NORTH : Direction.SOUTH)
                    : (leftSide ? Direction.WEST  : Direction.EAST);

                int tx = wx + (alongX ? 0 : (leftSide ? -1 : 1));
                int tz = wz + (alongX ? (leftSide ? -1 : 1) : 0);

                BlockState wallTorch = torch.setValue(
                    net.minecraft.world.level.block.WallTorchBlock.FACING, torchDir);
                level.setBlock(new BlockPos(tx, oy + 1, tz), wallTorch, 2);

                nextTorch = i + 4 + rng.nextInt(3); // interval acak 4–6
            }
        }

        BackroomsMod.LOGGER.debug(
            "[Backrooms] ⛏ mineshaft fragment ({} blok, {}) → ({},{},{})",
            length, alongX ? "EW" : "NS", ox, oy, oz);
    }

    /** Helper: set blok dengan offset relatif dari (wx, wy, wz). */
    private static void setBlock(ServerLevel level,
                                  int wx, int wy, int wz,
                                  int dx, int dy, int dz,
                                  BlockState state) {
        level.setBlock(new BlockPos(wx + dx, wy + dy, wz + dz), state, 2);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // PLACE (NBT structures)
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
