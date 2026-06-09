package com.backrooms.mod.sound;

import com.backrooms.mod.dimension.ModDimensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Memainkan ambient sound secara looping saat player berada di dimensi Backrooms.
 * Berhenti otomatis saat player keluar dari dimensi.
 *
 * Menggunakan AbstractTickableSoundInstance agar bisa dikontrol (stop/start)
 * dan di-tick tiap frame untuk cek apakah masih di backrooms.
 */
@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class BackroomsAmbientSound extends AbstractTickableSoundInstance {

    private boolean stopping = false;

    public BackroomsAmbientSound() {
        super(ModSounds.AMBIENT_AMBIENCE.get(), SoundSource.AMBIENT,
              SoundInstance.createUnseededRandom());
        this.looping  = true;
        this.delay    = 0;
        this.volume   = 0.5f;
        this.pitch    = 1.0f;
        // Posisi tidak relevan karena ini ambient — ikut player
        this.relative = true;
        this.x = 0; this.y = 0; this.z = 0;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null
                || !mc.player.level().dimension().equals(ModDimensions.BACKROOMS_LEVEL)) {
            // Keluar dari backrooms — stop sound
            this.stop();
            stopping = true;
        }
    }

    @Override
    public boolean canPlaySound()    { return !stopping; }
    @Override
    public boolean canStartSilent()  { return true; }

    // ── Event: cek tiap client tick apakah perlu start/stop sound ────────────

    private static BackroomsAmbientSound currentInstance = null;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        boolean inBackrooms = mc.level.dimension()
                                      .equals(ModDimensions.BACKROOMS_LEVEL);

        if (inBackrooms) {
            // Mulai sound kalau belum jalan
            if (currentInstance == null || currentInstance.isStopped()) {
                currentInstance = new BackroomsAmbientSound();
                mc.getSoundManager().play(currentInstance);
            }
        } else {
            // Stop sound kalau masih jalan
            if (currentInstance != null && !currentInstance.isStopped()) {
                mc.getSoundManager().stop(currentInstance);
                currentInstance = null;
            }
        }
    }
}
