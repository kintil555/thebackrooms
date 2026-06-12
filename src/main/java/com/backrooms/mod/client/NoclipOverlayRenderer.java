package com.backrooms.mod.client;

import com.backrooms.mod.BackroomsMod;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Render overlay glitch full-screen saat player noclip masuk Backrooms.
 *
 * Forge 1.21.1 — cara render overlay GUI yang benar:
 *   RenderGuiEvent.Post → menyediakan GuiGraphics langsung dari engine.
 *   Jangan buat GuiGraphics manual — itu tidak flush ke framebuffer dengan benar.
 *
 * Texture 800×533 → di-stretch ke full screen via blit dengan texW/texH eksplisit.
 *
 * Timer: dikurangi di ClientTickEvent.Post (1x per game tick = 20/detik),
 * bukan di render tick agar tidak tergantung framerate.
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID, value = Dist.CLIENT)
public class NoclipOverlayRenderer {

    private static final ResourceLocation GLITCH_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "textures/overlay/noclip_glitch.png");

    // Ukuran asli texture PNG
    private static final int TEX_W = 800;
    private static final int TEX_H = 533;

    private static int remainingTicks = 0;
    private static int totalTicks     = 0;

    /** Dipanggil dari NoclipOverlayPacket saat server trigger noclip. */
    public static void trigger(int durationTicks) {
        totalTicks     = durationTicks;
        remainingTicks = durationTicks;
        BackroomsMod.LOGGER.debug("[Backrooms] Noclip overlay triggered: {} ticks", durationTicks);
    }

    public static boolean isActive() {
        return remainingTicks > 0;
    }

    // ─── Timer tick (1x per game tick, tidak tergantung framerate) ───────────

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        if (remainingTicks > 0) {
            remainingTicks--;
        }
    }

    // ─── Render overlay (setiap frame saat aktif) ─────────────────────────────

    /**
     * RenderGuiEvent.Post — event ini menyediakan GuiGraphics yang sudah
     * diinisialisasi benar oleh engine. Prioritas LOWEST agar render di atas
     * semua elemen HUD lain (hotbar, health, dll).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (remainingTicks <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        GuiGraphics graphics = event.getGuiGraphics();
        int screenW = graphics.guiWidth();
        int screenH = graphics.guiHeight();

        // ── Hitung alpha ──────────────────────────────────────────────────────
        float progress = (float) remainingTicks / totalTicks; // 1.0 → 0.0

        float alpha;
        if (progress > 0.85f) {
            // Fase fade IN — 15% pertama dari durasi
            alpha = (1.0f - progress) / 0.15f;
        } else if (progress < 0.15f) {
            // Fase fade OUT — 15% terakhir
            alpha = progress / 0.15f;
        } else {
            // Fase HOLD + flicker efek glitch
            float flicker = (float)(
                    Math.sin(remainingTicks * 1.7f) * 0.12f
                  + Math.cos(remainingTicks * 3.3f) * 0.08f
            );
            alpha = 0.88f + flicker;
        }
        alpha = Math.max(0f, Math.min(1f, alpha));

        // ── Render texture full-screen ─────────────────────────────────────────
        // GuiGraphics.blit di MC 1.21.1:
        //   blit(ResourceLocation, int x, int y, int u, int v,
        //        int width, int height, int texWidth, int texHeight)
        // Ini stretch texture dari (u,v)=(0,0) sampai ukuran texture asli
        // dan di-render ke layar dari (x,y) dengan ukuran (width,height).
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);

        graphics.blit(
                GLITCH_TEXTURE,
                0, 0,           // x, y di layar
                0, 0,           // u, v offset dalam texture
                screenW, screenH, // ukuran render di layar (stretch penuh)
                TEX_W, TEX_H    // ukuran asli texture untuk UV calculation
        );

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }
}
