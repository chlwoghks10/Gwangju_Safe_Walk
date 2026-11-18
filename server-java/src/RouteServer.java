import com.google.gson.*;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Tmap 보행자 경로 프록시 서버 (server.js의 Java 버전)
 *
 * 실행: TMAP_APP_KEY=발급받은키 java -cp .:gson-2.11.0.jar RouteServer
 *       (Windows PowerShell: $env:TMAP_APP_KEY="키"; java -cp ".;gson-2.11.0.jar" RouteServer)
 *
 * GET /api/route?points=lat,lng;lat,lng;...
 *   첫 번째 = 출발, 마지막 = 도착, 중간 = 경유지(Tmap 최대 5개)
 * 응답: { path:[{lat,lng}...], distance, duration, guides:[...] }
 */
public class RouteServer {
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000"));
    private static final String APP_KEY = System.getenv("TMAP_APP_KEY");
    private static final String BASE = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1&format=json";

    private static final int MAX_VIA = 5;            // Tmap passList 제한
    private static final double MAX_DISTANCE = 20000; // 보행 경로 상한(m)

    private static final Gson GSON = new Gson();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    public static void main(String[] args) throws IOException {
        // 내 컴퓨터에서만 접속 가능하도록 localhost에만 바인딩
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
        server.createContext("/", RouteServer::handle);
        server.start();
        System.out.println("프록시 서버 실행 중: http://localhost:" + PORT + "/api/route");
        if (APP_KEY == null || APP_KEY.isBlank()) System.out.println("! 환경변수 TMAP_APP_KEY 를 설정하세요.");
    }

    private static void send(HttpExchange ex, int status, Object obj) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Access-Control-Allow-Origin", "*");   // 로컬 개발용. 배포 시 허용 도메인으로 제한할 것
        h.set("Access-Control-Allow-Headers", "Content-Type");

        if (status == 204) {
            ex.sendResponseHeaders(204, -1);
        } else {
            byte[] b = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        }
        ex.close();
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    /** 두 지점 직선거리(m) — 요청 전 상한 검사용. a, b = {lat, lng} */
    private static double haversine(double[] a, double[] b) {
        return SafeRoute.haversine(new LatLng(a[0], a[1]), new LatLng(b[0], b[1]));
    }

