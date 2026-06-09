package com.backrooms.mod.network;

import com.backrooms.mod.client.NoclipOverlayRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.network.CustomPayloadEvent;
import net.minecraftforge.fml.DistExecutor;

/**
 * Packet server → client: trigger overlay glitch noclip.
 *
 * Forge 1.21.1: handler pakai BiConsumer<MSG, CustomPayloadEvent.Context>
 * NetworkEvent dan NetworkEvent.Context TIDAK ADA di Forge 1.21.1.
 * Gantinya: net.minecraftforge.event.network.CustomPayloadEvent.Context
 */
public class NoclipOverlayPacket {

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

    public static void handle(NoclipOverlayPacket msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() ->
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> NoclipOverlayRenderer.trigger(msg.durationTicks))
        );
        ctx.setPacketHandled(true);
    }
}
