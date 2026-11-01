package com.exception.gwangju_safe_walk;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

public final class Tabs {
    public static final int HOME     = 0;
    public static final int ROUTE    = 1;
    public static final int MAP      = 2;
    public static final int SAVED    = 3;
    public static final int MORE     = 4;

    private Tabs() {}

    public static void attach(Activity a, int current) {
        bind(a, current, HOME,     R.id.tabHome,     R.id.tabHomeIcon,     R.id.tabHomeText,
                MainActivity.class);
        bind(a, current, ROUTE,    R.id.tabRoute,    R.id.tabRouteIcon,    R.id.tabRouteText,
                RouteActivity.class);
        bind(a, current, MAP,      R.id.tabMap,      R.id.tabMapIcon,      R.id.tabMapText,
                MapActivity.class);
        bind(a, current, SAVED,    R.id.tabSaved,    R.id.tabSavedIcon,    R.id.tabSavedText,
                SavedActivity.class);
        bind(a, current, MORE,     R.id.tabMore,     R.id.tabMoreIcon,     R.id.tabMoreText,
                MenuActivity.class);
    }

    private static void bind(Activity a, int current, int self,
                             int rowId, int iconId, int textId, Class<?> target) {
        View row = a.findViewById(rowId);
        if (row == null) return;

        boolean on = (current == self);
        int tint = ContextCompat.getColor(a, on ? R.color.tab_on : R.color.tab_off);

        ImageView icon = a.findViewById(iconId);
        if (icon != null) icon.setColorFilter(tint);

        TextView text = a.findViewById(textId);
        if (text != null) text.setTextColor(tint);

        if (on) {
            row.setOnClickListener(null);
            return;
        }
        row.setOnClickListener(v -> {
            Intent i = new Intent(a, target);

            // 탭을 오갈 때 같은 화면이 쌓이지 않게 한다
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            a.startActivity(i);
            a.overridePendingTransition(0, 0);
        });
    }
}
