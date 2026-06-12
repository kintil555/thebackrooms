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
 * Forge 1.21.1 (52.1.0) — RenderGuiEvent, IGuiOverlay, dan RegisterGuiOverlaysEvent
 * SEMUANYA DIHAPUS oleh Mojang (layered rendering system baru, tidak accessible di 52.1.0).
 * Referensi: https://github.com/MinecraftForge/MinecraftForge/issues/10546
 *
 * Satu-satunya cara yang compile dan bekerja di 52.1.0:
 *   TickEvent.RenderTickEvent.Post → buat GuiGraphics manual → blit → flush.
 *
 * GuiGraphics harus di-flush() setelah render agar batch terkirim ke GPU.
 * Ini berbeda dari cara salah sebelumnya yang tidak set shader dengan benar.
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

    // ─── Timer tick — dikurangi 1x per game tick ──────────────────────────────

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        if (remainingTicks > 0) {
            remainingTicks--;
        }
    }

    // ─── Render overlay setiap frame ──────────────────────────────────────────

    /**
     * RenderTickEvent.Post — fired setiap frame setelah semua rendering selesai.
     *
     * Di Forge 1.21.1-52.1.0, ini satu-satunya event render yang ada dan compile.
     * GuiGraphics dibuat manual karena tidak ada event yang menyediakannya.
     *
     * Pattern yang benar:
     *   1. Buat GuiGraphics dengan mc.renderBuffers().bufferSource()
     *   2. Set RenderSystem state (blend, shader color)
     *   3. blit() texture
     *   4. WAJIB panggil graphics.flush() agar buffer terkirim ke GPU
     *   5. Reset RenderSystem state
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent.Post event) {
        if (remainingTicks <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        // Jangan render saat ada screen/inventory terbuka
        if (mc.screen != null) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // ── Hitung alpha ──────────────────────────────────────────────────────
        float progress = (float) remainingTicks / totalTicks; // 1.0 → 0.0

        float alpha;
        if (progress > 0.85f) {
            // Fade IN — 15% pertama
            alpha = (1.0f - progress) / 0.15f;
        } else if (progress < 0.15f) {
            // Fade OUT — 15% terakhir
            alpha = progress / 0.15f;
        } else {
            // Hold + flicker glitch
            float flicker = (float)(
                    Math.sin(remainingTicks * 1.7f) * 0.12f
                  + Math.cos(remainingTicks * 3.3f) * 0.08f
            );
            alpha = 0.88f + flicker;
        }
        alpha = Math.max(0f, Math.min(1f, alpha));

        // ── Render fullscreen ─────────────────────────────────────────────────
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);

        // Buat GuiGraphics dari bufferSource Minecraft yang aktif
        GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());

        // blit(texture, x, y, u, v, renderW, renderH, texW, texH)
        graphics.blit(
                GLITCH_TEXTURE,
                0, 0,         // posisi layar
                0, 0,         // u, v dalam texture
                screenW, screenH, // ukuran di layar (stretch penuh)
                TEX_W, TEX_H  // ukuran asli texture
        );

        // WAJIB: flush buffer ke GPU agar texture benar-benar muncul di layar
        graphics.flush();

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }
}
