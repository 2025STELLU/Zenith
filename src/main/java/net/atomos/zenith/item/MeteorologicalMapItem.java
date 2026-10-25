package net.atomos.zenith.item;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 气象图：右键打开气象图界面（客户端）。 */
public class MeteorologicalMapItem extends Item {
    public MeteorologicalMapItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            net.atomos.zenith.client.ClientHooks.openMeteorologicalMap();
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
