package com.backrooms.mod.client;

import com.backrooms.mod.BackroomsMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Post-processing style screen overlay saat player noclip ke Backrooms.
 *
 * Mirip spectator creeper di Minecraft vanilla — pakai layered color fill
 * + scanlines programatik, tidak bergantung texture PNG sama sekali.
 *
 * Layer rendering:
 *   1. Base color fill  — hijau gelap transparan, alpha ikut progress
 *   2. Scanlines        — garis horizontal gelap setiap 3px (CRT effect)
 *   3. Flicker vignette — tepi layar lebih gelap, berflicker
 *   4. Corruption bars  — horizontal bar acak (glitch artifact)
 *   5. Texture overlay  — PNG di atas semua, jika tersedia (opsional)
 *
 * Fade timing:
 *   0–10%  : fade IN cepat
 *   10–85% : hold + flicker + depth effect
 *   85–100%: fade OUT smooth
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID, value = Dist.CLIENT)
public class NoclipOverlayRenderer {

    private static final ResourceLocation GLITCH_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "textures/overlay/noclip_glitch.png");

    private static int remainingTicks = 0;
    private static int totalTicks     = 0;
    private static long seed          = 0; // per-trigger random seed buat corruption bars

    public static void trigger(int durationTicks) {
        totalTicks     = durationTicks;
        remainingTicks = durationTicks;
        seed           = System.currentTimeMillis();
        BackroomsMod.LOGGER.info("[Backrooms] Noclip overlay triggered: {} ticks", durationTicks);
    }

    public static boolean isActive() {
        return remainingTicks > 0;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        if (remainingTicks > 0) remainingTicks--;
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent.Post event) {
        if (remainingTicks <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        int W = mc.getWindow().getGuiScaledWidth();
        int H = mc.getWindow().getGuiScaledHeight();

        float progress = (float) remainingTicks / totalTicks; // 1.0→0.0

        // ── Alpha utama ──────────────────────────────────────────────────────
        float alpha;
        if (progress > 0.90f) {
            alpha = (1.0f - progress) / 0.10f;                        // fade in
        } else if (progress < 0.15f) {
            alpha = progress / 0.15f;                                  // fade out
        } else {
            float flicker = (float)(
                    Math.sin(remainingTicks * 3.7f) * 0.07f
                  + Math.cos(remainingTicks * 7.3f) * 0.05f);
            float depth = 1.0f - (progress - 0.15f) / 0.75f;          // 0→1
            alpha = 0.75f + depth * 0.15f + flicker;
        }
        alpha = Math.max(0f, Math.min(1f, alpha));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        GuiGraphics g = new GuiGraphics(mc, mc.renderBuffers().bufferSource());

        // ── Layer 1: Base green fill ─────────────────────────────────────────
        // ARGB: alpha dikontrol manual, R=10, G=60, B=10 → hijau gelap like night-vision
        int baseAlphaInt = (int)(alpha * 180);  // max 180/255 supaya dunia masih kelihatan
        int baseColor = (baseAlphaInt << 24) | 0x000A3C0A; // #0A3C0A = hijau gelap
        g.fill(0, 0, W, H, baseColor);

        // ── Layer 2: Scanlines (CRT effect) ─────────────────────────────────
        // Garis gelap setiap 3 piksel — mirip monitor tabung
        int scanAlpha = (int)(alpha * 60);
        int scanColor = (scanAlpha << 24) | 0x00000000;
        for (int y = 0; y < H; y += 3) {
            g.fill(0, y, W, y + 1, scanColor);
        }

        // ── Layer 3: Vignette (tepi gelap) ──────────────────────────────────
        // Bikin corners lebih gelap, efek "filter" yang wrap seluruh layar
        int vigAlpha = (int)(alpha * 120);
        int vigColor = (vigAlpha << 24) | 0x00001500; // hitam kehijauan
        int vigSize = W / 5;
        // Kiri
        g.fill(0, 0, vigSize, H, vigColor);
        // Kanan
        g.fill(W - vigSize, 0, W, H, vigColor);
        // Atas
        g.fill(0, 0, W, H / 6, vigColor);
        // Bawah
        g.fill(0, H - H / 6, W, H, vigColor);

        // ── Layer 4: Corruption bars (glitch artifacts) ──────────────────────
        // Hanya aktif saat fase hold (progress 0.15–0.85)
        if (progress > 0.15f && progress < 0.85f) {
            // Pseudo-random dari seed + tick → posisi bar bergerak
            long r = seed ^ (remainingTicks * 6364136223846793005L + 1442695040888963407L);
            int numBars = 2 + (remainingTicks % 3); // 2–4 bars
            for (int i = 0; i < numBars; i++) {
                r = r * 6364136223846793005L + 1442695040888963407L;
                int barY  = (int)(Math.abs(r % H));
                r = r * 6364136223846793005L + 1442695040888963407L;
                int barH  = 1 + (int)(Math.abs(r % 4));   // 1–4 px tinggi
                r = r * 6364136223846793005L + 1442695040888963407L;
                int barX  = (int)(Math.abs(r % (W / 2)));  // mulai dari tengah-ish
                r = r * 6364136223846793005L + 1442695040888963407L;
                int barW  = W / 4 + (int)(Math.abs(r % (W / 2)));

                int barAlpha = (int)(alpha * 100);
                // Warna bar: kadang lebih terang (bright glitch), kadang gelap
                boolean bright = (r & 1) == 0;
                int barColor = bright
                    ? ((barAlpha << 24) | 0x0028FF28)  // hijau terang
                    : ((barAlpha << 24) | 0x00001200);  // hijau sangat gelap
                g.fill(barX, barY, Math.min(barX + barW, W), barY + barH, barColor);
            }
        }

        // ── Layer 5: Texture PNG overlay (opsional, di atas semua layer) ─────
        // Pakai tint hijau seperti sebelumnya, tapi sekarang ini hanya garnish
        // — bahkan kalau teksturnya corrupt/kecil, 4 layer di atas sudah cukup
        try {
            RenderSystem.setShaderColor(0.1f, 1.0f, 0.1f, alpha * 0.4f);
            g.blit(GLITCH_TEXTURE, 0, 0, 0, 0, W, H, 1920, 1080);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        } catch (Exception ignored) {
            // Texture tidak ada / corrupt → 4 layer di atas sudah cukup
        }

        g.flush();

        // Reset render state
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }
}
