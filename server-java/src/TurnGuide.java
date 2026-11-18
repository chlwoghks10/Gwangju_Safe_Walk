import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 경로 좌표 -> 좌/우회전 안내(turns) 생성.
 * 최단 / 안심 A 경로는 makeTurns()로 만들고,
 * 안심 B(Tmap)는 Tmap 안내 문구를 fromTmap()으로 Turn으로 바꿔서 담는다.
 *
 * 프로젝트 공용 LatLng(record: lat, lng)를 그대로 사용한다. (중첩 LatLng 제거 -> 타입 불일치 방지)
 * Turn 필드는 RouteServer의 guides JSON({lat, lon, description, turnType})과 같은 이름(lat, lon)을 쓴다.
 *
 * 개선 사항
 *  1) 모퉁이 중복 제거: 인덱스 기준 -> 거리 기준(PEAK_RADIUS_M)으로 가장 크게 꺾이는 점만 선택
 *  2) 출발/도착 근처 오탐 제거: 앞뒤 기준선이 너무 짧으면 각도 계산 생략
 *  3) 유턴 / 약한 꺾임 구분 (TTS 문구가 자연스러워짐)
 *  4) 가까운 안내 병합: MIN_TURN_GAP_M 이내 연속 안내는 더 크게 꺾이는 쪽만 유지
 *  5) Turn에 출발점부터의 누적 거리(distFromStart), 각도 추가 (안내 타이밍/이탈 감지용)
 *  6) fromTmap: 좌표 순서 뒤바뀜/범위 오류/null 문구 방어, turnType 필터 추가
 */
public class TurnGuide {

    // ===== 꺾임 판정 기준 (안내가 너무 많으면 ANGLE을 키우고, 너무 적으면 줄인다) =====
    static final double ANGLE_THRESHOLD = 30;    // 도: 이 이상 꺾이면 후보
    static final double SLIGHT_MAX = 60;         // 도: 이 미만이면 "약간 좌/우회전"
    static final double UTURN_MIN = 150;         // 도: 이 이상이면 유턴
    static final double LOOK_DISTANCE_M = 12;    // 앞뒤로 볼 거리(m)
    static final double MIN_LOOK_M = 5;          // 앞뒤 기준선 최소 길이(m), 미만이면 오탐으로 보고 제외
    // 우리 도로망은 좌표 간격이 중앙값 17m 라, 15m 로는 묶이는 것이 거의 없어서 40m 로 올림
    static final double PEAK_RADIUS_M = 40;      // 이 반경 안에서 가장 크게 꺾이는 점만 안내
    static final double MIN_TURN_GAP_M = 25;     // 안내 사이 최소 간격(m)

    // Tmap 보행자 경로 turnType 중 좌/우회전 계열 (※ Tmap 문서로 값 한 번 확인할 것)
    // 12 좌회전, 13 우회전, 14 유턴, 16/17 좌측 방향, 18/19 우측 방향
    static final Set<Integer> TMAP_TURN_TYPES = Set.of(12, 13, 14, 16, 17, 18, 19);

    // 한국 좌표 범위 (lat/lon 순서가 뒤바뀐 경우를 잡기 위함)
    static final double LAT_MIN = 33, LAT_MAX = 39, LON_MIN = 124, LON_MAX = 132;

    public static class Turn {
        public double lat, lon;
        public String text;
        public double distFromStart = -1;  // 출발점부터 누적 거리(m), 모르면 -1
        public double angle = 0;           // 꺾임 각도(도), 양수 우 / 음수 좌

        public Turn(LatLng pos, String text) {
            this.lat = pos.lat();
            this.lon = pos.lng();
            this.text = text;
        }

        public Turn(LatLng pos, String text, double distFromStart, double angle) {
            this(pos, text);
            this.distFromStart = distFromStart;
            this.angle = angle;
        }
    }

