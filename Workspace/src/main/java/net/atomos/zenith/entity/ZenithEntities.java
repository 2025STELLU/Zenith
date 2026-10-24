package net.atomos.zenith.entity;

import net.atomos.zenith.ZenithMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ZenithEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ZenithMod.MOD_ID);

    public static final Supplier<EntityType<SailboatEntity>> SAILBOAT = ENTITY_TYPES.register(
            "sailboat",
            () -> EntityType.Builder.of(SailboatEntity::new, MobCategory.MISC)
                    .sized(1.6f, 0.9f)
                    .clientTrackingRange(10)
                    .build(ResourceLocation.fromNamespaceAndPath(ZenithMod.MOD_ID, "sailboat").toString()));

    private ZenithEntities() {}
}
