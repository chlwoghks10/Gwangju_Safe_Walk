import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;

/**
 * 안전 경로 알고리즘 (API 독립) - safety.js의 Java 버전
 *
 * 흐름: 후보 경로 생성(경유지) -> 노드별 안전 점수 -> 경로 점수 -> 최선 선택
 * 경로 좌표를 받아오는 부분(RouteProvider)만 바꾸면 어떤 경로 API도 사용 가능.
 */
public final class SafeRoute {
    private SafeRoute() {}

    // ---------- 설정 ----------
    public enum Type {
        CCTV("CCTV", 1.0, 150),
        LIGHT("보안등", 0.3, 60),
        BELL("안전비상벨", 0.5, 120),
        POLICE("경찰서/지구대", 1.4, 300);

        public final String label;
        public final double weight;
        public final double radius;

        Type(String label, double weight, double radius) {
            this.label = label;
            this.weight = weight;
            this.radius = radius;
        }

        /** geojson 맵의 키 ("cctv", "light", "bell", "police") */
        public String key() { return name().toLowerCase(); }
    }

    public static class Options {
        public double step = 20;            // 경로 보간 간격(m)
        public double sRef = 2.5;           // 안전도 정규화 기준
        public double weakThreshold = 0.3;  // 이 값 미만이면 취약 노드
        public double alpha = 1.0;          // 안전 가중치 (0이면 최단 거리만)
        public double maxDetour = 1.35;     // 최단 대비 허용 우회율
        public double corridor = 300;       // 경유지 후보 탐색 폭(m)
        public int numVia = 4;              // 경유지 후보 개수
        public double minGap = 250;         // 경유지 후보 간 최소 간격(m)
        public double hitOffset = 60;       // "경로 위 시설"로 볼 수직거리(m)
        public double minWeakSeg = 60;      // 주의 구간 최소 연속 길이(m)
    }

    // 광주 범위 밖 좌표(이상치) 제거용
    private static final double LAT_MIN = 35.0, LAT_MAX = 35.30, LNG_MIN = 126.6, LNG_MAX = 127.1;

    // ---------- 데이터 타입 ----------
    /** 동일성(identity) 비교를 쓰기 위해 record 대신 일반 클래스 */
    public static final class Facility {
        public final double lat, lng, w, radius, cnt;
        public final Type type;
        public final String name, addr;

        Facility(double lat, double lng, Type type, double w, double radius, double cnt, String name, String addr) {
            this.lat = lat; this.lng = lng; this.type = type;
            this.w = w; this.radius = radius; this.cnt = cnt;
            this.name = name; this.addr = addr;
        }

        LatLng pos() { return new LatLng(lat, lng); }
    }

    public record Score(double distance, double meanSafety, double weakRatio, double deficit, double total) {}

    public record RouteResult(String label, List<LatLng> path, Score score) {}

    public record PlanResult(RouteResult best, RouteResult shortest, List<RouteResult> all) {}

    public record Hit(Facility facility, double along, double offset) {}

    /** waypoints = [출발, (경유지), 도착] -> 경로 좌표 */
    @FunctionalInterface
    public interface RouteProvider {
        List<LatLng> getPath(List<LatLng> waypoints) throws Exception;
    }

    // ---------- 기본 유틸 ----------
    private static final double R_EARTH = 6371000;

    public static double haversine(LatLng a, LatLng b) {
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double x = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.pow(Math.sin(dLng / 2), 2);
        return 2 * R_EARTH * Math.asin(Math.sqrt(x));
    }

