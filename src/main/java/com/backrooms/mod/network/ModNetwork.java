package com.backrooms.mod.network;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.SimpleChannel;

/**
 * Registry untuk semua packet jaringan mod Backrooms.
 *
 * Menggunakan ChannelBuilder (Forge 1.21.1 API).
 * Handler menggunakan consumer() dengan Supplier<NetworkEvent.Context>
 * — bukan consumerMainThread (yang butuh PlayPayloadContext dari NeoForge).
 */
public class ModNetwork {

    private static final int PROTOCOL_VERSION = 1;

    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named(ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "main"))
            .networkProtocolVersion(PROTOCOL_VERSION)
            .clientAcceptedVersions((status, v) -> v == PROTOCOL_VERSION)
            .serverAcceptedVersions((status, v) -> v == PROTOCOL_VERSION)
            .simpleChannel();

    public static void register() {
        CHANNEL.messageBuilder(NoclipOverlayPacket.class, 0)
                .encoder(NoclipOverlayPacket::encode)
                .decoder(NoclipOverlayPacket::decode)
                .consumer(NoclipOverlayPacket::handle)   // pakai consumer(), bukan consumerMainThread()
                .add();
    }
}
