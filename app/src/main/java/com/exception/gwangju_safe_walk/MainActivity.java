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

    /** 미니맵: 조작 불가, 미리보기 용도 */
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

    private void setupClicks() {
        View.OnClickListener toMap   = v -> startActivity(new Intent(this, MapActivity.class));
        View.OnClickListener toRoute = v -> startActivity(new Intent(this, RouteActivity.class));

        findViewById(R.id.btnExpandMap).setOnClickListener(toMap);
        findViewById(R.id.svcMap).setOnClickListener(toMap);
        findViewById(R.id.layerCctv).setOnClickListener(toMap);
        findViewById(R.id.layerLight).setOnClickListener(toMap);

        findViewById(R.id.svcRoute).setOnClickListener(toRoute);
        findViewById(R.id.btnBannerGo).setOnClickListener(toRoute);
        findViewById(R.id.fabRoute).setOnClickListener(toRoute);

        // 아직 준비 중인 기능
        View.OnClickListener soon = v -> android.widget.Toast
                .makeText(this, "준비 중인 기능입니다", android.widget.Toast.LENGTH_SHORT).show();
        findViewById(R.id.svcGap).setOnClickListener(soon);
        findViewById(R.id.svcShare).setOnClickListener(soon);
        findViewById(R.id.layerBell).setOnClickListener(soon);
        findViewById(R.id.tabMenu).setOnClickListener(soon);
        findViewById(R.id.btnSettings).setOnClickListener(soon);
    }
}
