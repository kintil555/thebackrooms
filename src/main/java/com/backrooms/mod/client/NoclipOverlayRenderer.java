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
 * Forge 1.21.1-52.1.0: RenderGuiEvent tidak ada — pakai RenderTickEvent.Post.
 *
 * Efek:
 *   - Fase 1 (0–10%): fade IN cepat, screen mulai ijo
 *   - Fase 2 (10–85%): hold + flicker + makin gelap seiring player turun
 *   - Fase 3 (85–100%): fade OUT saat teleport
 *
 * Texture: hijau pixelated + scanlines + glitch bars.
 * Tint warna: setShaderColor(R, G, B, alpha) → green channel dominan.
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID, value = Dist.CLIENT)
public class NoclipOverlayRenderer {

    private static final ResourceLocation GLITCH_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "textures/overlay/noclip_glitch.png");

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

    // Dikurangi 1x per game tick (20 ticks/detik)
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

        // progress: 1.0 saat baru trigger → 0.0 saat selesai
        float progress = (float) remainingTicks / totalTicks;

        float alpha;
        if (progress > 0.90f) {
            // Fase FADE IN: 10% pertama — muncul cepat
            alpha = (1.0f - progress) / 0.10f;
        } else if (progress < 0.15f) {
            // Fase FADE OUT: 15% terakhir
            alpha = progress / 0.15f;
        } else {
            // Fase HOLD: flicker intens saat player lagi turun nembus tanah
            float flicker = (float)(
                    Math.sin(remainingTicks * 3.7f) * 0.08f
                  + Math.cos(remainingTicks * 7.3f) * 0.05f
                  + Math.sin(remainingTicks * 1.1f) * 0.04f
            );
            // Makin lama makin solid (efek "masuk lebih dalam")
            float depthFactor = 1.0f - (progress - 0.15f) / 0.75f; // 0→1 seiring waktu
            alpha = 0.85f + depthFactor * 0.10f + flicker;
        }
        alpha = Math.max(0f, Math.min(1f, alpha));

        // Setup blend
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // Tint hijau: R=0.15, G=1.0, B=0.15 → texture jadi dominan hijau
        // Alpha dikontrol terpisah via parameter keempat
        RenderSystem.setShaderColor(0.15f, 1.0f, 0.15f, alpha);

        GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());

        // Render texture fullscreen
        graphics.blit(
                GLITCH_TEXTURE,
                0, 0,
                0, 0,
                screenW, screenH,
                TEX_W, TEX_H
        );

        graphics.flush();

        // Reset state — WAJIB agar render dunia berikutnya tidak ketint hijau
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }
}
