package com.exception.gwangju_safe_walk;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
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

        setupMiniMap();
        setupClicks();
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
        naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(CHOSUN, 15));
        UiSettings ui = naverMap.getUiSettings();
        ui.setAllGesturesEnabled(false);
        ui.setZoomControlEnabled(false);
        ui.setLogoClickEnabled(false);
        ui.setScaleBarEnabled(false);
    }

    /** 특정 레이어만 켠 상태로 지도를 연다. null 이면 전체 표시 */
    private void openMap(String only) {
        Intent i = new Intent(this, MapActivity.class);
        if (only != null) i.putExtra("only", only);
        startActivity(i);
    }

    private void setupClicks() {
        View.OnClickListener toMap   = v -> openMap(null);
        View.OnClickListener toRoute = v -> startActivity(new Intent(this, RouteActivity.class));
        View.OnClickListener toMenu  = v -> startActivity(new Intent(this, MenuActivity.class));

        findViewById(R.id.btnExpandMap).setOnClickListener(toMap);
        findViewById(R.id.svcMap).setOnClickListener(toMap);

        // 안심지도 아이콘은 해당 레이어만 켜고 들어간다
        findViewById(R.id.layerCctv).setOnClickListener(v -> openMap("CCTV"));
        findViewById(R.id.layerLight).setOnClickListener(v -> openMap("보안등"));
        findViewById(R.id.layerBell).setOnClickListener(v -> openMap("비상벨"));
        findViewById(R.id.layerPolice).setOnClickListener(v -> openMap("경찰서"));

        findViewById(R.id.svcRoute).setOnClickListener(toRoute);
        findViewById(R.id.btnBannerGo).setOnClickListener(toRoute);
        findViewById(R.id.fabRoute).setOnClickListener(toRoute);

        findViewById(R.id.tabMenu).setOnClickListener(toMenu);
        findViewById(R.id.btnSettings).setOnClickListener(toMenu);
    }
}