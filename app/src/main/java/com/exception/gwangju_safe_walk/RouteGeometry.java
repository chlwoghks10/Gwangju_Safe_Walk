package com.exception.gwangju_safe_walk;

import com.naver.maps.geometry.LatLng;

import java.util.List;

// 경로 이탈 판정에 쓰는 거리 계산
public final class RouteGeometry {
    private RouteGeometry() {}

    private static final double M_PER_DEG_LAT = 111_320.0;

    // 점에서 경로선까지의 최단 거리(m). 꼭짓점이 아니라 선분 위의 가장 가까운 점까지 잰다
    public static double distanceToPolylineM(double lat, double lon, List<LatLng> pts) {
        if (pts == null || pts.isEmpty()) return Double.NaN;

        final double mLat = M_PER_DEG_LAT;
        final double mLon = M_PER_DEG_LAT * Math.cos(Math.toRadians(lat));

        if (pts.size() == 1) {
            LatLng p = pts.get(0);
            return Math.hypot((p.longitude - lon) * mLon, (p.latitude - lat) * mLat);
        }

        double best = Double.MAX_VALUE;
        // 현재 위치를 원점(0,0)으로 둔 미터 좌표
        double ax = (pts.get(0).longitude - lon) * mLon;
        double ay = (pts.get(0).latitude  - lat) * mLat;

        for (int i = 1; i < pts.size(); i++) {
            double bx = (pts.get(i).longitude - lon) * mLon;
            double by = (pts.get(i).latitude  - lat) * mLat;

            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : -(ax * dx + ay * dy) / len2;
            t = Math.max(0, Math.min(1, t));

            double d = Math.hypot(ax + t * dx, ay + t * dy);
            if (d < best) best = d;

            ax = bx;
            ay = by;
        }
        return best;
    }

    // 경로를 따라 도착지까지 남은 거리(m)
    public static double remainingAlongM(double lat, double lon, List<LatLng> pts) {
        if (pts == null || pts.isEmpty()) return Double.NaN;
        if (pts.size() == 1) return 0;

        final double mLat = M_PER_DEG_LAT;
        final double mLon = M_PER_DEG_LAT * Math.cos(Math.toRadians(lat));

        int bestSeg = 0;
        double bestT = 0, bestD = Double.MAX_VALUE;
        double ax = (pts.get(0).longitude - lon) * mLon;
        double ay = (pts.get(0).latitude  - lat) * mLat;
        for (int i = 1; i < pts.size(); i++) {
            double bx = (pts.get(i).longitude - lon) * mLon;
            double by = (pts.get(i).latitude  - lat) * mLat;
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : -(ax * dx + ay * dy) / len2;
            t = Math.max(0, Math.min(1, t));
            double d = Math.hypot(ax + t * dx, ay + t * dy);
            if (d < bestD) { bestD = d; bestSeg = i - 1; bestT = t; }
            ax = bx;
            ay = by;
        }

        LatLng a = pts.get(bestSeg), b = pts.get(bestSeg + 1);
        double remain = (1 - bestT) * haversineM(a, b);
        for (int i = bestSeg + 1; i < pts.size() - 1; i++) {
            remain += haversineM(pts.get(i), pts.get(i + 1));
        }
        return remain;
    }

    private static double haversineM(LatLng a, LatLng b) {
        final double R = 6_371_000.0;
        double dLat = Math.toRadians(b.latitude - a.latitude);
        double dLon = Math.toRadians(b.longitude - a.longitude);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
