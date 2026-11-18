
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Facilities {
    // 경로선에서 이만큼 안에 있는 시설만 센다. 세 경로 모두 같은 기준
    public static final double MATCH_RADIUS = 10.0;

    private static final double LAT0  = 35.14;
    private static final double LON0  = 126.927;
    private static final double M_LAT = 111320.0;
    private static final double M_LON = 111320.0 * Math.cos(Math.toRadians(LAT0));
    private static final double CELL  = 100.0;

    private static final String[][] LAYER_FILES = {
            {"cctv",   "cctv.geojson"},
            {"light",  "light.geojson"},
            {"bell",   "bell.geojson"},
            {"police", "police.geojson"},
    };

    private static final String[] COUNT_KEYS = {"cnt", "count", "카메라대수", "설치개수"};

    private static final class Layer {
        double[] lat, lon;
        int[] cnt;
        double[] x, y;
        Map<Long, int[]> grid = new HashMap<>();
        int size() { return lat.length; }
    }

    private final Map<String, Layer> layers = new LinkedHashMap<>();

    public Facilities(Path dataDir) {
        for (String[] lf : LAYER_FILES) {
            String key = lf[0];
            Path p = dataDir.resolve(lf[1]);
            if (!Files.exists(p)) {
                System.out.println("[facilities] " + lf[1] + " 없음 — " + key + " 건너뜀");
                continue;
            }
            try {
                Layer L = loadGeoJson(p);
                buildGrid(L);
                layers.put(key, L);
                System.out.printf("[facilities] %s %,d지점%n", key, L.size());
            } catch (Exception e) {
                System.out.println("[facilities] " + lf[1] + " 읽기 실패: " + e.getMessage());
            }
        }
    }

    public Map<String, Integer> layerSizes() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Map.Entry<String, Layer> e : layers.entrySet()) m.put(e.getKey(), e.getValue().size());
        return m;
    }

    public Set<String> layerKeys() { return layers.keySet(); }

    private static Layer loadGeoJson(Path p) throws IOException {
        String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        Map<String, Object> root = Json.asMap(Json.parse(text));
        List<Object> feats = root == null ? null : Json.asList(root.get("features"));
        if (feats == null) throw new IOException("features 배열이 없습니다");

        List<double[]> pts = new ArrayList<>(feats.size());
        for (Object fo : feats) {
            Map<String, Object> f = Json.asMap(fo);
            if (f == null) continue;
            Map<String, Object> geom = Json.asMap(f.get("geometry"));
            if (geom == null || !"Point".equals(geom.get("type"))) continue;
            List<Object> c = Json.asList(geom.get("coordinates"));
            if (c == null || c.size() < 2) continue;

            double cnt = 1;
            Map<String, Object> props = Json.asMap(f.get("properties"));
            if (props != null) {
                for (String k : COUNT_KEYS) {
                    if (props.containsKey(k)) { cnt = Json.asDouble(props.get(k), 1); break; }
                }
            }

            pts.add(new double[]{Json.asDouble(c.get(1), 0), Json.asDouble(c.get(0), 0), cnt});
        }

        Map<String, double[]> merged = new LinkedHashMap<>();
        for (double[] q : pts) {
            String key = String.format("%.6f,%.6f", q[0], q[1]);
            double[] cur = merged.get(key);
            if (cur == null) merged.put(key, new double[]{q[0], q[1], q[2]});
            else cur[2] += q[2];
        }
        if (merged.size() < pts.size()) {
            System.out.printf("[facilities] %s 좌표 중복 %,d건 합침%n",
                    p.getFileName(), pts.size() - merged.size());
        }
        pts = new ArrayList<>(merged.values());

        Layer L = new Layer();
        int m = pts.size();
        L.lat = new double[m]; L.lon = new double[m]; L.cnt = new int[m];
        L.x = new double[m];   L.y = new double[m];
        for (int i = 0; i < m; i++) {
            double[] q = pts.get(i);
            L.lat[i] = q[0];
            L.lon[i] = q[1];
            L.cnt[i] = (int) Math.round(q[2]);
            L.x[i] = (q[1] - LON0) * M_LON;
            L.y[i] = (q[0] - LAT0) * M_LAT;
        }
        return L;
    }

    private static void buildGrid(Layer L) {
        Map<Long, List<Integer>> tmp = new HashMap<>();
        for (int i = 0; i < L.size(); i++) {
            tmp.computeIfAbsent(cellKey((int) Math.floor(L.x[i] / CELL),
                                        (int) Math.floor(L.y[i] / CELL)),
                                k -> new ArrayList<>()).add(i);
        }
        for (Map.Entry<Long, List<Integer>> e : tmp.entrySet()) {
            List<Integer> v = e.getValue();
            int[] arr = new int[v.size()];
            for (int i = 0; i < arr.length; i++) arr[i] = v.get(i);
            L.grid.put(e.getKey(), arr);
        }
    }

    private static long cellKey(int cx, int cy) {
        return (((long) cx) << 32) ^ (cy & 0xffffffffL);
    }

    public static final class Hit {
        public final double lat, lon;
        public final int cnt;
        Hit(double lat, double lon, int cnt) { this.lat = lat; this.lon = lon; this.cnt = cnt; }
    }

    public Map<String, List<Hit>> nearPolyline(List<double[]> poly) {
        Map<String, List<Hit>> result = new LinkedHashMap<>();
        for (String k : layers.keySet()) result.put(k, new ArrayList<>());
        if (poly.size() < 2) return result;

        int segCount = poly.size() - 1;
        double[] ax = new double[segCount], ay = new double[segCount];
        double[] bx = new double[segCount], by = new double[segCount], l2 = new double[segCount];
        Set<Long> cells = new HashSet<>();
        int span = (int) (MATCH_RADIUS / CELL) + 1;

        for (int s = 0; s < segCount; s++) {
            double[] p1 = poly.get(s), p2 = poly.get(s + 1);
            ax[s] = (p1[1] - LON0) * M_LON;
            ay[s] = (p1[0] - LAT0) * M_LAT;
            double ex = (p2[1] - LON0) * M_LON, ey = (p2[0] - LAT0) * M_LAT;
            bx[s] = ex - ax[s];
            by[s] = ey - ay[s];
            l2[s] = bx[s] * bx[s] + by[s] * by[s];

            int steps = Math.max(1, (int) (Math.hypot(bx[s], by[s]) / (CELL / 2)) + 1);
            for (int k = 0; k <= steps; k++) {
                double t = (double) k / steps;
                int cx = (int) Math.floor((ax[s] + bx[s] * t) / CELL);
                int cy = (int) Math.floor((ay[s] + by[s] * t) / CELL);
                for (int dx = -span; dx <= span; dx++) {
                    for (int dy = -span; dy <= span; dy++) cells.add(cellKey(cx + dx, cy + dy));
                }
            }
        }

        double r2 = MATCH_RADIUS * MATCH_RADIUS;

        for (Map.Entry<String, Layer> en : layers.entrySet()) {
            Layer L = en.getValue();
            List<Hit> found = result.get(en.getKey());
            Set<Integer> seen = new HashSet<>();

            for (Long c : cells) {
                int[] idxs = L.grid.get(c);
                if (idxs == null) continue;
                for (int i : idxs) {
                    if (!seen.add(i)) continue;
                    double px = L.x[i], py = L.y[i];
                    for (int s = 0; s < segCount; s++) {
                        double dx = px - ax[s], dy = py - ay[s];
                        double d2;
                        if (l2[s] == 0.0) {
                            d2 = dx * dx + dy * dy;
                        } else {
                            double t = (dx * bx[s] + dy * by[s]) / l2[s];
                            if (t < 0) t = 0; else if (t > 1) t = 1;
                            double qx = dx - t * bx[s], qy = dy - t * by[s];
                            d2 = qx * qx + qy * qy;
                        }
                        if (d2 <= r2) { found.add(new Hit(L.lat[i], L.lon[i], L.cnt[i])); break; }
                    }
                }
            }
        }
        return result;
    }
}
