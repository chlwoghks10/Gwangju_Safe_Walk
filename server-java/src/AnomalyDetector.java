import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 이상치(장시간 정지 / 위치 신호 두절) 판정기.
 * 사용자(경로 안내 세션)마다 인스턴스 1개를 만들어 쓴다.
 *
 * 호출 방법
 *  - 위치가 들어올 때마다 onLocation(point, safety)
 *  - 서버 스케줄러가 주기적으로(예: 5~10초) tick(now)
 *  - 앱에서 "괜찮아요" 응답이 오면 confirmOk(now)
 *  반환된 Action에 따라 서버가 FCM 푸시(ASK_CONFIRM) 또는 보호자 전화/문자(ALERT_GUARDIAN)를 보낸다.
 */
public class AnomalyDetector {

    public enum State { MOVING, CONFIRM_PENDING, ALERTED }
    public enum Action { NONE, ASK_CONFIRM, ALERT_GUARDIAN }

    public record Point(double lat, double lon, long timeMs, double accuracyM) {}

    // ---- 튜닝 대상 설정값 ----
    static final long MIN_STILL_MS = 3 * 60_000;      // 위험 구간(safety=0) 정지 임계
    static final long MAX_STILL_MS = 6 * 60_000;      // 안전 구간(safety=1) 정지 임계
    static final double STILL_RADIUS_M = 25;          // 이 반경 안이면 "제자리"
    static final double MAX_ACCURACY_M = 50;          // GPS 정확도가 이보다 나쁘면 버림
    static final long NO_SIGNAL_MS = 3 * 60_000;      // 위치 수신이 이만큼 끊기면 이상
    static final long CONFIRM_TIMEOUT_MS = 60_000;    // "괜찮으세요?" 응답 대기 시간
    static final long SAMPLE_SLACK_MS = 15_000;       // 전송 간격 오차 허용

    private final Deque<Point> window = new ArrayDeque<>();
    private State state = State.MOVING;
    private boolean paused = false;
    private long lastReceivedMs;
    private long confirmSinceMs;
    private double centerLat = Double.NaN, centerLon = Double.NaN;

    public AnomalyDetector(long startMs) {
        this.lastReceivedMs = startMs;
    }

    /** 위치 수신. safety: 현재 구간 안전도 0(위험)~1(안전). 위험할수록 정지 임계가 짧아진다. */
    public synchronized Action onLocation(Point p, double safety) {
        if (paused) return Action.NONE;
        lastReceivedMs = p.timeMs();

        if (p.accuracyM() <= MAX_ACCURACY_M) window.addLast(p);
        while (!window.isEmpty()
                && window.peekFirst().timeMs() < p.timeMs() - MAX_STILL_MS - SAMPLE_SLACK_MS) {
            window.pollFirst();
        }

        if (state == State.MOVING) {
            if (isStill(p.timeMs(), stillThresholdMs(safety))) {
                state = State.CONFIRM_PENDING;
                confirmSinceMs = p.timeMs();
                return Action.ASK_CONFIRM;
            }
        } else if (state == State.CONFIRM_PENDING) {
            // 확인 대기 중 다시 움직이기 시작하면 정상 복귀
            if (distM(p.lat(), p.lon(), centerLat, centerLon) > STILL_RADIUS_M * 1.5) reset();
        }
        return Action.NONE;
    }

    /** 주기 호출. 신호 두절 감지와 확인 응답 타임아웃 처리. */
    public synchronized Action tick(long nowMs) {
        if (paused) return Action.NONE;

        if (state == State.MOVING && nowMs - lastReceivedMs >= NO_SIGNAL_MS) {
            state = State.CONFIRM_PENDING;
            confirmSinceMs = nowMs;
            Point last = window.peekLast();
            if (last != null) { centerLat = last.lat(); centerLon = last.lon(); }
            return Action.ASK_CONFIRM;
        }
        if (state == State.CONFIRM_PENDING && nowMs - confirmSinceMs >= CONFIRM_TIMEOUT_MS) {
            state = State.ALERTED;
            return Action.ALERT_GUARDIAN;   // 상태 전이 시 1회만 반환
        }
        return Action.NONE;
    }

    /** 사용자가 "괜찮아요"를 눌렀을 때. */
    public synchronized void confirmOk(long nowMs) {
        reset();
        lastReceivedMs = nowMs;
    }

    /** "잠시 멈춤" 또는 도착 시 감시 중단 / 재개. */
    public synchronized void pause() { paused = true; reset(); }
    public synchronized void resume(long nowMs) { paused = false; reset(); lastReceivedMs = nowMs; }

    public synchronized State getState() { return state; }

    // ---- 내부 로직 ----

    private void reset() {
        state = State.MOVING;
        window.clear();
        centerLat = centerLon = Double.NaN;
    }

    private static long stillThresholdMs(double safety) {
        double s = Math.max(0, Math.min(1, safety));
        return (long) (MIN_STILL_MS + (MAX_STILL_MS - MIN_STILL_MS) * s);
    }

    /** 최근 thresholdMs 동안의 모든 점이 중심 반경 STILL_RADIUS_M 안이면 정지. */
    private boolean isStill(long nowMs, long thresholdMs) {
        Point first = window.peekFirst();
        // 데이터가 임계시간만큼 쌓였는지 확인
        if (first == null || first.timeMs() > nowMs - thresholdMs + SAMPLE_SLACK_MS) return false;

        double sLat = 0, sLon = 0;
        int n = 0;
        for (Point q : window) {
            if (q.timeMs() >= nowMs - thresholdMs) { sLat += q.lat(); sLon += q.lon(); n++; }
        }
        if (n < 3) return false;
        double cLat = sLat / n, cLon = sLon / n;

        for (Point q : window) {
            if (q.timeMs() >= nowMs - thresholdMs
                    && distM(q.lat(), q.lon(), cLat, cLon) > STILL_RADIUS_M) return false;
        }
        centerLat = cLat;
        centerLon = cLon;
        return true;
    }

    /** 두 좌표 사이 거리(m), Haversine. NaN이 들어오면 0 반환(= 복귀 판정 안 함). */
    static double distM(double lat1, double lon1, double lat2, double lon2) {
        if (Double.isNaN(lat2) || Double.isNaN(lon2)) return 0;
        double R = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.sqrt(a));
    }

    // ---- 간단 시뮬레이션: 같은 자리에서 6분간 10초마다 위치 수신 ----
    public static void main(String[] args) {
        AnomalyDetector d = new AnomalyDetector(0);
        for (long t = 0; t <= 6 * 60_000; t += 10_000) {
            double jitter = (Math.random() - 0.5) * 0.00004;   // 약 ±2~4m 흔들림
            Action a = d.onLocation(new Point(35.1400 + jitter, 126.9300 + jitter, t, 10), 0.5);
            if (a == Action.NONE) a = d.tick(t);
            if (a != Action.NONE) System.out.println(t / 1000 + "s: " + a);
        }
    }
}
