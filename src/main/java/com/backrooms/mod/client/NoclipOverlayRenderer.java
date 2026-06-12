package com.backrooms.mod.client;

import com.backrooms.mod.BackroomsMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Render overlay glitch full-screen saat player noclip masuk Backrooms.
 *
 * Forge 1.21.1-52.1.0: RenderGuiEvent tidak ada (dihapus Mojang).
 * Pakai RenderTickEvent.Post + GuiGraphics manual + flush().
 *
 * Alpha dikontrol via RenderSystem.setShaderColor — ini yang paling reliable
 * di MC 1.21.1 untuk mengatur transparansi texture yang di-blit.
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID, value = Dist.CLIENT)
public class NoclipOverlayRenderer {

    private static final ResourceLocation GLITCH_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "textures/overlay/noclip_glitch.png");

    // Ukuran asli texture PNG (harus match ukuran file)
    private static final int TEX_W = 800;
    private static final int TEX_H = 533;

    private static int remainingTicks = 0;
    private static int totalTicks     = 0;

    public static void trigger(int durationTicks) {
        totalTicks     = durationTicks;
        remainingTicks = durationTicks;
        BackroomsMod.LOGGER.info("[Backrooms] Noclip overlay triggered: {} ticks", durationTicks);
    }

    public static boolean isActive() {
        return remainingTicks > 0;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        if (remainingTicks > 0) {
            remainingTicks--;
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent.Post event) {
        if (remainingTicks <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // Hitung alpha berdasarkan sisa ticks
        float progress = (float) remainingTicks / totalTicks; // 1.0 → 0.0

        float alpha;
        if (progress > 0.85f) {
            // Fade IN cepat (15% pertama dari durasi)
            alpha = (1.0f - progress) / 0.15f;
        } else if (progress < 0.15f) {
            // Fade OUT (15% terakhir)
            alpha = progress / 0.15f;
        } else {
            // Hold penuh — flicker ringan efek glitch
            float flicker = (float)(
                    Math.sin(remainingTicks * 2.3f) * 0.06f
                  + Math.cos(remainingTicks * 5.1f) * 0.04f
            );
            alpha = 0.92f + flicker;
        }
        alpha = Math.max(0f, Math.min(1f, alpha));

        // Setup blend state
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        // setShaderColor mengatur RGBA multiplier untuk semua blit berikutnya
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);

        // Buat GuiGraphics dan render texture fullscreen
        GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());

        // blit(texture, x, y, u, v, renderW, renderH, texW, texH)
        // Render dari (0,0) ke (screenW,screenH) — full screen stretch
        // u=0,v=0 ambil dari pojok kiri atas texture
        graphics.blit(
                GLITCH_TEXTURE,
                0, 0,
                0, 0,
                screenW, screenH,
                TEX_W, TEX_H
        );

        // Flush wajib — kirim semua vertex buffer ke GPU
        graphics.flush();

        // Reset state agar tidak mempengaruhi render berikutnya
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }
}
