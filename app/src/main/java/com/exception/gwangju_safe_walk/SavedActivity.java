package com.exception.gwangju_safe_walk;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

public class SavedActivity extends AppCompatActivity {
    private LinearLayout savedBox;
    private View emptyBox;
    private TextView savedCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved);

        Ui.systemBars(this);

        savedBox   = findViewById(R.id.savedBox);
        emptyBox   = findViewById(R.id.emptyBox);
        savedCount = findViewById(R.id.savedCount);

        Tabs.attach(this, Tabs.SAVED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Tabs.attach(this, Tabs.SAVED);
        build();
    }

    private void build() {
        savedBox.removeAllViews();
        List<Saved.Item> items = Saved.list(this);

        emptyBox.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        savedCount.setText(items.isEmpty() ? "아직 비어 있습니다" : items.size() + "곳");

        for (Saved.Item it : items) {
            savedBox.addView(row(it));
        }
    }

    private View row(final Saved.Item it) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(15), dp(14), dp(15), dp(14));
        card.setClickable(true);
        card.setFocusable(true);
        card.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_card_ripple));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        card.setLayoutParams(lp);

        ImageView star = new ImageView(this);
        star.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_tab_saved));
        star.setColorFilter(ContextCompat.getColor(this, R.color.may));
        star.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        card.addView(star);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.setMarginStart(dp(12));
        col.setLayoutParams(cp);

        TextView name = new TextView(this);
        name.setText(it.name);
        name.setTextSize(15);
        name.setTextColor(ContextCompat.getColor(this, R.color.text_main));
        col.addView(name);

        TextView sub = new TextView(this);
        sub.setText(String.format(Locale.KOREA, "%.5f, %.5f", it.lat, it.lon));
        sub.setTextSize(11);
        sub.setTextColor(ContextCompat.getColor(this, R.color.text_weak));
        col.addView(sub);

        card.addView(col);

        TextView go = new TextView(this);
        go.setText("경로");
        go.setTextSize(12);
        go.setTextColor(ContextCompat.getColor(this, R.color.brand));
        go.setPadding(dp(11), dp(6), dp(11), dp(6));
        go.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_chip));
        card.addView(go);

        View.OnClickListener open = v -> {
            Intent i = new Intent(this, RouteActivity.class);
            i.putExtra(RouteActivity.EXTRA_DEST_NAME, it.name);
            i.putExtra(RouteActivity.EXTRA_DEST_LAT, it.lat);
            i.putExtra(RouteActivity.EXTRA_DEST_LON, it.lon);
            startActivity(i);
        };
        card.setOnClickListener(open);
        go.setOnClickListener(open);

        card.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("저장 해제")
                    .setMessage(it.name + " 을(를) 목록에서 지울까요?")
                    .setPositiveButton("지우기", (d, w) -> {
                        Saved.remove(this, it.name);
                        build();
                        Toast.makeText(this, "지웠습니다", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("취소", null)
                    .show();
            return true;
        });

        return card;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
