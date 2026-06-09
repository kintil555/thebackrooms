package com.backrooms.mod.client;

import com.backrooms.mod.BackroomsMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Render overlay glitch saat player noclip masuk backrooms.
 *
 * Dipanggil dari NoclipOverlayPacket saat server kirim sinyal teleport.
 * Overlay tampil selama durationTicks tick (default 60 = 3 detik),
 * dengan animasi alpha yang:
 *   - fade IN cepat (0.3 detik pertama)
 *   - hold penuh (2.4 detik tengah)
 *   - fade OUT pelan (0.3 detik akhir)
 *
 * Efek glitch: opacity berkedip acak ±20% di fase tengah (simulating static).
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID, value = Dist.CLIENT)
public class NoclipOverlayRenderer {

    private static final ResourceLocation GLITCH_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "textures/overlay/noclip_glitch.png");

    /** Total durasi overlay dalam ticks. */
    private static int remainingTicks = 0;
    /** Durasi total saat trigger (untuk hitung progress). */
    private static int totalTicks = 0;

    /**
     * Dipanggil dari packet handler (main thread) untuk mulai overlay.
     */
    public static void trigger(int durationTicks) {
        totalTicks = durationTicks;
        remainingTicks = durationTicks;
        BackroomsMod.LOGGER.debug("[Backrooms] Noclip overlay triggered: {} ticks", durationTicks);
    }

    /** Cek apakah overlay sedang aktif. */
    public static boolean isActive() {
        return remainingTicks > 0;
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Pre event) {
        if (remainingTicks <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // ── Hitung alpha ────────────────────────────────────────────────────
        float progress = (float) remainingTicks / totalTicks; // 1.0 → 0.0

        // Phase: fade in (atas 85%), hold + flicker (tengah), fade out (bawah 15%)
        float alpha;
        float fadeInEnd   = 0.85f;  // top 15% = fade in
        float fadeOutStart = 0.15f; // bottom 15% = fade out

        if (progress > fadeInEnd) {
            // Fade IN: progress 1.0→0.85 → alpha 0→1
            alpha = (1.0f - progress) / (1.0f - fadeInEnd);
        } else if (progress < fadeOutStart) {
            // Fade OUT: progress 0.15→0 → alpha 1→0
            alpha = progress / fadeOutStart;
        } else {
            // HOLD + FLICKER: opacity berkedip acak ±0.2 untuk efek glitch
            float flicker = (float)(Math.sin(remainingTicks * 1.7f) * 0.12f
                                  + Math.cos(remainingTicks * 3.3f) * 0.08f);
            alpha = 0.88f + flicker;
        }

        alpha = Math.max(0f, Math.min(1f, alpha));

        // ── Render texture fullscreen ────────────────────────────────────────
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        RenderSystem.setShaderTexture(0, GLITCH_TEXTURE);

        graphics.blit(GLITCH_TEXTURE, 0, 0, 0, 0,
                screenW, screenH, screenW, screenH);

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();

        // ── Decrement tiap tick ──────────────────────────────────────────────
        // RenderGuiEvent dipanggil tiap frame, bukan tiap tick.
        // Kita gunakan partialTick untuk sinkronisasi ke 20 tick/detik.
        float partialTick = event.getPartialTick();
        // Decrement setiap ~1/20 detik (1 frame per 1 tick pada 20fps)
        // Untuk frame rate tinggi, hanya decrement saat partialTick reset
        if (partialTick < 0.1f) {
            remainingTicks--;
        }
    }
}
