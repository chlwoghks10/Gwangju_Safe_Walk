package com.exception.gwangju_safe_walk;

import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MapActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final String TAG = "SafeWalkMap";

    /** 한 레이어가 이 수를 넘으면 그리지 않고 확대를 안내한다 (멈춤 방지) */
    private static final int SAFETY_CAP = 1200;

    private static class Layer {
        final String file, label;
        final int color, sizeSmall, sizeBig;
        final double minZoom;                 // 이 줌 이상에서만 표시
        final List<double[]> pts = new ArrayList<>();   // {lat, lon, count}
        boolean on = true;
        TextView chip;

        Layer(String file, String label, int color, int sizeSmall, int sizeBig, double minZoom) {
            this.file = file; this.label = label; this.color = color;
            this.sizeSmall = sizeSmall; this.sizeBig = sizeBig; this.minZoom = minZoom;
        }
    }

    private final List<Layer> layers = new ArrayList<>();

    private NaverMap naverMap;
    private TextView txtCount;

    private final List<Marker> pool = new ArrayList<>();
    private int poolUsed = 0;

    private boolean dataReady = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_map);

        txtCount = findViewById(R.id.txtCount);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        // 지점이 많은 레이어일수록 더 확대해야 표시된다
        layers.add(new Layer("light.geojson",  "보안등", Color.rgb(232, 161,  58), 14, 20, 16));
        layers.add(new Layer("cctv.geojson",   "CCTV",   Color.rgb( 31, 111, 235), 22, 30, 15));
        layers.add(new Layer("bell.geojson",   "비상벨", Color.rgb(214,  69,  69), 24, 32, 11));
        layers.add(new Layer("police.geojson", "경찰서", Color.rgb( 46, 140,  96), 26, 34, 11));

        bindChips();

        txtCount.setVisibility(View.VISIBLE);
        txtCount.setText("데이터 읽는 중…");
        loadInBackground();

        FragmentManager fm = getSupportFragmentManager();
        MapFragment f = (MapFragment) fm.findFragmentById(R.id.map);
        if (f == null) {
            f = MapFragment.newInstance();
            fm.beginTransaction().add(R.id.map, f).commit();
        }
        f.getMapAsync(this);
    }

    private void bindChips() {
        // 홈이나 메뉴에서 특정 레이어를 지정해 들어온 경우 그것만 켠다
        String only = getIntent().getStringExtra("only");

        int[] ids = {R.id.chipLight, R.id.chipCctv, R.id.chipBell, R.id.chipPolice};
        for (int i = 0; i < layers.size() && i < ids.length; i++) {
            final Layer L = layers.get(i);

            if (only != null) L.on = L.label.equals(only);

            L.chip = findViewById(ids[i]);
            if (L.chip == null) continue;
            L.chip.setText(L.label);
            L.chip.setOnClickListener(v -> {
                L.on = !L.on;
                styleChip(L);
                redraw();
            });
            styleChip(L);
        }
    }

    private void styleChip(Layer L) {
        if (L.chip == null) return;
        L.chip.setAlpha(L.on ? 1f : 0.4f);
        L.chip.setTextColor(L.on ? L.color : Color.rgb(138, 147, 172));
    }

    /* ─────────── 데이터 읽기 ─────────── */

    private void loadInBackground() {
        new Thread(() -> {
            for (Layer L : layers) {
                try {
                    if (L.file.endsWith(".geojson") || L.file.endsWith(".json")) readGeoJson(L);
                    else readCsv(L);
                    Log.i(TAG, L.label + " " + L.pts.size() + "지점 로드");
                } catch (Exception e) {
                    Log.e(TAG, L.label + " 로드 실패: " + e.getMessage());
                }
            }
            new Handler(Looper.getMainLooper()).post(() -> {
                dataReady = true;
                redraw();
            });
        }).start();
    }

    private void readCsv(Layer L) throws Exception {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(getAssets().open(L.file)))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length < 2) continue;
                try {
                    L.pts.add(new double[]{
                            Double.parseDouble(p[0].trim()),
                            Double.parseDouble(p[1].trim()),
                            p.length >= 3 ? Double.parseDouble(p[2].trim()) : 1});
                } catch (NumberFormatException ignored) { }
            }
        }
    }

    /** GeoJSON 좌표 순서는 [경도, 위도] 이므로 뒤집어 저장 */
    private void readGeoJson(Layer L) throws Exception {
        JSONObject root = new JSONObject(readAll(getAssets().open(L.file)));
        JSONArray feats = root.optJSONArray("features");
        if (feats == null) {
            addGeometry(L, root.optJSONObject("geometry") != null
                    ? root.getJSONObject("geometry") : root, 1);
            return;
        }
        for (int i = 0; i < feats.length(); i++) {
            JSONObject f = feats.optJSONObject(i);
            if (f == null) continue;
            double count = 1;
            JSONObject props = f.optJSONObject("properties");
            if (props != null) {
                for (String key : new String[]{"cnt", "count", "카메라대수", "설치개수", "대수", "개수"}) {
                    if (props.has(key)) { count = props.optDouble(key, 1); break; }
                }
                if (i == 0) Log.i(TAG, L.label + " 속성 예시: " + props.toString());
            }
            addGeometry(L, f.optJSONObject("geometry"), count);
        }
    }

    private void addGeometry(Layer L, JSONObject geom, double count) {
        if (geom == null) return;
        String type = geom.optString("type", "");
        JSONArray c = geom.optJSONArray("coordinates");
        if (c == null) return;
        try {
            if ("Point".equals(type)) {
                L.pts.add(new double[]{c.getDouble(1), c.getDouble(0), count});
            } else if ("MultiPoint".equals(type)) {
                for (int i = 0; i < c.length(); i++) {
                    JSONArray p = c.getJSONArray(i);
                    L.pts.add(new double[]{p.getDouble(1), p.getDouble(0), count});
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "좌표 해석 실패: " + e.getMessage());
        }
    }

    private String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    /* ─────────── 지도 ─────────── */

    @Override
    public void onMapReady(@NonNull NaverMap map) {
        naverMap = map;

        // 특정 레이어로 들어왔다면 그 레이어가 보이는 줌에서 시작한다
        String only = getIntent().getStringExtra("only");
        double startZoom = 16;
        if (only != null) {
            for (Layer L : layers) {
                if (L.label.equals(only)) {
                    startZoom = Math.max(L.minZoom, 15);
                    break;
                }
            }
        }
        naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(MainActivity.CHOSUN, startZoom));

        UiSettings ui = naverMap.getUiSettings();
        ui.setAllGesturesEnabled(true);
        ui.setZoomControlEnabled(true);
        ui.setScaleBarEnabled(true);
        ui.setCompassEnabled(true);

        naverMap.setMinZoom(11);
        naverMap.setMaxZoom(19);
        naverMap.addOnCameraIdleListener(this::redraw);

        final double zoomForLocate = startZoom;
        View locate = findViewById(R.id.btnLocate);
        if (locate != null) {
            locate.setOnClickListener(v ->
                    naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(MainActivity.CHOSUN, zoomForLocate)));
        }
        redraw();
    }

    /**
     * 표시 규칙
     *  - 줌이 레이어 기준에 못 미치면 그 레이어는 그리지 않는다
     *  - 그릴 때는 화면 안의 지점을 하나도 빠짐없이 그린다
     *  → 같은 위치를 다시 봐도 항상 같은 마커가 같은 자리에 찍힌다
     */
    private void redraw() {
        if (naverMap == null || !dataReady) return;

        poolUsed = 0;

        LatLngBounds b = naverMap.getContentBounds();
        double zoom = naverMap.getCameraPosition().zoom;
        boolean big = zoom >= 17;

        int shownTotal = 0;
        List<String> waiting = new ArrayList<>();

        for (Layer L : layers) {
            if (!L.on) continue;

            if (zoom < L.minZoom) {            // 아직 표시할 줌이 아님
                waiting.add(L.label);
                continue;
            }

            List<double[]> vis = new ArrayList<>();
            for (double[] p : L.pts) {
                if (b.contains(new LatLng(p[0], p[1]))) vis.add(p);
            }
            if (vis.size() > SAFETY_CAP) {     // 지나치게 많으면 보류
                waiting.add(L.label);
                continue;
            }

            for (double[] p : vis) {
                Marker m = obtain();
                m.setPosition(new LatLng(p[0], p[1]));
                m.setIconTintColor(L.color);
                m.setWidth(big ? L.sizeBig : L.sizeSmall);
                m.setHeight(big ? L.sizeBig : L.sizeSmall);
                if (m.getMap() == null) m.setMap(naverMap);
            }
            shownTotal += vis.size();
        }

        hideRest();

        if (!waiting.isEmpty()) {
            txtCount.setText(String.join("·", waiting) + " 은 확대하면 표시됩니다"
                    + (shownTotal > 0 ? "   (현재 " + shownTotal + "곳)" : ""));
        } else if (shownTotal == 0) {
            txtCount.setText("이 범위에 표시할 시설이 없습니다");
        } else {
            txtCount.setText("화면 안 " + shownTotal + "곳 모두 표시");
        }
    }

    private Marker obtain() {
        if (poolUsed < pool.size()) return pool.get(poolUsed++);
        Marker m = new Marker();
        m.setIcon(MarkerIcons.BLACK);
        m.setHideCollidedMarkers(false);
        pool.add(m);
        poolUsed++;
        return m;
    }

    private void hideRest() {
        for (int i = poolUsed; i < pool.size(); i++) {
            Marker m = pool.get(i);
            if (m.getMap() != null) m.setMap(null);
        }
    }
}