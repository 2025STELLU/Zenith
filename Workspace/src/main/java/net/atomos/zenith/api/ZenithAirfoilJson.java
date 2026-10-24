package net.atomos.zenith.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.List;

/**
 * 翼型 JSON 解析：{@code {"name":"...","points":[[x,y],...]}}。
 *
 * <p><b>API-09</b>：旧实现是手写的 {@code indexOf('"')} + {@code split("\\],\\s*\\[")}，
 * 有四个真实缺陷：① 没有冒号时 {@code indexOf('"', -1)} 从 0 开始，把第一个引号当名字起点；
 * ② 遇到 {@code \"} 转义直接截断；③ 数组未闭合时 {@code findMatching} 返回 {@code length()}，
 * 随后 {@code substring} 抛未包装的 {@code StringIndexOutOfBoundsException}；
 * ④ 依赖"两个数组之间恰好是 {@code ],[}"，遇到 {@code NaN}/{@code Infinity} 静默丢点。
 *
 * <p>现在用 Gson（MC 自带，无额外依赖）。所有错误统一包装成
 * {@link IllegalArgumentException}，调用方不需要 catch 乱七八糟的运行时异常。
 */
public final class ZenithAirfoilJson {
    private ZenithAirfoilJson() {}

    public static ZenithAirfoilDefinition parse(ZenithId id, String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Airfoil JSON is empty for " + id);
        }
        JsonObject root;
        try {
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                throw new IllegalArgumentException("Airfoil JSON root must be an object: " + id);
            }
            root = el.getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new IllegalArgumentException("Malformed airfoil JSON for " + id + ": " + e.getMessage(), e);
        }

        String name = id.path();
        JsonElement nameEl = root.get("name");
        if (nameEl != null && nameEl.isJsonPrimitive() && nameEl.getAsJsonPrimitive().isString()) {
            String s = nameEl.getAsString();
            if (!s.isBlank()) name = s;
        }

        JsonElement ptsEl = root.get("points");
        if (ptsEl == null || !ptsEl.isJsonArray()) {
            throw new IllegalArgumentException("Airfoil JSON is missing a \"points\" array: " + id);
        }
        List<ZenithAirfoilCoordinate> pts = new ArrayList<>();
        JsonArray arr = ptsEl.getAsJsonArray();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement p = arr.get(i);
            if (!p.isJsonArray()) {
                throw new IllegalArgumentException("Airfoil point #" + i + " is not an array: " + id);
            }
            JsonArray xy = p.getAsJsonArray();
            if (xy.size() != 2) {
                throw new IllegalArgumentException("Airfoil point #" + i + " must have 2 components: " + id);
            }
            pts.add(new ZenithAirfoilCoordinate(asFinite(xy.get(0), id, i),
                    asFinite(xy.get(1), id, i)));
        }
        if (pts.isEmpty()) throw new IllegalArgumentException("No points in airfoil JSON: " + id);
        return new ZenithAirfoilDefinition(id, name, pts);
    }

    /**
     * 取有限 double。Gson 的 {@code getAsDouble} 会把字符串 {@code "1.2"} 也解析出来，
     * 这里显式要求数值型；{@code NaN} 只可能是字符串（JSON 本身没有 NaN 字面量），
     * 所以非有限值就是坏数据，直接报错而不是静默丢点（旧实现会 {@code ignored}）。
     */
    private static double asFinite(JsonElement el, ZenithId id, int index) {
        if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Airfoil point #" + index + " has a non-numeric component: " + id);
        }
        double v = el.getAsDouble();
        if (!Double.isFinite(v)) {
            throw new IllegalArgumentException("Airfoil point #" + index + " is not finite: " + id);
        }
        return v;
    }
}
