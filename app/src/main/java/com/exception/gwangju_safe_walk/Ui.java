package com.exception.gwangju_safe_walk;

import android.app.Activity;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 상태바 아이콘(시계·배터리) 색을 화면 밝기에 맞춘다.
 *
 * targetSdk 35 이상에서는 화면이 상태바 뒤까지 그려지므로,
 * 레이아웃의 fitsSystemWindows 로 여백을 확보하고
 * 여기서 아이콘 색만 맞춰 주면 된다.
 */
public final class Ui {

    private Ui() {}

    /** 일몰 여부에 따라 자동 */
    public static void systemBars(Activity a) {
        systemBars(a, !SunTime.isNight(MainActivity.CHOSUN.latitude,
                                       MainActivity.CHOSUN.longitude));
    }

    /** @param lightBackground 배경이 밝으면 true (아이콘이 어두워진다) */
    public static void systemBars(Activity a, boolean lightBackground) {
        WindowInsetsControllerCompat c =
                WindowCompat.getInsetsController(a.getWindow(), a.getWindow().getDecorView());
        c.setAppearanceLightStatusBars(lightBackground);
        c.setAppearanceLightNavigationBars(lightBackground);
    }
}