    // ---------- 시설 로딩 ----------
    /** geo: {"cctv": FeatureCollection, "bell": ..., "police": ...} */
    public static List<Facility> toFacilities(Map<String, JsonObject> geo) {
        List<Facility> out = new ArrayList<>();
        for (Type type : Type.values()) {
            JsonObject fc = geo.get(type.key());
            if (fc == null) continue;

            // 공공데이터는 같은 자리를 여러 줄로 나눠 적는다. 먼저 좌표별로 대수를 합쳐야
            // 아래 로그 포화가 줄마다 따로 걸리지 않는다
            Map<String, double[]> bySpot = new LinkedHashMap<>();
            Map<String, String[]> nameOf = new LinkedHashMap<>();
            for (JsonElement el : fc.getAsJsonArray("features")) {
                JsonObject f = el.getAsJsonObject();
                JsonArray c = f.getAsJsonObject("geometry").getAsJsonArray("coordinates");
                double lng = c.get(0).getAsDouble();
                double lat = c.get(1).getAsDouble();
                if (lat < LAT_MIN || lat > LAT_MAX || lng < LNG_MIN || lng > LNG_MAX) continue;

                JsonObject p = f.has("properties") && f.get("properties").isJsonObject()
                        ? f.getAsJsonObject("properties") : new JsonObject();
                double cnt = num(p, 1, "cnt", "수량", "count");

                String key = String.format("%.6f,%.6f", lat, lng);
                double[] cur = bySpot.get(key);
                if (cur == null) {
                    bySpot.put(key, new double[]{lat, lng, cnt});
                    nameOf.put(key, new String[]{str(p, "name"),
                            firstStr(p, "addr", "소재지도로명주소", "설치위치", "place")});
                } else {
                    cur[2] += cnt;
                }
            }

            for (Map.Entry<String, double[]> e : bySpot.entrySet()) {
                double[] v = e.getValue();
                double cnt = v[2];

                // 한 지점에 여러 대 있는 경우 로그로 포화 (1대=1.0, 4대=1.5, 16대=2.0)
                double mult = (type == Type.CCTV || type == Type.LIGHT)
                        ? 1 + 0.5 * (Math.log(cnt) / Math.log(4)) : 1;

                String[] nm = nameOf.get(e.getKey());
                out.add(new Facility(v[0], v[1], type, type.weight * mult, type.radius, cnt,
                        nm[0], nm[1]));
            }
        }
        return out;
    }

