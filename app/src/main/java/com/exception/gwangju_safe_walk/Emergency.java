package com.exception.gwangju_safe_walk;

import android.Manifest;
import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.SmsManager;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

// 이상 판정을 받은 뒤의 처리. 확인 창을 띄우고, 응답이 없으면 보호자에게 문자를 보낸다
public class Emergency {

    private static final String TAG = "Emergency";

    // 확인 창을 띄워 두는 시간. 사람이 누른 신고가 아니라 자동 판정이라 넉넉히 준다
    private static final int COUNTDOWN_SEC = 15;

    // 취소한 뒤 이만큼은 다시 판정을 받아도 무시한다
    private static final long COOLDOWN_MS = 3 * 60 * 1000L;

    // 같은 보호자에게 문자를 다시 보내기까지 최소 간격
    private static final long SMS_GAP_MS = 2 * 60 * 1000L;

    // 사용자가 괜찮아요를 눌렀을 때 서버에도 알리기 위한 통로
    public interface Listener {
        void onUserOk();
    }

    private Listener listener;
    private final Activity act;
    private final Voice voice;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private AlertDialog dialog;
    private Runnable ticker;
    private int left;
    private boolean running = false;   // 확인 창이 떠 있는 중
    private boolean alerted = false;   // 이번 건으로 보호자에게 이미 알렸는지
    private long blockedUntil = 0;
    private long lastSmsMs = 0;
    private Anomaly current;

