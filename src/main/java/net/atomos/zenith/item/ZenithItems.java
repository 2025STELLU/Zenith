package net.atomos.zenith.item;

import net.atomos.zenith.ZenithMod;
import net.atomos.zenith.block.ZenithBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;

/** 物品注册。 */
public final class ZenithItems {
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(ZenithMod.MOD_ID);

    public static final DeferredItem<BlockItem> FAN =
            ITEMS.register("fan", () -> new BlockItem(ZenithBlocks.FAN.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> DUCT =
            ITEMS.register("duct", () -> new BlockItem(ZenithBlocks.DUCT.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> WIND_VANE =
            ITEMS.register("wind_vane", () -> new BlockItem(ZenithBlocks.WIND_VANE.get(), new Item.Properties()));
    public static final DeferredItem<BlockItem> WIND_TURBINE_PROBE =
            ITEMS.register("wind_turbine_probe",
                    () -> new BlockItem(ZenithBlocks.WIND_TURBINE_PROBE.get(), new Item.Properties()));

    public static final DeferredItem<Item> WIND_METER =
            ITEMS.register("wind_meter", WindMeterItem::new);
    public static final DeferredItem<Item> METEOROLOGICAL_MAP =
            ITEMS.register("meteorological_map", MeteorologicalMapItem::new);
    public static final DeferredItem<Item> SAILBOAT =
            ITEMS.register("sailboat", SailboatItem::new);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ZenithMod.MOD_ID);

    static {
        CREATIVE_TABS.register("zenith", () -> CreativeModeTab.builder()
                .title(net.minecraft.network.chat.Component.translatable("itemGroup.zenith"))
                .icon(() -> new ItemStack(WIND_METER.get()))
                .displayItems((params, output) -> {
                    output.accept(FAN.get());
                    output.accept(DUCT.get());
                    output.accept(WIND_VANE.get());
                    output.accept(WIND_TURBINE_PROBE.get());
                    output.accept(WIND_METER.get());
                    output.accept(METEOROLOGICAL_MAP.get());
                    output.accept(SAILBOAT.get());
                })
                .build());
    }

    private ZenithItems() {}
}
