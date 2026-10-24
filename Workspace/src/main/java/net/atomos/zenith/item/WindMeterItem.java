package net.atomos.zenith.item;

import net.atomos.zenith.api.GameplayWindSample;
import net.atomos.zenith.api.ZenithVec3;
import net.atomos.zenith.api.minecraft.ZenithMinecraftWindApi;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** 风速计：右键显示当前位置风速/风向/湍流。 */
public class WindMeterItem extends Item {
    public WindMeterItem() {
        super(new Properties().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            GameplayWindSample wind = ZenithMinecraftWindApi.sampleGameplay(
                    serverPlayer, serverPlayer.getEyePosition());
            if (wind.isTrustedForGameplay()) {
                ZenithVec3 v = wind.effectiveVelocityVector();
                double speed = v.length();
                // dirDeg：atan2(x,z) 的度数，0=南(+Z)、90=东(+X)；这是风的去向
                double dirDeg = Math.toDegrees(Math.atan2(v.x(), v.z()));
                // 转成"北为0、顺时针"的罗盘角：南0→180、东90→90（原来这里+180写反了，东西互换）
                double toBearing = (180 - dirDeg + 360) % 360;
                // 气象惯例报来向
                double fromBearing = (toBearing + 180) % 360;
                String compass = compass16(fromBearing);
                serverPlayer.displayClientMessage(Component.translatable("message.zenith.wind_meter",
                        String.format("%.1f", speed), compass,
                        String.format("%.0f", fromBearing),
                        String.format("%.0f%%", wind.turbulenceIntensity() * 100)), true);
            } else {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.zenith.wind_meter_unavailable"), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** bearing："北为0、顺时针"的罗盘角，转 16 方位名。 */
    private static String compass16(double bearing) {
        String[] names = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return names[(int) Math.round(bearing / 22.5) % 16];
    }
}
