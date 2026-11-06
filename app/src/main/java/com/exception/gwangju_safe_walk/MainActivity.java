package com.exception.gwangju_safe_walk;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.FragmentManager;

import com.naver.maps.geometry.LatLng;
import com.naver.maps.map.CameraUpdate;
import com.naver.maps.map.MapFragment;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.OnMapReadyCallback;
import com.naver.maps.map.UiSettings;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    public static final LatLng CHOSUN = new LatLng(35.1400, 126.9270);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Ui.systemBars(this);

        setupMiniMap();
        setupClicks();
        showSunset();
        setupScreenMode();

        Tabs.attach(this, Tabs.HOME);
    }

    // 제목 왼쪽 달 표시를 누르면 자동, 낮, 야간 순으로 바뀐다
    private void setupScreenMode() {
        View btn = findViewById(R.id.btnScreenMode);
        if (btn == null) return;
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int m = SunTime.cycle(MainActivity.this);
                Toast.makeText(MainActivity.this,
                        SunTime.modeText(m), Toast.LENGTH_SHORT).show();
                AppCompatDelegate.setDefaultNightMode(
                        SunTime.isNight(CHOSUN.latitude, CHOSUN.longitude)
                                ? AppCompatDelegate.MODE_NIGHT_YES
                                : AppCompatDelegate.MODE_NIGHT_NO);
                recreate();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Tabs.attach(this, Tabs.HOME);
    }

    private void showSunset() {
        TextView sub = findViewById(R.id.bannerSub);
        if (sub != null) {
            sub.setText("오늘 일몰 "
                    + SunTime.sunsetText(CHOSUN.latitude, CHOSUN.longitude)
                    + " · 두 경로를 비교하세요");
        }
        TextView may = findViewById(R.id.cardMaySub);
        if (may != null) {
            may.setText("광주 5·18 사적지 " + Place518.count() + "곳");
        }
    }

    private void setupMiniMap() {
        FragmentManager fm = getSupportFragmentManager();
        MapFragment f = (MapFragment) fm.findFragmentById(R.id.miniMap);
        if (f == null) {
            f = MapFragment.newInstance();
            fm.beginTransaction().add(R.id.miniMap, f).commit();
        }
        f.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull NaverMap naverMap) {
        naverMap.setNightModeEnabled(
                SunTime.isNight(CHOSUN.latitude, CHOSUN.longitude));

        naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(CHOSUN, 15));
        UiSettings ui = naverMap.getUiSettings();
        ui.setAllGesturesEnabled(false);
        ui.setZoomControlEnabled(false);
        ui.setLogoClickEnabled(false);
        ui.setScaleBarEnabled(false);
    }

    private void openMap(String only) {
        Intent i = new Intent(this, MapActivity.class);
        if (only != null) i.putExtra("only", only);
        startActivity(i);
    }

    private void setupClicks() {
        View.OnClickListener toMap = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMap(null);
            }
        };
        View.OnClickListener toRoute = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, RouteActivity.class));
            }
        };

        findViewById(R.id.btnExpandMap).setOnClickListener(toMap);
        findViewById(R.id.svcMap).setOnClickListener(toMap);

        findViewById(R.id.layerCctv).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMap("CCTV");
            }
        });
        findViewById(R.id.layerLight).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMap("보안등");
            }
        });
        findViewById(R.id.layerBell).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMap("비상벨");
            }
        });
        findViewById(R.id.layerPolice).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMap("경찰서");
            }
        });

        findViewById(R.id.svcRoute).setOnClickListener(toRoute);
        findViewById(R.id.btnBannerGo).setOnClickListener(toRoute);

        findViewById(R.id.cardMay).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, MayActivity.class));
            }
        });

        findViewById(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, MenuActivity.class));
            }
        });
    }
}
