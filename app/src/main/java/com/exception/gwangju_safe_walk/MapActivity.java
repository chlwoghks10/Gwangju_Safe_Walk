package com.exception.gwangju_safe_walk;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.naver.maps.geometry.LatLng;
import com.naver.maps.geometry.LatLngBounds;
import com.naver.maps.map.CameraUpdate;
import com.naver.maps.map.MapFragment;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.OnMapReadyCallback;
import com.naver.maps.map.UiSettings;
import com.naver.maps.map.overlay.Marker;
import com.naver.maps.map.util.MarkerIcons;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MapActivity extends AppCompatActivity implements OnMapReadyCallback {

    /** 한 번에 그릴 최대 마커 수. 버벅이면 줄이세요. */
    private static final int MAX_MARKERS = 400;

    private static final int COLOR_CCTV  = Color.rgb(31, 111, 235);
    private static final int COLOR_LIGHT = Color.rgb(232, 161, 58);

    private NaverMap naverMap;
    private TextView txtCount, chipCctv, chipLight;

    private final List<double[]> cctv  = new ArrayList<>();   // {lat, lon, count}
    private final List<double[]> light = new ArrayList<>();
    private final List<Marker> shown   = new ArrayList<>();

    private boolean showCctv = true, showLight = true;
    private boolean dataReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        txtCount  = findViewById(R.id.txtCount);
        chipCctv  = findViewById(R.id.chipCctv);
        chipLight = findViewById(R.id.chipLight);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        chipCctv.setOnClickListener(v -> {
            showCctv = !showCctv;
            updateChips();
            redraw();
        });
        chipLight.setOnClickListener(v -> {
            showLight = !showLight;
            updateChips();
            redraw();
        });

        txtCount.setVisibility(View.VISIBLE);
        txtCount.setText("데이터 읽는 중…");

        loadDataInBackground();

        FragmentManager fm = getSupportFragmentManager();
        MapFragment f = (MapFragment) fm.findFragmentById(R.id.map);
        if (f == null) {
            f = MapFragment.newInstance();
            fm.beginTransaction().add(R.id.map, f).commit();
        }
        f.getMapAsync(this);
    }

    /* ─────────── 데이터 읽기 ─────────── */

    /** assets의 CSV를 별도 스레드에서 읽는다 (헤더 없음: lat,lon,count) */
    private void loadDataInBackground() {
        new Thread(() -> {
            readCsv("cctv.csv", cctv);
            readCsv("light.csv", light);
            new Handler(Looper.getMainLooper()).post(() -> {
                dataReady = true;
                redraw();
            });
        }).start();
    }

    private void readCsv(String name, List<double[]> into) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(getAssets().open(name)))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length < 3) continue;
                try {
                    into.add(new double[]{
                            Double.parseDouble(p[0]),
                            Double.parseDouble(p[1]),
                            Double.parseDouble(p[2])});
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* ─────────── 지도 ─────────── */

    @Override
    public void onMapReady(@NonNull NaverMap map) {
        naverMap = map;
        naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(MainActivity.CHOSUN, 15));

        UiSettings ui = naverMap.getUiSettings();
        ui.setAllGesturesEnabled(true);
        ui.setZoomControlEnabled(true);
        ui.setScaleBarEnabled(true);
        ui.setCompassEnabled(true);

        naverMap.setMinZoom(11);
        naverMap.setMaxZoom(19);

        naverMap.addOnCameraIdleListener(this::redraw);

        View locate = findViewById(R.id.btnLocate);
        if (locate != null) {
            locate.setOnClickListener(v ->
                    naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(MainActivity.CHOSUN, 16)));
        }

        updateChips();
        redraw();
    }

    /** 화면에 보이는 범위만, 개수를 제한해 그린다 */
    private void redraw() {
        if (naverMap == null || !dataReady) return;

        for (Marker m : shown) m.setMap(null);
        shown.clear();

        LatLngBounds b = naverMap.getContentBounds();
        double zoom = naverMap.getCameraPosition().zoom;
        boolean big = zoom >= 16;

        int budget = MAX_MARKERS / Math.max(1, (showCctv ? 1 : 0) + (showLight ? 1 : 0));
        int inView = 0, drawn = 0;

        if (showLight) {
            int[] r = drawSet(light, b, COLOR_LIGHT, big ? 20 : 14, budget);
            inView += r[0]; drawn += r[1];
        }
        if (showCctv) {
            int[] r = drawSet(cctv, b, COLOR_CCTV, big ? 30 : 22, budget);
            inView += r[0]; drawn += r[1];
        }

        if (inView == 0) {
            txtCount.setText("이 범위에 표시할 시설이 없습니다");
        } else if (drawn < inView) {
            txtCount.setText("화면 안 " + inView + "곳 중 " + drawn + "곳 표시  ·  확대해 보세요");
        } else {
            txtCount.setText("화면 안 " + inView + "곳 모두 표시");
        }
    }

    /** @return {화면 안 개수, 실제 그린 개수} */
    private int[] drawSet(List<double[]> src, LatLngBounds b, int color, int size, int budget) {
        List<double[]> vis = new ArrayList<>();
        for (double[] p : src) {
            if (b.contains(new LatLng(p[0], p[1]))) vis.add(p);
        }
        int step = vis.size() > budget ? (vis.size() + budget - 1) / budget : 1;

        int drawn = 0;
        for (int i = 0; i < vis.size(); i += step) {
            double[] p = vis.get(i);
            Marker m = new Marker();
            m.setPosition(new LatLng(p[0], p[1]));
            m.setIcon(MarkerIcons.BLACK);
            m.setIconTintColor(color);
            m.setWidth(size);
            m.setHeight(size);
            m.setHideCollidedMarkers(false);
            m.setMap(naverMap);
            shown.add(m);
            drawn++;
        }
        return new int[]{vis.size(), drawn};
    }

    /* ─────────── 칩 상태 ─────────── */

    private void updateChips() {
        styleChip(chipCctv, showCctv, COLOR_CCTV);
        styleChip(chipLight, showLight, Color.rgb(196, 130, 15));
    }

    private void styleChip(TextView chip, boolean on, int onColor) {
        chip.setAlpha(on ? 1f : 0.4f);
        chip.setTextColor(on ? onColor : Color.rgb(138, 147, 172));
    }
}
