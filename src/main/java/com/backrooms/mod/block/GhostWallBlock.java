package com.backrooms.mod.block;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.dimension.ModDimensions;
import com.backrooms.mod.event.BackroomsTeleporter;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Null Zone Block — sepenuhnya invisible dan tidak punya collision.
 * Player bisa jatuh/berjalan menembus block ini (noclip).
 *
 * Berdasarkan lore Kane Pixels:
 *   Null Zone = area di mana gelombang EM saling meniadakan,
 *   membuat portal sementara ke The Backrooms.
 *
 * Mekanisme:
 *   1. Satu kolom ghost block dari permukaan sampai Y=-63
 *   2. Player jatuh tembus (no collision)
 *   3. Ketika Y player <= -58, teleport ke Backrooms dimension
 *   4. Landing di ruangan backrooms yang sudah di-generate
 */
public class GhostWallBlock extends Block {

    // Y dimana backrooms floor berada (oak planks di Y=2, spawn player di Y=3)
    private static final int BACKROOMS_FLOOR_Y = 3;

    public GhostWallBlock(Properties properties) {
        super(properties);
    }

    // ─── No collision ─────────────────────────────────────────────────────────

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level,
                                        BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    /**
     * Micro-box kecil di tengah (tidak visible, tidak menghalangi apapun)
     * Diperlukan agar entityInside() terpanggil reliably di Forge 1.21.1.
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level,
                               BlockPos pos, CollisionContext context) {
        return Block.box(7, 7, 7, 9, 9, 9);
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    // ─── Trigger teleport saat player cukup dalam ─────────────────────────────

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (level.isClientSide()) return;
        if (!(entity instanceof ServerPlayer player)) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        // Trigger ketika player mendekati bedrock (Y <= -58)
        if (player.getBlockY() > -58) return;

        triggerBackroomsTransition(player, serverLevel);
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

        // Carve safe room lalu spawn player di floor level
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

        // Flavor text — Kane Pixels style
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
