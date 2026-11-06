package com.exception.gwangju_safe_walk;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PointF;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.naver.maps.geometry.LatLng;
import com.naver.maps.geometry.LatLngBounds;
import com.naver.maps.map.CameraPosition;
import com.naver.maps.map.CameraUpdate;
import com.naver.maps.map.LocationTrackingMode;
import com.naver.maps.map.MapFragment;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.OnMapReadyCallback;
import com.naver.maps.map.util.FusedLocationSource;
import com.naver.maps.map.overlay.Marker;
import com.naver.maps.map.overlay.Overlay;
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
    private TextView btnGuide, btnMute;
    private View bottomSheet, sheetHandle;
    private int sheetMinH, sheetMaxH;
    private float dragStartY;
    private int dragStartH;
    private boolean dragMoved;

    private View turnBand;
    private ImageView imgTurn;
    private TextView txtTurnDist, txtTurnText;

    private LatLng startPoint, endPoint;
    private Marker startMarker, endMarker;
    private final List<Marker> facilityMarkers = new ArrayList<>();

    private static final String[] FAC_KEYS   = {"light", "cctv", "bell", "police"};
    private static final String[] FAC_NAMES  = {"보안등", "CCTV", "비상벨", "경찰서"};
    private static final String[] FAC_UNITS  = {"곳", "곳", "곳", "곳"};
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

    // 안내 지점 몇 m 앞에서 읽어 줄지
    private static final double TRIGGER_M = 30;

    // 이보다 가까워지면 그 지점을 지난 것으로 보고 다음으로 넘긴다
    private static final double PASS_M = 12;

    // 걷는 동안 서버에 위치를 보내는 간격
    private static final long TRACK_EVERY_MS = 5000;

    // 경로에서 이보다 멀면 지나온 길을 지우지 않는다
    private static final double ON_ROUTE_M = 60;

    // 경로 출발점에서 이보다 멀리 떨어진 채로 안내를 켜면 먼저 확인을 받는다
    private static final double START_NEAR_M = 200;

    // 안내 중 화면을 기울이는 각도 (도). 0이면 위에서 내려다보는 평면
    private static final double NAV_TILT = 45;

    private boolean guiding = false;
    private int nextTurn = 0;
    private int saidTurn = -1;
    private String navId = null;
    private long lastTrackMs = 0;
    private boolean tracking = false;
    private PathOverlay navOverlay;
    private double remainM = -1;
    private Location myLoc;
    private int lastPathIndex = -1;
    private boolean doneSaid = false;

    private static class Turn {
        final double lat, lon;
        final String text;
        int pathIndex = -1;          // 경로 좌표열에서 이 지점이 몇 번째인지
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
        String navId;

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
        btnMute        = findViewById(R.id.btnMute);
        bottomSheet    = findViewById(R.id.bottomSheet);
        sheetHandle    = findViewById(R.id.sheetHandle);
        turnBand       = findViewById(R.id.turnBand);
        imgTurn        = findViewById(R.id.imgTurn);
        txtTurnDist    = findViewById(R.id.txtTurnDist);
        txtTurnText    = findViewById(R.id.txtTurnText);

        editStart.setFocusable(false);
        editEnd.setFocusable(false);
        editStart.setHint("눌러서 장소 검색 · 또는 지도를 탭");
        editEnd.setHint("눌러서 장소 검색 · 또는 지도를 탭");

        editStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSearch(true);
            }
        });
        editEnd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSearch(false);
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestRoute();
            }
        });

        locationSource = new FusedLocationSource(this, REQ_LOCATION);

        View locateBtn = findViewById(R.id.btnLocate);
        if (locateBtn != null) {
            locateBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMyLocationOnMap();
                }
            });
        }

        setupSheetDrag();

        voice = new Voice(this, new Voice.Listener() {
            @Override
            public void onVoiceState(final boolean ready, final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ready) return;
                        if (voice == null || !voice.isMuted()) btnMute.setText("음성 불가");
                        Toast.makeText(RouteActivity.this, message, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        btnGuide.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (routes.isEmpty()) return;
                if (guiding) stopGuide();
                else startGuide();
            }
        });
        btnMute.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean muted = voice.toggleMute();
                btnMute.setText(muted ? "음성 꺼짐" : "음성 켜짐");
            }
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
                .setPositiveButton("저장", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String v = input.getText().toString().trim()
                                .replace("http://", "").replace("https://", "");
                        if (v.isEmpty()) return;
                        if (!v.contains(":")) v = v + ":8000";
                        getSharedPreferences(PREF, Context.MODE_PRIVATE)
                                .edit().putString(KEY_SERVER, v).apply();
                        Toast.makeText(RouteActivity.this, "저장되었습니다",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    @Override
    public void onMapReady(@NonNull NaverMap map) {
        naverMap = map;
        naverMap.setNightModeEnabled(SunTime.isNight(35.1400, 126.9270));
        naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(MainActivity.CHOSUN, 15));
        naverMap.setOnMapClickListener(new NaverMap.OnMapClickListener() {
            @Override
            public void onMapClick(PointF point, LatLng coord) {
                onMapTapped(coord);
            }
        });

        naverMap.setLocationSource(locationSource);
        naverMap.addOnLocationChangeListener(new NaverMap.OnLocationChangeListener() {
            @Override
            public void onLocationChange(Location loc) {
                onLocationChanged(loc);
            }
        });
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
        myLoc = loc;

        // 안내 중일 때만 지나온 길을 지우고 위치를 서버에 보고한다
        if (guiding) {
            paintRemaining(loc);
            checkGuide(loc);
            paintTurnBand(loc);
            track(loc.getLatitude(), loc.getLongitude());
        }

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

        io.execute(new Runnable() {
            @Override
            public void run() {
                String url = String.format(Locale.US,
                        "http://%s/route?start_lat=%f&start_lon=%f&end_lat=%f&end_lon=%f",
                        host, startPoint.latitude, startPoint.longitude,
                        endPoint.latitude, endPoint.longitude);
                try {
                    String body = httpGet(url);
                    final JSONObject json = new JSONObject(body);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            btnSearch.setText("경로 비교하기");
                            btnSearch.setEnabled(true);
                            showResult(json);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "요청 실패: " + e.getMessage(), e);
                    final String msg =
                            e.getMessage() == null ? "알 수 없는 오류" : e.getMessage();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            btnSearch.setText("경로 비교하기");
                            btnSearch.setEnabled(true);

                            // 서버가 한글로 이유를 돌려준 경우는 연결 실패가 아니라 거부다
                            boolean refused = msg.contains("도로망")
                                    || msg.contains("가깝습니다")
                                    || msg.contains("이어지지");
                            if (refused) {
                                new AlertDialog.Builder(RouteActivity.this)
                                        .setTitle("경로를 만들 수 없습니다")
                                        .setMessage(msg
                                                + "\n\n현재 도로망은 조선대 주변 2km 입니다.")
                                        .setPositiveButton("닫기", null)
                                        .show();
                            } else {
                                new AlertDialog.Builder(RouteActivity.this)
                                        .setTitle("서버에 연결하지 못했습니다")
                                        .setMessage(msg + "\n\n현재 주소: " + host
                                                + "\n\nEC2 를 다시 시작했다면 주소가"
                                                + " 바뀌었을 수 있습니다.")
                                        .setPositiveButton("주소 변경",
                                                new DialogInterface.OnClickListener() {
                                                    @Override
                                                    public void onClick(DialogInterface d,
                                                                        int w) {
                                                        askServer();
                                                    }
                                                })
                                        .setNegativeButton("닫기", null)
                                        .show();
                            }
                        }
                    });
                }
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

            // 서버가 경로를 도로 위에서 시작하므로 마커도 같이 옮긴다
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
                p.setOnClickListener(new Overlay.OnClickListener() {
                    @Override
                    public boolean onClick(Overlay o) {
                        select(idx);
                        return true;
                    }
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
        r.navId = o.optString("nav_id", null);
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
        markTurnIndexes(r);
        return r;
    }

    // 회전 지점이 경로 좌표열에서 몇 번째인지 미리 구해 둔다.
    // 지나쳐 버린 지점을 건너뛸 때 쓴다
    private void markTurnIndexes(RouteInfo r) {
        float[] out = new float[1];
        for (Turn t : r.turns) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < r.pts.size(); i++) {
                Location.distanceBetween(t.lat, t.lon,
                        r.pts.get(i).latitude, r.pts.get(i).longitude, out);
                if (out[0] < bestD) { bestD = out[0]; best = i; }
            }
            t.pathIndex = best;
        }
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
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(idx);
                }
            });

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
        if (guiding && index != selected) {
            Toast.makeText(this, "안내를 멈춘 뒤에 다른 경로를 고를 수 있습니다",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        selected = index;
        if (!guiding) { nextTurn = 0; saidTurn = -1; }

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
        if (guiding && remainM >= 0) {
            txtRouteTime.setText(
                    Math.max(1, (int) Math.round(remainM / WALK_M_PER_MIN)) + "분");
            txtRouteMeta.setText(String.format(Locale.KOREA, "남은 거리 %,.0f m", remainM));
            paintArrive(remainM, true);
            paintFacilities(r);
            return;
        }
        meta.append(String.format(Locale.KOREA, "%,.0f m", r.lengthM));
        if (r.sameAsShortest) {
            meta.append(" · 최단 경로와 같은 길");
        } else if (r.extraM > 0.5) {
            meta.append(String.format(Locale.KOREA, " · 최단보다 %,.0f m 더 걸음", r.extraM));
        } else if (r.extraM < -0.5) {
            meta.append(String.format(Locale.KOREA, " · 최단보다 %,.0f m 짧음", -r.extraM));
        }
        txtRouteMeta.setText(meta.toString());

        paintArrive(r.lengthM, false);
        paintFacilities(r);
    }

    // 남은 거리로 도착 시각을 적는다
    private void paintArrive(double meters, boolean walking) {
        int mins = Math.max(1, (int) Math.round(meters / WALK_M_PER_MIN));
        Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, mins);
        int h = c.get(Calendar.HOUR_OF_DAY);
        String ampm = h < 12 ? "오전" : "오후";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        txtRouteArrive.setText(String.format(Locale.KOREA,
                "%s %s %d:%02d 도착", walking ? "이대로 걸으면" : "지금 출발하면",
                ampm, h12, c.get(Calendar.MINUTE)));
    }

    private void paintFacilities(RouteInfo r) {
        facBox.removeAllViews();
        for (int i = 0; i < FAC_KEYS.length; i++) {
            TextView t = new TextView(this);
            t.setText(FAC_NAMES[i] + " " + r.fac[i] + FAC_UNITS[i]);
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

    private void startGuide() {
        final RouteInfo r = routes.get(selected);

        // 경로 출발점에서 멀리 떨어진 채로 켜면 경로 이탈로 잡혀 엉뚱하게 동작한다
        double away = distanceToStart(r);
        if (away > START_NEAR_M) {
            new AlertDialog.Builder(this)
                    .setTitle("출발지에서 떨어져 있습니다")
                    .setMessage(String.format(Locale.KOREA,
                            "현재 위치가 경로 출발점에서 %,.0f m 떨어져 있습니다.\n\n"
                            + "안내는 출발점에서 시작해야 남은 거리와 음성이 맞습니다.", away))
                    .setPositiveButton("내 위치에서 다시 찾기",
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    useMyLocationAsStart();
                                }
                            })
                    .setNeutralButton("그래도 시작", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            beginGuide(r);
                        }
                    })
                    .setNegativeButton("취소", null)
                    .show();
            return;
        }
        beginGuide(r);
    }

    // 현재 위치에서 경로 출발점까지 거리. 위치를 아직 못 받았으면 -1
    private double distanceToStart(RouteInfo r) {
        if (myLoc == null || r.pts.isEmpty()) return -1;
        float[] out = new float[1];
        Location.distanceBetween(myLoc.getLatitude(), myLoc.getLongitude(),
                r.pts.get(0).latitude, r.pts.get(0).longitude, out);
        return out[0];
    }

    // 출발지를 내 위치로 바꾸고 경로를 다시 찾는다
    private void useMyLocationAsStart() {
        if (myLoc == null) {
            Toast.makeText(this, "현재 위치를 아직 못 받았습니다", Toast.LENGTH_SHORT).show();
            return;
        }
        setStart(new LatLng(myLoc.getLatitude(), myLoc.getLongitude()), "내 위치");
        requestRoute();
    }

    private void beginGuide(RouteInfo r) {
        guiding = true;
        nextTurn = 0;
        saidTurn = -1;
        doneSaid = false;
        lastPathIndex = -1;
        navId = r.navId;
        remainM = r.lengthM;
        lastTrackMs = 0;

        // 실제 내비처럼 화면을 기울이고, 진행 방향이 위로 오게 돌린다
        if (naverMap != null) {
            if (myLoc != null) {
                naverMap.moveCamera(CameraUpdate.toCameraPosition(new CameraPosition(
                        new LatLng(myLoc.getLatitude(), myLoc.getLongitude()),
                        17, NAV_TILT, myLoc.getBearing())));
            }
            naverMap.setLocationTrackingMode(LocationTrackingMode.Face);
        }

        voice.say(r.name + " 경로로 안내를 시작합니다. 총 "
                + Voice.distanceText(r.lengthM) + ", 도보 " + r.minutes() + "분입니다.", true);

        if (r.turns.isEmpty()) {
            Toast.makeText(this, "이 경로에는 회전 안내 정보가 없습니다", Toast.LENGTH_SHORT).show();
        }
        if (myLoc != null) {
            paintRemaining(myLoc);
            paintTurnBand(myLoc);
        }
        paintGuideButton();
        updateDetail();
    }

    private void stopGuide() {
        guiding = false;
        nextTurn = 0;
        saidTurn = -1;
        doneSaid = false;
        lastPathIndex = -1;
        navId = null;
        remainM = -1;
        clearNavOverlay();
        if (turnBand != null) turnBand.setVisibility(View.GONE);
        if (voice != null) voice.stop();
        if (naverMap != null) {
            naverMap.setLocationTrackingMode(LocationTrackingMode.NoFollow);
            // 기울기와 회전을 원래대로 돌린다
            CameraPosition cur = naverMap.getCameraPosition();
            naverMap.moveCamera(CameraUpdate.toCameraPosition(
                    new CameraPosition(cur.target, cur.zoom, 0, 0)));
        }
        paintGuideButton();
        if (!routes.isEmpty() && selected < routes.size()) updateDetail();
    }

    private void clearNavOverlay() {
        if (navOverlay != null) {
            navOverlay.setMap(null);
            navOverlay = null;
        }
        for (RouteInfo r : routes) {
            if (r.overlay != null) r.overlay.setMap(naverMap);
        }
    }

    private void paintGuideButton() {
        if (btnGuide == null) return;
        if (!guiding) { btnGuide.setText("이 경로로 안내 시작"); return; }
        List<Turn> ts = routes.isEmpty() ? null : routes.get(selected).turns;
        int total = (ts == null) ? 0 : ts.size();
        btnGuide.setText(total == 0 ? "안내 중지"
                : "안내 중지 (" + Math.min(nextTurn + 1, total) + "/" + total + ")");
    }

    // 지나온 부분을 지우고 남은 길만 굵게 다시 그린다
    private void paintRemaining(Location loc) {
        if (routes.isEmpty() || naverMap == null) return;
        RouteInfo r = routes.get(selected);
        List<LatLng> p = r.pts;
        if (p.size() < 2) return;

        int best = 0;
        double bestD = Double.MAX_VALUE;
        float[] out = new float[1];
        for (int i = 0; i < p.size(); i++) {
            Location.distanceBetween(loc.getLatitude(), loc.getLongitude(),
                    p.get(i).latitude, p.get(i).longitude, out);
            if (out[0] < bestD) { bestD = out[0]; best = i; }
        }
        if (bestD > ON_ROUTE_M) return;
        lastPathIndex = best;
        if (best > p.size() - 2) best = p.size() - 2;

        List<LatLng> rest = new ArrayList<>();
        rest.add(new LatLng(loc.getLatitude(), loc.getLongitude()));
        for (int i = best + 1; i < p.size(); i++) rest.add(p.get(i));
        if (rest.size() < 2) return;

        if (r.overlay != null) r.overlay.setMap(null);
        if (navOverlay == null) {
            navOverlay = new PathOverlay();
            navOverlay.setOutlineWidth(0);
        }
        navOverlay.setCoords(rest);
        navOverlay.setColor(r.color);
        navOverlay.setWidth(dp(7));
        navOverlay.setMap(naverMap);
    }

    // 화면 위 띠에 다음 회전을 거리와 함께 띄운다. 지날 때까지 같은 지점을 보여 준다
    private void paintTurnBand(Location loc) {
        if (turnBand == null) return;
        if (!guiding || routes.isEmpty()) { turnBand.setVisibility(View.GONE); return; }

        List<Turn> ts = routes.get(selected).turns;
        if (nextTurn >= ts.size()) {
            txtTurnDist.setText("");
            txtTurnText.setText("목적지까지 직진");
            if (imgTurn != null) imgTurn.setImageResource(R.drawable.ic_turn_straight);
            turnBand.setVisibility(View.VISIBLE);
            return;
        }
        Turn t = ts.get(nextTurn);
        float[] out = new float[1];
        Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), t.lat, t.lon, out);
        txtTurnDist.setText(String.format(Locale.KOREA, "%,.0f m", out[0]));
        txtTurnText.setText(t.text);
        if (imgTurn != null) imgTurn.setImageResource(turnIcon(t.text));
        turnBand.setVisibility(View.VISIBLE);
    }

    // 안내 문구에서 방향을 읽어 아이콘을 고른다.
    // 최단·안심 A 는 "좌회전"/"우회전", 안심 B 는 "○○에서 좌회전" 처럼 온다
    private static int turnIcon(String text) {
        if (text == null) return R.drawable.ic_turn_straight;
        if (text.contains("유턴")) return R.drawable.ic_turn_uturn;
        if (text.contains("좌회전") || text.contains("왼쪽")) return R.drawable.ic_turn_left;
        if (text.contains("우회전") || text.contains("오른쪽")) return R.drawable.ic_turn_right;
        return R.drawable.ic_turn_straight;
    }

    // 30m 앞에서 읽어 주고, 12m 안에 들어오면 지난 것으로 보고 다음 지점으로 넘긴다
    private void checkGuide(Location loc) {
        if (!guiding || routes.isEmpty()) return;
        List<Turn> ts = routes.get(selected).turns;

        // GPS 가 튀어 12m 안에 못 들어가도 멈추지 않게, 이미 지나친 지점은 건너뛴다
        while (nextTurn < ts.size()
                && ts.get(nextTurn).pathIndex >= 0
                && lastPathIndex > ts.get(nextTurn).pathIndex) {
            nextTurn++;
            paintGuideButton();
        }
        if (nextTurn >= ts.size()) {
            if (!doneSaid) {
                doneSaid = true;
                voice.say("안내를 마칩니다.");
            }
            return;
        }

        Turn t = ts.get(nextTurn);
        float[] out = new float[1];
        Location.distanceBetween(loc.getLatitude(), loc.getLongitude(), t.lat, t.lon, out);
        double d = out[0];

        if (d <= TRIGGER_M && saidTurn != nextTurn) {
            saidTurn = nextTurn;
            voice.say(String.format(Locale.KOREA, "%.0f미터 앞 %s입니다.", d, t.text), true);
        }
        if (d <= PASS_M) {
            nextTurn++;
            paintGuideButton();
        }
    }

    // 위치를 서버에 보내 경로를 벗어났는지 묻는다. 벗어났으면 새 경로를 받아 바꾼다
    private void track(final double lat, final double lon) {
        // 아직 경로 위에 올라서지 않았으면 보내지 않는다.
        // 보내면 서버가 이탈로 보고 고른 경로를 내 위치 기준으로 바꿔 버린다
        if (navId == null || tracking || lastPathIndex < 0) return;
        long now = System.currentTimeMillis();
        if (now - lastTrackMs < TRACK_EVERY_MS) return;
        lastTrackMs = now;
        tracking = true;

        final String host = serverAddress();
        final String id = navId;
        io.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = String.format(Locale.US,
                            "http://%s/track?id=%s&lat=%f&lon=%f", host, id, lat, lon);
                    final JSONObject res = new JSONObject(httpGet(url));
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            onTrack(res);
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "위치 보고 실패: " + e.getMessage());
                } finally {
                    tracking = false;
                }
            }
        });
    }

    private void onTrack(JSONObject res) {
        if (!guiding || routes.isEmpty()) return;
        remainM = res.optDouble("remain_m", remainM);

        if (res.optBoolean("off", false)) {
            JSONObject nr = res.optJSONObject("route");
            if (nr == null) {
                Toast.makeText(this, "경로를 벗어났습니다", Toast.LENGTH_SHORT).show();
                updateDetail();
                return;
            }
            try {
                RouteInfo r = routes.get(selected);
                RouteInfo n = build(r.name, r.color, nr, nr.getDouble("length_m"), false);
                n.overlay = r.overlay;
                routes.set(selected, n);
                navId = res.optString("nav_id", navId);
                nextTurn = 0;
                saidTurn = -1;
                doneSaid = false;
                lastPathIndex = -1;
                remainM = n.lengthM;
                clearNavOverlay();
                if (n.overlay != null) n.overlay.setCoords(n.pts);
                if (myLoc != null) { paintRemaining(myLoc); paintTurnBand(myLoc); }
                voice.say("경로를 벗어나 새 경로로 안내합니다.", true);
                Toast.makeText(this, "경로를 다시 찾았습니다", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "새 경로 적용 실패", e);
            }
        }
        updateDetail();
        paintGuideButton();
    }

    // 하단 패널을 손으로 끌어 올리고 내린다. 지도를 더 보고 싶을 때 내리면 된다
    private void setupSheetDrag() {
        if (bottomSheet == null || sheetHandle == null) return;

        sheetMinH = dp(116);
        sheetMaxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.70);

        // 처음에는 화면의 절반을 넘지 않게 둔다
        bottomSheet.post(new Runnable() {
            @Override
            public void run() {
                int h = bottomSheet.getHeight();
                int half = (int) (getResources().getDisplayMetrics().heightPixels * 0.50);
                setSheetHeight(h > half ? half : h);
            }
        });

        sheetHandle.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int act = e.getActionMasked();
                if (act == MotionEvent.ACTION_DOWN) {
                    dragStartY = e.getRawY();
                    dragStartH = bottomSheet.getHeight();
                    dragMoved = false;
                    return true;
                }
                if (act == MotionEvent.ACTION_MOVE) {
                    float moved = dragStartY - e.getRawY();
                    if (Math.abs(moved) > dp(5)) dragMoved = true;
                    setSheetHeight(dragStartH + (int) moved);
                    return true;
                }
                if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                    if (!dragMoved) {
                        // 끌지 않고 톡 눌렀으면 접었다 펴진다
                        int mid = (sheetMinH + sheetMaxH) / 2;
                        setSheetHeight(bottomSheet.getHeight() > mid ? sheetMinH : sheetMaxH);
                    }
                    v.performClick();
                    return true;
                }
                return false;
            }
        });
    }

    private void setSheetHeight(int h) {
        if (bottomSheet == null) return;
        if (h < sheetMinH) h = sheetMinH;
        if (h > sheetMaxH) h = sheetMaxH;
        ViewGroup.LayoutParams lp = bottomSheet.getLayoutParams();
        lp.height = h;
        bottomSheet.setLayoutParams(lp);
    }
}
