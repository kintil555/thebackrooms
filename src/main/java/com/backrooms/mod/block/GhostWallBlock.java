package com.backrooms.mod.block;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.dimension.ModDimensions;
import com.backrooms.mod.event.BackroomsTeleporter;
import com.backrooms.mod.world.BackroomsChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class GhostWallBlock extends Block {

    // Y=1 = lantai karpet. Y=6 = ceiling zone normal.
    private static final int Y_FLOOR = 1;
    // Spawn di Y=5 (tepat di bawah ceiling zone normal Y=6).
    // Player muncul dekat langit-langit → jatuh ke lantai → kena fall damage.
    private static final double Y_SPAWN = 5.0;

    public GhostWallBlock(Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level,
                                        BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level,
                               BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    public static void triggerBackroomsTransition(ServerPlayer player, ServerLevel serverLevel) {
        if (player.isOnPortalCooldown()) return;

        ServerLevel backroomsLevel = serverLevel.getServer().getLevel(ModDimensions.BACKROOMS_LEVEL);
        if (backroomsLevel == null) {
            BackroomsMod.LOGGER.error("[Backrooms] Backrooms dimension not found!");
            return;
        }

        player.setPortalCooldown(200);

        int originX = player.getBlockX();
        int originZ = player.getBlockZ();

        // ── FIX: Cari posisi XZ yang tidak berada di dalam dinding ──────────
        // Dapatkan chunk generator backrooms
        ChunkGenerator gen = backroomsLevel.getChunkSource().getGenerator();
        int spawnX = originX;
        int spawnZ = originZ;

        if (gen instanceof BackroomsChunkGenerator brGen) {
            int zone = brGen.getZoneAt(originX, originZ);
            int[] safeXZ = BackroomsChunkGenerator.findSafeSpawnXZ(originX, originZ, zone, brGen);
            spawnX = safeXZ[0];
            spawnZ = safeXZ[1];
            BackroomsMod.LOGGER.debug("[Backrooms] Safe spawn adjusted: ({},{}) → ({},{}) zone={}",
                    originX, originZ, spawnX, spawnZ, zone);
        }

        BackroomsTeleporter.ensureSafeRoom(backroomsLevel, spawnX, Y_FLOOR, spawnZ);

        DimensionTransition transition = new DimensionTransition(
                backroomsLevel,
                new Vec3(spawnX + 0.5, Y_SPAWN, spawnZ + 0.5), // spawn dekat ceiling → fall damage
                Vec3.ZERO,
                player.getYRot(),
                player.getXRot(),
                DimensionTransition.DO_NOTHING
        );

        // Teleport player ke backrooms
        player.changeDimension(transition);

        // Set fallDistance agar Minecraft apply fall damage saat player landing.
        // Y_SPAWN (5.0) - Y_FLOOR (1) = 4 blok jatuh → ~1 HP damage (4-3=1 damage point).
        // Lebih dari cukup untuk efek dramatis tanpa langsung membunuh.
        player.resetFallDistance();
        player.fallDistance = (float)(Y_SPAWN - Y_FLOOR); // ~4 blok → damage terjamin

        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§c§lYou have noclipped out of reality."));
        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§e§oThe warm hum of fluorescent lights fills the air."));
        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§7§oGod save you if you hear something wandering nearby..."));

        BackroomsMod.LOGGER.info("[Backrooms] {} noclipped → Backrooms @ X={} Z={}",
                player.getName().getString(), spawnX, spawnZ);
    }

    /**
     * Teleport player kembali ke Overworld dari Backrooms.
     * Dipanggil saat player noclip di dalam backrooms (menekan blok ghost wall
     * atau dari RandomNoclipHandler versi backrooms).
     *
     * FIX: Cari posisi overworld yang aman (di permukaan, tidak di dalam blok).
     * Gunakan world surface heightmap agar player tidak spawning di dalam tanah,
     * tidak jatuh dari ketinggian, dan tidak suffocate.
     */
    public static void triggerOverworldReturn(ServerPlayer player, ServerLevel backroomsLevel) {
        if (player.isOnPortalCooldown()) return;

        ServerLevel overworld = backroomsLevel.getServer()
                .getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (overworld == null) {
            BackroomsMod.LOGGER.error("[Backrooms] Overworld not found during return!");
            return;
        }

        player.setPortalCooldown(200);
        player.noPhysics = false;

        // Ambil posisi XZ player sebagai titik awal
        int originX = player.getBlockX();
        int originZ = player.getBlockZ();

        // ── Cari permukaan overworld yang aman ──────────────────────────────
        // Gunakan WORLD_SURFACE heightmap → Y paling atas yang tidak air dan tidak void.
        // Ini menjamin player tidak masuk dalam tanah (suffocation)
        // dan tidak jatuh dari ketinggian (karena kita spawn tepat di surface+0.1).
        BlockPos safePos = findSafeOverworldPos(overworld, originX, originZ);
        double spawnY = safePos.getY() + 0.1; // tepat di atas permukaan

        DimensionTransition transition = new DimensionTransition(
                overworld,
                new Vec3(safePos.getX() + 0.5, spawnY, safePos.getZ() + 0.5),
                Vec3.ZERO,
                player.getYRot(),
                player.getXRot(),
                DimensionTransition.DO_NOTHING
        );

        // Reset fall distance sebelum teleport → tidak ada fall damage di overworld
        player.resetFallDistance();
        player.changeDimension(transition);
        // Reset lagi setelah changeDimension untuk memastikan bersih
        player.resetFallDistance();

        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§a§lYou have noclipped back to reality."));
        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§7§oThe fluorescent hum fades... the sky returns."));

        BackroomsMod.LOGGER.info("[Backrooms] {} returned to Overworld @ X={} Y={} Z={}",
                player.getName().getString(), safePos.getX(), (int)spawnY, safePos.getZ());
    }

    /**
     * Cari posisi overworld yang aman.
     *
     * Strategi:
     * 1. Gunakan WORLD_SURFACE heightmap di posisi XZ asal.
     * 2. Cek apakah Y surface adalah blok yang bisa diinjak (bukan cairan, bukan bedrock void).
     * 3. Jika tidak aman (misal di dalam air, lava, atau di luar world border),
     *    spiral outward hingga radius 8 blok untuk cari posisi darat.
     * 4. Fallback ke world spawn jika tidak ada yang ditemukan.
     */
    private static BlockPos findSafeOverworldPos(ServerLevel overworld, int originX, int originZ) {
        // Coba posisi asal dulu
        BlockPos candidate = tryGetSafePos(overworld, originX, originZ);
        if (candidate != null) return candidate;

        // Spiral outward hingga 8 blok
        for (int r = 1; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    candidate = tryGetSafePos(overworld, originX + dx, originZ + dz);
                    if (candidate != null) return candidate;
                }
            }
        }

        // Fallback ke world spawn
        BlockPos spawnPos = overworld.getSharedSpawnPos();
        int spawnSurface = overworld.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
                spawnPos.getX(), spawnPos.getZ());
        BackroomsMod.LOGGER.warn("[Backrooms] Could not find safe overworld pos, using world spawn");
        return new BlockPos(spawnPos.getX(), spawnSurface, spawnPos.getZ());
    }

    /**
     * Coba dapatkan posisi aman di (wx, wz) menggunakan WORLD_SURFACE heightmap.
     * Return null jika posisi tidak aman (di air, lava, atau Y <= 60 = terlalu dalam).
     */
    private static BlockPos tryGetSafePos(ServerLevel overworld, int wx, int wz) {
        int surfaceY = overworld.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, wx, wz);

        // surfaceY = Y blok pertama dari atas yang ada isinya
        // Player harus berdiri di surfaceY (di atas blok ini)
        BlockPos floorPos = new BlockPos(wx, surfaceY - 1, wz);
        BlockState floorState = overworld.getBlockState(floorPos);

        // Tidak aman jika lantai adalah cairan atau bedrock void (Y=0)
        if (floorState.getFluidState().isEmpty() == false) return null; // di atas air/lava
        if (surfaceY <= 1) return null; // terlalu dalam atau void

        // Cek blok di posisi kepala dan kaki player tidak solid (tidak suffocate)
        BlockPos feetPos = new BlockPos(wx, surfaceY, wz);
        BlockPos headPos = new BlockPos(wx, surfaceY + 1, wz);
        BlockState feetState = overworld.getBlockState(feetPos);
        BlockState headState = overworld.getBlockState(headPos);

        // Jika kepala atau kaki dalam blok solid, tidak aman
        if (feetState.isSuffocating(overworld, feetPos)) return null;
        if (headState.isSuffocating(overworld, headPos)) return null;

        return floorPos; // posisi lantai yang aman, player akan spawn di Y+0.1
    }
}
