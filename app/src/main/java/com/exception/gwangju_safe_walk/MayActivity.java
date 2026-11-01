package com.exception.gwangju_safe_walk;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentManager;

import com.naver.maps.geometry.LatLng;
import com.naver.maps.map.CameraUpdate;
import com.naver.maps.map.MapFragment;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.OnMapReadyCallback;
import com.naver.maps.map.overlay.Marker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MayActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final String TAG = "SafeWalkMay";

    private static final String PREF = "safewalk";
    private static final String KEY_SERVER = "server";
    private static final String DEFAULT_SERVER = "13.209.99.73:8000";

    private NaverMap naverMap;
    private Marker pin;

    private LinearLayout spotBox;
    private View pickedBox, mayHint;
    private TextView pickedName, pickedRange, btnPickedRoute;
    private ImageView btnPickedStar;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String curName;
    private double curLat, curLon;
    private boolean curInRange;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_may);

        Ui.systemBars(this);

        spotBox        = findViewById(R.id.spotBox);
        pickedBox      = findViewById(R.id.pickedBox);
        mayHint        = findViewById(R.id.mayHint);
        pickedName     = findViewById(R.id.pickedName);
        pickedRange    = findViewById(R.id.pickedRange);
        btnPickedRoute = findViewById(R.id.btnPickedRoute);
        btnPickedStar  = findViewById(R.id.btnPickedStar);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnPickedRoute.setOnClickListener(v -> goRoute());
        btnPickedStar.setOnClickListener(v -> toggleStar());

        TextView sub = findViewById(R.id.maySub);
        sub.setText("광주 5·18 사적지 " + Place518.count() + "곳");

        buildSpots();

        FragmentManager fm = getSupportFragmentManager();
        MapFragment f = (MapFragment) fm.findFragmentById(R.id.mayMap);
        if (f == null) {
            f = MapFragment.newInstance();
            fm.beginTransaction().add(R.id.mayMap, f).commit();
        }
        f.getMapAsync(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (curName != null) paintStar();
    }

    @Override
    public void onMapReady(@NonNull NaverMap map) {
        naverMap = map;
        naverMap.setNightModeEnabled(SunTime.isNight(
                MainActivity.CHOSUN.latitude, MainActivity.CHOSUN.longitude));
        naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(MainActivity.CHOSUN, 12));
        naverMap.getUiSettings().setZoomControlEnabled(false);
    }

    private void buildSpots() {
        spotBox.removeAllViews();
        List<Place518.Spot> all = Place518.all();

        for (int i = 0; i < all.size(); i++) {
            final Place518.Spot s = all.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(18), dp(13), dp(18), dp(13));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_row_ripple));
            row.setOnClickListener(v -> pick(s));

            TextView no = new TextView(this);
            no.setText(String.valueOf(i + 1));
            no.setTextSize(11);
            no.setTextColor(ContextCompat.getColor(this, R.color.may));
            no.setGravity(Gravity.CENTER);
            no.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_may_badge));
            no.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
            row.addView(no);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            cp.setMarginStart(dp(12));
            col.setLayoutParams(cp);

            TextView name = new TextView(this);
            name.setText(s.name);
            name.setTextSize(15);
            name.setTextColor(ContextCompat.getColor(this, R.color.text_main));
            col.addView(name);

            TextView note = new TextView(this);
            note.setText(s.area + " · " + s.note);
            note.setTextSize(12);
            note.setTextColor(ContextCompat.getColor(this, R.color.text_weak));
            col.addView(note);

            row.addView(col);

            ImageView chev = new ImageView(this);
            chev.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_chevron));
            chev.setColorFilter(ContextCompat.getColor(this, R.color.text_weak));
            chev.setLayoutParams(new LinearLayout.LayoutParams(dp(15), dp(15)));
            row.addView(chev);

            spotBox.addView(row);
        }
    }

    private void pick(Place518.Spot s) {
        curName = s.name;
        curInRange = false;
        curLat = curLon = 0;

        pickedName.setText(s.name);
        pickedRange.setText("위치를 찾는 중…");
        btnPickedRoute.setEnabled(false);
        btnPickedRoute.setAlpha(0.4f);
        paintStar();

        mayHint.setVisibility(View.GONE);
        pickedBox.setVisibility(View.VISIBLE);

        lookUp(s.name);
    }

    // 사적지 좌표는 적어 두지 않고 장소 검색으로 받아 쓴다
    private void lookUp(final String name) {
        final String host = serverAddress();
        io.execute(() -> {
            try {
                String url = "http://" + host + "/search?q=" + URLEncoder.encode(name, "UTF-8");
                JSONArray arr = new JSONObject(httpGet(url)).getJSONArray("places");
                if (arr.length() == 0) throw new Exception("검색 결과 없음");

                JSONObject best = arr.getJSONObject(0);
                final double lat = best.optDouble("lat", 0), lon = best.optDouble("lon", 0);
                final boolean inRange = best.optBoolean("in_range", false);

                ui.post(() -> {
                    // 그 사이 다른 사적지를 골랐으면 버린다
                    if (!name.equals(curName)) return;
                    curLat = lat; curLon = lon; curInRange = inRange;
                    dropPin(name, lat, lon);
                    pickedRange.setText(inRange
                            ? "도로망 안에 있어 경로를 만들 수 있습니다"
                            : "도로망(조선대 주변 2km) 밖이라 경로는 아직 만들 수 없습니다");
                    btnPickedRoute.setEnabled(inRange);
                    btnPickedRoute.setAlpha(inRange ? 1f : 0.4f);
                    paintStar();
                });

            } catch (Exception e) {
                Log.e(TAG, "장소 찾기 실패: " + e.getMessage(), e);
                ui.post(() -> {
                    if (!name.equals(curName)) return;
                    pickedRange.setText("위치를 찾지 못했습니다. 서버 연결을 확인하세요");
                });
            }
        });
    }

    private void dropPin(String name, double lat, double lon) {
        if (naverMap == null) return;
        if (pin == null) pin = new Marker();
        pin.setPosition(new LatLng(lat, lon));
        pin.setCaptionText(name);
        pin.setIconTintColor(ContextCompat.getColor(this, R.color.may));
        pin.setMap(naverMap);
        naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(new LatLng(lat, lon), 15));
    }

    private void goRoute() {
        if (!curInRange || curName == null) return;
        Intent i = new Intent(this, RouteActivity.class);
        i.putExtra(RouteActivity.EXTRA_DEST_NAME, curName);
        i.putExtra(RouteActivity.EXTRA_DEST_LAT, curLat);
        i.putExtra(RouteActivity.EXTRA_DEST_LON, curLon);
        startActivity(i);
    }

    private void toggleStar() {
        if (curName == null) return;
        if (curLat == 0 && curLon == 0) {
            Toast.makeText(this, "위치를 찾은 뒤에 저장할 수 있습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean on = Saved.toggle(this, curName, curLat, curLon);
        paintStar();
        Toast.makeText(this, on ? "저장했습니다" : "저장을 해제했습니다", Toast.LENGTH_SHORT).show();
    }

    private void paintStar() {
        boolean on = curName != null && Saved.has(this, curName);
        btnPickedStar.setColorFilter(ContextCompat.getColor(this,
                on ? R.color.may : R.color.text_weak));
        btnPickedStar.setAlpha(on ? 1f : 0.7f);
    }

    private String serverAddress() {
        return getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_SERVER, DEFAULT_SERVER);
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(8000);
        try {
            int code = conn.getResponseCode();
            java.io.InputStream in = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            if (code < 200 || code >= 300) throw new Exception(sb.toString());
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
