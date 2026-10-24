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
import net.minecraft.world.level.material.Fluids;
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
        if (hit.getType() != HitResult.Type.BLOCK) {
            return InteractionResultHolder.pass(stack);
        }

        // SAIL-10：`ClipContext.Fluid` 只影响流体，不影响方块——对草地/树叶/半砖右键同样会
        // 返回 Type.BLOCK。旧实现不校验命中点，于是能在草地上"放船"，船 tick 时找不到水面，
        // 一路掉进地里。这里要求命中方块本身是水（且是源水，因为用的是 SOURCE_ONLY）。
        if (!level.getFluidState(hit.getBlockPos()).is(Fluids.WATER)) {
            return InteractionResultHolder.pass(stack);
        }

        Vec3 pos = hit.getLocation();
        if (!level.isClientSide) {
            SailboatEntity boat = new SailboatEntity(ZenithEntities.SAILBOAT.get(), level);
            boat.setPos(pos.x, pos.y + 0.2, pos.z);
            // SAIL-08：艏向必须和放置朝向一致，否则服务端首 tick 会把船拧回正南。
            boat.setHeading((float) Math.toRadians(player.getYRot()));
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
}
