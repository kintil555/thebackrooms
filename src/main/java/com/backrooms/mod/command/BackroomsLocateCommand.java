package com.backrooms.mod.command;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.dimension.ModDimensions;
import com.backrooms.mod.event.BackroomsTeleporter;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

/**
 * /backrooms locate <zone> — cari region terdekat dari zona tertentu dan
 * langsung teleport pemain ke tengahnya.
 *
 * Zone names:
 *   corridor, office, open, void, complex, pitfalls
 *
 * Algoritma: scan spiral dari posisi X/Z pemain saat ini, cek tiap region
 * 48×48, temukan yang pertama cocok dengan zone target.
 * Radius scan maksimal 200 region (~9600 blok) — hampir pasti ketemu.
 *
 * Usage:
 *   /backrooms locate void
 *   /backrooms locate pitfalls
 *   /backrooms locate corridor
 */
public class BackroomsLocateCommand {

    private static final int REGION_SIZE = 48;
    private static final double Y_SPAWN  = 2.0;

    // Zone IDs — harus identik dengan BackroomsChunkGenerator
    private static final int ZONE_CORRIDOR = 0;
    private static final int ZONE_OFFICE   = 1;
    private static final int ZONE_OPEN     = 2;
    private static final int ZONE_VOID     = 3;
    private static final int ZONE_COMPLEX  = 4;
    private static final int ZONE_PITFALLS = 5;

    private static final String[] ZONE_NAMES = {
        "corridor", "office", "open", "void", "complex", "pitfalls"
    };

    private static final SuggestionProvider<CommandSourceStack> ZONE_SUGGESTIONS =
        (ctx, builder) -> SharedSuggestionProvider.suggest(ZONE_NAMES, builder);

