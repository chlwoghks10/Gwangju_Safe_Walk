package com.exception.gwangju_safe_walk;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

public final class Saved {
    // 폰 안에만 두고 서버로 보내지 않는다
    private static final String PREF = "safewalk";
    private static final String KEY  = "saved_places";
    private static final String F = "\u0001";
    private static final String R = "\u0002";
    private static final int MAX = 50;

    private Saved() {}

    public static class Item {
        public final String name;
        public final double lat, lon;

        public Item(String name, double lat, double lon) {
            this.name = name; this.lat = lat; this.lon = lon;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY, "");
        if (raw.isEmpty()) return out;

        for (String row : raw.split(R)) {
            if (row.isEmpty()) continue;
            String[] f = row.split(F);
            if (f.length < 3) continue;
            try {
                out.add(new Item(f[0], Double.parseDouble(f[1]), Double.parseDouble(f[2])));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public static boolean has(Context c, String name) {
        for (Item i : list(c)) if (i.name.equals(name)) return true;
        return false;
    }

    public static boolean toggle(Context c, String name, double lat, double lon) {
        if (has(c, name)) {
            remove(c, name);
            return false;
        }
        add(c, name, lat, lon);
        return true;
    }

    public static void add(Context c, String name, double lat, double lon) {
        List<Item> cur = list(c);
        for (int i = cur.size() - 1; i >= 0; i--) {
            if (cur.get(i).name.equals(name)) cur.remove(i);
        }
        cur.add(0, new Item(name, lat, lon));
        while (cur.size() > MAX) cur.remove(cur.size() - 1);
        write(c, cur);
    }

    public static void remove(Context c, String name) {
        List<Item> cur = list(c);
        for (int i = cur.size() - 1; i >= 0; i--) {
            if (cur.get(i).name.equals(name)) cur.remove(i);
        }
        write(c, cur);
    }

    private static void write(Context c, List<Item> items) {
        StringBuilder b = new StringBuilder();
        for (Item i : items) {
            if (b.length() > 0) b.append(R);
            b.append(i.name).append(F).append(i.lat).append(F).append(i.lon);
        }
        prefs(c).edit().putString(KEY, b.toString()).apply();
    }
}
