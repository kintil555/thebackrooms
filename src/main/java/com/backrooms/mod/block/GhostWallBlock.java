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
 * Null Zone Block — berdasarkan lore Kane Pixels.
 *
 * Block ini sepenuhnya tak terlihat dan tidak memiliki collision (ghost block).
 * Player bisa jatuh/berjalan menembus block ini seperti noclip.
 *
 * Mekanisme teleport:
 * - Satu kolom vertikal dari permukaan sampai Y=-63 diisi ghost block
 * - Bedrock di Y=-64 tetap ada dan tidak bisa ditembus
 * - Ketika player berada di dalam ghost block dan Y-posisi mereka mendekati
 *   bedrock (Y <= -62), mereka diteleport ke dimensi Backrooms
 *
 * Kenapa tidak pakai entityInside() untuk bedrock check saja:
 * - Block tanpa collision (Shapes.empty) tidak selalu trigger entityInside
 *   dengan reliable di Forge 1.21.1 — pengecekan Y dilakukan di sini
 *   dan di NullZonePlayerTickHandler sebagai fallback.
 */
public class GhostWallBlock extends Block {

    public GhostWallBlock(Properties properties) {
        super(properties);
    }

    // ─── No collision — player falls/walks straight through ───────────────────

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level,
                                        BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    /**
     * Keeps a tiny non-empty visual shape so the block can still be
     * selected/placed by creative players, but renders as fully invisible
     * because the block has noOcclusion() + air() properties.
     * This also ensures entityInside() gets called reliably.
     */
    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level,
                               BlockPos pos, CollisionContext context) {
        // A 1/16 cube in the center — sub-pixel, effectively invisible
        // but non-empty so Minecraft still ticks entityInside() for it.
        return Block.box(7, 7, 7, 9, 9, 9);
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    // ─── Entity tick inside this block ────────────────────────────────────────

    /**
     * Called every tick while an entity is occupying this block's position.
     *
     * Trigger condition: player is a ServerPlayer AND their feet Y is at or
     * below -60 (close enough to bedrock at -64, accounting for fall speed).
     * We don't wait for bedrock contact because the player might fall fast.
     */
    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos,
                             Entity entity) {
        if (level.isClientSide()) return;
        if (!(entity instanceof ServerPlayer player)) return;
        if (!(level instanceof ServerLevel serverLevel)) return;

        // Trigger when player's feet Y reaches the bottom threshold
        // (within 4 blocks above bedrock at Y=-64)
        if (player.getBlockY() > -60) return;

        triggerBackroomsTransition(player, serverLevel);
    }

    // ─── Core teleport logic ──────────────────────────────────────────────────

    /**
     * Called both from entityInside() and from the external player tick handler.
     * Has portal cooldown guard to prevent double-fire.
     */
    public static void triggerBackroomsTransition(ServerPlayer player, ServerLevel serverLevel) {
        if (player.isOnPortalCooldown()) return;

        ServerLevel backroomsLevel = serverLevel.getServer().getLevel(ModDimensions.BACKROOMS_LEVEL);
        if (backroomsLevel == null) {
            BackroomsMod.LOGGER.error("[Backrooms] Backrooms dimension not found!");
            return;
        }

        // Set cooldown before teleport to prevent re-entry
        player.setPortalCooldown(200); // 10 seconds

        int spawnX = player.getBlockX();
        int spawnZ = player.getBlockZ();
        int spawnY = findSafeY(backroomsLevel, spawnX, spawnZ);

        BackroomsTeleporter.ensureSafeRoom(backroomsLevel, spawnX, spawnY, spawnZ);

        DimensionTransition transition = new DimensionTransition(
                backroomsLevel,
                new net.minecraft.world.phys.Vec3(spawnX + 0.5, spawnY, spawnZ + 0.5),
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
                net.minecraft.network.chat.Component.literal("§e§oThe warm hum of fluorescent lights surrounds you."));
        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§7§oGod save you if you hear something wandering nearby..."));

        BackroomsMod.LOGGER.info("[Backrooms] Player {} noclipped into The Backrooms at {},{},{}",
                player.getName().getString(), spawnX, spawnY, spawnZ);
    }

    private static int findSafeY(ServerLevel level, int x, int z) {
        // Backrooms floor starts at Y=1 (bedrock at Y=0, floor at Y=1)
        for (int y = 2; y < 15; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.getBlockState(pos).isAir()
                    && level.getBlockState(pos.above()).isAir()) {
                return y;
            }
        }
        return 2;
    }
}
