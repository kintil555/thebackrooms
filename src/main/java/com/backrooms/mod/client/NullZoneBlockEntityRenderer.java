package com.backrooms.mod.client;

import com.backrooms.mod.blockentity.NullZoneBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Renderer untuk NullZoneBlockEntity.
 *
 * Membuat ghost block tampak PERSIS seperti blok aslinya yang tersimpan
 * di NullZoneBlockEntity#originalBlockState.
 *
 * Cara kerja:
 *   Setiap frame, kita panggil BlockRenderDispatcher untuk merender
 *   originalBlockState di posisi block ini — hasilnya block terlihat
 *   normal di layar, tapi tidak punya collision.
 *
 * PENTING: Renderer ini berjalan di CLIENT side saja.
 */
public class NullZoneBlockEntityRenderer implements BlockEntityRenderer<NullZoneBlockEntity> {

    public NullZoneBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {
        // context tidak dipakai tapi diperlukan oleh constructor signature
    }

    @Override
    public void render(NullZoneBlockEntity blockEntity, float partialTick,
                       PoseStack poseStack, MultiBufferSource bufferSource,
                       int packedLight, int packedOverlay) {

        BlockState originalState = blockEntity.getOriginalBlockState();

        // Jangan render jika originalState adalah AIR atau null
        if (originalState == null || originalState.isAir()) return;
        if (originalState.is(Blocks.AIR)) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        // Render originalBlockState menggunakan BlockRenderDispatcher
        // Ini membuat block terlihat persis seperti aslinya
        mc.getBlockRenderer().renderSingleBlock(
                originalState,
                poseStack,
                bufferSource,
                packedLight,
                packedOverlay
        );
    }

    /**
     * Render bahkan ketika kamera berada di dalam block.
     * Diperlukan agar tetap kelihatan saat player menembus.
     */
    @Override
    public boolean shouldRenderOffScreen(NullZoneBlockEntity blockEntity) {
        return false;
    }

    @Override
    public int getViewDistance() {
        return 64; // Jarak render 64 blok (sama dengan block entity normal)
    }
}
