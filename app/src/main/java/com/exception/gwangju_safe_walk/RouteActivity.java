package com.exception.gwangju_safe_walk;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.naver.maps.geometry.LatLng;
import com.naver.maps.geometry.LatLngBounds;
import com.naver.maps.map.CameraUpdate;
import com.naver.maps.map.MapFragment;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.OnMapReadyCallback;
import com.naver.maps.map.overlay.Marker;
import com.naver.maps.map.overlay.PathOverlay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 경로 비교 화면.
 * 지도에서 출발지와 도착지를 탭하면 서버가 세 경로를 돌려준다.
 *   최단   거리만 보고 찾은 길
 *   안심 A 윤해준 모델 (구간별 안전 비용)
 *   안심 B 윤신혁 모델 (안전 부족도, 최단의 1.35배 이내)
 */
public class RouteActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final String TAG = "SafeWalkRoute";

    private static final String PREF = "safewalk";
    private static final String KEY_SERVER = "server";
    private static final String DEFAULT_SERVER = "13.209.99.73:8000";

    private static final int COLOR_SHORT = Color.rgb(31, 111, 235);    // 파랑
    private static final int COLOR_SAFE  = Color.rgb(46, 140, 96);     // 초록
    private static final int COLOR_SAFE2 = Color.rgb(124, 92, 224);    // 보라

    private NaverMap naverMap;
    private EditText editStart, editEnd;
    private TextView txtShort, txtSafe, txtSafe2, btnSearch;
    private View compareBox;

    private LatLng startPoint, endPoint;
    private Marker startMarker, endMarker;
    private PathOverlay pathShort, pathSafe, pathSafe2;
    private final List<Marker> facilityMarkers = new ArrayList<>();

    /** 시설 종류별 색과 표시 크기 */
    private static final String[] FAC_KEYS   = {"light", "cctv", "bell", "police"};
    private static final int[]    FAC_COLORS = {
            Color.rgb(232, 161,  58),   // 보안등
            Color.rgb( 31, 111, 235),   // CCTV
            Color.rgb(214,  69,  69),   // 비상벨
            Color.rgb( 46, 140,  96)};  // 경찰서
    private static final int[]    FAC_SIZES  = {16, 26, 28, 30};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route);

        Ui.systemBars(this);

        editStart  = findViewById(R.id.editStart);
        editEnd    = findViewById(R.id.editEnd);
        txtShort   = findViewById(R.id.txtShort);
        txtSafe    = findViewById(R.id.txtSafe);
        txtSafe2   = findViewById(R.id.txtSafe2);
        compareBox = findViewById(R.id.compareBox);
        btnSearch  = findViewById(R.id.btnSearch);

        editStart.setFocusable(false);
        editEnd.setFocusable(false);
        editStart.setHint("지도를 눌러 출발지를 정하세요");
        editEnd.setHint("지도를 눌러 도착지를 정하세요");

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnSearch.setOnClickListener(v -> requestRoute());

        View serverBtn = findViewById(R.id.btnServer);
        if (serverBtn != null) serverBtn.setOnClickListener(v -> askServer());

        FragmentManager fm = getSupportFragmentManager();
        MapFragment f = (MapFragment) fm.findFragmentById(R.id.routeMap);
        if (f == null) {
            f = MapFragment.newInstance();
            fm.beginTransaction().add(R.id.routeMap, f).commit();
        }
        f.getMapAsync(this);
    }

    /* ─────────── 서버 주소 ─────────── */

    private String serverAddress() {
        SharedPreferences p = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return p.getString(KEY_SERVER, DEFAULT_SERVER);
    }

    /** EC2 를 껐다 켜면 주소가 바뀌므로 앱에서 직접 수정할 수 있게 한다 */
    private void askServer() {
        final EditText input = new EditText(this);
        input.setText(serverAddress());
        input.setHint("예: 13.209.99.73:8000");

        new AlertDialog.Builder(this)
                .setTitle("서버 주소")
                .setMessage("EC2 를 다시 시작하면 주소가 바뀝니다.\n새 퍼블릭 IP 를 입력하세요.")
                .setView(input)
                .setPositiveButton("저장", (d, w) -> {
                    String v = input.getText().toString().trim()
                            .replace("http://", "").replace("https://", "");
                    if (v.isEmpty()) return;
                    if (!v.contains(":")) v = v + ":8000";
                    getSharedPreferences(PREF, Context.MODE_PRIVATE)
                            .edit().putString(KEY_SERVER, v).apply();
                    Toast.makeText(this, "저장되었습니다", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /* ─────────── 지도 ─────────── */

    @Override
    public void onMapReady(@NonNull NaverMap map) {
        naverMap = map;
        naverMap.setNightModeEnabled(SunTime.isNight(35.1400, 126.9270));
        naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(MainActivity.CHOSUN, 15));
        naverMap.setOnMapClickListener((point, coord) -> onMapTapped(coord));
    }

    /** 첫 탭은 출발지, 두 번째는 도착지, 세 번째부터 다시 출발지 */
    private void onMapTapped(LatLng c) {
        if (startPoint == null || endPoint != null) {
            clearRoutes();
            endPoint = null;
            startPoint = c;

            if (endMarker != null) endMarker.setMap(null);
            if (startMarker == null) startMarker = new Marker();
            startMarker.setPosition(c);
            startMarker.setCaptionText("출발");
            startMarker.setMap(naverMap);

            editStart.setText(format(c));
            editEnd.setText("");
            compareBox.setVisibility(View.GONE);
        } else {
            endPoint = c;
            if (endMarker == null) endMarker = new Marker();
            endMarker.setPosition(c);
            endMarker.setCaptionText("도착");
            endMarker.setIconTintColor(Color.rgb(214, 69, 69));
            endMarker.setMap(naverMap);

            editEnd.setText(format(c));
        }
    }

    private String format(LatLng c) {
        return String.format("%.5f, %.5f", c.latitude, c.longitude);
    }

    private void clearRoutes() {
        if (pathShort != null) { pathShort.setMap(null); pathShort = null; }
        if (pathSafe  != null) { pathSafe.setMap(null);  pathSafe  = null; }
        if (pathSafe2 != null) { pathSafe2.setMap(null); pathSafe2 = null; }
        for (Marker m : facilityMarkers) m.setMap(null);
        facilityMarkers.clear();
    }

    /* ─────────── 서버 요청 ─────────── */

    private void requestRoute() {
        if (startPoint == null || endPoint == null) {
            Toast.makeText(this, "지도를 눌러 출발지와 도착지를 정하세요", Toast.LENGTH_SHORT).show();
            return;
        }

        final String host = serverAddress();
        btnSearch.setText("계산 중…");
        btnSearch.setEnabled(false);

        io.execute(() -> {
            String url = String.format(
                    "http://%s/route?start_lat=%f&start_lon=%f&end_lat=%f&end_lon=%f",
                    host, startPoint.latitude, startPoint.longitude,
                    endPoint.latitude, endPoint.longitude);
            try {
                String body = httpGet(url);
                JSONObject json = new JSONObject(body);
                ui.post(() -> {
                    btnSearch.setText("경로 비교하기");
                    btnSearch.setEnabled(true);
                    showResult(json);
                });
            } catch (Exception e) {
                Log.e(TAG, "요청 실패: " + e.getMessage(), e);
                final String msg = e.getMessage() == null ? "알 수 없는 오류" : e.getMessage();
                ui.post(() -> {
                    btnSearch.setText("경로 비교하기");
                    btnSearch.setEnabled(true);
                    new AlertDialog.Builder(this)
                            .setTitle("서버에 연결하지 못했습니다")
                            .setMessage(msg + "\n\n현재 주소: " + host
                                    + "\n\nEC2 를 다시 시작했다면 주소가 바뀌었을 수 있습니다.")
                            .setPositiveButton("주소 변경", (d, w) -> askServer())
                            .setNegativeButton("닫기", null)
                            .show();
                });
            }
        });
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        try {
            int code = conn.getResponseCode();
            java.io.InputStream in = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            if (code < 200 || code >= 300) {
                String detail = sb.toString();
                try {
                    detail = new JSONObject(detail).optString("detail", detail);
                } catch (Exception ignored) { }
                throw new Exception(detail);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /* ─────────── 결과 표시 ─────────── */

    private void showResult(JSONObject json) {
        try {
            JSONObject sh = json.getJSONObject("shortest");
            JSONObject sa = json.getJSONObject("safest");
            JSONObject sb = json.optJSONObject("safest2");       // 없으면 옛 서버
            boolean same  = json.optBoolean("same", false);
            boolean same2 = json.optBoolean("same2", false);

            clearRoutes();

            List<LatLng> shc = coords(sh.getJSONArray("coords"));
            List<LatLng> sac = coords(sa.getJSONArray("coords"));
            List<LatLng> sbc = sb == null ? null : coords(sb.getJSONArray("coords"));

            // 굵은 것부터 깔아야 위의 선이 가려지지 않는다
            pathShort = drawPath(shc, COLOR_SHORT, 15);
            if (!same)  pathSafe  = drawPath(sac, COLOR_SAFE, 10);
            if (sbc != null && !same2) pathSafe2 = drawPath(sbc, COLOR_SAFE2, 6);

            drawFacilities(same ? sh : sa);

            txtShort.setText(summary(sh, 0));
            txtSafe.setText(same
                    ? "최단 경로와 동일"
                    : summary(sa, sa.getDouble("length_m") - sh.getDouble("length_m")));

            if (sb == null) {
                txtSafe2.setText("서버가 아직 이 경로를 보내지 않습니다");
            } else if (same2) {
                txtSafe2.setText("최단 경로와 동일");
            } else {
                txtSafe2.setText(summary(sb, sb.getDouble("length_m") - sh.getDouble("length_m")));
            }

            compareBox.setVisibility(View.VISIBLE);

            List<LatLng> all = new ArrayList<>(shc);
            all.addAll(sac);
            if (sbc != null) all.addAll(sbc);
            fitTo(all);

        } catch (Exception e) {
            Log.e(TAG, "응답 해석 실패", e);
            Toast.makeText(this, "응답을 읽지 못했습니다", Toast.LENGTH_SHORT).show();
        }
    }

    /** 한 줄 요약. extra 가 0 보다 크면 추가 거리를 괄호로 덧붙인다 */
    private String summary(JSONObject r, double extra) throws Exception {
        double m = r.getDouble("length_m");
        String head = String.format("%,.0f m · 도보 %d분", m, Math.max(1, (int) Math.round(m / 67.0)));
        if (extra > 0.5) head += String.format("  (+%,.0f m)", extra);
        return head + String.format("\nCCTV %d대 · 보안등 %d개", r.getInt("cctv"), r.getInt("light"));
    }

    /** 응답의 facilities 를 종류별 색으로 지도에 찍는다 */
    private void drawFacilities(JSONObject route) {
        JSONObject fac = route.optJSONObject("facilities");
        if (fac == null) return;

        for (int k = 0; k < FAC_KEYS.length; k++) {
            JSONArray arr = fac.optJSONArray(FAC_KEYS[k]);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONArray p = arr.optJSONArray(i);
                if (p == null || p.length() < 2) continue;
                Marker m = new Marker();
                m.setPosition(new LatLng(p.optDouble(0), p.optDouble(1)));
                m.setIcon(com.naver.maps.map.util.MarkerIcons.BLACK);
                m.setIconTintColor(FAC_COLORS[k]);
                m.setWidth(FAC_SIZES[k]);
                m.setHeight(FAC_SIZES[k]);
                m.setHideCollidedMarkers(false);
                m.setZIndex(-1);
                m.setMap(naverMap);
                facilityMarkers.add(m);
            }
        }
    }

    private List<LatLng> coords(JSONArray arr) throws Exception {
        List<LatLng> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONArray p = arr.getJSONArray(i);
            out.add(new LatLng(p.getDouble(0), p.getDouble(1)));
        }
        return out;
    }

    private PathOverlay drawPath(List<LatLng> pts, int color, int width) {
        if (pts.size() < 2) return null;
        PathOverlay p = new PathOverlay();
        p.setCoords(pts);
        p.setColor(color);
        p.setWidth(width);
        p.setOutlineWidth(0);
        p.setMap(naverMap);
        return p;
    }

    private void fitTo(List<LatLng> pts) {
        if (pts.isEmpty()) return;
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (LatLng p : pts) b.include(p);
        naverMap.moveCamera(CameraUpdate.fitBounds(b.build(), 120, 120, 120, 880));
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}