package com.backrooms.mod.client;

import com.backrooms.mod.BackroomsMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Post-processing style screen overlay saat player noclip ke Backrooms.
 *
 * FIX Forge 1.21.1:
 *   - RenderGuiEvent / RenderGuiOverlayEvent DIHAPUS di Forge 1.21.1
 *   - Solusi: pakai RegisterGuiOverlaysEvent (mod bus) + IGuiOverlay
 *   - IGuiOverlay.render() dipanggil oleh vanilla render pipeline secara benar,
 *     sehingga GuiGraphics context valid dan rendering tidak glitch
 *
 * Layer rendering:
 *   1. Base color fill  — hijau gelap transparan, alpha ikut progress
 *   2. Scanlines        — garis horizontal gelap setiap 3px (CRT effect)
 *   3. Flicker vignette — tepi layar lebih gelap, berflicker
 *   4. Corruption bars  — horizontal bar acak (glitch artifact)
 *   5. Texture overlay  — PNG di atas semua, jika tersedia (opsional)
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID, value = Dist.CLIENT)
public class NoclipOverlayRenderer {

    private static final ResourceLocation GLITCH_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "textures/overlay/noclip_glitch.png");

    private static volatile int remainingTicks = 0;
    private static volatile int totalTicks     = 0;
    private static volatile long seed          = 0;

    /**
     * Dipanggil dari server via network packet.
     * Trigger overlay glitch selama durationTicks.
     */
    public static void trigger(int durationTicks) {
        totalTicks     = durationTicks;
        remainingTicks = durationTicks;
        seed           = System.currentTimeMillis();
        BackroomsMod.LOGGER.info("[Backrooms] Noclip overlay triggered: {} ticks", durationTicks);
    }

    public static boolean isActive() {
        return remainingTicks > 0;
    }

    /**
     * Tick counter — dikurangi tiap client tick.
     * Pakai CLIENT event bus bawaan (@EventBusSubscriber sudah cukup).
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        if (remainingTicks > 0) remainingTicks--;
    }

    /**
     * Render overlay via RenderGuiEvent.Post (Forge 1.21.1+).
     * Dipanggil otomatis oleh MinecraftForge.EVENT_BUS karena @EventBusSubscriber.
     */
    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (remainingTicks <= 0) return;

        GuiGraphics guiGraphics = event.getGuiGraphics();
        int W = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int H = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            // Jangan render saat di screen (inventory, dll)
            if (mc.screen != null) return;

            float progress = (float) remainingTicks / Math.max(totalTicks, 1); // 1.0→0.0

            // ── Alpha utama ──────────────────────────────────────────────────
            float alpha;
            if (progress > 0.90f) {
                // Fade in: dari 0 ke 1 saat progress turun dari 1.0 ke 0.9
                alpha = (1.0f - progress) / 0.10f;
            } else if (progress < 0.15f) {
                // Fade out: dari 1 ke 0 saat progress turun dari 0.15 ke 0
                alpha = progress / 0.15f;
            } else {
                // Hold + flicker
                float flicker = (float)(
                        Math.sin(remainingTicks * 3.7f) * 0.07f
                      + Math.cos(remainingTicks * 7.3f) * 0.05f);
                float depth = 1.0f - (progress - 0.15f) / 0.75f; // 0→1
                alpha = 0.75f + depth * 0.15f + flicker;
            }
            alpha = Math.max(0f, Math.min(1f, alpha));

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            // ── Layer 1: Base green fill ──────────────────────────────────────
            // ARGB: hijau gelap transparan — #0A3C0A
            int baseAlphaInt = (int)(alpha * 180); // max 180/255 biar dunia masih kelihatan
            int baseColor = (baseAlphaInt << 24) | 0x000A3C0A;
            guiGraphics.fill(0, 0, W, H, baseColor);

            // ── Layer 2: Scanlines (CRT effect) ──────────────────────────────
            int scanAlpha = (int)(alpha * 60);
            int scanColor = (scanAlpha << 24); // pure black scanlines
            for (int y = 0; y < H; y += 3) {
                guiGraphics.fill(0, y, W, y + 1, scanColor);
            }

            // ── Layer 3: Vignette (tepi gelap kehijauan) ─────────────────────
            int vigAlpha = (int)(alpha * 120);
            int vigColor = (vigAlpha << 24) | 0x00001500; // hitam kehijauan
            int vigSize = W / 5;
            guiGraphics.fill(0, 0, vigSize, H, vigColor);
            guiGraphics.fill(W - vigSize, 0, W, H, vigColor);
            guiGraphics.fill(0, 0, W, H / 6, vigColor);
            guiGraphics.fill(0, H - H / 6, W, H, vigColor);

            // ── Layer 4: Corruption bars (glitch horizontal) ─────────────────
            if (progress > 0.15f && progress < 0.85f) {
                long r = seed ^ (remainingTicks * 6364136223846793005L + 1442695040888963407L);
                int numBars = 2 + (remainingTicks % 3);
                for (int i = 0; i < numBars; i++) {
                    r = r * 6364136223846793005L + 1442695040888963407L;
                    int barY = (int)(Math.abs(r % H));
                    r = r * 6364136223846793005L + 1442695040888963407L;
                    int barH = 1 + (int)(Math.abs(r % 4));
                    r = r * 6364136223846793005L + 1442695040888963407L;
                    int barX = (int)(Math.abs(r % (W / 2)));
                    r = r * 6364136223846793005L + 1442695040888963407L;
                    int barW = W / 4 + (int)(Math.abs(r % (W / 2)));

                    int barAlpha = (int)(alpha * 100);
                    boolean bright = (r & 1) == 0;
                    int barColor = bright
                        ? ((barAlpha << 24) | 0x0028FF28)  // hijau terang
                        : ((barAlpha << 24) | 0x00001200);  // hijau sangat gelap
                    guiGraphics.fill(barX, barY, Math.min(barX + barW, W), barY + barH, barColor);
                }
            }

            // ── Layer 5: Texture PNG overlay (opsional, garnish di atas semua) ─
            try {
                RenderSystem.setShaderColor(0.1f, 1.0f, 0.1f, alpha * 0.4f);
                guiGraphics.blit(GLITCH_TEXTURE, 0, 0, 0, 0, W, H, 1920, 1080);
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            } catch (Exception ignored) {
                // Texture tidak ada / corrupt → 4 layer di atas sudah cukup
            }

            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            RenderSystem.disableBlend();
        }
    }
}
