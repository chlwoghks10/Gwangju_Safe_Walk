package com.exception.gwangju_safe_walk;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;

public class MenuActivity extends AppCompatActivity {
    private static final String PREF = "safewalk";
    private static final String KEY_SERVER = "server";
    private static final String DEFAULT_SERVER = "13.209.99.73:8000";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_menu);

        Ui.systemBars(this);

        findViewById(R.id.rowCctv).setOnClickListener(v -> openMap("CCTV"));
        findViewById(R.id.rowLight).setOnClickListener(v -> openMap("보안등"));
        findViewById(R.id.rowBell).setOnClickListener(v -> openMap("비상벨"));
        findViewById(R.id.rowPolice).setOnClickListener(v -> openMap("경찰서"));

        findViewById(R.id.rowServer).setOnClickListener(v -> askServer());
        findViewById(R.id.rowTheme).setOnClickListener(v -> dialog("화면 색",
                "해가 지면 어두운 색으로, 해가 뜨면 밝은 색으로 저절로 바뀝니다.\n\n"
                        + "날짜와 광주의 위경도로 그날의 일출·일몰 시각을 계산해서 정하므로, "
                        + "인터넷에 연결되어 있지 않아도 동작합니다."));

        findViewById(R.id.rowPrinciple).setOnClickListener(v -> dialog("설계 원칙",
                "이 앱은 안전을 판정하지 않습니다.\n\n"
                        + "어느 길이 안전한지는 증명할 수 없습니다. 사고가 일어나지 않았다는 것은 "
                        + "측정되지 않기 때문입니다.\n\n"
                        + "대신 경로에 어떤 시설이 몇 개 있는지는 현장에서 확인할 수 있습니다. "
                        + "이 앱은 그 정보를 제시하고, 어느 길로 갈지는 사용자가 판단합니다."));

        findViewById(R.id.rowSource).setOnClickListener(v -> dialog("데이터 출처",
                "· 전국CCTV표준데이터 (행정안전부)\n"
                        + "· 전국보안등정보표준데이터\n"
                        + "· 전국안전비상벨위치표준데이터\n"
                        + "· 경찰관서 위치 정보\n"
                        + "· 보행 도로망 — OpenStreetMap\n"
                        + "· 장소 검색 — 카카오 로컬\n"
                        + "· 보행자 경로 — Tmap\n\n"
                        + "공공데이터는 모두 공공데이터포털에서 개방된 자료이며, "
                        + "좌표 결측과 중복 지점을 정제하여 사용합니다."));

        findViewById(R.id.rowTeam).setOnClickListener(v -> dialog("만든 사람",
                "익셉션 (Exception)\n조선대학교 정보통신공학과\n\n"
                        + "20233267 윤신혁\n20213148 최재환\n20233202 윤해준"));

        countFacilities();
        Tabs.attach(this, Tabs.MORE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Tabs.attach(this, Tabs.MORE);
        showServer();
        showTheme();
    }

    private void showServer() {
        TextView t = findViewById(R.id.valServer);
        if (t != null) t.setText(serverAddress());
    }

    private void showTheme() {
        TextView t = findViewById(R.id.valTheme);
        if (t == null) return;
        boolean night = SunTime.isNight(MainActivity.CHOSUN.latitude, MainActivity.CHOSUN.longitude);
        t.setText(night ? "자동 · 지금은 밤" : "자동 · 지금은 낮");
    }

    private String serverAddress() {
        return getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_SERVER, DEFAULT_SERVER);
    }

    // 서버를 다시 시작하면 주소가 바뀌어서 직접 고칠 수 있어야 한다
    private void askServer() {
        final EditText input = new EditText(this);
        input.setText(serverAddress());
        input.setHint("예: 13.209.99.73:8000");

        new AlertDialog.Builder(this)
                .setTitle("서버 주소")
                .setMessage("경로 계산을 맡는 서버의 주소입니다.\n"
                        + "서버를 다시 시작하면 주소가 바뀔 수 있습니다.")
                .setView(input)
                .setPositiveButton("저장", (d, w) -> {
                    String v = input.getText().toString().trim()
                            .replace("http://", "").replace("https://", "");
                    if (v.isEmpty()) return;
                    if (!v.contains(":")) v = v + ":8000";
                    getSharedPreferences(PREF, Context.MODE_PRIVATE)
                            .edit().putString(KEY_SERVER, v).apply();
                    showServer();
                    Toast.makeText(this, "저장되었습니다", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void openMap(String only) {
        Intent i = new Intent(this, MapActivity.class);
        if (only != null) i.putExtra("only", only);
        startActivity(i);
    }

    private void dialog(String title, String msg) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("확인", null)
                .show();
    }

    private void countFacilities() {
        new Thread(() -> {
            final int cctv   = count("cctv.geojson");
            final int light  = count("light.geojson");
            final int bell   = count("bell.geojson");
            final int police = count("police.geojson");

            new Handler(Looper.getMainLooper()).post(() -> {
                set(R.id.cntCctv,   cctv);
                set(R.id.cntLight,  light);
                set(R.id.cntBell,   bell);
                set(R.id.cntPolice, police);
            });
        }).start();
    }

    private void set(int id, int n) {
        TextView t = findViewById(id);
        if (t != null) t.setText(n > 0 ? String.format(Locale.KOREA, "%,d곳", n) : "없음");
    }

    private int count(String file) {
        try (InputStream in = getAssets().open(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String body = out.toString("UTF-8");

            if (file.endsWith(".geojson")) {
                JSONArray arr = new JSONObject(body).optJSONArray("features");
                return arr == null ? 0 : arr.length();
            }
            int lines = 0;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    getAssets().open(file), "UTF-8"))) {
                while (r.readLine() != null) lines++;
            }
            return Math.max(0, lines - 1);

        } catch (Exception e) {
            return 0;
        }
    }
}
