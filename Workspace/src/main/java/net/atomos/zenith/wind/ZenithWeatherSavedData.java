package net.atomos.zenith.wind;

import net.atomos.zenith.weather.WeatherPhenomenon;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 每维度天气状态存档（SYS-08）。
 *
 * <p>旧实现没有任何 {@code SavedData}：台风、锋面、风暴、龙卷、驱动器相位全部在
 * 服务端重启时清零，同一个存档再进一次就是"另一场天气"。现在每个 {@link ServerLevel}
 * 自带一份 {@code zenith_weather.dat}，内容是驱动器 {@link WorldScaleDriver#save()}
 * 加上各现象的 {@code saveKey()/save()}。
 *
 * <p>只存 NBT 载荷，不存对象引用：{@link ZenithServerRuntime.DimensionState} 是懒创建的，
 * 可能在存档读出来之后才建，所以这里做成"先落盘、后 attach"。
 * {@code DimensionState.restored} 保证只装一次。
 */
public class ZenithWeatherSavedData extends SavedData {
    public static final String DATA_NAME = "zenith_weather";

    public static final SavedData.Factory<ZenithWeatherSavedData> FACTORY =
            new SavedData.Factory<>(ZenithWeatherSavedData::new,
                    (tag, provider) -> new ZenithWeatherSavedData(tag), null);

    private CompoundTag payload = new CompoundTag();

    public ZenithWeatherSavedData() {
    }

    private ZenithWeatherSavedData(CompoundTag tag) {
        this.payload = tag.getCompound("payload").copy();
    }

    /** 取（必要时创建）该维度的存档。 */
    public static ZenithWeatherSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** 服务端状态 → 本存档。调用方负责 {@link #setDirty()}。 */
    public void capture(WorldScaleDriver driver, Iterable<WeatherPhenomenon> phenomena) {
        CompoundTag root = new CompoundTag();
        root.put("driver", driver.save());
        for (WeatherPhenomenon p : phenomena) {
            String key = p.saveKey();
            if (key == null) continue;
            root.put(key, p.save());
        }
        this.payload = root;
    }

    /**
     * 本存档 → 服务端状态。没有存档内容（新世界）时什么都不做，
     * 让现象从构造器里的初始状态起步。
     */
    public void applyTo(WorldScaleDriver driver, Iterable<WeatherPhenomenon> phenomena) {
        if (payload.isEmpty()) return;
        // getCompound 在键缺失/类型不对时返回空 tag，比 contains(key, TAG_COMPOUND)
        // 少一个对 Tag 常量可见性的依赖（1.21 的常量在 Tag 接口上）。
        CompoundTag driverTag = payload.getCompound("driver");
        if (!driverTag.isEmpty()) {
            driver.load(driverTag);
        }
        for (WeatherPhenomenon p : phenomena) {
            String key = p.saveKey();
            if (key == null) continue;
            CompoundTag t = payload.getCompound(key);
            if (!t.isEmpty()) {
                p.load(t);
            }
        }
    }

    public boolean hasPayload() {
        return !payload.isEmpty();
    }

    /** 供调试/迁移：原始载荷。 */
    public CompoundTag payload() {
        return payload.copy();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("payload", payload.copy());
        return tag;
    }
}
