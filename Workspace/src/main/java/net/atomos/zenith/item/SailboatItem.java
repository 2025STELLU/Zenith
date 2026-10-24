package net.atomos.zenith.item;

import net.atomos.zenith.entity.SailboatEntity;
import net.atomos.zenith.entity.ZenithEntities;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** 帆船物品：对水面右键放置帆船。 */
public class SailboatItem extends Item {
    public SailboatItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BlockHitResult hit = getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() == HitResult.Type.BLOCK) {
            Vec3 pos = hit.getLocation();
            if (!level.isClientSide) {
                SailboatEntity boat = new SailboatEntity(ZenithEntities.SAILBOAT.get(), level);
                boat.setPos(pos.x, pos.y + 0.2, pos.z);
                boat.setYRot(player.getYRot());
                // 避免重叠
                List<Entity> nearby = level.getEntities(boat, boat.getBoundingBox().inflate(0.5));
                if (nearby.stream().noneMatch(e -> e instanceof SailboatEntity)) {
                    level.addFreshEntity(boat);
                    if (!player.getAbilities().instabuild) stack.shrink(1);
                    player.awardStat(Stats.ITEM_USED.get(this));
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        return InteractionResultHolder.pass(stack);
    }
}
