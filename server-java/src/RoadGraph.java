
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 보행 도로망 그래프 (안드로이드 의존성 없음).
 * assets/graph_nodes.csv, assets/graph_edges.csv 를 읽어 인접 리스트(CSR)로 보관한다.
 * 간선(arc)은 방향이 있으며, 파이썬 export_graph.py 가 양방향을 모두 내보낸다.
 */
public class RoadGraph {

    public final int nodeCount;
    public final double[] lat, lon;

    public final int arcCount;
    public final int[] from, to;
    public final double[] length;     // 구간 길이(m)
    public final double[] safeCost;   // 안전 우선 경로용 통행 비용 (노트북의 비용 함수 결과)
    public final int[] cctv, light;   // 구간 주변 시설 수
    public final double[][] geom;     // 구간 꺾은선 [lat0,lon0,lat1,lon1,...], 없으면 null

    private final int[] adjStart;     // 노드별 나가는 간선 시작 위치
    private final int[] adjArcs;      // 나가는 간선 id 목록
    private final int[] reverseArc;   // 간선 a 의 반대 방향 간선 id (없으면 -1)

    private RoadGraph(double[] lat, double[] lon, int[] from, int[] to, double[] length,
                      double[] safeCost, int[] cctv, int[] light, double[][] geom) {
        this.nodeCount = lat.length;
        this.lat = lat;
        this.lon = lon;
        this.arcCount = from.length;
        this.from = from;
        this.to = to;
        this.length = length;
        this.safeCost = safeCost;
        this.cctv = cctv;
        this.light = light;
        this.geom = geom;

        adjStart = new int[nodeCount + 1];
        for (int a = 0; a < arcCount; a++) adjStart[from[a] + 1]++;
        for (int i = 0; i < nodeCount; i++) adjStart[i + 1] += adjStart[i];
        adjArcs = new int[arcCount];
        int[] fill = new int[nodeCount];
        for (int a = 0; a < arcCount; a++) {
            adjArcs[adjStart[from[a]] + fill[from[a]]++] = a;
        }

        Map<Long, Integer> byEnds = new HashMap<>();
        for (int a = 0; a < arcCount; a++) byEnds.put((long) from[a] * nodeCount + to[a], a);
        reverseArc = new int[arcCount];
        for (int a = 0; a < arcCount; a++) {
            Integer r = byEnds.get((long) to[a] * nodeCount + from[a]);
            reverseArc[a] = (r == null) ? -1 : r;
        }
    }

    /** 노드 n 에서 나가는 간선 id 범위 [start, end) 를 adjArcAt 으로 접근 */
    public int outStart(int n) { return adjStart[n]; }
    public int outEnd(int n)   { return adjStart[n + 1]; }
    public int adjArcAt(int i) { return adjArcs[i]; }

    // ───────────────────────── 로딩 ─────────────────────────

