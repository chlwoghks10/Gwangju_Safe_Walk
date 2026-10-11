package com.exception.gwangju_safe_walk;

import java.util.Calendar;

/**
 * 일출·일몰 시각 계산 (Almanac for Computers 표준 알고리즘).
 * 네트워크나 외부 라이브러리 없이 날짜와 좌표만으로 계산한다.
 *
 * 광주 기준 검증값
 *   10/10  일출 06:34  일몰 18:05
 *   12/21  일출 07:36  일몰 17:24
 *   06/21  일출 05:18  일몰 19:50
 */
public final class SunTime {

    private SunTime() {}

    /** 공식 일출·일몰 천정각 (대기 굴절과 태양 반지름 보정 포함) */
    private static final double ZENITH = 90.833;

    /** 한국 표준시 */
    private static final double TZ = 9.0;

    /**
     * @return 하루 중 시각(0~24). 극야·백야면 -1
     */
    public static double calc(int year, int month, int day,
                              double lat, double lon, boolean rising) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day);
        int n = c.get(Calendar.DAY_OF_YEAR);

        double lngHour = lon / 15.0;
        double t = n + (((rising ? 6 : 18) - lngHour) / 24.0);

        double m = (0.9856 * t) - 3.289;
        double mr = Math.toRadians(m);
        double l = m + 1.916 * Math.sin(mr) + 0.020 * Math.sin(2 * mr) + 282.634;
        l = norm(l, 360);

        double ra = norm(Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(l)))), 360);
        // 적경을 황경과 같은 사분면으로 맞춘다
        double lQuad = Math.floor(l / 90) * 90;
        double raQuad = Math.floor(ra / 90) * 90;
        ra = (ra + (lQuad - raQuad)) / 15.0;

        double sinDec = 0.39782 * Math.sin(Math.toRadians(l));
        double cosDec = Math.cos(Math.asin(sinDec));

        double latR = Math.toRadians(lat);
        double cosH = (Math.cos(Math.toRadians(ZENITH)) - sinDec * Math.sin(latR))
                / (cosDec * Math.cos(latR));
        if (cosH > 1 || cosH < -1) return -1;   // 그 날 해가 뜨지 않거나 지지 않음

        double h = rising
                ? (360 - Math.toDegrees(Math.acos(cosH)))
                : Math.toDegrees(Math.acos(cosH));
        h /= 15.0;

        double localMean = h + ra - (0.06571 * t) - 6.622;
        double ut = norm(localMean - lngHour, 24);
        return norm(ut + TZ, 24);
    }

    /** 지금이 밤인지 (일몰 후 ~ 일출 전) */
    public static boolean isNight(double lat, double lon) {
        Calendar c = Calendar.getInstance();
        double rise = calc(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), lat, lon, true);
        double set = calc(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), lat, lon, false);
        if (rise < 0 || set < 0) return false;

        double now = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0;
        return now < rise || now >= set;
    }

    /** "18:05" 형태로 */
    public static String format(double hours) {
        if (hours < 0) return "--:--";
        int h = (int) hours;
        int m = (int) Math.round((hours - h) * 60);
        if (m == 60) { m = 0; h = (h + 1) % 24; }
        return String.format("%02d:%02d", h, m);
    }

    /** 오늘 일몰 시각 문자열 */
    public static String sunsetText(double lat, double lon) {
        Calendar c = Calendar.getInstance();
        return format(calc(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), lat, lon, false));
    }

    private static double norm(double v, double max) {
        v = v % max;
        return v < 0 ? v + max : v;
    }
}
