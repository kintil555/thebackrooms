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
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public class GhostWallBlock extends Block {

    // Y=1 = lantai karpet. Y=6 = ceiling zone normal.
    private static final int Y_FLOOR = 1;
    // Spawn di Y=5 (1 blok di bawah ceiling zone normal).
    // Player muncul tepat di bawah langit-langit → jatuh 4 blok ke lantai Y=1 → ~2 HP fall damage.
    // Di ZONE_VOID (ceiling Y=40), efek fall damage lebih besar karena ruang lebih tinggi —
    // tapi kita spawn dari Y tetap agar tidak menembus ceiling di zone normal.
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

        int spawnX = player.getBlockX();
        int spawnZ = player.getBlockZ();

        // Cek/fix chunk spawn — hanya bersihkan jika ada blok solid di area udara
        BackroomsTeleporter.ensureSafeRoom(backroomsLevel, spawnX, Y_FLOOR, spawnZ);

        DimensionTransition transition = new DimensionTransition(
                backroomsLevel,
                new Vec3(spawnX + 0.5, Y_SPAWN, spawnZ + 0.5), // spawn dekat ceiling
                Vec3.ZERO,
                player.getYRot(),
                player.getXRot(),
                DimensionTransition.DO_NOTHING
        );

        // Teleport player ke backrooms
        player.changeDimension(transition);

        // Paksa fall distance sehingga saat landing Minecraft hitung fall damage.
        // Y_SPAWN - Y_FLOOR - 3 (Minecraft mulai damage setelah 3 blok jatuh) = jarak efektif.
        // Set ke nilai yang lebih besar agar fall damage terasa (sekitar 3–6 HP).
        // resetFallDistance() dulu agar bersih, lalu set manual.
        player.resetFallDistance();
        player.fallDistance = (float)(Y_SPAWN - Y_FLOOR); // ~4 blok → damage terjamin saat landing

        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§c§lYou have noclipped out of reality."));
        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§e§oThe warm hum of fluorescent lights fills the air."));
        player.sendSystemMessage(
                net.minecraft.network.chat.Component.literal("§7§oGod save you if you hear something wandering nearby..."));

        BackroomsMod.LOGGER.info("[Backrooms] {} noclipped → Backrooms @ X={} Z={}",
                player.getName().getString(), spawnX, spawnZ);
    }
}
