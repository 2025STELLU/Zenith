package net.atomos.zenith.api;

import java.util.ArrayList;
import java.util.List;

/** 极简翼型 JSON 解析：{"name":"...","points":[[x,y],...]}。 */
public final class ZenithAirfoilJson {
    private ZenithAirfoilJson() {}

    public static ZenithAirfoilDefinition parse(ZenithId id, String json) {
        List<ZenithAirfoilCoordinate> pts = new ArrayList<>();
        String name = id.path();
        int nameIdx = json.indexOf("\"name\"");
        if (nameIdx >= 0) {
            int c = json.indexOf(':', nameIdx), q1 = json.indexOf('"', c), q2 = json.indexOf('"', q1 + 1);
            if (q1 > 0 && q2 > q1) name = json.substring(q1 + 1, q2);
        }
        int ptsIdx = json.indexOf("\"points\"");
        if (ptsIdx >= 0) {
            int arr = json.indexOf('[', ptsIdx);
            int end = findMatching(json, arr);
            String body = json.substring(arr + 1, end);
            for (String pair : body.split("\\],\\s*\\[")) {
                String clean = pair.replaceAll("[\\[\\]\\s]", "");
                String[] xy = clean.split(",");
                if (xy.length == 2) {
                    try {
                        pts.add(new ZenithAirfoilCoordinate(
                                Double.parseDouble(xy[0]), Double.parseDouble(xy[1])));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        if (pts.isEmpty()) throw new IllegalArgumentException("No points in airfoil JSON");
        return new ZenithAirfoilDefinition(id, name, pts);
    }

    private static int findMatching(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) return i; }
        }
        return s.length();
    }
}
