package net.atomos.zenith.block;

import net.atomos.zenith.ZenithMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class ZenithBlocks {
    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(ZenithMod.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ZenithMod.MOD_ID);

    public static final DeferredBlock<Block> FAN = BLOCKS.register("fan", FanBlock::new);
    public static final DeferredBlock<Block> DUCT = BLOCKS.register("duct", DuctBlock::new);
    public static final DeferredBlock<Block> WIND_VANE = BLOCKS.register("wind_vane", WindVaneBlock::new);
    public static final DeferredBlock<Block> WIND_TURBINE_PROBE =
            BLOCKS.register("wind_turbine_probe", WindTurbineProbeBlock::new);

    public static final Supplier<BlockEntityType<FanBlockEntity>> FAN_BE = BLOCK_ENTITIES.register(
            "fan", () -> BlockEntityType.Builder.of(FanBlockEntity::new, FAN.get()).build(null));
    public static final Supplier<BlockEntityType<DuctBlockEntity>> DUCT_BE = BLOCK_ENTITIES.register(
            "duct", () -> BlockEntityType.Builder.of(DuctBlockEntity::new, DUCT.get()).build(null));
    public static final Supplier<BlockEntityType<WindVaneBlockEntity>> WIND_VANE_BE = BLOCK_ENTITIES.register(
            "wind_vane", () -> BlockEntityType.Builder.of(WindVaneBlockEntity::new, WIND_VANE.get()).build(null));
    public static final Supplier<BlockEntityType<WindTurbineProbeBlockEntity>> TURBINE_PROBE_BE =
            BLOCK_ENTITIES.register("wind_turbine_probe",
                    () -> BlockEntityType.Builder.of(WindTurbineProbeBlockEntity::new,
                            WIND_TURBINE_PROBE.get()).build(null));

    private ZenithBlocks() {}

    private static BlockBehaviour.Properties metalProps() {
        return BlockBehaviour.Properties.of()
                .strength(3.5f, 6.0f)
                .requiresCorrectToolForDrops();
    }

    static BlockBehaviour.Properties fanProps() { return metalProps().lightLevel(s -> 0); }
    static BlockBehaviour.Properties ductProps() { return metalProps(); }
    static BlockBehaviour.Properties vaneProps() {
        return BlockBehaviour.Properties.of().strength(1.5f, 2.0f).noOcclusion();
    }
    static BlockBehaviour.Properties probeProps() { return metalProps().noOcclusion(); }
}
