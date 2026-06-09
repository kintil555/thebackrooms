package com.backrooms.mod.network;

import com.backrooms.mod.client.NoclipOverlayRenderer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Packet server → client: trigger overlay glitch noclip.
 * durationTicks: berapa lama overlay tampil (default 80).
 *
 * Handler pakai Supplier<NetworkEvent.Context> — API yang berlaku di Forge 1.21.1.
 * PlayPayloadContext tidak ada di Forge (itu NeoForge API).
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

    public static void handle(NoclipOverlayPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() ->
            // DistExecutor memastikan ini hanya jalan di physical CLIENT
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> NoclipOverlayRenderer.trigger(msg.durationTicks))
        );
        ctx.setPacketHandled(true);
    }
}
