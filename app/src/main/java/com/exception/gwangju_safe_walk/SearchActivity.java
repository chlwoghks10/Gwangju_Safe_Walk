package com.exception.gwangju_safe_walk;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SearchActivity extends AppCompatActivity {
    private static final String TAG = "SafeWalkSearch";

    public static final String EXTRA_FOR_START = "for_start";

    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_LAT  = "lat";
    public static final String EXTRA_LON  = "lon";

    public static final String EXTRA_USE_MY_LOCATION = "use_my_location";

    private static final String PREF = "safewalk";
    private static final String KEY_SERVER = "server";
    private static final String DEFAULT_SERVER = "13.209.99.73:8000";
    private static final String KEY_RECENT = "recent_search";
    private static final String SEP = "\u0001";
    private static final int RECENT_MAX = 8;

    private static final long DEBOUNCE_MS = 350;

    private TextView txtStatus;
    private LinearLayout listBox;
    private LayoutInflater inflater;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable pending;

    private int requestSeq = 0;

    private boolean forStart = true;

    private static class Place {
        final String name, address;
        final double lat, lon;
        final boolean inRange;

        Place(String name, String address, double lat, double lon, boolean inRange) {
            this.name = name; this.address = address;
            this.lat = lat; this.lon = lon; this.inRange = inRange;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);

        Ui.systemBars(this);

        forStart = getIntent().getBooleanExtra(EXTRA_FOR_START, true);
        inflater = getLayoutInflater();

        txtStatus = findViewById(R.id.txtStatus);
        listBox   = findViewById(R.id.listBox);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        findViewById(R.id.btnMyLocation).setOnClickListener(v -> {
            Intent out = new Intent();
            out.putExtra(EXTRA_USE_MY_LOCATION, true);
            setResult(RESULT_OK, out);
            finish();
        });

        android.widget.EditText editQuery = findViewById(R.id.editQuery);
        editQuery.setHint(forStart ? "출발지를 검색하세요" : "도착지를 검색하세요");
        editQuery.requestFocus();

        editQuery.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                onQueryChanged(e.toString().trim());
            }
        });

        showRecent();
    }

    private void onQueryChanged(String q) {
        if (pending != null) ui.removeCallbacks(pending);

        if (q.length() < 2) {
            showRecent();
            return;
        }
        txtStatus.setText("검색 중…");
        pending = () -> runSearch(q);
        ui.postDelayed(pending, DEBOUNCE_MS);
    }

    private void runSearch(String q) {
        final int seq = ++requestSeq;
        final String host = serverAddress();

        io.execute(() -> {
            try {
                // 한글을 그대로 붙이면 서버가 400 으로 거부한다
                String url = "http://" + host + "/search?q=" + URLEncoder.encode(q, "UTF-8");
                JSONArray arr = new JSONObject(httpGet(url)).getJSONArray("places");

                final List<Place> found = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    found.add(new Place(
                            o.optString("name", ""),
                            o.optString("address", ""),
                            o.optDouble("lat", 0),
                            o.optDouble("lon", 0),
                            o.optBoolean("in_range", false)));
                }
                ui.post(() -> {
                    // 더 최신 요청이 있으면 이 응답은 버린다
                    if (seq != requestSeq) return;
                    showPlaces(q, found);
                });

            } catch (Exception e) {
                Log.e(TAG, "검색 실패: " + e.getMessage(), e);
                final String msg = e.getMessage() == null ? "알 수 없는 오류" : e.getMessage();
                ui.post(() -> {
                    if (seq != requestSeq) return;
                    listBox.removeAllViews();
                    txtStatus.setText("검색하지 못했습니다 — " + msg);
                });
            }
        });
    }

    private void showPlaces(String q, List<Place> places) {
        listBox.removeAllViews();

        if (places.isEmpty()) {
            txtStatus.setText("\"" + q + "\" 검색 결과가 없습니다");
            return;
        }

        List<Place> sorted = new ArrayList<>();
        for (Place p : places) if (p.inRange)  sorted.add(p);
        for (Place p : places) if (!p.inRange) sorted.add(p);

        int usable = 0;
        for (Place p : places) if (p.inRange) usable++;
        txtStatus.setText(usable == places.size()
                ? "검색 결과 " + places.size() + "곳"
                : "검색 결과 " + places.size() + "곳 · 경로 가능 " + usable + "곳");

        for (Place p : sorted) addRow(q, p);
    }

    private void addRow(String q, Place p) {
        View row = inflater.inflate(R.layout.item_place, listBox, false);
        ((TextView) row.findViewById(R.id.txtName)).setText(p.name);
        ((TextView) row.findViewById(R.id.txtAddr)).setText(p.address);

        TextView flag = row.findViewById(R.id.txtFlag);
        if (p.inRange) {
            flag.setVisibility(View.GONE);
        } else {
            flag.setVisibility(View.VISIBLE);
            row.setAlpha(0.45f);
        }

        ImageView star = row.findViewById(R.id.btnStar);
        star.setVisibility(View.VISIBLE);
        paintStar(star, Saved.has(this, p.name));
        star.setOnClickListener(v -> {
            boolean on = Saved.toggle(this, p.name, p.lat, p.lon);
            paintStar(star, on);
            Toast.makeText(this, on ? "저장했습니다" : "저장을 해제했습니다",
                    Toast.LENGTH_SHORT).show();
        });

        row.setOnClickListener(v -> {
            if (!p.inRange) {
                Toast.makeText(this,
                        "도로망 범위 밖이라 경로를 만들 수 없습니다 (현재 조선대 주변 2km)",
                        Toast.LENGTH_LONG).show();
                return;
            }
            addRecent(q);
            Intent out = new Intent();
            out.putExtra(EXTRA_NAME, p.name);
            out.putExtra(EXTRA_LAT, p.lat);
            out.putExtra(EXTRA_LON, p.lon);
            setResult(RESULT_OK, out);
            finish();
        });

        listBox.addView(row);
    }

    private void showRecent() {
        listBox.removeAllViews();
        List<String> recent = recentList();
        if (recent.isEmpty()) {
            txtStatus.setText("예: 조선대학교, 명륜진사갈비 문흥점");
            return;
        }
        txtStatus.setText("최근 검색");

        for (String word : recent) {
            View row = inflater.inflate(R.layout.item_place, listBox, false);
            ((TextView) row.findViewById(R.id.txtName)).setText(word);
            ((TextView) row.findViewById(R.id.txtAddr)).setText("다시 검색");
            row.findViewById(R.id.txtFlag).setVisibility(View.GONE);
            row.findViewById(R.id.btnStar).setVisibility(View.GONE);
            row.setOnClickListener(v -> {
                android.widget.EditText e = findViewById(R.id.editQuery);
                e.setText(word);
                e.setSelection(word.length());
            });
            listBox.addView(row);
        }
    }

    private List<String> recentList() {
        String raw = prefs().getString(KEY_RECENT, "");
        List<String> out = new ArrayList<>();
        if (raw.isEmpty()) return out;
        for (String w : raw.split(SEP)) if (!w.isEmpty()) out.add(w);
        return out;
    }

    private void addRecent(String word) {
        List<String> list = recentList();
        list.remove(word);
        list.add(0, word);
        while (list.size() > RECENT_MAX) list.remove(list.size() - 1);
        prefs().edit().putString(KEY_RECENT, String.join(SEP, list)).apply();
    }

    private void paintStar(ImageView star, boolean on) {
        star.setColorFilter(androidx.core.content.ContextCompat.getColor(this,
                on ? R.color.may : R.color.text_weak));
        star.setAlpha(on ? 1f : 0.6f);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private String serverAddress() {
        return prefs().getString(KEY_SERVER, DEFAULT_SERVER);
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

    @Override
    protected void onDestroy() {
        if (pending != null) ui.removeCallbacks(pending);
        io.shutdownNow();
        super.onDestroy();
    }
}
