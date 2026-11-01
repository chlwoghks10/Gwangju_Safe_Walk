package com.exception.gwangju_safe_walk;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.naver.maps.geometry.LatLng;
import com.naver.maps.geometry.LatLngBounds;
import com.naver.maps.map.CameraUpdate;
import com.naver.maps.map.LocationTrackingMode;
import com.naver.maps.map.MapFragment;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.OnMapReadyCallback;
import com.naver.maps.map.util.FusedLocationSource;
import com.naver.maps.map.overlay.Marker;
import com.naver.maps.map.overlay.PathOverlay;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RouteActivity extends AppCompatActivity implements OnMapReadyCallback {
    private static final String TAG = "SafeWalkRoute";

    private static final String PREF = "safewalk";
    private static final String KEY_SERVER = "server";
    private static final String DEFAULT_SERVER = "13.209.99.73:8000";

    public static final String EXTRA_DEST_NAME = "dest_name";
    public static final String EXTRA_DEST_LAT  = "dest_lat";
    public static final String EXTRA_DEST_LON  = "dest_lon";

    private static final int COLOR_SHORT = Color.rgb(31, 111, 235);
    private static final int COLOR_SAFE  = Color.rgb(46, 140, 96);
    private static final int COLOR_SAFE2 = Color.rgb(124, 92, 224);

    private static final double WALK_M_PER_MIN = 67.0;

    private NaverMap naverMap;
    private EditText editStart, editEnd;
    private View compareBox;
    private TextView btnSearch;

    private View routeTabs;
    private LinearLayout tabBox;

    private View routeColorBar;
    private TextView txtRouteName, txtRouteTime, txtRouteMeta, txtRouteArrive;
    private LinearLayout facBox;
    private TextView btnGuide, btnVoiceTest, btnMute;

    private LatLng startPoint, endPoint;
    private Marker startMarker, endMarker;
    private final List<Marker> facilityMarkers = new ArrayList<>();

    private static final String[] FAC_KEYS   = {"light", "cctv", "bell", "police"};
    private static final String[] FAC_NAMES  = {"보안등", "CCTV", "비상벨", "경찰서"};
    private static final int[]    FAC_COLORS = {
            Color.rgb(232, 161,  58),
            Color.rgb( 31, 111, 235),
            Color.rgb(214,  69,  69),
            Color.rgb( 46, 140,  96)};
    private static final int[]    FAC_SIZES  = {16, 26, 28, 30};

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final int REQ_LOCATION = 1000;
    private FusedLocationSource locationSource;

    private static final int WANT_NONE = 0, WANT_START = 1, WANT_END = 2;
    private int wantMyLocation = WANT_NONE;

    private Voice voice;

    // 안내 지점 몇 m 앞에서 읽어 줄지 (팀장 voiceGuide.js 의 TRIGGER_M 과 같은 값)
    private static final double TRIGGER_M = 30;
    private boolean guiding = false;
    private int nextTurn = 0;

    private static class Turn {
        final double lat, lon;
        final String text;
        Turn(double lat, double lon, String text) {
            this.lat = lat; this.lon = lon; this.text = text;
        }
    }

    private static class RouteInfo {
        String name;
        int color;
        JSONObject json;
        List<LatLng> pts;
        double lengthM;
        double extraM;
        int[] fac = new int[4];
        List<Turn> turns = new ArrayList<>();
        boolean sameAsShortest;
        PathOverlay overlay;

        int minutes() {
            return Math.max(1, (int) Math.round(lengthM / WALK_M_PER_MIN));
        }
    }

    private final List<RouteInfo> routes = new ArrayList<>();
    private int selected = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route);

        Ui.systemBars(this);

        editStart  = findViewById(R.id.editStart);
        editEnd    = findViewById(R.id.editEnd);
        compareBox = findViewById(R.id.compareBox);
        btnSearch  = findViewById(R.id.btnSearch);

        routeTabs  = findViewById(R.id.routeTabs);
        tabBox     = findViewById(R.id.tabBox);

        routeColorBar  = findViewById(R.id.routeColorBar);
        txtRouteName   = findViewById(R.id.txtRouteName);
        txtRouteTime   = findViewById(R.id.txtRouteTime);
        txtRouteMeta   = findViewById(R.id.txtRouteMeta);
        txtRouteArrive = findViewById(R.id.txtRouteArrive);
        facBox         = findViewById(R.id.facBox);
        btnGuide       = findViewById(R.id.btnGuide);
        btnVoiceTest   = findViewById(R.id.btnVoiceTest);
        btnMute        = findViewById(R.id.btnMute);

        editStart.setFocusable(false);
        editEnd.setFocusable(false);
        editStart.setHint("눌러서 장소 검색 · 또는 지도를 탭");
        editEnd.setHint("눌러서 장소 검색 · 또는 지도를 탭");

        editStart.setOnClickListener(v -> openSearch(true));
        editEnd.setOnClickListener(v -> openSearch(false));

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        btnSearch.setOnClickListener(v -> requestRoute());

        locationSource = new FusedLocationSource(this, REQ_LOCATION);

        View locateBtn = findViewById(R.id.btnLocate);
        if (locateBtn != null) locateBtn.setOnClickListener(v -> showMyLocationOnMap());

        voice = new Voice(this, (ready, message) -> ui.post(() -> {
            if (ready) return;
            // ui.post 로 넘겼으므로 이 시점에는 voice 가 들어가 있다
            if (voice == null || !voice.isMuted()) btnMute.setText("음성 불가");
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }));

        btnGuide.setOnClickListener(v -> startGuide());
        btnVoiceTest.setOnClickListener(v -> voice.say("300미터 앞 좌회전입니다.", true));
        btnMute.setOnClickListener(v -> {
            boolean muted = voice.toggleMute();
            btnMute.setText(muted ? "음성 꺼짐" : "음성 켜짐");
        });

        FragmentManager fm = getSupportFragmentManager();
        MapFragment f = (MapFragment) fm.findFragmentById(R.id.routeMap);
        if (f == null) {
            f = MapFragment.newInstance();
            fm.beginTransaction().add(R.id.routeMap, f).commit();
        }
        f.getMapAsync(this);

        Tabs.attach(this, Tabs.ROUTE);
        takeDest(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Tabs.attach(this, Tabs.ROUTE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        takeDest(intent);
    }

    private LatLng pendingDest;
    private String pendingDestName;

    private void takeDest(Intent i) {
        if (i == null) return;
        String name = i.getStringExtra(EXTRA_DEST_NAME);
        double lat = i.getDoubleExtra(EXTRA_DEST_LAT, 0);
        double lon = i.getDoubleExtra(EXTRA_DEST_LON, 0);
        if (name == null || (lat == 0 && lon == 0)) return;

        i.removeExtra(EXTRA_DEST_NAME);

        pendingDest = new LatLng(lat, lon);
        pendingDestName = name;
        if (naverMap != null) applyPendingDest();
    }

    private void applyPendingDest() {
        if (pendingDest == null) return;
        LatLng c = pendingDest;
        String name = pendingDestName;
        pendingDest = null;
        pendingDestName = null;

        setEnd(c, name);
        naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(c, 16));
        Toast.makeText(this, "출발지를 정하면 경로를 비교할 수 있습니다", Toast.LENGTH_SHORT).show();
    }

    private String serverAddress() {
        SharedPreferences p = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return p.getString(KEY_SERVER, DEFAULT_SERVER);
    }

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

    @Override
    public void onMapReady(@NonNull NaverMap map) {
        naverMap = map;
        naverMap.setNightModeEnabled(SunTime.isNight(35.1400, 126.9270));
        naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(MainActivity.CHOSUN, 15));
        naverMap.setOnMapClickListener((point, coord) -> onMapTapped(coord));

        naverMap.setLocationSource(locationSource);
        naverMap.addOnLocationChangeListener(this::onLocationChanged);
        naverMap.getUiSettings().setZoomControlEnabled(false);

        applyPendingDest();
    }

    private void showMyLocationOnMap() {
        if (naverMap == null) return;
        naverMap.setLocationTrackingMode(LocationTrackingMode.Follow);
    }

    private void pickMyLocation(int target) {
        if (naverMap == null) return;
        wantMyLocation = target;
        naverMap.setLocationTrackingMode(LocationTrackingMode.Follow);
        Toast.makeText(this, "현재 위치를 찾고 있습니다…", Toast.LENGTH_SHORT).show();
    }

    private void onLocationChanged(@NonNull Location loc) {
        checkGuide(loc);

        // 버튼을 누른 직후 한 번만 쓴다. 계속 덮어쓰면 출발지가 따라 움직인다
        if (wantMyLocation == WANT_NONE) return;
        int target = wantMyLocation;
        wantMyLocation = WANT_NONE;

        LatLng c = new LatLng(loc.getLatitude(), loc.getLongitude());
        if (target == WANT_START) setStart(c, "내 위치");
        else                      setEnd(c, "내 위치");

        naverMap.setLocationTrackingMode(LocationTrackingMode.NoFollow);
        Toast.makeText(this,
                target == WANT_START ? "현재 위치를 출발지로 정했습니다"
                                     : "현재 위치를 도착지로 정했습니다",
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (locationSource.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            if (!locationSource.isActivated()) {
                wantMyLocation = WANT_NONE;
                if (naverMap != null) naverMap.setLocationTrackingMode(LocationTrackingMode.None);
                Toast.makeText(this,
                        "위치 권한이 없어 현재 위치를 쓸 수 없습니다. 지도를 눌러 출발지를 정하세요",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    private void onMapTapped(LatLng c) {
        if (startPoint == null || endPoint != null) setStart(c);
        else setEnd(c);
    }

    private void setStart(LatLng c) {
        setStart(c, null);
    }

    private void setStart(LatLng c, String label) {
        clearRoutes();
        endPoint = null;
        startPoint = c;

        if (endMarker != null) endMarker.setMap(null);
        if (startMarker == null) startMarker = new Marker();
        startMarker.setPosition(c);
        startMarker.setCaptionText("출발");
        startMarker.setMap(naverMap);

        editStart.setText(label != null ? label : format(c));
        editEnd.setText("");
        compareBox.setVisibility(View.GONE);
    }

    private void setEnd(LatLng c) {
        setEnd(c, null);
    }

    private void setEnd(LatLng c, String label) {
        endPoint = c;
        if (endMarker == null) endMarker = new Marker();
        endMarker.setPosition(c);
        endMarker.setCaptionText("도착");
        endMarker.setIconTintColor(Color.rgb(214, 69, 69));
        endMarker.setMap(naverMap);

        editEnd.setText(label != null ? label : format(c));
    }

    private String format(LatLng c) {
        return String.format(Locale.KOREA, "%.5f, %.5f", c.latitude, c.longitude);
    }

    private void clearRoutes() {
        for (RouteInfo r : routes) {
            if (r.overlay != null) r.overlay.setMap(null);
        }
        routes.clear();
        for (Marker m : facilityMarkers) m.setMap(null);
        facilityMarkers.clear();

        tabBox.removeAllViews();
        routeTabs.setVisibility(View.GONE);
        stopGuide();
    }

    private static final int REQ_SEARCH_START = 2001;
    private static final int REQ_SEARCH_END   = 2002;

    private void openSearch(boolean forStart) {
        Intent i = new Intent(this, SearchActivity.class);
        i.putExtra(SearchActivity.EXTRA_FOR_START, forStart);
        startActivityForResult(i, forStart ? REQ_SEARCH_START : REQ_SEARCH_END);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode != REQ_SEARCH_START && requestCode != REQ_SEARCH_END) return;

        boolean toStart = (requestCode == REQ_SEARCH_START);

        if (data.getBooleanExtra(SearchActivity.EXTRA_USE_MY_LOCATION, false)) {
            pickMyLocation(toStart ? WANT_START : WANT_END);
            return;
        }

        String name = data.getStringExtra(SearchActivity.EXTRA_NAME);
        double lat  = data.getDoubleExtra(SearchActivity.EXTRA_LAT, 0);
        double lon  = data.getDoubleExtra(SearchActivity.EXTRA_LON, 0);
        if (lat == 0 && lon == 0) return;

        LatLng c = new LatLng(lat, lon);
        if (toStart) setStart(c, name);
        else         setEnd(c, name);
        if (naverMap != null) naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(c, 16));
    }

    private void requestRoute() {
        if (startPoint == null || endPoint == null) {
            Toast.makeText(this, "지도를 눌러 출발지와 도착지를 정하세요", Toast.LENGTH_SHORT).show();
            return;
        }

        final String host = serverAddress();
        btnSearch.setText("계산 중…");
        btnSearch.setEnabled(false);

        io.execute(() -> {
            String url = String.format(Locale.US,
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

                    // 서버가 한글로 이유를 돌려준 경우는 연결 실패가 아니라 거부다
                    boolean refused = msg.contains("도로망") || msg.contains("가깝습니다")
                            || msg.contains("이어지지");
                    if (refused) {
                        new AlertDialog.Builder(this)
                                .setTitle("경로를 만들 수 없습니다")
                                .setMessage(msg + "\n\n현재 도로망은 조선대 주변 2km 입니다.")
                                .setPositiveButton("닫기", null)
                                .show();
                    } else {
                        new AlertDialog.Builder(this)
                                .setTitle("서버에 연결하지 못했습니다")
                                .setMessage(msg + "\n\n현재 주소: " + host
                                        + "\n\nEC2 를 다시 시작했다면 주소가 바뀌었을 수 있습니다.")
                                .setPositiveButton("주소 변경", (d, w) -> askServer())
                                .setNegativeButton("닫기", null)
                                .show();
                    }
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

    private void showResult(JSONObject json) {
        try {
            JSONObject sh = json.getJSONObject("shortest");
            JSONObject sa = json.getJSONObject("safest");
            JSONObject sb = json.optJSONObject("safest2");
            boolean same  = json.optBoolean("same", false);
            boolean same2 = json.optBoolean("same2", false);

            clearRoutes();

            // 서버는 탭한 지점을 가장 가까운 도로 위로 옮겨 경로를 만든다.
            // 마커도 같이 옮기지 않으면 마커와 경로선이 떨어져 보인다.
            JSONObject snap = json.optJSONObject("snap");
            if (snap != null) {
                JSONArray s0 = snap.optJSONArray("start");
                JSONArray e0 = snap.optJSONArray("end");
                if (s0 != null && s0.length() == 2 && startMarker != null) {
                    startMarker.setPosition(new LatLng(s0.getDouble(0), s0.getDouble(1)));
                }
                if (e0 != null && e0.length() == 2 && endMarker != null) {
                    endMarker.setPosition(new LatLng(e0.getDouble(0), e0.getDouble(1)));
                }
                double off = Math.max(snap.optDouble("start_off_m", 0),
                                      snap.optDouble("end_off_m", 0));
                if (off >= 40) {
                    Toast.makeText(this,
                            String.format("도로에서 %.0fm 떨어진 지점이라 가까운 길 위에서 안내합니다", off),
                            Toast.LENGTH_SHORT).show();
                }
            }

            double shortestM = sh.getDouble("length_m");

            routes.add(build("최단",   COLOR_SHORT, sh, shortestM, false));
            routes.add(build("안심 A", COLOR_SAFE,  sa, shortestM, same));
            if (sb != null) routes.add(build("안심 B", COLOR_SAFE2, sb, shortestM, same2));

            // 세 개를 모두 깔아 두고, 고른 것만 굵게 칠한다
            for (int i = 0; i < routes.size(); i++) {
                final int idx = i;
                RouteInfo r = routes.get(i);
                if (r.pts.size() < 2) continue;
                PathOverlay p = new PathOverlay();
                p.setCoords(r.pts);
                p.setOutlineWidth(0);
                p.setMap(naverMap);
                p.setOnClickListener(o -> {
                    select(idx);
                    return true;
                });
                r.overlay = p;
            }

            buildTabs();
            select(0);
            compareBox.setVisibility(View.VISIBLE);

            List<LatLng> all = new ArrayList<>();
            for (RouteInfo r : routes) all.addAll(r.pts);
            fitTo(all);

        } catch (Exception e) {
            Log.e(TAG, "응답 해석 실패", e);
            Toast.makeText(this, "응답을 읽지 못했습니다", Toast.LENGTH_SHORT).show();
        }
    }

    private RouteInfo build(String name, int color, JSONObject o,
                            double shortestM, boolean same) throws Exception {
        RouteInfo r = new RouteInfo();
        r.name = name;
        r.color = color;
        r.json = o;
        r.pts = coords(o.getJSONArray("coords"));
        r.lengthM = o.getDouble("length_m");
        r.extraM = r.lengthM - shortestM;
        r.sameAsShortest = same;
        for (int i = 0; i < FAC_KEYS.length; i++) r.fac[i] = o.optInt(FAC_KEYS[i], 0);

        JSONArray ta = o.optJSONArray("turns");
        if (ta != null) {
            for (int i = 0; i < ta.length(); i++) {
                JSONObject t = ta.optJSONObject(i);
                if (t == null) continue;
                r.turns.add(new Turn(t.optDouble("lat", 0), t.optDouble("lon", 0),
                        t.optString("text", "")));
            }
        }
        return r;
    }

    private void buildTabs() {
        tabBox.removeAllViews();
        for (int i = 0; i < routes.size(); i++) {
            final int idx = i;
            RouteInfo r = routes.get(i);

            TextView chip = new TextView(this);
            chip.setText(r.name + "  " + r.minutes() + "분");
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(16), dp(9), dp(16), dp(9));
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(v -> select(idx));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(7));
            chip.setLayoutParams(lp);

            tabBox.addView(chip);
        }
        routeTabs.setVisibility(routes.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void select(int index) {
        if (index < 0 || index >= routes.size()) return;
        selected = index;

        for (int i = 0; i < tabBox.getChildCount() && i < routes.size(); i++) {
            TextView chip = (TextView) tabBox.getChildAt(i);
            boolean on = (i == selected);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(20));
            if (on) {
                g.setColor(routes.get(i).color);
                chip.setTextColor(Color.WHITE);
            } else {
                g.setColor(Color.WHITE);
                g.setStroke(dp(1), Color.rgb(230, 234, 242));
                chip.setTextColor(Color.rgb(92, 104, 128));
            }
            chip.setBackground(g);
        }

        for (int i = 0; i < routes.size(); i++) {
            RouteInfo r = routes.get(i);
            if (r.overlay == null) continue;
            boolean on = (i == selected);
            r.overlay.setColor(on ? r.color : fade(r.color));
            r.overlay.setWidth(dp(on ? 7 : 3));
            r.overlay.setZIndex(on ? 100 : 0);
        }

        nextTurn = 0;
        if (guiding) paintGuideButton();

        drawFacilities(routes.get(selected).json);
        updateDetail();
    }

    private static int fade(int color) {
        return Color.argb(80, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void updateDetail() {
        RouteInfo r = routes.get(selected);

        routeColorBar.setBackgroundColor(r.color);
        txtRouteName.setText(r.name);
        txtRouteName.setTextColor(r.color);
        txtRouteTime.setText(r.minutes() + "분");

        StringBuilder meta = new StringBuilder();
        meta.append(String.format(Locale.KOREA, "%,.0f m", r.lengthM));
        if (r.sameAsShortest) {
            meta.append(" · 최단 경로와 같은 길");
        } else if (r.extraM > 0.5) {
            meta.append(String.format(Locale.KOREA, " · 최단보다 %,.0f m 더 걸음", r.extraM));
        } else if (r.extraM < -0.5) {
            meta.append(String.format(Locale.KOREA, " · 최단보다 %,.0f m 짧음", -r.extraM));
        }
        txtRouteMeta.setText(meta.toString());

        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, r.minutes());
        int h = c.get(Calendar.HOUR_OF_DAY);
        String ampm = h < 12 ? "오전" : "오후";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        txtRouteArrive.setText(String.format(Locale.KOREA,
                "지금 출발하면 %s %d:%02d 도착", ampm, h12, c.get(Calendar.MINUTE)));

        facBox.removeAllViews();
        for (int i = 0; i < FAC_KEYS.length; i++) {
            TextView t = new TextView(this);
            t.setText(FAC_NAMES[i] + " " + r.fac[i]);
            t.setTextSize(12);
            t.setTextColor(r.fac[i] > 0 ? FAC_COLORS[i] : Color.rgb(137, 146, 168));
            t.setPadding(dp(11), dp(6), dp(11), dp(6));

            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(14));
            g.setColor(Color.WHITE);
            g.setStroke(dp(1), r.fac[i] > 0 ? FAC_COLORS[i] : Color.rgb(230, 234, 242));
            t.setBackground(g);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(6));
            t.setLayoutParams(lp);

            facBox.addView(t);
        }
    }

    private void startGuide() {
        if (routes.isEmpty()) return;
        if (guiding) { stopGuide(); return; }

        RouteInfo r = routes.get(selected);
        guiding = true;
        nextTurn = 0;

        // 걷는 동안 위치가 계속 들어와야 안내 지점을 지나는지 알 수 있다
        if (naverMap != null) naverMap.setLocationTrackingMode(LocationTrackingMode.Follow);

        voice.say(r.name + " 경로로 안내를 시작합니다. 총 "
                + Voice.distanceText(r.lengthM) + ", 도보 " + r.minutes() + "분입니다.", true);

        if (r.turns.isEmpty()) {
            Toast.makeText(this, "이 경로에는 회전 안내 정보가 없습니다", Toast.LENGTH_SHORT).show();
        }
        paintGuideButton();
    }

    private void stopGuide() {
        guiding = false;
        nextTurn = 0;
        if (voice != null) voice.stop();
        if (naverMap != null) naverMap.setLocationTrackingMode(LocationTrackingMode.NoFollow);
        paintGuideButton();
    }

    private void paintGuideButton() {
        if (!guiding) { btnGuide.setText("안내 시작"); return; }
        List<Turn> ts = routes.isEmpty() ? null : routes.get(selected).turns;
        int total = (ts == null) ? 0 : ts.size();
        btnGuide.setText(total == 0 ? "안내 중지"
                : "안내 중지 (" + Math.min(nextTurn + 1, total) + "/" + total + ")");
    }

    /**
     * 걷는 동안 위치가 들어올 때마다, 다음 안내 지점까지 거리를 재서 가까워지면 읽어 준다.
     * 팀장이 준 voiceGuide.js 의 onPosition 과 같은 방식이다.
     */
    private void checkGuide(Location loc) {
        if (!guiding || routes.isEmpty()) return;
        List<Turn> ts = routes.get(selected).turns;
        if (nextTurn >= ts.size()) return;

        Turn t = ts.get(nextTurn);
        float[] out = new float[1];
        Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), t.lat, t.lon, out);
        if (out[0] > TRIGGER_M) return;

        String msg = (int) TRIGGER_M + "미터 앞 " + t.text + "입니다.";
        voice.say(msg, true);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        nextTurn++;
        paintGuideButton();

        if (nextTurn >= ts.size()) voice.say("안내를 마칩니다.");
    }

    private void drawFacilities(JSONObject route) {
        for (Marker m : facilityMarkers) m.setMap(null);
        facilityMarkers.clear();

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

    private void fitTo(List<LatLng> pts) {
        if (pts.isEmpty() || naverMap == null) return;
        LatLngBounds.Builder b = new LatLngBounds.Builder();
        for (LatLng p : pts) b.include(p);

        View sheet = findViewById(R.id.bottomSheet);
        int bottom = (sheet != null && sheet.getHeight() > 0)
                ? sheet.getHeight() + dp(24) : dp(300);
        naverMap.moveCamera(CameraUpdate.fitBounds(b.build(), dp(40), dp(110), dp(40), bottom));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        guiding = false;
        io.shutdownNow();
        if (voice != null) voice.shutdown();
        super.onDestroy();
    }
}