    /** 키 목록 중 처음으로 "0이 아닌 숫자"로 읽히는 값. 없으면 def */
    private static double num(JsonObject p, double def, String... keys) {
        for (String k : keys) {
            JsonElement e = p.get(k);
            if (e == null || e.isJsonNull()) continue;
            try {
                double v = Double.parseDouble(e.getAsString().trim());
                if (v != 0 && !Double.isNaN(v)) return v;
            } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static String str(JsonObject p, String key) {
        JsonElement e = p.get(key);
        return (e == null || e.isJsonNull()) ? "" : e.getAsString();
    }

    private static String firstStr(JsonObject p, String... keys) {
        for (String k : keys) {
            String s = str(p, k);
            if (!s.isEmpty()) return s;
        }
        return "";
    }

    // ---------- 격자 인덱스 ----------
    // 셀 크기 > 최대 반경(300m)이므로 주변 3x3 셀만 보면 충분
    private static final double CELL_LAT = 0.0032;  // 약 354m
    private static final double CELL_LNG = 0.0039;  // 약 355m (위도 35도)

    public static final class Index {
        final Map<String, List<Facility>> grid = new HashMap<>();
        final List<Facility> facilities;

        Index(List<Facility> facilities) { this.facilities = facilities; }
    }

    private static String cellKey(long i, long j) { return i + "," + j; }

    public static Index buildIndex(List<Facility> facilities) {
        Index idx = new Index(facilities);
        for (Facility f : facilities) {
            String k = cellKey((long) Math.floor(f.lat / CELL_LAT), (long) Math.floor(f.lng / CELL_LNG));
            idx.grid.computeIfAbsent(k, x -> new ArrayList<>()).add(f);
        }
        return idx;
    }

    @FunctionalInterface
    private interface Visitor { void visit(Facility f, double dist); }

    /** 주변 3x3 셀의 시설을 순회 (nodeScore / facilitiesAlong 공용) */
    private static void eachNearby(LatLng p, Index idx, Visitor fn) {
        long ci = (long) Math.floor(p.lat() / CELL_LAT);
        long cj = (long) Math.floor(p.lng() / CELL_LNG);
        for (long i = ci - 1; i <= ci + 1; i++) {
            for (long j = cj - 1; j <= cj + 1; j++) {
                List<Facility> cell = idx.grid.get(cellKey(i, j));
                if (cell == null) continue;
                for (Facility f : cell) fn.visit(f, haversine(p, f.pos()));
            }
        }
    }

    // ---------- 노드 안전 점수 ----------
    public static double nodeScore(LatLng p, Index idx) {
        double[] s = {0};
        eachNearby(p, idx, (f, d) -> {
            if (d < f.radius) s[0] += f.w * (1 - d / f.radius);
        });
        return s[0];
    }

    // ---------- 경로 보간 ----------
    /** 경로 좌표를 step(m) 간격으로 촘촘하게 */
    public static List<LatLng> resample(List<LatLng> path, double step) {
        if (path.size() < 2) return new ArrayList<>(path);
        List<LatLng> out = new ArrayList<>();
        out.add(path.get(0));
        for (int i = 0; i < path.size() - 1; i++) {
            LatLng a = path.get(i), b = path.get(i + 1);
            double d = haversine(a, b);
            long n = Math.max(1, Math.round(d / step));
            for (long k = 1; k <= n; k++) {
                double t = (double) k / n;
                out.add(new LatLng(a.lat() + (b.lat() - a.lat()) * t, a.lng() + (b.lng() - a.lng()) * t));
            }
        }
        return out;
    }

    public static double pathLength(List<LatLng> path) {
        double len = 0;
        for (int i = 0; i < path.size() - 1; i++) len += haversine(path.get(i), path.get(i + 1));
        return len;
    }

    // ---------- 경로 점수 ----------
    /**
     * total = 거리 * (1 + alpha * deficit)
     * deficit = 0.5 * (1 - 평균안전도) + 0.5 * 취약노드비율
     */
    public static Score scoreRoute(List<LatLng> path, Index idx, Options o) {
        List<LatLng> pts = resample(path, o.step);
        double sum = 0;
        int weakCnt = 0;
        for (LatLng p : pts) {
            double level = Math.min(1, nodeScore(p, idx) / o.sRef);
            sum += level;
            if (level < o.weakThreshold) weakCnt++;
        }
        double mean = sum / pts.size();
        double weak = (double) weakCnt / pts.size();
        double deficit = 0.5 * (1 - mean) + 0.5 * weak;
        double distance = pathLength(pts);
        return new Score(distance, mean, weak, deficit, distance * (1 + o.alpha * deficit));
    }

    // ---------- 경로 위 시설 ----------
    /** 경로에서 hitOffset(m) 이내 시설을 출발지 기준 거리순으로. 같은 시설은 가장 가까웠던 지점만 */
    public static List<Hit> facilitiesAlong(List<LatLng> path, Index idx, Options o) {
        List<LatLng> pts = resample(path, o.step);
        Map<Facility, Hit> hit = new IdentityHashMap<>();
        double along = 0;
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) along += haversine(pts.get(i - 1), pts.get(i));
            final double at = along;
            eachNearby(pts.get(i), idx, (f, d) -> {
                if (d > o.hitOffset) return;
                Hit prev = hit.get(f);
                if (prev == null || d < prev.offset()) hit.put(f, new Hit(f, at, d));
            });
        }
        List<Hit> list = new ArrayList<>(hit.values());
        list.sort(Comparator.comparingDouble(Hit::along));
        return list;
    }

    /** 종류별 개수 */
    public static Map<Type, Integer> countByType(List<Hit> hits) {
        Map<Type, Integer> c = new EnumMap<>(Type.class);
        for (Type t : Type.values()) c.put(t, 0);
        for (Hit h : hits) c.merge(h.facility().type, 1, Integer::sum);
        return c;
    }

