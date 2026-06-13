package com.backrooms.mod.client;

import com.backrooms.mod.BackroomsMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiLayersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Post-processing style screen overlay saat player noclip ke Backrooms.
 *
 * Forge 1.21.1: RenderGuiEvent dihapus.
 * Solusi: RegisterGuiOverlaysEvent (mod bus) + IGuiOverlay.
 */
public class NoclipOverlayRenderer {

    private static final ResourceLocation GLITCH_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "textures/overlay/noclip_glitch.png");

    private static volatile int remainingTicks = 0;
    private static volatile int totalTicks     = 0;
    private static volatile long seed          = 0;

    /** LayeredDraw.Layer — didaftarkan via RegisterGuiLayersEvent di mod bus */
    public static final LayeredDraw.Layer NOCLIP_OVERLAY = NoclipOverlayRenderer::renderOverlay;

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

    private static void renderOverlay(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        if (remainingTicks <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (mc.screen != null) return;

        int W = guiGraphics.guiWidth();
        int H = guiGraphics.guiHeight();

        float progress = (float) remainingTicks / Math.max(totalTicks, 1); // 1.0→0.0

        // ── Alpha utama ──────────────────────────────────────────────────
        float alpha;
        if (progress > 0.90f) {
            alpha = (1.0f - progress) / 0.10f;
        } else if (progress < 0.15f) {
            alpha = progress / 0.15f;
        } else {
            float flicker = (float)(
                    Math.sin(remainingTicks * 3.7f) * 0.07f
                  + Math.cos(remainingTicks * 7.3f) * 0.05f);
            float depth = 1.0f - (progress - 0.15f) / 0.75f;
            alpha = 0.75f + depth * 0.15f + flicker;
        }
        alpha = Math.max(0f, Math.min(1f, alpha));

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // ── Layer 1: Base green fill ──────────────────────────────────────
        int baseAlphaInt = (int)(alpha * 180);
        int baseColor = (baseAlphaInt << 24) | 0x000A3C0A;
        guiGraphics.fill(0, 0, W, H, baseColor);

        // ── Layer 2: Scanlines (CRT effect) ──────────────────────────────
        int scanAlpha = (int)(alpha * 60);
        int scanColor = (scanAlpha << 24);
        for (int y = 0; y < H; y += 3) {
            guiGraphics.fill(0, y, W, y + 1, scanColor);
        }

        // ── Layer 3: Vignette (tepi gelap kehijauan) ─────────────────────
        int vigAlpha = (int)(alpha * 120);
        int vigColor = (vigAlpha << 24) | 0x00001500;
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
                int barH2 = 1 + (int)(Math.abs(r % 4));
                r = r * 6364136223846793005L + 1442695040888963407L;
                int barX = (int)(Math.abs(r % (W / 2)));
                r = r * 6364136223846793005L + 1442695040888963407L;
                int barW = W / 4 + (int)(Math.abs(r % (W / 2)));

                int barAlpha = (int)(alpha * 100);
                boolean bright = (r & 1) == 0;
                int barColor = bright
                    ? ((barAlpha << 24) | 0x0028FF28)
                    : ((barAlpha << 24) | 0x00001200);
                guiGraphics.fill(barX, barY, Math.min(barX + barW, W), barY + barH2, barColor);
            }
        }

        // ── Layer 5: Texture PNG overlay (opsional) ─────────────────────
        try {
            RenderSystem.setShaderColor(0.1f, 1.0f, 0.1f, alpha * 0.4f);
            guiGraphics.blit(GLITCH_TEXTURE, 0, 0, 0, 0, W, H, 1920, 1080);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        } catch (Exception ignored) {
            // Texture tidak ada → 4 layer di atas sudah cukup
        }

        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        RenderSystem.disableBlend();
    }

    // ─── Kelas inner: event listener yang harus didaftarkan di MOD BUS ───────

    /**
     * Listener untuk mendaftarkan overlay ke vanilla pipeline.
     * Harus diregister ke MOD event bus (bukan MinecraftForge.EVENT_BUS).
     * Daftarkan via: modEventBus.addListener(NoclipOverlayRenderer.ClientEvents::onRegisterOverlays)
     * atau gunakan @EventBusSubscriber(bus = MOD, value = CLIENT).
     */
    @Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID, value = Dist.CLIENT,
                            bus = Mod.EventBusSubscriber.Bus.MOD)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onRegisterLayers(RegisterGuiLayersEvent event) {
            event.registerAboveAll(
                    ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "noclip_overlay"),
                    NOCLIP_OVERLAY);
        }
    }

    /**
     * Tick counter — didaftarkan ke FORGE event bus via @EventBusSubscriber default.
     */
    @Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID, value = Dist.CLIENT)
    public static class ClientForgeEvents {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
            if (remainingTicks > 0) remainingTicks--;
        }
    }
}
