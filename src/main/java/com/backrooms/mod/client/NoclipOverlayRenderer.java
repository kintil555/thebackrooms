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
 * Render overlay glitch saat player noclip masuk backrooms.
 *
 * RenderGuiEvent DIHAPUS di Forge 1.21.1 (Mojang buat layered rendering system sendiri).
 * Solusi: pakai TickEvent.RenderTickEvent yang masih ada, buat GuiGraphics manual.
 *
 * Animasi alpha:
 *   - Fase 1 (15% awal): fade IN cepat
 *   - Fase 2 (70% tengah): hold + flicker glitch (sin/cos oscillation)
 *   - Fase 3 (15% akhir): fade OUT pelan
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID, value = Dist.CLIENT)
public class NoclipOverlayRenderer {

    private static final ResourceLocation GLITCH_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "textures/overlay/noclip_glitch.png");

    private static int remainingTicks = 0;
    private static int totalTicks     = 0;

    /** Dipanggil dari packet handler (main thread via enqueueWork). */
    public static void trigger(int durationTicks) {
        totalTicks     = durationTicks;
        remainingTicks = durationTicks;
        BackroomsMod.LOGGER.debug("[Backrooms] Noclip overlay triggered: {} ticks", durationTicks);
    }

    public static boolean isActive() {
        return remainingTicks > 0;
    }

    /**
     * RenderTickEvent masih ada di Forge 1.21.1.
     * Phase.END = setelah semua rendering dunia selesai, sebelum frame di-present.
     * Ini waktu yang tepat untuk render HUD fullscreen di atas segalanya.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (remainingTicks <= 0) return;

        Minecraft mc = Minecraft.getInstance();

        // Jangan render kalau tidak ada level (di menu utama dll)
        if (mc.level == null) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // ── Hitung alpha ─────────────────────────────────────────────────────
        float progress = (float) remainingTicks / totalTicks; // 1.0 → 0.0

        float alpha;
        float fadeInEnd    = 0.85f;
        float fadeOutStart = 0.15f;

        if (progress > fadeInEnd) {
            // Fade IN
            alpha = (1.0f - progress) / (1.0f - fadeInEnd);
        } else if (progress < fadeOutStart) {
            // Fade OUT
            alpha = progress / fadeOutStart;
        } else {
            // HOLD + FLICKER
            float flicker = (float)(Math.sin(remainingTicks * 1.7f) * 0.12f
                                  + Math.cos(remainingTicks * 3.3f) * 0.08f);
            alpha = 0.88f + flicker;
        }
        alpha = Math.max(0f, Math.min(1f, alpha));

        // ── Render ───────────────────────────────────────────────────────────
        // Buat GuiGraphics manual — ini cara yang benar di 1.21 setelah RenderGuiEvent dihapus
        var poseStack = new com.mojang.blaze3d.vertex.PoseStack();
        GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);

        graphics.blit(GLITCH_TEXTURE, 0, 0, 0, 0,
                screenW, screenH, screenW, screenH);

        // Flush buffer source agar render masuk ke frame
        graphics.flush();

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();

        // ── Decrement 1 per tick ─────────────────────────────────────────────
        // RenderTickEvent dipanggil tiap frame, tapi kita decrement tiap tick (20/detik).
        // Gunakan partialTick untuk tahu kapan tick baru mulai.
        if (event.renderTickTime <= 0.05f) {
            remainingTicks--;
        }
    }
}
