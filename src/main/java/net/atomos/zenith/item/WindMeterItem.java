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
                double dirDeg = Math.toDegrees(Math.atan2(v.x(), v.z()));
                String compass = compass16(dirDeg);
                serverPlayer.displayClientMessage(Component.translatable("message.zenith.wind_meter",
                        String.format("%.1f", speed), compass,
                        String.format("%.0f", Math.toDegrees(dirDeg)),
                        String.format("%.0f%%", wind.turbulenceIntensity() * 100)), true);
            } else {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.zenith.wind_meter_unavailable"), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    private static String compass16(double dirDeg) {
        String[] names = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        // dirDeg: atan2(x, z)，0=南(+Z)；转成以北为0
        double north0 = (dirDeg + 180 + 360) % 360;
        // 这是风向（风的去向）；通常报来向，加180
        double from = (north0 + 180) % 360;
        return names[(int) Math.round(from / 22.5) % 16];
    }
}
