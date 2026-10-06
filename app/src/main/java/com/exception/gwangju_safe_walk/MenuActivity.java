package com.exception.gwangju_safe_walk;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;

public class MenuActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_menu);

        findViewById(R.id.menuRoute).setOnClickListener(v -> go(RouteActivity.class));
        findViewById(R.id.fabRoute).setOnClickListener(v -> go(RouteActivity.class));
        findViewById(R.id.menuMap).setOnClickListener(v -> openMap(null));

        // 시설 행을 누르면 해당 레이어만 켠 지도로 이동
        findViewById(R.id.rowCctv).setOnClickListener(v -> openMap("CCTV"));
        findViewById(R.id.rowLight).setOnClickListener(v -> openMap("보안등"));
        findViewById(R.id.rowBell).setOnClickListener(v -> openMap("비상벨"));
        findViewById(R.id.rowPolice).setOnClickListener(v -> openMap("경찰서"));

        findViewById(R.id.tabHome).setOnClickListener(v -> finish());

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
                        + "· 보행 도로망 — OpenStreetMap\n\n"
                        + "모두 공공데이터포털에서 개방된 자료이며, "
                        + "좌표 결측과 중복 지점을 정제하여 사용합니다."));

        findViewById(R.id.rowTeam).setOnClickListener(v -> dialog("만든 사람",
                "익셉션 (Exception)\n조선대학교 정보통신공학과\n\n"
                        + "20233267 윤신혁\n20213148 최재환\n20233202 윤해준"));

        findViewById(R.id.btnMenuSettings).setOnClickListener(v ->
                Toast.makeText(this, "준비 중인 기능입니다", Toast.LENGTH_SHORT).show());

        countFacilities();
    }

    private void go(Class<?> c) {
        startActivity(new Intent(this, c));
    }

    /** 특정 레이어만 켠 상태로 지도를 연다. null 이면 전체 표시 */
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

    /** assets 파일을 읽어 지점 수를 세어 표시 */
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
        if (t != null) t.setText(n > 0 ? String.format("%,d곳", n) : "없음");
    }

    private int count(String file) {
        try {
            if (file.endsWith(".geojson") || file.endsWith(".json")) {
                JSONObject root = new JSONObject(readAll(getAssets().open(file)));
                JSONArray f = root.optJSONArray("features");
                return f == null ? 0 : f.length();
            }
            int n = 0;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(getAssets().open(file)))) {
                while (r.readLine() != null) n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
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
}