    // ──────────────────────────────────────────────────────────────────────────

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("backrooms")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("locate")
                    .then(Commands.argument("zone", StringArgumentType.word())
                        .suggests(ZONE_SUGGESTIONS)
                        .executes(ctx -> {
                            String zoneName = StringArgumentType.getString(ctx, "zone");
                            return locate(ctx.getSource(), zoneName);
                        })
                    )
                )
        );
        BackroomsMod.LOGGER.info("[Backrooms] /backrooms locate registered.");
    }

    // ──────────────────────────────────────────────────────────────────────────

    private static int locate(CommandSourceStack source, String zoneName) {
        // Parse zone name → id
        int targetZone = parseZone(zoneName);
        if (targetZone < 0) {
            source.sendFailure(Component.literal(
                "§cZone tidak dikenal: §e" + zoneName +
                "§c. Pilihan: §7corridor, office, open, void, complex, pitfalls"));
            return 0;
        }

        // Pastikan player ada
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cHarus dijalankan oleh player."));
            return 0;
        }

        // Dapatkan backrooms level
        ServerLevel backrooms = source.getServer().getLevel(ModDimensions.BACKROOMS_LEVEL);
        if (backrooms == null) {
            source.sendFailure(Component.literal("§c[Backrooms] Dimensi tidak ditemukan!"));
            return 0;
        }

        // Titik awal scan: posisi player di backrooms (atau 0,0 jika di overworld)
        int startX, startZ;
        if (player.level().dimension().equals(ModDimensions.BACKROOMS_LEVEL)) {
            startX = player.getBlockX();
            startZ = player.getBlockZ();
        } else {
            startX = 0;
            startZ = 0;
        }

        // Seed dunia untuk getZone
        long worldSeed = backrooms.getSeed();

        // Feedback awal
        source.sendSuccess(() -> Component.literal(
            "§7[Backrooms] Mencari zone §e" + zoneName + "§7..."), false);

        // Scan spiral untuk cari region terdekat
        int[] found = scanSpiral(startX, startZ, targetZone, worldSeed, 200);
        if (found == null) {
            source.sendFailure(Component.literal(
                "§c[Backrooms] Zone §e" + zoneName + "§c tidak ditemukan dalam radius 9600 blok."));
            return 0;
        }

        // Tengah region yang ditemukan
        int destX = found[0] * REGION_SIZE + REGION_SIZE / 2;
        int destZ = found[1] * REGION_SIZE + REGION_SIZE / 2;
        int distBlok = (int) Math.sqrt(
            Math.pow(destX - startX, 2) + Math.pow(destZ - startZ, 2));

        // Cari lantai solid tanpa nulis blok
        int floorY = BackroomsTeleporter.findSafeFloorY(backrooms, destX, destZ);
        double spawnY = floorY + 1.0;

        // Teleport
        DimensionTransition transition = new DimensionTransition(
            backrooms,
            new Vec3(destX + 0.5, spawnY, destZ + 0.5),
            Vec3.ZERO,
            player.getYRot(),
            player.getXRot(),
            DimensionTransition.DO_NOTHING
        );
        player.changeDimension(transition);

        // Pesan
        String label = zoneLabel(targetZone);
        player.sendSystemMessage(Component.literal(
            "§b§l[LOCATE] §r§7Zone §e" + label + "§7 ditemukan."));
        player.sendSystemMessage(Component.literal(
            "§7Koordinat: §f" + destX + ", " + (int)spawnY + ", " + destZ +
            " §7(§f" + distBlok + " blok§7 dari titik awal)"));

        source.sendSuccess(() -> Component.literal(
            "§a[Backrooms] Teleported ke §e" + label +
            "§a @ §7" + destX + ", " + (int)spawnY + ", " + destZ), true);

        BackroomsMod.LOGGER.info(
            "[Backrooms] /backrooms locate {} → ({},{}) jarak {} blok",
            zoneName, destX, destZ, distBlok);

        return 1;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SPIRAL SCAN
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Scan spiral dari region asal, cari region pertama yang cocok dengan zone.
     * Mengembalikan {rx, rz} atau null jika tidak ditemukan.
     *
     * Spiral: kanan 1, atas 1, kiri 2, bawah 2, kanan 3, atas 3, ...
     * Ini memastikan region terdekat dari titik asal yang ditemukan duluan.
     */
    private static int[] scanSpiral(int startX, int startZ, int targetZone,
                                    long worldSeed, int maxRadius) {
        int originRx = Math.floorDiv(startX, REGION_SIZE);
        int originRz = Math.floorDiv(startZ, REGION_SIZE);

        // Cek origin dulu
        if (getZone(originRx, originRz, worldSeed) == targetZone) {
            return new int[]{originRx, originRz};
        }

        int rx = originRx, rz = originRz;
        int step = 1;

        while (step <= maxRadius) {
            // Kanan
            for (int i = 0; i < step; i++) {
                rx++;
                if (getZone(rx, rz, worldSeed) == targetZone) return new int[]{rx, rz};
            }
            // Atas (Z-)
            for (int i = 0; i < step; i++) {
                rz--;
                if (getZone(rx, rz, worldSeed) == targetZone) return new int[]{rx, rz};
            }
            step++;
            // Kiri
            for (int i = 0; i < step; i++) {
                rx--;
                if (getZone(rx, rz, worldSeed) == targetZone) return new int[]{rx, rz};
            }
            // Bawah (Z+)
            for (int i = 0; i < step; i++) {
                rz++;
                if (getZone(rx, rz, worldSeed) == targetZone) return new int[]{rx, rz};
            }
            step++;
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // HELPERS — duplikat logika dari BackroomsChunkGenerator (harus identik)
    // ──────────────────────────────────────────────────────────────────────────

    /** Harus identik dengan BackroomsChunkGenerator.getZone() */
    private static int getZone(int rx, int rz, long worldSeed) {
        long s = regionSeed(rx, rz);
        int r = (int) Math.abs(s % 20);
        if (r < 2)  return ZONE_VOID;
        if (r < 4)  return ZONE_PITFALLS;
        if (r < 7)  return ZONE_CORRIDOR;
        if (r < 10) return ZONE_COMPLEX;
        if (r < 15) return ZONE_OPEN;
        return ZONE_OFFICE;
    }

    /** Identik dengan BackroomsChunkGenerator.regionSeed() — TIDAK pakai worldSeed */
    private static long regionSeed(int rx, int rz) {
        return ((long) rx * 0xD1B54A32D192ED03L)
             ^ ((long) rz * 0x9E3779B97F4A7C15L)
             ^ 0xBAC0D00D0000000L;
    }

    private static int parseZone(String name) {
        return switch (name.toLowerCase()) {
            case "corridor" -> ZONE_CORRIDOR;
            case "office"   -> ZONE_OFFICE;
            case "open"     -> ZONE_OPEN;
            case "void"     -> ZONE_VOID;
            case "complex"  -> ZONE_COMPLEX;
            case "pitfalls" -> ZONE_PITFALLS;
            default         -> -1;
        };
    }

    private static String zoneLabel(int zone) {
        return switch (zone) {
            case ZONE_CORRIDOR -> "Corridor";
            case ZONE_OFFICE   -> "Office";
            case ZONE_OPEN     -> "Open Space";
            case ZONE_VOID     -> "Void";
            case ZONE_COMPLEX  -> "Complex";
            case ZONE_PITFALLS -> "Pitfalls";
            default            -> "Unknown";
        };
    }
}
