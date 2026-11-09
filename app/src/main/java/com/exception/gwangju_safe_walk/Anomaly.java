package com.exception.gwangju_safe_walk;

/** 이상 상황 판정 결과. 팀장 모듈이 만들어서 Emergency 로 넘긴다. */
public class Anomaly {

    // 확인 창만 띄울지, 무응답 시 보호자에게까지 알릴지
    public static final int LEVEL_CHECK = 1;
    public static final int LEVEL_ALERT = 2;

    public String kind;      // "정지", "낙상" 같은 종류. 로그와 구분용
    public String reason;    // 사람이 읽을 문구. 보호자 문자에 그대로 들어간다
    public int level;
    public double lat, lon;
    public long atMs;

    public Anomaly(String kind, String reason, int level, double lat, double lon) {
        this.kind = kind;
        this.reason = reason;
        this.level = level;
        this.lat = lat;
        this.lon = lon;
        this.atMs = System.currentTimeMillis();
    }

    public String text() {
        return (reason == null || reason.trim().isEmpty()) ? "이상 상황이 감지되었습니다" : reason.trim();
    }
}
