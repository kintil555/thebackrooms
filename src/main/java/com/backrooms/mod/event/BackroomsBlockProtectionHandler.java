package com.backrooms.mod.event;

import com.backrooms.mod.BackroomsMod;
import com.backrooms.mod.dimension.ModDimensions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Mencegah player menghancurkan blok yang di-generate oleh BackroomsChunkGenerator.
 *
 * Blok yang TIDAK BISA dihancurkan (generated terrain):
 *   - Bedrock (lantai & atap)
 *   - Brown Wool (karpet)
 *   - Cut Sandstone (baseboard)
 *   - Stripped Oak Log (dinding)
 *   - Smooth Stone (ceiling)
 *   - Ochre Froglight (lampu ceiling)
 *
 * Blok yang BISA dihancurkan (yang ditaruh player, dan bukan vanilla generated):
 *   Semua blok lain yang bukan bagian dari list di atas → boleh dihancurkan.
 *
 * Creative mode melewati proteksi ini (supaya admin/dev tetap bisa edit).
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID)
public class BackroomsBlockProtectionHandler {

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        // Hanya berlaku di dimensi backrooms
        Level level = (Level) event.getLevel();
        if (!level.dimension().equals(ModDimensions.BACKROOMS_LEVEL)) return;

        Player player = event.getPlayer();

        // Creative mode bisa hancurkan apapun (untuk debugging/building)
        if (player.isCreative()) return;

        BlockPos pos = event.getPos();
        BlockState state = event.getState();

        // Cek apakah blok ini adalah bagian dari generated terrain
        if (isGeneratedBlock(state)) {
            // Batalkan penghancuran
            event.setCanceled(true);

            // Kirim pesan ke player (hanya sekali tiap beberapa detik biar tidak spam)
            if (player instanceof ServerPlayer serverPlayer) {
                if (serverPlayer.tickCount % 20 == 0) {
                    serverPlayer.displayClientMessage(
                        Component.literal("§7[Backrooms] §cBlok ini tidak bisa dihancurkan."),
                        true // actionbar
                    );
                }
            }
        }
    }

    /**
     * Apakah blok ini bagian dari generated terrain backrooms?
     * Sesuai dengan blok yang dipakai BackroomsChunkGenerator.
     */
    private static boolean isGeneratedBlock(BlockState state) {
        return state.is(Blocks.BEDROCK)
            || state.is(Blocks.BROWN_WOOL)
            || state.is(Blocks.CUT_SANDSTONE)
            || state.is(Blocks.STRIPPED_OAK_LOG)
            || state.is(Blocks.SMOOTH_STONE)
            || state.is(Blocks.OCHRE_FROGLIGHT);
    }
}
