package com.exception.gwangju_safe_walk;

import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.FragmentManager;

import com.naver.maps.map.CameraUpdate;
import com.naver.maps.map.MapFragment;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.OnMapReadyCallback;

public class RouteActivity extends AppCompatActivity implements OnMapReadyCallback {

    private NaverMap naverMap;
    private EditText editStart, editEnd;
    private TextView txtShort, txtSafe;
    private View compareBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route);

        editStart  = findViewById(R.id.editStart);
        editEnd    = findViewById(R.id.editEnd);
        txtShort   = findViewById(R.id.txtShort);
        txtSafe    = findViewById(R.id.txtSafe);
        compareBox = findViewById(R.id.compareBox);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnSearch).setOnClickListener(v -> search());

        FragmentManager fm = getSupportFragmentManager();
        MapFragment f = (MapFragment) fm.findFragmentById(R.id.routeMap);
        if (f == null) {
            f = MapFragment.newInstance();
            fm.beginTransaction().add(R.id.routeMap, f).commit();
        }
        f.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull NaverMap map) {
        naverMap = map;
        naverMap.moveCamera(CameraUpdate.scrollAndZoomTo(MainActivity.CHOSUN, 15));
    }

    /** 지금은 화면 확인용 더미. 서버 연동 시 이 부분을 API 호출로 교체 */
    private void search() {
        if (editEnd.getText().toString().trim().isEmpty()) {
            Toast.makeText(this, "도착지를 입력하세요", Toast.LENGTH_SHORT).show();
            return;
        }
        compareBox.setVisibility(View.VISIBLE);
        txtShort.setText("1,606 m\nCCTV 22대\n보안등 87개");
        txtSafe.setText("2,359 m\nCCTV 52대\n보안등 256개");

        // TODO: 서버 경로 API 호출 후 지도에 두 경로 그리기
    }
}
