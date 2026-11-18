package com.exception.gwangju_safe_walk;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

public class SplashActivity extends AppCompatActivity {
    private static final int DELAY_MS = 1500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 사용자가 정해 둔 화면 모드를 먼저 읽는다
        SunTime.load(this);
        AppCompatDelegate.setDefaultNightMode(
                SunTime.isNight(35.1400, 126.9270)
                        ? AppCompatDelegate.MODE_NIGHT_YES
                        : AppCompatDelegate.MODE_NIGHT_NO);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        Ui.systemBars(this, false);
        clearOldServer();

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(SplashActivity.this, MainActivity.class));
                finish();
            }
        }, DELAY_MS);
    }

    // 주소를 고정했으니 전에 손으로 넣어 둔 옛 주소는 한 번만 지운다
    private void clearOldServer() {
        SharedPreferences p = getSharedPreferences("safewalk", Context.MODE_PRIVATE);
        if (p.getBoolean("server_fixed", false)) return;
        p.edit().remove("server").putBoolean("server_fixed", true).apply();
    }
}
