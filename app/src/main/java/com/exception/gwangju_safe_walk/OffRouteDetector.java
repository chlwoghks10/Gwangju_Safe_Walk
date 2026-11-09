package com.exception.gwangju_safe_walk;

/**
 * 경로 이탈 판정기.
 * 경로에서 THRESHOLD_M 이상 벗어난 측정이 CONSECUTIVE 번 연속되면 이탈로 본다.
 * 한 번이라도 경로 안으로 돌아오면 횟수는 0으로 돌아간다 (GPS 튐 방지).
 */
public final class OffRouteDetector {
    public static final double THRESHOLD_M = 30.0;
    public static final int CONSECUTIVE = 2;

    private int count = 0;

    /** @return 이번 측정으로 이탈이 확정되면 true */
    public boolean onSample(double distanceM) {
        if (Double.isNaN(distanceM)) return false;
        if (distanceM >= THRESHOLD_M) {
            count++;
            return count >= CONSECUTIVE;
        }
        count = 0;
        return false;
    }

    public void reset() {
        count = 0;
    }
}
