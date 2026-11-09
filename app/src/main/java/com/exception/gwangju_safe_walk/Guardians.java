package com.exception.gwangju_safe_walk;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/** 긴급 시 연락할 보호자. 폰에만 저장하고 서버로 보내지 않는다. */
public class Guardians {

    private static final String PREF = "safewalk";
    private static final String KEY  = "guardians";
    private static final String KEY_TEST = "emergency_test";
    public static final int MAX = 3;

    public static class Person {
        public final String name, phone;
        public Person(String name, String phone) {
            this.name = name;
            this.phone = phone;
        }
    }

    public static List<Person> load(Context c) {
        List<Person> out = new ArrayList<Person>();
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String raw = p.getString(KEY, "");
        if (raw == null || raw.isEmpty()) return out;
        String[] rows = raw.split(";");
        for (int i = 0; i < rows.length; i++) {
            String[] f = rows[i].split("\\|");
            if (f.length == 2 && !f[1].trim().isEmpty()) {
                out.add(new Person(f[0].trim(), f[1].trim()));
            }
        }
        return out;
    }

    private static void save(Context c, List<Person> list) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (b.length() > 0) b.append(';');
            b.append(list.get(i).name).append('|').append(list.get(i).phone);
        }
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, b.toString()).apply();
    }

    /** 더보기 화면에 보여 줄 한 줄 */
    public static String summary(Context c) {
        List<Person> g = load(c);
        if (g.isEmpty()) return "등록 안 됨";
        if (g.size() == 1) return g.get(0).name;
        return g.get(0).name + " 외 " + (g.size() - 1) + "명";
    }

    /** 실제로 문자를 보내지 않고 화면에만 띄우는 시험 모드 */
    public static boolean isTestMode(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_TEST, true);
    }

    public static void setTestMode(Context c, boolean on) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_TEST, on).apply();
    }

    /** 보호자를 넣고 빼는 창. 끝나면 after 를 부른다 */
    public static void edit(final Activity a, final Runnable after) {
        List<Person> now = load(a);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);

        final EditText[] names  = new EditText[MAX];
        final EditText[] phones = new EditText[MAX];

        for (int i = 0; i < MAX; i++) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);

            EditText n = new EditText(a);
            n.setHint("이름");
            n.setSingleLine(true);
            LinearLayout.LayoutParams lpn = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

            EditText t = new EditText(a);
            t.setHint("010-0000-0000");
            t.setSingleLine(true);
            t.setInputType(InputType.TYPE_CLASS_PHONE);
            LinearLayout.LayoutParams lpt = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f);

            if (i < now.size()) {
                n.setText(now.get(i).name);
                t.setText(now.get(i).phone);
            }
            row.addView(n, lpn);
            row.addView(t, lpt);
            box.addView(row);

            names[i] = n;
            phones[i] = t;
        }

        TextView note = new TextView(a);
        note.setText("보호자 번호는 이 휴대폰에만 저장되고 서버로 보내지 않습니다.");
        note.setTextSize(12);
        box.addView(note);

        final boolean test = isTestMode(a);
        new AlertDialog.Builder(a)
                .setTitle("긴급 시 연락할 사람")
                .setView(box)
                .setPositiveButton("저장", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        List<Person> list = new ArrayList<Person>();
                        for (int i = 0; i < MAX; i++) {
                            String nm = names[i].getText().toString().trim();
                            String ph = phones[i].getText().toString().trim().replace("-", "");
                            if (ph.isEmpty()) continue;
                            list.add(new Person(nm.isEmpty() ? "보호자" : nm, ph));
                        }
                        save(a, list);
                        Toast.makeText(a, list.isEmpty() ? "보호자를 지웠습니다"
                                : list.size() + "명 저장했습니다", Toast.LENGTH_SHORT).show();
                        // 급할 때 권한 창이 뜨면 늦는다. 등록할 때 미리 받아 둔다
                        if (!list.isEmpty() && ContextCompat.checkSelfPermission(a,
                                Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                            ActivityCompat.requestPermissions(a,
                                    new String[]{Manifest.permission.SEND_SMS}, 3100);
                        }
                        if (after != null) after.run();
                    }
                })
                .setNeutralButton(test ? "시험 모드 끄기" : "시험 모드 켜기",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                setTestMode(a, !test);
                                Toast.makeText(a, !test
                                        ? "시험 모드. 문자를 실제로 보내지 않습니다"
                                        : "실제 발송 모드입니다", Toast.LENGTH_LONG).show();
                                if (after != null) after.run();
                            }
                        })
                .setNegativeButton("취소", null)
                .show();
    }
}
