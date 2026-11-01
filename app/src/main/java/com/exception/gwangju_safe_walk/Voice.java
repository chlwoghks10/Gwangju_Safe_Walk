package com.exception.gwangju_safe_walk;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 길 안내 음성.
 *
 * 기본 엔진에 붙는 것이 실패하는 기기가 있어서, 폰에 깔린 엔진을 직접 찾아
 * 하나씩 지정해 붙어 본다. 그래도 안 되면 마지막으로 기본 방식을 한 번 더 시도한다.
 */
public class Voice {

    private static final String TAG = "SafeWalkVoice";

    public interface Listener {
        void onVoiceState(boolean ready, String message);
    }

    private final Context appCtx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final List<String> engines = new ArrayList<>();
    private int attempt = 0;
    private String lastTried = "";

    private TextToSpeech tts;        // 연결에 성공한 것
    private TextToSpeech pending;    // 지금 붙어 보는 중인 것

    private boolean ready = false;
    private boolean muted = false;
    private String lastError = null;

    private final List<String> waiting = new ArrayList<>();
    private int seq = 0;

    public Voice(Context c, Listener listener) {
        this.listener = listener;
        this.appCtx = c.getApplicationContext();

        engines.addAll(visibleEngines(appCtx));
        Log.i(TAG, "폰에서 보이는 TTS 엔진 " + engines.size() + "개: " + engines);

        tryNext();
    }

    /** 이 앱이 볼 수 있는 TTS 엔진 목록. 매니페스트 queries 선언이 있어야 보인다 */
    private static List<String> visibleEngines(Context c) {
        List<String> out = new ArrayList<>();
        try {
            PackageManager pm = c.getPackageManager();
            Intent i = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
            for (ResolveInfo ri : pm.queryIntentServices(i, 0)) {
                if (ri.serviceInfo != null && ri.serviceInfo.packageName != null) {
                    out.add(ri.serviceInfo.packageName);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "엔진 목록 조회 실패", e);
        }
        return out;
    }

    /** 엔진을 하나씩 지정해 붙어 보고, 다 실패하면 기본 방식으로 한 번 더 */
    private void tryNext() {
        if (pending != null) {
            try { pending.shutdown(); } catch (Exception ignored) { }
            pending = null;
        }
        if (attempt > engines.size()) {
            fail("음성 엔진에 연결하지 못했습니다\n"
                    + "시도한 엔진: " + engines + " + 기본");
            return;
        }

        final String pkg = (attempt < engines.size()) ? engines.get(attempt) : null;
        attempt++;
        lastTried = (pkg == null ? "기본 엔진" : pkg);
        Log.i(TAG, "연결 시도 " + attempt + ": " + lastTried);

        try {
            TextToSpeech t = (pkg == null)
                    ? new TextToSpeech(appCtx, this::onInit)
                    : new TextToSpeech(appCtx, this::onInit, pkg);
            // onInit 이 생성자 안에서 바로 불릴 수 있어, 그 경우 pending 을 덮지 않는다
            if (pending == null && !ready) pending = t;
        } catch (Exception e) {
            Log.e(TAG, "생성 실패 (" + lastTried + "): " + e.getMessage(), e);
            main.post(this::tryNext);
        }
    }

    private void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "연결 실패 (" + lastTried + ") status=" + status);
            main.post(this::tryNext);
            return;
        }
        main.post(this::finishInit);
    }

    private void finishInit() {
        if (ready) return;
        if (pending == null) { tryNext(); return; }

        tts = pending;
        pending = null;

        int r;
        try {
            r = tts.setLanguage(Locale.KOREAN);
        } catch (Exception e) {
            fail("언어 설정 실패 (" + lastTried + "): " + e.getMessage());
            return;
        }
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            fail("한국어 음성이 설치되어 있지 않습니다 (" + lastTried + ")\n"
                    + "설정 → 접근성 → 글자 읽어주기에서 한국어를 받아 주세요");
            return;
        }

        tts.setSpeechRate(1.0f);
        tts.setPitch(1.0f);
        ready = true;
        lastError = null;
        Log.i(TAG, "음성 준비됨 — " + lastTried + " / setLanguage=" + r);
        notifyState(true, "음성 준비됨 (" + lastTried + ")");

        for (String s : waiting) raw(s, false);
        waiting.clear();
    }

    public void say(String text, boolean now) {
        if (text == null || text.trim().isEmpty()) return;

        if (muted) {
            notifyState(false, "음성이 꺼져 있습니다. 음성 칩을 눌러 켜 주세요");
            return;
        }
        if (!ready) {
            if (lastError != null) {
                notifyState(false, lastError);
                return;
            }
            if (now) waiting.clear();
            waiting.add(text);
            return;
        }
        raw(text, now);
    }

    public void say(String text) {
        say(text, false);
    }

    private void raw(String text, boolean now) {
        try {
            int r = tts.speak(text,
                    now ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                    null, "sw-" + (seq++));
            if (r != TextToSpeech.SUCCESS) {
                Log.w(TAG, "speak 실패 r=" + r);
                notifyState(false, "읽기 실패 (speak=" + r + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "읽기 실패: " + e.getMessage(), e);
            notifyState(false, "읽기 예외: " + e.getMessage());
        }
    }

    public void stop() {
        waiting.clear();
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) { }
        }
    }

    public boolean isMuted() {
        return muted;
    }

    public boolean toggleMute() {
        muted = !muted;
        if (muted) stop();
        return muted;
    }

    public boolean isReady() {
        return ready;
    }

    public void shutdown() {
        stop();
        if (tts != null) {
            try { tts.shutdown(); } catch (Exception ignored) { }
            tts = null;
        }
        if (pending != null) {
            try { pending.shutdown(); } catch (Exception ignored) { }
            pending = null;
        }
        ready = false;
    }

    public static String distanceText(double meters) {
        if (meters >= 1000) {
            return String.format(Locale.KOREA, "%.1f킬로미터", meters / 1000.0);
        }
        long rounded = Math.round(meters / 10.0) * 10;
        if (rounded < 10) rounded = 10;
        return rounded + "미터";
    }

    private void fail(String message) {
        ready = false;
        lastError = message;
        Log.w(TAG, message);
        notifyState(false, message);
    }

    private void notifyState(boolean ok, String message) {
        if (listener != null) listener.onVoiceState(ok, message);
    }
}
