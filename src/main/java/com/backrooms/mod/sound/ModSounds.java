package com.backrooms.mod.sound;

import com.backrooms.mod.BackroomsMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
        DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, BackroomsMod.MOD_ID);

    public static final RegistryObject<SoundEvent> AMBIENT_AMBIENCE =
        SOUNDS.register("backrooms.ambient.ambience",
            () -> SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(BackroomsMod.MOD_ID, "backrooms.ambient.ambience")
            )
        );
}
