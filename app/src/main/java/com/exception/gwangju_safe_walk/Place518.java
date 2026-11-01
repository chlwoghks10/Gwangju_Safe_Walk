package com.exception.gwangju_safe_walk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Place518 {
    private Place518() {}

    public static class Spot {
        public final String name;
        public final String area;
        public final String note;

        Spot(String name, String area, String note) {
            this.name = name; this.area = area; this.note = note;
        }
    }

    private static final List<Spot> LIST = new ArrayList<>(Arrays.asList(
            new Spot("전남대학교 정문", "북구 용봉동",
                    "1980년 5월 18일 아침, 학생들과 계엄군이 처음 맞선 자리다."),

            new Spot("광주역", "북구 중흥동",
                    "5월 19일과 20일, 시민과 계엄군이 크게 부딪친 곳이다."),

            new Spot("금남로", "동구",
                    "시민들이 모여 행진하던 길. 5·18의 한복판이었다."),

            new Spot("전일빌딩245", "동구 금남로",
                    "건물에서 발견된 탄흔 245개를 이름으로 삼았다. 헬기 사격의 근거가 되었다."),

            new Spot("5·18민주광장", "동구 광산동",
                    "옛 전남도청 앞 광장. 매일 저녁 시민궐기대회가 열렸다."),

            new Spot("국립아시아문화전당", "동구 광산동",
                    "옛 전남도청 자리. 5월 27일 새벽 마지막까지 사람들이 남아 있던 건물이다."),

            new Spot("5·18민주화운동기록관", "동구 금남로",
                    "그때의 기록을 모아 둔 곳. 유네스코 세계기록유산으로 올랐다."),

            new Spot("광주공원", "남구 구동",
                    "시민군이 모여 조를 짜고 무기를 나눠 받던 자리다."),

            new Spot("5·18자유공원", "서구 치평동",
                    "붙잡힌 사람들이 갇혔던 옛 상무대 영창과 법정을 옮겨 복원했다."),

            new Spot("5·18기념공원", "서구 쌍촌동",
                    "희생자를 기리는 공원. 지하에 추모공간이 있다."),

            new Spot("국립5·18민주묘지", "북구 운정동",
                    "희생자들이 잠든 국립묘지다."),

            new Spot("망월동 구묘역", "북구 망월동",
                    "국립묘지로 옮기기 전, 희생자들이 처음 묻혔던 곳이다.")
    ));

    public static List<Spot> all() {
        return LIST;
    }

    public static int count() {
        return LIST.size();
    }
}