    private static String queryParam(URI uri, String name) {
        String q = uri.getRawQuery();
        if (q == null) return "";
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8);
            if (k.equals(name)) return i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
        }
        return "";
    }

    /** "lat,lng;lat,lng" -> [[lat,lng],...]. 형식이 틀리면 null */
    private static double[][] parsePoints(String s) {
        String[] parts = s.split(";", -1);
        if (parts.length < 2) return null;
        double[][] pts = new double[parts.length][];
        for (int i = 0; i < parts.length; i++) {
            String[] xy = parts[i].split(",", -1);
            if (xy.length != 2) return null;
            try {
                double lat = Double.parseDouble(xy[0].trim());
                double lng = Double.parseDouble(xy[1].trim());
                if (!Double.isFinite(lat) || !Double.isFinite(lng)) return null;
                pts[i] = new double[]{lat, lng};
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return pts;
    }

    private static boolean has(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull();
    }

    private static void handle(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("OPTIONS")) { send(ex, 204, null); return; }
        URI uri = ex.getRequestURI();
        if (!uri.getPath().equals("/api/route")) { send(ex, 404, err("not found")); return; }

        if (APP_KEY == null || APP_KEY.isBlank()) {
            send(ex, 500, err("환경변수 TMAP_APP_KEY 가 설정되지 않았습니다."));
            return;
        }

        // ---- 입력 검증 ----
        double[][] pts = parsePoints(queryParam(uri, "points"));
        if (pts == null) {
            send(ex, 400, err("points 형식이 올바르지 않습니다. (lat,lng;lat,lng;...)"));
            return;
        }
        if (pts.length - 2 > MAX_VIA) {
            send(ex, 400, err("경유지는 최대 " + MAX_VIA + "개까지 가능합니다."));
            return;
        }
        double[] start = pts[0], goal = pts[pts.length - 1];
        if (haversine(start, goal) > MAX_DISTANCE) {
            send(ex, 400, err("보행 경로 범위를 벗어났습니다 (직선 20km 초과)."));
            return;
        }

        // ---- Tmap 요청 본문 ----
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startX", start[1]); body.put("startY", start[0]);   // 경도=X, 위도=Y
        body.put("endX", goal[1]);    body.put("endY", goal[0]);
        body.put("startName", URLEncoder.encode("출발", StandardCharsets.UTF_8));  // 한글은 반드시 인코딩
        body.put("endName", URLEncoder.encode("도착", StandardCharsets.UTF_8));
        body.put("reqCoordType", "WGS84GEO");
        body.put("resCoordType", "WGS84GEO");
        body.put("searchOption", "0");   // 0=추천, 4=대로우선, 10=최단, 30=계단제외
        if (pts.length > 2) {
            // passList는 "경도,위도_경도,위도" 형식
            StringJoiner sj = new StringJoiner("_");
            for (int i = 1; i < pts.length - 1; i++) sj.add(pts[i][1] + "," + pts[i][0]);
            body.put("passList", sj.toString());
        }

        // ---- 호출 ----
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE))
                    .timeout(Duration.ofSeconds(10))   // 10초 타임아웃
                    .header("appKey", APP_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();
            HttpResponse<String> r = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

            String text = r.body();
            JsonObject json = null;
            try { json = JsonParser.parseString(text).getAsJsonObject(); } catch (Exception ignored) {}

            boolean ok = r.statusCode() >= 200 && r.statusCode() < 300;
            if (!ok || json == null) {
                String msg = "HTTP " + r.statusCode();
                if (json != null) {
                    if (has(json, "error") && json.get("error").isJsonObject()
                            && has(json.getAsJsonObject("error"), "message")) {
                        msg = json.getAsJsonObject("error").get("message").getAsString();
                    } else if (has(json, "message")) {
                        msg = json.get("message").getAsString();
                    }
                }
                Map<String, Object> e = err("Tmap 호출 실패: " + msg);
                e.put("detail", text.substring(0, Math.min(300, text.length())));
                send(ex, 502, e);
                return;
            }
            if (!has(json, "features") || !json.get("features").isJsonArray()
                    || json.getAsJsonArray("features").isEmpty()) {
                send(ex, 502, err("경로를 찾지 못했습니다. (보행 불가 지점일 수 있음)"));
                return;
            }

            // ---- GeoJSON 파싱 ----
            // LineString = 링크(좌표열), Point = 노드(안내 지점)
            List<LatLng> path = new ArrayList<>();
            List<Map<String, Object>> guides = new ArrayList<>();
            JsonElement distance = new JsonPrimitive(0), duration = new JsonPrimitive(0);

            for (JsonElement fe : json.getAsJsonArray("features")) {
                JsonObject f = fe.getAsJsonObject();
                JsonObject g = f.getAsJsonObject("geometry");
                JsonObject p = has(f, "properties") ? f.getAsJsonObject("properties") : new JsonObject();

                // 첫 Point에 총계가 담겨 옴
                if (has(p, "totalDistance") && p.get("totalDistance").getAsDouble() != 0) distance = p.get("totalDistance");
                if (has(p, "totalTime") && p.get("totalTime").getAsDouble() != 0) duration = p.get("totalTime");

                String gType = g.get("type").getAsString();
                if (gType.equals("LineString")) {
                    for (JsonElement ce : g.getAsJsonArray("coordinates")) {
                        JsonArray c = ce.getAsJsonArray();
                        path.add(new LatLng(c.get(1).getAsDouble(), c.get(0).getAsDouble()));  // [lng, lat]
                    }
                } else if (gType.equals("Point") && has(p, "description")
                        && !p.get("description").getAsString().isEmpty()) {
                    JsonArray c = g.getAsJsonArray("coordinates");   // [lng, lat]
                    Map<String, Object> gd = new LinkedHashMap<>();
                    gd.put("lat", c.get(1).getAsDouble());
                    gd.put("lon", c.get(0).getAsDouble());
                    gd.put("description", p.get("description").getAsString());
                    if (has(p, "turnType")) gd.put("turnType", p.get("turnType").getAsInt());
                    guides.add(gd);
                }
            }

            if (path.isEmpty()) { send(ex, 502, err("경로 좌표가 비어 있습니다.")); return; }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("path", path);
            result.put("distance", distance);
            result.put("duration", duration);
            result.put("guides", guides);
            send(ex, 200, result);
        } catch (HttpTimeoutException e) {
            send(ex, 504, err("Tmap 응답 시간 초과"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(ex, 500, err("서버 내부 오류: 요청이 중단되었습니다."));
        } catch (Exception e) {
            send(ex, 500, err("서버 내부 오류: " + (e.getMessage() != null ? e.getMessage() : e.toString())));
        }
    }
}