    /** 취약 노드가 연속되는 구간을 [시작m, 끝m]으로 묶어서 반환 */
    public static List<double[]> weakSegments(List<LatLng> path, Index idx, Options o) {
        List<LatLng> pts = resample(path, o.step);
        List<double[]> segs = new ArrayList<>();
        double along = 0, from = -1;
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) along += haversine(pts.get(i - 1), pts.get(i));
            boolean weak = Math.min(1, nodeScore(pts.get(i), idx) / o.sRef) < o.weakThreshold;
            if (weak && from < 0) from = along;
            if (!weak && from >= 0) {
                if (along - from >= o.minWeakSeg) segs.add(new double[]{from, along});
                from = -1;
            }
        }
        if (from >= 0 && along - from >= o.minWeakSeg) segs.add(new double[]{from, along});
        return segs;
    }

    // ---------- 경유지 후보 ----------
    public record Via(double lat, double lng, double t, double score) {
        LatLng pos() { return new LatLng(lat, lng); }
    }

    /**
     * 출발-도착 직선 주변 코리도를 격자로 샘플링해, 안전 점수가 높은 지점을 경유지로.
     * (격자 중심점을 쓰므로 보행로에 잘 붙음)
     */
    public static List<Via> pickViaPoints(LatLng origin, LatLng dest, Index idx, Options o) {
        double total = haversine(origin, dest);
        int steps = (int) Math.max(6, Math.round(total / 120));
        int half = 2;  // 수직 방향 샘플 수 5개 = -2..2

        double mLat = 110540;
        double mLng = Math.cos(Math.toRadians(origin.lat())) * 111320;
        double dx = (dest.lng() - origin.lng()) * mLng;
        double dy = (dest.lat() - origin.lat()) * mLat;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len == 0) return new ArrayList<>();
        double nx = -dy / len, ny = dx / len;   // 단위 법선벡터

        List<Via> cands = new ArrayList<>();
        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            if (t < 0.15 || t > 0.85) continue;   // 출발/도착 근처 제외
            double baseLat = origin.lat() + (dest.lat() - origin.lat()) * t;
            double baseLng = origin.lng() + (dest.lng() - origin.lng()) * t;
            for (int k = -half; k <= half; k++) {
                double off = k * (o.corridor / half);
                LatLng p = new LatLng(baseLat + (ny * off) / mLat, baseLng + (nx * off) / mLng);
                double penalty = 1 - 0.4 * (Math.abs(off) / o.corridor);
                cands.add(new Via(p.lat(), p.lng(), t, nodeScore(p, idx) * penalty));
            }
        }
        cands.sort((a, b) -> Double.compare(b.score(), a.score()));

        List<Via> picked = new ArrayList<>();
        for (Via c : cands) {
            if (picked.size() >= o.numVia) break;
            if (c.score() <= 0) break;
            boolean ok = true;
            for (Via p : picked) {
                if (!(Math.abs(p.t() - c.t()) >= 0.18 && haversine(p.pos(), c.pos()) >= o.minGap)) {
                    ok = false;
                    break;
                }
            }
            if (ok) picked.add(c);
        }
        return picked;
    }

    // ---------- 전체 파이프라인 ----------
    private record Plan(String label, List<LatLng> waypoints) {}

    public static PlanResult planSafeRoute(LatLng origin, LatLng dest, Index idx,
                                           RouteProvider provider, Options o) {
        List<Via> vias = pickViaPoints(origin, dest, idx, o);
        List<Plan> plans = new ArrayList<>();
        plans.add(new Plan("기본 경로", List.of(origin, dest)));
        for (int i = 0; i < vias.size(); i++) {
            plans.add(new Plan("경유 후보 " + (i + 1), List.of(origin, vias.get(i).pos(), dest)));
        }

        List<RouteResult> results = new ArrayList<>();
        Exception lastError = null;
        for (Plan p : plans) {
            try {
                List<LatLng> path = provider.getPath(p.waypoints());
                results.add(new RouteResult(p.label(), path, scoreRoute(path, idx, o)));
            } catch (Exception e) {
                lastError = e;
                System.err.println(p.label() + " 경로 요청 실패: " + e);
            }
        }
        if (results.isEmpty()) {
            throw new IllegalStateException("경로를 하나도 받아오지 못했습니다: "
                    + (lastError != null ? lastError.getMessage() : "알 수 없는 오류"));
        }

        RouteResult shortest = results.stream()
                .min(Comparator.comparingDouble(r -> r.score().distance())).get();

        // 거리를 "제약조건"으로: 최단 대비 maxDetour 배를 넘는 후보는 제외
        double limit = shortest.score().distance() * o.maxDetour;
        List<RouteResult> feasible = results.stream().filter(r -> r.score().distance() <= limit).toList();
        RouteResult best = (feasible.isEmpty() ? results : feasible).stream()
                .min(Comparator.comparingDouble(r -> r.score().total())).get();

        return new PlanResult(best, shortest, results);
    }
}
