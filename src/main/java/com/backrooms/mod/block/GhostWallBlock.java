package com.backrooms.mod.block;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.dimension.ModDimensions;
import com.backrooms.mod.event.BackroomsTeleporter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Ghost Wall Block — block cadangan untuk mekanisme null zone.
 *
 * CATATAN: Block ini TIDAK lagi ditempatkan di terrain overworld.
 * (Menempatkan block noOcclusion merusak face culling → lubang hitam di terrain.)
 *
 * Efek noclip sekarang diimplementasi via player.noPhysics di NullZoneEventHandler:
 * Terrain asli tetap utuh, player yang "menembus" block menggunakan noPhysics.
 *
 * Class ini dipertahankan untuk:
 * 1. triggerBackroomsTransition() — logic teleport ke backrooms
 * 2. Kemungkinan penggunaan manual di masa depan (creative mode, dll)
 */
public class GhostWallBlock extends Block {

    private static final int BACKROOMS_FLOOR_Y = 3;

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

    // ─── Teleport logic ───────────────────────────────────────────────────────

    public static void triggerBackroomsTransition(ServerPlayer player, ServerLevel serverLevel) {
        if (player.isOnPortalCooldown()) return;

        ServerLevel backroomsLevel = serverLevel.getServer().getLevel(ModDimensions.BACKROOMS_LEVEL);
        if (backroomsLevel == null) {
            BackroomsMod.LOGGER.error("[Backrooms] Backrooms dimension not found! Check dimension JSON.");
            return;
        }

        player.setPortalCooldown(200);

        int spawnX = player.getBlockX();
        int spawnZ = player.getBlockZ();

        BackroomsTeleporter.ensureSafeRoom(backroomsLevel, spawnX, BACKROOMS_FLOOR_Y, spawnZ);

        DimensionTransition transition = new DimensionTransition(
                backroomsLevel,
                new net.minecraft.world.phys.Vec3(spawnX + 0.5, BACKROOMS_FLOOR_Y, spawnZ + 0.5),
                net.minecraft.world.phys.Vec3.ZERO,
                player.getYRot(),
                player.getXRot(),
                DimensionTransition.DO_NOTHING
        );
        player.changeDimension(transition);

        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§c§lYou have noclipped out of reality."));
        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§e§oThe warm hum of fluorescent lights fills the air."));
        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§7§oGod save you if you hear something wandering nearby..."));

        BackroomsMod.LOGGER.info("[Backrooms] Player {} noclipped → Backrooms at X={} Z={}",
                player.getName().getString(), spawnX, spawnZ);
    }
}