    public static RoadGraph load(InputStream nodesCsv, InputStream edgesCsv) throws IOException {
        // 노드
        Map<Long, Integer> idToIndex = new HashMap<>();
        List<double[]> nodes = new ArrayList<>();
        try (BufferedReader r = reader(nodesCsv)) {
            Map<String, Integer> col = header(r.readLine());
            int cId = col("id", col), cLat = col("lat", col), cLon = col("lon", col);
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] f = line.split(",", -1);
                idToIndex.put(Long.parseLong(f[cId].trim()), nodes.size());
                nodes.add(new double[]{Double.parseDouble(f[cLat]), Double.parseDouble(f[cLon])});
            }
        }
        double[] lat = new double[nodes.size()], lon = new double[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            lat[i] = nodes.get(i)[0];
            lon[i] = nodes.get(i)[1];
        }

        // 간선
        List<int[]> ends = new ArrayList<>();       // {from,to,cctv,light}
        List<double[]> costs = new ArrayList<>();   // {length,safeCost}
        List<double[]> geoms = new ArrayList<>();
        try (BufferedReader r = reader(edgesCsv)) {
            Map<String, Integer> col = header(r.readLine());
            int cU = col("u", col), cV = col("v", col), cLen = col("length", col),
                cCctv = col("cctv", col), cLight = col("light", col), cCost = col("safe_cost", col);
            Integer cGeom = col.get("geom");
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] f = line.split(",", -1);
                Integer u = idToIndex.get(Long.parseLong(f[cU].trim()));
                Integer v = idToIndex.get(Long.parseLong(f[cV].trim()));
                if (u == null || v == null) continue;
                ends.add(new int[]{u, v,
                        (int) Math.round(Double.parseDouble(f[cCctv])),
                        (int) Math.round(Double.parseDouble(f[cLight]))});
                costs.add(new double[]{Double.parseDouble(f[cLen]), Double.parseDouble(f[cCost])});
                geoms.add(cGeom != null && cGeom < f.length ? parseGeom(f[cGeom]) : null);
            }
        }
        int m = ends.size();
        int[] from = new int[m], to = new int[m], cctv = new int[m], light = new int[m];
        double[] length = new double[m], safeCost = new double[m];
        double[][] geom = new double[m][];
        for (int a = 0; a < m; a++) {
            from[a] = ends.get(a)[0];
            to[a] = ends.get(a)[1];
            cctv[a] = ends.get(a)[2];
            light[a] = ends.get(a)[3];
            length[a] = costs.get(a)[0];
            safeCost[a] = costs.get(a)[1];
            geom[a] = geoms.get(a);
        }
        return new RoadGraph(lat, lon, from, to, length, safeCost, cctv, light, geom);
    }

    /** "lat lon|lat lon|..." → [lat,lon,lat,lon,...] */
    private static double[] parseGeom(String s) {
        s = s.trim();
        if (s.isEmpty()) return null;
        String[] pts = s.split("\\|");
        double[] out = new double[pts.length * 2];
        for (int i = 0; i < pts.length; i++) {
            String[] ll = pts[i].trim().split("\\s+");
            out[2 * i] = Double.parseDouble(ll[0]);
            out[2 * i + 1] = Double.parseDouble(ll[1]);
        }
        return out;
    }

    private static BufferedReader reader(InputStream in) {
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    private static Map<String, Integer> header(String line) {
        if (line == null) throw new IllegalArgumentException("빈 CSV 파일입니다");
        if (line.startsWith("\uFEFF")) line = line.substring(1);
        Map<String, Integer> m = new HashMap<>();
        String[] h = line.split(",", -1);
        for (int i = 0; i < h.length; i++) m.put(h[i].trim(), i);
        return m;
    }

    private static int col(String name, Map<String, Integer> m) {
        Integer i = m.get(name);
        if (i == null) throw new IllegalArgumentException("CSV에 '" + name + "' 열이 없습니다");
        return i;
    }

    // ───────────────────────── 조회 ─────────────────────────

    /** 좌표에서 가장 가까운 노드 인덱스 */
    public int nearestNode(double la, double lo) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < nodeCount; i++) {
            double d = distanceMeters(la, lo, lat[i], lon[i]);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    public double distanceToNode(int node, double la, double lo) {
        return distanceMeters(la, lo, lat[node], lon[node]);
    }

    /** 근거리용 등장방형 근사 거리(m) */
    public static double distanceMeters(double la1, double lo1, double la2, double lo2) {
        double dLat = Math.toRadians(la2 - la1);
        double dLon = Math.toRadians(lo2 - lo1) * Math.cos(Math.toRadians((la1 + la2) / 2));
        return 6371000.0 * Math.sqrt(dLat * dLat + dLon * dLon);
    }

    /** 간선 경로를 지도에 그릴 좌표열 {lat,lon} 로 변환 (꺾은선 포함, 연속 중복점 제거) */
    public List<double[]> coordsOf(int[] arcs) {
        List<double[]> out = new ArrayList<>();
        for (int a : arcs) {
            double[] g = geom[a];
            if (g == null || g.length < 4) {
                add(out, lat[from[a]], lon[from[a]]);
                add(out, lat[to[a]], lon[to[a]]);
            } else {
                for (int i = 0; i < g.length; i += 2) add(out, g[i], g[i + 1]);
            }
        }
        return out;
    }

    // ─────────────── 도로 위로 붙이기(스냅) ───────────────

    /**
     * 탭한 지점을 가장 가까운 "도로 구간 위"의 한 점으로 보정한 결과.
     * 노드(교차로)가 아니라 구간 위의 점이라서, 구간 중간을 찍어도 오차가 거의 없다.
     */
    public static final class Snap {
        public final int arc;        // 이 점이 놓인 구간(같은 도로의 두 방향 중 번호가 작은 쪽)
        public final double frac;    // arc 의 출발 노드에서 도착 노드 쪽으로 0~1 (선 길이 기준)
        public final double lat, lon;// 도로 위로 보정된 좌표
        public final double distM;   // 탭한 지점 ~ 도로까지의 거리(m)

        Snap(int arc, double frac, double lat, double lon, double distM) {
            this.arc = arc;
            this.frac = frac;
            this.lat = lat;
            this.lon = lon;
            this.distM = distM;
        }
    }

    /** 구간 a 의 꺾은선 [lat0,lon0,lat1,lon1,...] (geometry 가 없으면 두 노드를 잇는 직선) */
    private double[] polyline(int a) {
        double[] g = geom[a];
        if (g != null && g.length >= 4) return g;
        return new double[]{lat[from[a]], lon[from[a]], lat[to[a]], lon[to[a]]};
    }

    /** 탭 좌표에서 가장 가까운 도로 구간 위의 점. 도로망이 비어 있으면 null */
    public Snap snapToRoad(double la, double lo) {
        final double mLat = 110540.0;
        final double mLon = 111320.0 * Math.cos(Math.toRadians(la));
        double best = Double.MAX_VALUE;
        int bestArc = -1, bestSeg = -1;
        double bestT = 0;

        for (int a = 0; a < arcCount; a++) {
            if (reverseArc[a] >= 0 && reverseArc[a] < a) continue;   // 같은 도로는 한 번만 검사
            double[] pl = polyline(a);
            for (int i = 0; i + 3 < pl.length; i += 2) {
                double ax = (pl[i + 1] - lo) * mLon, ay = (pl[i] - la) * mLat;
                double bx = (pl[i + 3] - lo) * mLon, by = (pl[i + 2] - la) * mLat;
                double dx = bx - ax, dy = by - ay;
                double len2 = dx * dx + dy * dy;
                double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
                double d = Math.hypot(ax + t * dx, ay + t * dy);
                if (d < best) { best = d; bestArc = a; bestSeg = i / 2; bestT = t; }
            }
        }
        if (bestArc < 0) return null;

        double[] pl = polyline(bestArc);
        double before = 0, total = 0;
        for (int i = 0; i + 3 < pl.length; i += 2) {
            double dx = (pl[i + 3] - pl[i + 1]) * mLon, dy = (pl[i + 2] - pl[i]) * mLat;
            double seg = Math.hypot(dx, dy);
            if (i / 2 < bestSeg) before += seg;
            if (i / 2 == bestSeg) before += seg * bestT;
            total += seg;
        }
        double frac = total == 0 ? 0 : before / total;
        double qLat = pl[2 * bestSeg] + bestT * (pl[2 * bestSeg + 2] - pl[2 * bestSeg]);
        double qLon = pl[2 * bestSeg + 1] + bestT * (pl[2 * bestSeg + 3] - pl[2 * bestSeg + 1]);
        return new Snap(bestArc, frac, qLat, qLon, best);
    }

    /** 구간 a 의 꺾은선에서 f0 지점부터 f1 지점까지(0~1, f0>f1 이면 거꾸로)의 좌표 {lat,lon} 목록 */
    private List<double[]> subPolyline(int a, double f0, double f1) {
        double[] pl = polyline(a);
        int n = pl.length / 2;
        double mLat = 110540.0, mLon = 111320.0 * Math.cos(Math.toRadians(pl[0]));
        double[] cum = new double[n];
        for (int i = 1; i < n; i++) {
            cum[i] = cum[i - 1] + Math.hypot((pl[2 * i + 1] - pl[2 * i - 1]) * mLon,
                                             (pl[2 * i] - pl[2 * i - 2]) * mLat);
        }
        double total = cum[n - 1];
        List<double[]> out = new ArrayList<>();
        if (total == 0) { out.add(new double[]{pl[0], pl[1]}); return out; }

        double d0 = f0 * total, d1 = f1 * total;
        out.add(pointAt(pl, cum, d0));
        if (d0 <= d1) {
            for (int i = 0; i < n; i++) if (cum[i] > d0 && cum[i] < d1) out.add(new double[]{pl[2 * i], pl[2 * i + 1]});
        } else {
            for (int i = n - 1; i >= 0; i--) if (cum[i] < d0 && cum[i] > d1) out.add(new double[]{pl[2 * i], pl[2 * i + 1]});
        }
        out.add(pointAt(pl, cum, d1));
        return out;
    }

    private static double[] pointAt(double[] pl, double[] cum, double d) {
        int n = cum.length;
        if (d <= 0) return new double[]{pl[0], pl[1]};
        if (d >= cum[n - 1]) return new double[]{pl[2 * n - 2], pl[2 * n - 1]};
        int i = 0;
        while (i + 1 < n && cum[i + 1] < d) i++;
        double seg = cum[i + 1] - cum[i];
        double t = seg == 0 ? 0 : (d - cum[i]) / seg;
        return new double[]{pl[2 * i] + t * (pl[2 * i + 2] - pl[2 * i]),
                            pl[2 * i + 1] + t * (pl[2 * i + 3] - pl[2 * i + 1])};
    }

    /**
     * 스냅한 출발·도착 지점을 포함한 경로 전체 좌표.
     * 출발 지점(도로 위) → 구간 일부 → 노드들 → 구간 일부 → 도착 지점(도로 위)
     */
    public List<double[]> routeCoords(Dijkstra.Result r) {
        List<double[]> out = new ArrayList<>();
        Snap s = r.from, t = r.to;
        if (s == null || t == null) return coordsOf(r.arcs);

        if (r.direct) {                       // 출발·도착이 같은 구간 위
            for (double[] p : subPolyline(s.arc, s.frac, t.frac)) add(out, p[0], p[1]);
            return out;
        }
        for (double[] p : (r.startViaFrom ? subPolyline(s.arc, s.frac, 0) : subPolyline(s.arc, s.frac, 1))) add(out, p[0], p[1]);
        for (double[] p : coordsOf(r.arcs)) add(out, p[0], p[1]);
        for (double[] p : (r.endViaFrom ? subPolyline(t.arc, 0, t.frac) : subPolyline(t.arc, 1, t.frac))) add(out, p[0], p[1]);
        return out;
    }

    private static void add(List<double[]> out, double la, double lo) {
        if (!out.isEmpty()) {
            double[] last = out.get(out.size() - 1);
            if (last[0] == la && last[1] == lo) return;
        }
        out.add(new double[]{la, lo});
    }
}