    /** 최단 / 안심 A: 경로 좌표에서 좌/우회전 지점 계산 */
    public static List<Turn> makeTurns(List<LatLng> path) {
        List<Turn> turns = new ArrayList<>();
        if (path == null || path.size() < 3) return turns;

        int size = path.size();
        double[] cum = cumulativeDistance(path);
        double[] ang = new double[size];

        // 1) 모든 점의 꺾임 각도 계산 (앞뒤로 LOOK_DISTANCE_M 떨어진 점 기준)
        for (int i = 1; i < size - 1; i++) {
            int p = i;
            while (p > 0 && cum[i] - cum[p] < LOOK_DISTANCE_M) p--;
            int n = i;
            while (n < size - 1 && cum[n] - cum[i] < LOOK_DISTANCE_M) n++;

            // 출발/도착 근처처럼 기준선이 너무 짧으면 각도가 튀므로 제외
            if (cum[i] - cum[p] < MIN_LOOK_M || cum[n] - cum[i] < MIN_LOOK_M) continue;

            ang[i] = turnAngle(path.get(p), path.get(i), path.get(n));
        }

        // 2) 기준 이상이면서, 반경 PEAK_RADIUS_M 안에서 가장 크게 꺾이는 점만 후보로 선택
        List<Integer> peaks = new ArrayList<>();
        for (int i = 1; i < size - 1; i++) {
            double a = Math.abs(ang[i]);
            if (a < ANGLE_THRESHOLD) continue;

            boolean isPeak = true;
            for (int j = i - 1; j >= 1 && cum[i] - cum[j] < PEAK_RADIUS_M; j--) {
                if (Math.abs(ang[j]) >= a) { isPeak = false; break; }   // 같으면 앞쪽 점 우선
            }
            for (int j = i + 1; isPeak && j <= size - 2 && cum[j] - cum[i] < PEAK_RADIUS_M; j++) {
                if (Math.abs(ang[j]) > a) isPeak = false;
            }
            if (isPeak) peaks.add(i);
        }

        // 3) 너무 가까운 연속 안내는 더 크게 꺾이는 쪽만 유지
        List<Integer> kept = new ArrayList<>();
        for (int idx : peaks) {
            if (!kept.isEmpty()) {
                int last = kept.get(kept.size() - 1);
                if (cum[idx] - cum[last] < MIN_TURN_GAP_M) {
                    if (Math.abs(ang[idx]) > Math.abs(ang[last])) kept.set(kept.size() - 1, idx);
                    continue;
                }
            }
            kept.add(idx);
        }

        // 4) Turn 생성
        for (int idx : kept) {
            turns.add(new Turn(path.get(idx), describe(ang[idx]), cum[idx], ang[idx]));
        }
        return turns;
    }

    /** 각도 -> 안내 문구 (양수 우, 음수 좌) */
    static String describe(double angle) {
        double a = Math.abs(angle);
        if (a >= UTURN_MIN) return "유턴";
        String side = angle > 0 ? "우회전" : "좌회전";
        return a < SLIGHT_MAX ? "약간 " + side : side;
    }

    /**
     * 안심 B: Tmap 응답의 Point feature -> Turn
     * Tmap 좌표는 [경도, 위도] 순서이므로 lon, lat 순서로 넣는다.
     * description 예: "○○에서 좌회전"
     *
     * 잘못된 입력이면 예외 대신 null을 반환하므로, 호출부에서 null이면 건너뛴다.
     */
    public static Turn fromTmap(double lon, double lat, String description) {
        return fromTmap(lon, lat, description, -1, -1);
    }

    /**
     * turnType이 0 이상이면 좌/우회전 계열만 통과시킨다 (직진, 횡단보도 등 제외).
     * distFromStart는 모르면 -1.
     */
    public static Turn fromTmap(double lon, double lat, String description,
                                int turnType, double distFromStart) {
        // lat/lon 순서가 뒤바뀐 경우 자동 교정
        if (!inKorea(lat, lon) && inKorea(lon, lat)) {
            double t = lat; lat = lon; lon = t;
        }
        if (!inKorea(lat, lon)) return null;

        if (turnType >= 0 && !TMAP_TURN_TYPES.contains(turnType)) return null;

        String text = (description == null || description.trim().isEmpty())
                ? "방향을 전환하세요" : description.trim();
        return new Turn(new LatLng(lat, lon), text, distFromStart, 0);
    }

    static boolean inKorea(double lat, double lon) {
        return lat >= LAT_MIN && lat <= LAT_MAX && lon >= LON_MIN && lon <= LON_MAX;
    }

    // 경로 시작점부터 각 점까지의 누적 거리(m)
    static double[] cumulativeDistance(List<LatLng> path) {
        double[] cum = new double[path.size()];
        for (int i = 1; i < path.size(); i++) {
            cum[i] = cum[i - 1] + dist(path.get(i - 1), path.get(i));
        }
        return cum;
    }

    // 세 점으로 좌/우 판정: 양수면 우회전, 음수면 좌회전
    static double turnAngle(LatLng prev, LatLng cur, LatLng next) {
        double d = bearing(cur, next) - bearing(prev, cur);
        while (d > 180) d -= 360;
        while (d <= -180) d += 360;
        return d;
    }

    static double bearing(LatLng a, LatLng b) {
        double p1 = Math.toRadians(a.lat()), p2 = Math.toRadians(b.lat());
        double dl = Math.toRadians(b.lng() - a.lng());
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    // 두 좌표 사이 거리(m)
    static double dist(LatLng a, LatLng b) {
        double R = 6371000;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat()))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.sqrt(h));
    }
}
