package com.exception.gwangju_safe_walk;

// 경로에서 30m 넘게 벗어난 측정이 2번 연속이면 이탈로 본다. 한 번 들어오면 0으로 돌아간다
public final class OffRouteDetector {
    public static final double THRESHOLD_M = 30.0;
    public static final int CONSECUTIVE = 2;

    private int count = 0;

    // 이탈이 확정되면 true
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
