package com.exception.gwangju_safe_walk;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

public class SplashActivity extends AppCompatActivity {

    private static final int DELAY_MS = 1500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 일몰이 지났으면 야간 색, 아직이면 주간 색.
        // super.onCreate 보다 먼저 정해야 이후 모든 화면에 적용된다.
        AppCompatDelegate.setDefaultNightMode(
                SunTime.isNight(35.1400, 126.9270)
                        ? AppCompatDelegate.MODE_NIGHT_YES
                        : AppCompatDelegate.MODE_NIGHT_NO);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        Ui.systemBars(this, false);   // 스플래시는 항상 어두운 배경

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
        }, DELAY_MS);
    }
}