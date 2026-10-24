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

import java.math.BigDecimal;
import java.math.RoundingMode;

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
                // 方位走 translatable：中文客户端显示"东北"，而不是写死的 "NE"（CMD-02）。
                // 数字在这里用 BigDecimal 定好位数（与服务器 Locale 无关，永远用 '.'），
                // 不再用 String.format("%.1f") 把格式硬编码进服务端字符串。
                serverPlayer.displayClientMessage(Component.translatable("message.zenith.wind_meter",
                        trim(speed, 1),
                        Component.translatable(compass16Key(fromBearing)),
                        String.valueOf(Math.round(fromBearing)),
                        Math.round(wind.turbulenceIntensity() * 100) + "%"), true);
            } else {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.zenith.wind_meter_unavailable"), true);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** 定点小数、去掉多余的 0，且不受默认 Locale 影响（{@code BigDecimal.toString} 永远是 '.'）。 */
    private static String trim(double value, int decimals) {
        if (!Double.isFinite(value)) return "0";
        return BigDecimal.valueOf(value)
                .setScale(decimals, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** bearing："北为0、顺时针"的罗盘角，转 16 方位的翻译键（CMD-02）。 */
    static String compass16Key(double bearing) {
        String[] keys = {"n", "nne", "ne", "ene", "e", "ese", "se", "sse",
                "s", "ssw", "sw", "wsw", "w", "wnw", "nw", "nnw"};
        int idx = (int) Math.round(bearing / 22.5);
        return "zenith.compass." + keys[Math.floorMod(idx, 16)];
    }
}
