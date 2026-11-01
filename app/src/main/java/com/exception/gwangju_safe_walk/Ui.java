package com.exception.gwangju_safe_walk;

import android.app.Activity;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

public final class Ui {
    private Ui() {}

    public static void systemBars(Activity a) {
        systemBars(a, !SunTime.isNight(MainActivity.CHOSUN.latitude,
                                       MainActivity.CHOSUN.longitude));
    }

    public static void systemBars(Activity a, boolean lightBackground) {
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(a.getWindow(), a.getWindow().getDecorView());
        c.setAppearanceLightStatusBars(lightBackground);
        c.setAppearanceLightNavigationBars(lightBackground);
    }
}
