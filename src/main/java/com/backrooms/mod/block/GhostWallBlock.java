package com.backrooms.mod.block;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.blockentity.NullZoneBlockEntity;
import com.backrooms.mod.dimension.ModDimensions;
import com.backrooms.mod.event.BackroomsTeleporter;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;

/**
 * Null Zone Ghost Block — block pengganti yang:
 *
 * 1. Terlihat PERSIS seperti blok asli yang digantikannya.
 *    Visual dihandle oleh NullZoneBlockEntityRenderer yang membaca
 *    originalBlockState dari NullZoneBlockEntity.
 *
 * 2. TIDAK punya collision — player bisa menembus (noclip).
 *
 * 3. TIDAK bisa dihancurkan (hardness -1).
 *
 * 4. Saat player jatuh tembus ke bedrock null zone → trigger teleport.
 *
 * LORE:
 *   Null Zone adalah area tak kasatmata di mana fabric of reality menipis.
 *   Blok yang ada di dalamnya masih terlihat normal, tapi sudah tidak solid.
 *   Player yang tidak waspada akan menembus dan "noclip out of reality".
 */
public class GhostWallBlock extends BaseEntityBlock {

    private static final int BACKROOMS_FLOOR_Y = 3;

    /** Required by BaseEntityBlock in 1.21.1 — used for block serialization. */
    public static final MapCodec<GhostWallBlock> CODEC = simpleCodec(GhostWallBlock::new);

    public GhostWallBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    // ─── BlockEntity ─────────────────────────────────────────────────────────

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NullZoneBlockEntity(pos, state);
    }

    /**
     * Gunakan INVISIBLE agar game tidak mencoba render model default ghost_wall.
     * Rendering asli dilakukan oleh NullZoneBlockEntityRenderer.
     */
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    // ─── No collision ─────────────────────────────────────────────────────────

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level,
                                        BlockPos pos, CollisionContext context) {
        return Shapes.empty(); // Player, mob, proyektil semua nembus
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level,
                               BlockPos pos, CollisionContext context) {
        return Shapes.block(); // Outline tetap blok penuh agar ray-cast / highlight normal
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    // ─── Teleport logic ───────────────────────────────────────────────────────

    /**
     * Dipanggil oleh NullZoneEventHandler saat player memenuhi kondisi noclip
     * (menyentuh bedrock dalam kolom null zone).
     */
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
