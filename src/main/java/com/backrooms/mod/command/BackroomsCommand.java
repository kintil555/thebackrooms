package com.backrooms.mod.command;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.block.GhostWallBlock;
import com.backrooms.mod.dimension.ModDimensions;
import com.backrooms.mod.event.BackroomsTeleporter;
import com.backrooms.mod.event.NoclipChanceManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;

/**
 * Command /backrooms — debugging tool untuk masuk ke dimensi Backrooms langsung
 * tanpa perlu noclip melalui null zone.
 *
 * Usage:
 *   /backrooms enter                        — teleport diri sendiri ke backrooms
 *   /backrooms enter <player>               — teleport player lain
 *   /backrooms enter <player> <x> <y> <z>  — teleport ke koordinat spesifik
 *   /backrooms leave                        — kembali ke overworld
 *
 * Requires: OP level 2
 */
public class BackroomsCommand {

    /** Y spawn default di Backrooms (lantai level 0). */
    private static final int Y_FLOOR = 1;
    private static final double Y_SPAWN = 2.0;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("backrooms")
                .requires(src -> src.hasPermission(2)) // OP level 2

                // /backrooms enter
                .then(Commands.literal("enter")

                    // /backrooms enter  (no args → self)
                    .executes(ctx -> enterBackrooms(ctx, null, null, null, null))

                    // /backrooms enter <player>
                    .then(Commands.argument("player", EntityArgument.players())
                        .executes(ctx -> {
                            Collection<ServerPlayer> targets =
                                EntityArgument.getPlayers(ctx, "player");
                            return enterBackroomsMultiple(ctx, targets, null, null, null);
                        })

                        // /backrooms enter <player> <x> <y> <z>
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("y", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                    .executes(ctx -> {
                                        Collection<ServerPlayer> targets =
                                            EntityArgument.getPlayers(ctx, "player");
                                        int x = IntegerArgumentType.getInteger(ctx, "x");
                                        int y = IntegerArgumentType.getInteger(ctx, "y");
                                        int z = IntegerArgumentType.getInteger(ctx, "z");
                                        return enterBackroomsMultiple(ctx, targets, x, y, z);
                                    })
                                )
                            )
                        )
                    )
                )

                // /backrooms leave
                .then(Commands.literal("leave")
                    .executes(ctx -> leaveBackrooms(ctx, null))

                    .then(Commands.argument("player", EntityArgument.players())
                        .executes(ctx -> {
                            Collection<ServerPlayer> targets =
                                EntityArgument.getPlayers(ctx, "player");
                            return leaveBackroomsMultiple(ctx, targets);
                        })
                    )
                )

                // /backrooms noclip ...
                .then(Commands.literal("noclip")

                    // /backrooms noclip chance <player>  — lihat multiplier saat ini
                    .then(Commands.literal("chance")
                        .then(Commands.argument("player", EntityArgument.players())
                            .executes(ctx -> {
                                Collection<ServerPlayer> targets =
                                    EntityArgument.getPlayers(ctx, "player");
                                return noclipChanceInfo(ctx, targets);
                            })
                        )
                    )

                    // /backrooms noclip set <player> <multiplier>  — set multiplier langsung
                    .then(Commands.literal("set")
                        .then(Commands.argument("player", EntityArgument.players())
                            .then(Commands.argument("multiplier", IntegerArgumentType.integer(1, 1000))
                                .executes(ctx -> {
                                    Collection<ServerPlayer> targets =
                                        EntityArgument.getPlayers(ctx, "player");
                                    int mult = IntegerArgumentType.getInteger(ctx, "multiplier");
                                    return noclipSetMultiplier(ctx, targets, mult);
                                })
                            )
                        )
                    )

                    // /backrooms noclip add <player> <amount>  — tambah multiplier
                    .then(Commands.literal("add")
                        .then(Commands.argument("player", EntityArgument.players())
                            .then(Commands.argument("amount", IntegerArgumentType.integer(1, 1000))
                                .executes(ctx -> {
                                    Collection<ServerPlayer> targets =
                                        EntityArgument.getPlayers(ctx, "player");
                                    int amount = IntegerArgumentType.getInteger(ctx, "amount");
                                    return noclipAddMultiplier(ctx, targets, amount);
                                })
                            )
                        )
                    )

                    // /backrooms noclip reset <player>  — reset multiplier ke 1
                    .then(Commands.literal("reset")
                        .then(Commands.argument("player", EntityArgument.players())
                            .executes(ctx -> {
                                Collection<ServerPlayer> targets =
                                    EntityArgument.getPlayers(ctx, "player");
                                return noclipResetMultiplier(ctx, targets);
                            })
                        )
                    )
                )
        );

        BackroomsMod.LOGGER.info("[Backrooms] /backrooms command registered.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ENTER
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Teleport pemain (self) ke backrooms.
     * x/y/z nullable → gunakan posisi XZ pemain saat ini, Y = DEFAULT_FLOOR_Y.
     */
    private static int enterBackrooms(CommandContext<CommandSourceStack> ctx,
                                      Integer x, Integer y, Integer z,
                                      ServerPlayer overridePlayer) {
        CommandSourceStack source = ctx.getSource();

        ServerPlayer player;
        try {
            player = overridePlayer != null ? overridePlayer : source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cHarus dijalankan oleh player, atau sertakan argumen <player>."));
            return 0;
        }

        ServerLevel server = source.getServer().getLevel(ModDimensions.BACKROOMS_LEVEL);
        if (server == null) {
            source.sendFailure(Component.literal(
                "§c[Backrooms] Dimensi Backrooms tidak ditemukan! Cek file dimension JSON."));
            BackroomsMod.LOGGER.error("[Backrooms] BACKROOMS_LEVEL is null — dimension JSON missing?");
            return 0;
        }

        // Tentukan koordinat tujuan
        int destX = (x != null) ? x : player.getBlockX();
        int destZ = (z != null) ? z : player.getBlockZ();

        // Cari lantai solid — menghormati lubang pitfalls, tidak nulis blok
        int floorY = (y != null) ? y - 1 : BackroomsTeleporter.findSafeFloorY(server, destX, destZ);
        double spawnY = floorY + 1.0;
        int destY = (int) spawnY;

        // Buat transition
        DimensionTransition transition = new DimensionTransition(
                server,
                new Vec3(destX + 0.5, spawnY, destZ + 0.5),
                Vec3.ZERO,
                player.getYRot(),
                player.getXRot(),
                DimensionTransition.DO_NOTHING
        );

        player.changeDimension(transition);

        // Pesan immersif ke player
        player.sendSystemMessage(Component.literal("§c§lYou have noclipped out of reality."));
        player.sendSystemMessage(Component.literal("§e§oThe warm hum of fluorescent lights fills the air."));
        player.sendSystemMessage(Component.literal("§7§oGod save you if you hear something wandering nearby..."));

        // Feedback ke command sender
        String msg = String.format("§a[Backrooms] Teleported §e%s §ake Backrooms (§7%d, %d, %d§a)",
                player.getName().getString(), destX, destY, destZ);
        source.sendSuccess(() -> Component.literal(msg), true);

        BackroomsMod.LOGGER.info("[Backrooms] /backrooms enter → {} teleported to Backrooms @ {},{},{}",
                player.getName().getString(), destX, destY, destZ);

        return 1;
    }

    /** Wrapper untuk multiple players. */
    private static int enterBackroomsMultiple(CommandContext<CommandSourceStack> ctx,
                                               Collection<ServerPlayer> players,
                                               Integer x, Integer y, Integer z) {
        int count = 0;
        for (ServerPlayer p : players) {
            count += enterBackrooms(ctx, x, y, z, p);
        }
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LEAVE
    // ─────────────────────────────────────────────────────────────────────────

    /** Teleport player keluar dari backrooms kembali ke overworld. */
    private static int leaveBackrooms(CommandContext<CommandSourceStack> ctx,
                                      ServerPlayer overridePlayer) {
        CommandSourceStack source = ctx.getSource();

        ServerPlayer player;
        try {
            player = overridePlayer != null ? overridePlayer : source.getPlayerOrException();
        } catch (Exception e) {
            source.sendFailure(Component.literal("§cHarus dijalankan oleh player, atau sertakan argumen <player>."));
            return 0;
        }

        // Cek apakah player memang ada di backrooms
        if (!player.level().dimension().equals(ModDimensions.BACKROOMS_LEVEL)) {
            source.sendFailure(Component.literal(
                String.format("§c%s tidak berada di Backrooms.", player.getName().getString())));
            return 0;
        }

        ServerLevel overworld = source.getServer().getLevel(
                net.minecraft.world.level.Level.OVERWORLD);
        if (overworld == null) {
            source.sendFailure(Component.literal("§cOverworld tidak ditemukan!"));
            return 0;
        }

        // Spawn ke posisi overworld player (atau world spawn jika belum ada)
        BlockPos spawnPos = player.getRespawnPosition() != null
                ? player.getRespawnPosition()
                : overworld.getSharedSpawnPos();

        int destX = spawnPos.getX();
        int destY = spawnPos.getY();
        int destZ = spawnPos.getZ();

        DimensionTransition transition = new DimensionTransition(
                overworld,
                new Vec3(destX + 0.5, Y_SPAWN, destZ + 0.5),
                Vec3.ZERO,
                player.getYRot(),
                player.getXRot(),
                DimensionTransition.DO_NOTHING
        );

        // Reset noPhysics kalau masih aktif dari null zone
        player.noPhysics = false;
        player.changeDimension(transition);

        player.sendSystemMessage(Component.literal("§a§lYou have returned to reality."));
        player.sendSystemMessage(Component.literal("§7§oThe fluorescent humming fades..."));

        String msg = String.format("§a[Backrooms] §e%s §akembali ke Overworld.",
                player.getName().getString());
        source.sendSuccess(() -> Component.literal(msg), true);

        BackroomsMod.LOGGER.info("[Backrooms] /backrooms leave → {} returned to Overworld",
                player.getName().getString());

        return 1;
    }

    private static int leaveBackroomsMultiple(CommandContext<CommandSourceStack> ctx,
                                               Collection<ServerPlayer> players) {
        int count = 0;
        for (ServerPlayer p : players) {
            count += leaveBackrooms(ctx, p);
        }
        return count;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NOCLIP CHANCE
    // ─────────────────────────────────────────────────────────────────────────

    private static int noclipChanceInfo(CommandContext<CommandSourceStack> ctx,
                                         Collection<ServerPlayer> players) {
        CommandSourceStack source = ctx.getSource();
        for (ServerPlayer p : players) {
            int mult  = NoclipChanceManager.getMultiplier(p.getUUID());
            int denom = NoclipChanceManager.getEffectiveChanceDenominator(p.getUUID());
            source.sendSuccess(() -> Component.literal(String.format(
                "§e[Backrooms] §b%s §7— multiplier: §e%dx §7| chance: §e1/%d §7per tick (~%.1f mnt rata-rata)",
                p.getName().getString(), mult, denom, (denom / 20.0 / 60.0))), false);
        }
        return players.size();
    }

    private static int noclipSetMultiplier(CommandContext<CommandSourceStack> ctx,
                                            Collection<ServerPlayer> players, int multiplier) {
        CommandSourceStack source = ctx.getSource();
        for (ServerPlayer p : players) {
            NoclipChanceManager.setMultiplier(p.getUUID(), multiplier);
            int denom = NoclipChanceManager.getEffectiveChanceDenominator(p.getUUID());
            source.sendSuccess(() -> Component.literal(String.format(
                "§a[Backrooms] Set noclip multiplier §e%s §a→ §e%dx §7(1/%d per tick)",
                p.getName().getString(), multiplier, denom)), true);
            BackroomsMod.LOGGER.info("[Backrooms] noclip multiplier {} set to {}x by {}",
                p.getName().getString(), multiplier, source.getTextName());
        }
        return players.size();
    }

    private static int noclipAddMultiplier(CommandContext<CommandSourceStack> ctx,
                                            Collection<ServerPlayer> players, int amount) {
        CommandSourceStack source = ctx.getSource();
        for (ServerPlayer p : players) {
            int before = NoclipChanceManager.getMultiplier(p.getUUID());
            int after  = before + amount;
            NoclipChanceManager.setMultiplier(p.getUUID(), after);
            int denom  = NoclipChanceManager.getEffectiveChanceDenominator(p.getUUID());
            source.sendSuccess(() -> Component.literal(String.format(
                "§a[Backrooms] §e%s §amultiplier §7%d §a→ §e%dx §7(1/%d per tick)",
                p.getName().getString(), before, after, denom)), true);
            BackroomsMod.LOGGER.info("[Backrooms] noclip multiplier {} {}→{}x by {}",
                p.getName().getString(), before, after, source.getTextName());
        }
        return players.size();
    }

    private static int noclipResetMultiplier(CommandContext<CommandSourceStack> ctx,
                                              Collection<ServerPlayer> players) {
        CommandSourceStack source = ctx.getSource();
        for (ServerPlayer p : players) {
            NoclipChanceManager.resetMultiplier(p.getUUID());
            source.sendSuccess(() -> Component.literal(String.format(
                "§a[Backrooms] Noclip multiplier §e%s §adireset ke §e1x §7(chance default).",
                p.getName().getString())), true);
            BackroomsMod.LOGGER.info("[Backrooms] noclip multiplier {} reset by {}",
                p.getName().getString(), source.getTextName());
        }
        return players.size();
    }
}
