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
 * Forge 1.21.1:
 * - RenderGuiEvent DIHAPUS (Mojang ganti ke layered rendering system)
 * - RenderTickEvent MASIH ADA tapi strukturnya berubah:
 *   event sekarang punya subclass Pre/Post, bukan field phase.
 *   Pakai TickEvent.RenderTickEvent.Post untuk render setelah semua selesai.
 * - DeltaTracker menggantikan partialTick langsung
 *
 * Tick decrement: pakai TickEvent.ClientTickEvent.Post (1x per game tick = 20/detik)
 * agar timer tepat 3 detik = 60 tick, tidak tergantung framerate.
 */
@Mod.EventBusSubscriber(modid = BackroomsMod.MOD_ID, value = Dist.CLIENT)
public class NoclipOverlayRenderer {

    private static final ResourceLocation GLITCH_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "textures/overlay/noclip_glitch.png");

    private static int remainingTicks = 0;
    private static int totalTicks     = 0;

    public static void trigger(int durationTicks) {
        totalTicks     = durationTicks;
        remainingTicks = durationTicks;
        BackroomsMod.LOGGER.debug("[Backrooms] Noclip overlay triggered: {} ticks", durationTicks);
    }

    public static boolean isActive() {
        return remainingTicks > 0;
    }

    /**
     * Decrement timer 1x per game tick (bukan per frame).
     * ClientTickEvent.Post = setelah tick logic selesai.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent.Post event) {
        if (remainingTicks > 0) {
            remainingTicks--;
        }
    }

    /**
     * Render overlay setiap frame saat aktif.
     * RenderTickEvent.Post = setelah semua world/entity rendering, sebelum frame di-present.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent.Post event) {
        if (remainingTicks <= 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();

        // ── Hitung alpha dari sisa tick ──────────────────────────────────────
        float progress = (float) remainingTicks / totalTicks; // 1.0→0.0

        float alpha;
        if (progress > 0.85f) {
            // Fase fade IN (15% pertama)
            alpha = (1.0f - progress) / 0.15f;
        } else if (progress < 0.15f) {
            // Fase fade OUT (15% terakhir)
            alpha = progress / 0.15f;
        } else {
            // Fase hold + flicker glitch
            float flicker = (float)(Math.sin(remainingTicks * 1.7f) * 0.12f
                                  + Math.cos(remainingTicks * 3.3f) * 0.08f);
            alpha = 0.88f + flicker;
        }
        alpha = Math.max(0f, Math.min(1f, alpha));

        // ── Render texture fullscreen ─────────────────────────────────────────
        GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);

        graphics.blit(GLITCH_TEXTURE, 0, 0, 0, 0,
                screenW, screenH, screenW, screenH);
        graphics.flush();

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
    }
}
