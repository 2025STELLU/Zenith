package net.atomos.zenith.weather;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/** 现象列表 ↔ NBT 的样板代码收拢（SYS-08）。 */
public final class WeatherNbt {
    private WeatherNbt() {}

    public interface Writer<T> {
        CompoundTag write(T item);
    }

    public interface Reader<T> {
        T read(CompoundTag tag);
    }

    public static <T> ListTag writeList(List<T> items, Writer<T> writer) {
        ListTag list = new ListTag();
        for (T item : items) list.add(writer.write(item));
        return list;
    }

    public static <T> List<T> readList(CompoundTag parent, String key, Reader<T> reader) {
        ListTag list = parent.getList(key, Tag.TAG_COMPOUND);
        List<T> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) out.add(reader.read(list.getCompound(i)));
        return out;
    }
}
