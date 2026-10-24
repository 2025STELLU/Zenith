package net.atomos.zenith.sound;

import net.atomos.zenith.ZenithMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ZenithSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, ZenithMod.MOD_ID);

    /** 环境风声循环（程序化合成的棕色噪声）。 */
    public static final Supplier<SoundEvent> WIND_LOOP = SOUND_EVENTS.register("wind_loop",
            () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(ZenithMod.MOD_ID, "wind_loop")));

    private ZenithSounds() {}

    public static void register(IEventBus bus) {
        SOUND_EVENTS.register(bus);
    }
}
