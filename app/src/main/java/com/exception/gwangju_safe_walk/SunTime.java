package com.exception.gwangju_safe_walk;

import java.util.Calendar;

public final class SunTime {
    private SunTime() {}

    private static final double ZENITH = 90.833;

    private static final double TZ = 9.0;

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

        double lQuad = Math.floor(l / 90) * 90;
        double raQuad = Math.floor(ra / 90) * 90;
        ra = (ra + (lQuad - raQuad)) / 15.0;

        double sinDec = 0.39782 * Math.sin(Math.toRadians(l));
        double cosDec = Math.cos(Math.asin(sinDec));

        double latR = Math.toRadians(lat);
        double cosH = (Math.cos(Math.toRadians(ZENITH)) - sinDec * Math.sin(latR))
                / (cosDec * Math.cos(latR));
        if (cosH > 1 || cosH < -1) return -1;

        double h = rising
                ? (360 - Math.toDegrees(Math.acos(cosH)))
                : Math.toDegrees(Math.acos(cosH));
        h /= 15.0;

        double localMean = h + ra - (0.06571 * t) - 6.622;
        double ut = norm(localMean - lngHour, 24);
        return norm(ut + TZ, 24);
    }

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

    public static String format(double hours) {
        if (hours < 0) return "--:--";
        int h = (int) hours;
        int m = (int) Math.round((hours - h) * 60);
        if (m == 60) { m = 0; h = (h + 1) % 24; }
        return String.format("%02d:%02d", h, m);
    }

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
