package net.atomos.zenith;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;

/**
 * 模组自定义伤害类型（WX-SC-01）。
 *
 * <p>旧实现用 {@code level.damageSources().generic()}：没有 {@code DamageType} 标签、
 * 没有死亡消息，第三方模组无法用 {@code DamageTypeTags} 做"冰雹抗性"之类的兼容。
 * 这里注册一个 {@code zenith:hail}（数据包定义见
 * {@code data/zenith/damage_type/hail.json}），走正常的伤害管线。
 */
public final class ZenithDamageTypes {
    /** 冰雹。数据包定义：{@code bypasses_armor = false}、{@code scaling = when_caused_by_living_non_player}。 */
    public static final ResourceKey<DamageType> HAIL = ResourceKey.create(
            Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(ZenithMod.MOD_ID, "hail"));

    private ZenithDamageTypes() {}

    /** 取冰雹伤害源；数据包缺失时退回 {@code generic}，绝不抛异常。 */
    public static DamageSource hail(ServerLevel level) {
        try {
            return level.damageSources().source(HAIL);
        } catch (RuntimeException missingDatapackEntry) {
            // 数据包被删/写错时不让整条冰雹路径炸掉
            return level.damageSources().generic();
        }
    }
}