    public Emergency(Activity act, Voice voice) {
        this.act = act;
        this.voice = voice;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    // 좌표를 주소로 바꾼 결과. 늦게 들어와도 된다
    public void setPlace(String place) {
        if (current != null && place != null && !place.trim().isEmpty()) {
            current.place = place.trim();
        }
    }

    // 창을 띄우지 않기로 하면 false 를 돌려준다. 그때는 부른 쪽이 서버도 초기화해야 한다
    public boolean handle(Anomaly a) {
        if (a == null) return false;
        if (gone()) return false;                                    // 화면이 이미 없어짐
        if (running) return false;                                   // 이미 처리 중
        if (SystemClock.elapsedRealtime() < blockedUntil) return false;  // 방금 취소함
        if (Guardians.load(act).isEmpty()) {
            Toast.makeText(act, "보호자가 등록되지 않아 알릴 수 없습니다", Toast.LENGTH_LONG).show();
            return false;
        }
        current = a;
        alerted = false;
        running = true;
        askUser();
        return true;
    }

    // 서버가 보호자 알림 단계라고 알려 왔을 때
    public void alertNow() {
        if (alerted || current == null || gone()) return;
        stopTicker();
        closeDialog();
        alert();
        running = false;
        blockedUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS;
    }

    public void shutdown() {
        stopTicker();
        closeDialog();
        running = false;
    }

    private void askUser() {
        say("이상 상황이 감지되었습니다. 괜찮으시면 괜찮아요를 눌러 주세요.");

        dialog = new AlertDialog.Builder(act)
                .setTitle("괜찮으세요?")
                .setMessage(body(current.waitSec > 0 ? current.waitSec : COUNTDOWN_SEC))
                .setCancelable(false)
                .setPositiveButton("괜찮아요", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        onSafe();
                    }
                })
                .setNegativeButton("도움이 필요해요", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        onHelp();
                    }
                })
                .show();

        left = current.waitSec > 0 ? current.waitSec : COUNTDOWN_SEC;
        ticker = new Runnable() {
            @Override
            public void run() {
                left--;
                if (!running) return;
                if (left <= 0) {
                    stopTicker();
                    onNoAnswer();
                    return;
                }
                if (dialog != null) dialog.setMessage(body(left));
                ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(ticker, 1000);
    }

    private String body(int sec) {
        return current.text() + "\n\n"
                + sec + "초 뒤에 보호자에게 위치를 보냅니다.";
    }

    private void onSafe() {
        stopTicker();
        closeDialog();
        running = false;
        blockedUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS;
        if (listener != null) listener.onUserOk();
        say("알겠습니다. 안내를 계속합니다.");
        // 이미 보호자에게 알렸으면 괜찮다는 것도 알려야 한다
        if (alerted) {
            sendToAll("[광주 안심길] 상황이 해제되었습니다. 괜찮습니다.", false);
            Toast.makeText(act, "보호자에게 해제 문자를 보냈습니다", Toast.LENGTH_SHORT).show();
        }
    }

    private void onHelp() {
        stopTicker();
        closeDialog();
        alert();
        showCallOptions();
    }

    private void onNoAnswer() {
        closeDialog();
        if (gone()) { running = false; return; }
        if (current.level < Anomaly.LEVEL_ALERT) {
            running = false;
            blockedUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS;
            return;
        }
        alert();
        new AlertDialog.Builder(act)
                .setTitle("보호자에게 알렸습니다")
                .setMessage("보호자가 곧 전화할 수 있도록 벨소리를 켰습니다.\n\n"
                        + "괜찮으시면 아래를 눌러 주세요. 보호자에게 해제 문자가 갑니다.")
                .setPositiveButton("괜찮아요", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        onSafe();
                    }
                })
                .setNegativeButton("전화 걸기", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        showCallOptions();
                    }
                })
                .show();
    }

    private void alert() {
        boostRinger();
        sendToAll(message(), alerted);
        alerted = true;
        say("보호자에게 위치를 보냈습니다.");
    }

    private String message() {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(current.atMs);
        String where = (current.place == null || current.place.isEmpty())
                ? String.format(Locale.US, "%.5f, %.5f", current.lat, current.lon)
                : current.place + " 부근";
        return String.format(Locale.KOREA,
                "[광주 안심길] 이상 상황이 감지되었습니다.\n%s\n%d/%d %02d:%02d · %s\n%s\n→ 지금 전화해 주세요. 받지 않으면 112에 신고해 주세요.",
                current.text(),
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE),
                where,
                mapLink(current.lat, current.lon));
    }

    private static String mapLink(double lat, double lon) {
        return String.format(Locale.US,
                "https://map.naver.com/v5/?c=%.5f,%.5f,17,0,0,0,dh", lon, lat);
    }

    // repeat 이면 2분 간격 제한을 받는다. 첫 알림과 해제 문자는 항상 보낸다
    private void sendToAll(final String text, boolean repeat) {
        List<Guardians.Person> g = Guardians.load(act);
        if (g.isEmpty()) {
            Toast.makeText(act, "보호자가 없어 문자를 못 보냈습니다", Toast.LENGTH_LONG).show();
            return;
        }

        long now = System.currentTimeMillis();
        if (repeat && now - lastSmsMs < SMS_GAP_MS) return;   // 문자 폭탄 방지
        lastSmsMs = now;

        if (Guardians.isTestMode(act)) {
            new AlertDialog.Builder(act)
                    .setTitle("시험 모드 — 보내지 않았습니다")
                    .setMessage("실제로 보내려면 더보기 > 긴급 시 연락할 사람 에서 "
                            + "시험 모드를 꺼 주세요.\n\n받는 사람 " + g.size() + "명\n\n" + text)
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }

        if (ContextCompat.checkSelfPermission(act, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            // 권한이 없으면 문자 앱을 열어 준다. 보내기만 누르면 된다
            Toast.makeText(act, "문자 권한이 없어 문자 앱을 엽니다", Toast.LENGTH_LONG).show();
            openSmsApp(g.get(0).phone, text);
            return;
        }

        SmsManager sms = smsManager();
        if (sms == null) {
            Toast.makeText(act, "문자 기능을 쓸 수 없어 문자 앱을 엽니다", Toast.LENGTH_LONG).show();
            openSmsApp(g.get(0).phone, text);
            return;
        }

        int ok = 0;
        String err = null;
        for (int i = 0; i < g.size(); i++) {
            try {
                sms.sendMultipartTextMessage(g.get(i).phone, null,
                        sms.divideMessage(text), null, null);
                ok++;
            } catch (Exception e) {
                err = e.getMessage();
                Log.e(TAG, "문자 실패: " + g.get(i).phone, e);
            }
        }
        if (ok > 0) {
            Toast.makeText(act, "보호자 " + ok + "명에게 문자를 보냈습니다",
                    Toast.LENGTH_SHORT).show();
        } else {
            // 조용히 실패하면 원인을 알 수 없다. 화면에 띄우고 문자 앱으로 넘긴다
            new AlertDialog.Builder(act)
                    .setTitle("문자를 보내지 못했습니다")
                    .setMessage((err == null ? "" : err + "\n\n") + "문자 앱으로 보내시겠습니까?")
                    .setPositiveButton("문자 앱 열기", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            openSmsApp(Guardians.load(act).get(0).phone, text);
                        }
                    })
                    .setNegativeButton("닫기", null)
                    .show();
        }
    }

    private SmsManager smsManager() {
        try {
            if (Build.VERSION.SDK_INT >= 31) return act.getSystemService(SmsManager.class);
            return SmsManager.getDefault();
        } catch (Exception e) {
            return null;
        }
    }

    private void openSmsApp(String phone, String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + phone));
            i.putExtra("sms_body", text);
            act.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "문자 앱 열기 실패", e);
        }
    }

    // 무음이면 보호자가 전화해도 안 울린다. 그러면 이 흐름 전체가 끊긴다
    private void boostRinger() {
        try {
            AudioManager am = (AudioManager) act.getSystemService(Activity.AUDIO_SERVICE);
            if (am == null) return;
            am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
            am.setStreamVolume(AudioManager.STREAM_RING,
                    am.getStreamMaxVolume(AudioManager.STREAM_RING), 0);
        } catch (Exception e) {
            Log.w(TAG, "벨소리 설정 실패: " + e.getMessage());
        }
    }

    private void showCallOptions() {
        running = false;
        blockedUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS;

        List<Guardians.Person> g = Guardians.load(act);
        final String first = g.isEmpty() ? null : g.get(0).phone;

        new AlertDialog.Builder(act)
                .setTitle("어디로 전화할까요")
                .setMessage("112 는 다이얼 화면이 열립니다. 통화 버튼을 눌러 주세요.")
                .setPositiveButton("112", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        dial("112");
                    }
                })
                .setNegativeButton(first == null ? "닫기" : "보호자",
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                if (first != null) dial(first);
                            }
                        })
                .show();
    }

    // 112 는 ACTION_CALL 로 걸 수 없다. 안드로이드가 막아 둔 것이라 다이얼만 띄운다
    private void dial(String number) {
        try {
            Intent i = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number));
            act.startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "다이얼 실패", e);
        }
    }

    // 화면이 없어진 뒤에 창을 띄우려 하면 앱이 죽는다
    private boolean gone() {
        if (act == null || act.isFinishing()) return true;
        return android.os.Build.VERSION.SDK_INT >= 17 && act.isDestroyed();
    }

    private void say(String text) {
        if (voice != null) voice.say(text, true);
    }

    private void stopTicker() {
        if (ticker != null) ui.removeCallbacks(ticker);
        ticker = null;
    }

    private void closeDialog() {
        if (dialog != null) {
            try { dialog.dismiss(); } catch (Exception ignored) { }
            dialog = null;
        }
    }

    // 시연용. 서버 판정 없이 흐름만 돌려 본다
    public void test(double lat, double lon) {
        handle(new Anomaly("정지", "5분째 같은 자리에 머물러 있습니다",
                Anomaly.LEVEL_ALERT, lat, lon));
    }
}
