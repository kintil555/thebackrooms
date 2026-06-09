package com.backrooms.mod.network;

import com.backrooms.mod.client.NoclipOverlayRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.handling.PlayPayloadContext;

/**
 * Packet dari server → client untuk trigger overlay glitch noclip.
 *
 * durationTicks: berapa lama overlay tampil (60 ticks = 3 detik).
 */
public class NoclipOverlayPacket {

    /** Durasi overlay dalam ticks. */
    public final int durationTicks;

    public NoclipOverlayPacket(int durationTicks) {
        this.durationTicks = durationTicks;
    }

    public static void encode(NoclipOverlayPacket msg, FriendlyByteBuf buf) {
        buf.writeInt(msg.durationTicks);
    }

    public static NoclipOverlayPacket decode(FriendlyByteBuf buf) {
        return new NoclipOverlayPacket(buf.readInt());
    }

    public static void handle(NoclipOverlayPacket msg, PlayPayloadContext ctx) {
        // Sudah di main thread karena consumerMainThread
        NoclipOverlayRenderer.trigger(msg.durationTicks);
    }
}
