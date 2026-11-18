import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

public class Server {
    // 탭한 지점이 도로에서 이만큼 넘게 떨어져 있으면 거부한다.
    // 2km 상자 안을 무작위로 찍어 보면 100m 까지 허용해도 99.3% 가 통과하고,
    // 300m 로 늘려 봐야 0.7% 밖에 늘지 않는다. 그 0.7% 는 경로를 억지로 내는 대신
    // 안내문을 띄우는 쪽이 낫다 (도로에서 300m 떨어진 곳에서 출발하는 경로가 되므로).
    private static final double MAX_SNAP_METERS = 100.0;

    private static final String PROXY =
            System.getenv().getOrDefault("ROUTE_PROXY", "http://127.0.0.1:3000/api/route");

    private static final long BUDGET_NANOS = 8_000_000_000L;

    // 키는 환경 변수로만 받는다. 코드에 적으면 깃허브에 그대로 올라간다
    private static final String KAKAO_KEY = System.getenv("KAKAO_REST_KEY");
    private static final String KAKAO_URL = "https://dapi.kakao.com/v2/local/search/keyword.json";

    private static final double SEARCH_LAT = 35.1400, SEARCH_LON = 126.9270;
    private static final int SEARCH_RADIUS = 20000;

    private static RoadGraph graph;
    private static Facilities facilities;
    private static SafeRoute.Index safeIdx;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();

    private static final SafeRoute.Options SAFE_OPT = new SafeRoute.Options();

    private record Guide(double lat, double lon, String text) {}

    // 경로를 벗어났다고 보는 거리. 회의에서 40m 로 정했다
    private static final double OFF_ROUTE_METERS = 40.0;

    // 안내 중인 경로를 id 로 기억해 둔다. 앱이 /track 으로 좌표만 보내면 이걸로 판정한다
    private record Nav(String kind, List<LatLng> coords, LatLng dest, long madeAt) {}

    private static final Map<String, Nav> NAVS = new ConcurrentHashMap<>();

    // 이상 판정 세션. 경로(nav_id)마다 팀장 판정기를 하나씩 둔다
    private static final class Watch {
        final AnomalyDetector det;
        final double safety;
        Watch(AnomalyDetector det, double safety) { this.det = det; this.safety = safety; }
    }

    private static final Map<String, Watch> WATCHES = new ConcurrentHashMap<>();
    private static final long NAV_TTL_MS = 2 * 60 * 60 * 1000L;
    private static final int NAV_MAX = 3000;
    private static final AtomicLong NAV_SEQ = new AtomicLong();

    private static String putNav(String kind, List<LatLng> coords, LatLng dest) {
        long now = System.currentTimeMillis();
        if (NAVS.size() > NAV_MAX) NAVS.clear();
        NAVS.entrySet().removeIf(e -> now - e.getValue().madeAt() > NAV_TTL_MS);
        String id = Long.toString(NAV_SEQ.incrementAndGet(), 36) + "-" + Long.toString(now % 100000, 36);
        NAVS.put(id, new Nav(kind, coords, dest, now));
        return id;
    }

    // viaProxy 가 받아 온 안내를 '그 경로' 에 붙여 둔다.
    // 후보 경로를 여러 개 받아 보므로, 채택된 경로의 안내만 골라 쓰려면 이게 필요하다.
    private static final ThreadLocal<Map<List<LatLng>, List<Guide>>> PROXY_GUIDES =
            ThreadLocal.withInitial(IdentityHashMap::new);

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8000;
        Path dataDir = Paths.get(args.length > 1 ? args[1] : "data");

        long t0 = System.nanoTime();
        try (InputStream nodes = Files.newInputStream(dataDir.resolve("graph_nodes.csv"));
             InputStream edges = Files.newInputStream(dataDir.resolve("graph_edges.csv"))) {
            graph = RoadGraph.load(nodes, edges);
        }
        System.out.printf("[graph] 노드 %,d / 간선 %,d  (%.0fms)%n",
                graph.nodeCount, graph.arcCount, (System.nanoTime() - t0) / 1e6);

        facilities = new Facilities(dataDir);

        long t1 = System.nanoTime();
        safeIdx = SafeRoute.buildIndex(SafeRoute.toFacilities(loadGeo(dataDir)));
        System.out.printf("[saferoute] 시설 %,d지점 / sRef %.1f / 경유지 후보 최대 %d개  (%.0fms)%n",
                safeIdx.facilities.size(), SAFE_OPT.sRef, SAFE_OPT.numVia, (System.nanoTime() - t1) / 1e6);
        System.out.println("[saferoute] 경로 제공자 1순위: " + PROXY + " (조장 RouteServer)");
        System.out.println(KAKAO_KEY == null || KAKAO_KEY.isBlank()
                ? "[search] KAKAO_REST_KEY 없음 — 장소 검색을 쓸 수 없습니다"
                : "[search] 키 확인 — 카카오 로컬 장소 검색을 쓸 수 있습니다");

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", Server::handleHealth);
        server.createContext("/route",  Server::handleRoute);
        server.createContext("/track",  Server::handleTrack);
        server.createContext("/watch",  Server::handleWatch);
        startWatchSweeper();
        server.createContext("/search", Server::handleSearch);
        server.createContext("/",       Server::handleRoot);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();

        System.out.println("[server] http://0.0.0.0:" + port + " 에서 대기 중");
    }

    private static Map<String, JsonObject> loadGeo(Path dir) {
        Map<String, JsonObject> geo = new HashMap<>();
        for (SafeRoute.Type type : SafeRoute.Type.values()) {
            Path p = dir.resolve(type.key() + ".geojson");
            if (!Files.exists(p)) {
                System.out.println("[saferoute] " + p.getFileName() + " 없음 — 건너뜀");
                continue;
            }
            try {
                String text = Files.readString(p, StandardCharsets.UTF_8);
                geo.put(type.key(), JsonParser.parseString(text).getAsJsonObject());
            } catch (Exception e) {
                System.out.println("[saferoute] " + p.getFileName() + " 읽기 실패: " + e.getMessage());
            }
        }
        return geo;
    }

    private static List<LatLng> viaProxy(List<LatLng> wps) throws Exception {
        StringBuilder pts = new StringBuilder();
        for (LatLng p : wps) {
            if (pts.length() > 0) pts.append(';');
            pts.append(Json.num(p.lat(), 7)).append(',').append(Json.num(p.lng(), 7));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(PROXY + "?points=" + pts))
                .timeout(Duration.ofSeconds(7))
                .GET()
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
        if (res.statusCode() != 200) {
            JsonElement err = o.get("error");
            throw new Exception("RouteServer " + res.statusCode()
                    + (err == null ? "" : " — " + err.getAsString()));
        }
        List<LatLng> out = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("path")) {
            JsonObject c = e.getAsJsonObject();
            out.add(new LatLng(c.get("lat").getAsDouble(), c.get("lng").getAsDouble()));
        }
        if (out.size() < 2) throw new Exception("RouteServer 경로 좌표가 비어 있습니다");

        // 안내 지점. 예전 RouteServer 는 글자만 보내므로, 좌표가 없으면 그냥 건너뛴다
        List<Guide> guides = new ArrayList<>();
        JsonElement ge = o.get("guides");
        if (ge != null && ge.isJsonArray()) {
            for (JsonElement e : ge.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject gd = e.getAsJsonObject();
                if (!gd.has("lat") || !gd.has("lon") || !gd.has("description")) continue;
                guides.add(new Guide(gd.get("lat").getAsDouble(),
                                     gd.get("lon").getAsDouble(),
                                     gd.get("description").getAsString()));
            }
        }
        PROXY_GUIDES.get().put(out, guides);
        return out;
    }

    private static List<LatLng> viaGraph(List<LatLng> wps) throws Exception {
        List<LatLng> out = new ArrayList<>();
        for (int i = 0; i + 1 < wps.size(); i++) {
            RoadGraph.Snap a = graph.snapToRoad(wps.get(i).lat(), wps.get(i).lng());
            RoadGraph.Snap b = graph.snapToRoad(wps.get(i + 1).lat(), wps.get(i + 1).lng());
            if (a == null || b == null) throw new Exception("도로망이 비어 있습니다");
            if (a.arc == b.arc && a.frac == b.frac) continue;
            Dijkstra.Result r = Dijkstra.shortestPath(graph, a, b, graph.length);
            if (r == null) throw new Exception("도로망에서 이어지지 않는 구간이 있습니다");
            for (double[] c : graph.routeCoords(r)) {
                LatLng p = new LatLng(c[0], c[1]);
                if (!out.isEmpty()) {
                    LatLng prev = out.get(out.size() - 1);
                    if (prev.lat() == p.lat() && prev.lng() == p.lng()) continue;
                }
                out.add(p);
            }
        }
        if (out.size() < 2) throw new Exception("경로 좌표가 비어 있습니다");
        return out;
    }

    private static void handleRoot(HttpExchange ex) throws IOException {
        send(ex, 200, "{\"service\":\"광주 안심길 경로 API\","
                + "\"endpoints\":[\"/health\",\"/route\",\"/track\",\"/watch\",\"/search\"]}");
    }

    // 점에서 경로선까지의 수직거리(m)와, 그 지점부터 도착까지 남은 거리(m)
    private static double[] onPath(List<LatLng> p, double lat, double lon) {
        if (p == null || p.size() < 2) return new double[]{Double.MAX_VALUE, 0};
        double mLat = 111320.0;
        double mLon = 111320.0 * Math.cos(Math.toRadians(lat));

        int bestSeg = 0;
        double bestT = 0, bestD = Double.MAX_VALUE;
        double ax = (p.get(0).lng() - lon) * mLon;
        double ay = (p.get(0).lat() - lat) * mLat;
        for (int i = 1; i < p.size(); i++) {
            double bx = (p.get(i).lng() - lon) * mLon;
            double by = (p.get(i).lat() - lat) * mLat;
            double dx = bx - ax, dy = by - ay;
            double len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : -(ax * dx + ay * dy) / len2;
            if (t < 0) t = 0;
            if (t > 1) t = 1;
            double d = Math.hypot(ax + t * dx, ay + t * dy);
            if (d < bestD) { bestD = d; bestSeg = i - 1; bestT = t; }
            ax = bx;
            ay = by;
        }

        LatLng a = p.get(bestSeg), c = p.get(bestSeg + 1);
        double remain = (1 - bestT) * RoadGraph.distanceMeters(a.lat(), a.lng(), c.lat(), c.lng());
        for (int i = bestSeg + 1; i < p.size() - 1; i++) {
            remain += RoadGraph.distanceMeters(p.get(i).lat(), p.get(i).lng(),
                                               p.get(i + 1).lat(), p.get(i + 1).lng());
        }
        return new double[]{bestD, remain};
    }

    // ── 이상 판정 (팀장 AnomalyDetector) ──────────────────────────────
    //
    // /watch/start  안내를 켤 때 한 번. 경로의 시설 밀도로 안전도를 정해 둔다
    // /watch        걷는 동안 5초마다. 판정 결과를 그대로 돌려준다
    // /watch/ok     사용자가 "괜찮아요" 를 눌렀을 때
    // /watch/stop   안내를 끄거나 앱이 내려갈 때. 안 보내면 오경보가 난다
    private static void handleWatch(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String id = q.get("id");
        if (id == null) {
            error(ex, 422, "id 가 필요합니다");
            return;
        }

        if (path.endsWith("/start")) {
            Nav nav = NAVS.get(id);
            if (nav == null) {
                error(ex, 404, "안내 중인 경로가 아닙니다 (id 없음 또는 만료)");
                return;
            }
            double safety = safetyOf(nav.coords());
            WATCHES.put(id, new Watch(new AnomalyDetector(System.currentTimeMillis()), safety));
            send(ex, 200, "{\"ok\":true,\"safety\":" + Json.num(safety, 2)
                    + ",\"wait_sec\":" + (AnomalyDetector.CONFIRM_TIMEOUT_MS / 1000)
                    + ",\"still_min\":" + Json.num(stillMinutes(safety), 1) + "}");
            return;
        }

        Watch w = WATCHES.get(id);
        if (w == null) {
            // 세션이 없으면 앱이 start 를 다시 부르게 한다
            send(ex, 200, "{\"action\":\"NONE\",\"session\":false}");
            return;
        }

        if (path.endsWith("/ok")) {
            w.det.confirmOk(System.currentTimeMillis());
            send(ex, 200, "{\"ok\":true}");
            return;
        }
        if (path.endsWith("/stop")) {
            w.det.pause();
            WATCHES.remove(id);
            send(ex, 200, "{\"ok\":true}");
            return;
        }

        Double lat = num(q.get("lat")), lon = num(q.get("lon"));
        if (lat == null || lon == null) {
            error(ex, 422, "lat, lon 이 필요합니다");
            return;
        }
        Double acc = num(q.get("acc"));
        // 폰 시계가 틀어져 있으면 판정이 깨지므로 시각은 서버 것으로 통일한다
        long now = System.currentTimeMillis();

        AnomalyDetector.Action a = w.det.onLocation(
                new AnomalyDetector.Point(lat, lon, now, acc == null ? 0 : acc), w.safety);
        boolean still = (a == AnomalyDetector.Action.ASK_CONFIRM);
        if (a == AnomalyDetector.Action.NONE) a = w.det.tick(now);

        StringBuilder b = new StringBuilder(128);
        b.append("{\"action\":").append(Json.quote(a.name()));
        if (a == AnomalyDetector.Action.ASK_CONFIRM) {
            b.append(",\"wait_sec\":").append(AnomalyDetector.CONFIRM_TIMEOUT_MS / 1000);
            b.append(",\"reason\":").append(Json.quote(still
                    ? String.format(java.util.Locale.KOREA, "%.0f분 넘게 같은 자리에 머물러 있습니다",
                            stillMinutes(w.safety))
                    : "위치 신호가 끊겼습니다"));
        }
        b.append('}');
        send(ex, 200, b.toString());
    }

    // 앱이 죽거나 폰이 꺼지면 /watch 가 안 온다. 그걸 알아채려면 서버가 스스로 돌려 봐야 한다
    private static void startWatchSweeper() {
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
        ses.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                for (Map.Entry<String, Watch> e : WATCHES.entrySet()) {
                    try {
                        AnomalyDetector.Action a = e.getValue().det.tick(now);
                        if (a == AnomalyDetector.Action.ASK_CONFIRM) {
                            System.out.println("[watch] " + e.getKey() + " 위치 신호가 끊겼습니다");
                        } else if (a == AnomalyDetector.Action.ALERT_GUARDIAN) {
                            // 앱이 죽어 있으므로 폰으로는 알릴 수 없다. 문자 중계를 붙이면 여기서 보낸다
                            System.out.println("[watch] " + e.getKey() + " 응답 없음 — 보호자 알림 필요");
                        }
                    } catch (Exception ex) {
                        System.out.println("[watch] 점검 실패: " + ex.getMessage());
                    }
                }
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    // 안전도 = 경로 1km 당 CCTV·보안등 개수를 0~1 로 누른 값.
    // 시설이 적은 길일수록 낮게 나와 팀장 로직이 더 빨리 반응한다
    private static double safetyOf(List<LatLng> coords) {
        if (coords == null || coords.size() < 2) return 0.5;
        double lengthM = 0;
        for (int i = 1; i < coords.size(); i++) {
            lengthM += RoadGraph.distanceMeters(coords.get(i - 1).lat(), coords.get(i - 1).lng(),
                                                coords.get(i).lat(), coords.get(i).lng());
        }
        if (lengthM < 1) return 0.5;

        List<double[]> raw = new ArrayList<>(coords.size());
        for (LatLng p : coords) raw.add(new double[]{p.lat(), p.lng()});
        Map<String, List<Facilities.Hit>> fac = facilities.nearPolyline(raw);
        int n = count(fac, "cctv") + count(fac, "light");

        double perKm = n / (lengthM / 1000.0);
        double s = perKm / FULL_SAFE_PER_KM;
        return s < 0 ? 0 : (s > 1 ? 1 : s);
    }

    // 1km 당 이만큼 있으면 가장 안전한 쪽으로 본다. 광주 실측이 대체로 4~13 개다
    private static final double FULL_SAFE_PER_KM = 20.0;

    private static double stillMinutes(double safety) {
        double ms = AnomalyDetector.MIN_STILL_MS
                + (AnomalyDetector.MAX_STILL_MS - AnomalyDetector.MIN_STILL_MS) * safety;
        return ms / 60000.0;
    }

    // 경로에서 40m 넘게 벗어났으면 그 경로를 다시 만들어 돌려준다.
    // force=1 이면 거리를 따지지 않고 바로 다시 만든다 (앱이 이미 이탈로 판정한 경우)
    private static void handleTrack(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String id = q.get("id");
        Double lat = num(q.get("lat")), lon = num(q.get("lon"));
        if (id == null || lat == null || lon == null) {
            error(ex, 422, "id, lat, lon 이 모두 필요합니다");
            return;
        }
        Nav nav = NAVS.get(id);
        if (nav == null) {
            error(ex, 404, "안내 중인 경로가 아닙니다 (id 없음 또는 만료)");
            return;
        }

        double[] r = onPath(nav.coords(), lat, lon);
        double dist = r[0], remain = r[1];

        if (!"1".equals(q.get("force")) && dist <= OFF_ROUTE_METERS) {
            send(ex, 200, "{\"off\":false,\"dist_m\":" + Json.num(dist, 1)
                    + ",\"remain_m\":" + Json.num(remain, 1) + "}");
            return;
        }

        // 벗어났다. 지금 위치를 출발지로 같은 종류의 경로를 다시 만든다
        Built nb;
        try {
            nb = buildOne(nav.kind(), lat, lon, nav.dest().lat(), nav.dest().lng());
        } catch (Exception e) {
            System.out.println("[track] 재탐색 실패: " + e.getMessage());
            send(ex, 200, "{\"off\":true,\"dist_m\":" + Json.num(dist, 1)
                    + ",\"remain_m\":" + Json.num(remain, 1) + "}");
            return;
        }

        String newId = putNav(nav.kind(), nb.coords(), nav.dest());
        StringBuilder b = new StringBuilder(1 << 15);
        b.append("{\"off\":true,\"dist_m\":").append(Json.num(dist, 1));
        b.append(",\"nav_id\":").append(Json.quote(newId));
        b.append(",\"remain_m\":").append(Json.num(nb.lengthM(), 1));
        b.append(",\"route\":");
        appendRoute(b, nb.coords(), nb.lengthM(), true, true, nb.guides(), newId);
        b.append('}');
        send(ex, 200, b.toString());
    }

    private record Built(List<LatLng> coords, double lengthM, List<Guide> guides) {}

    // 경로 한 종류만 다시 만든다. 안심 B 는 Tmap 을 쓰므로 실패하면 안심 A 로 대신한다
    private static Built buildOne(String kind, double sLat, double sLon,
                                  double eLat, double eLon) throws Exception {
        RoadGraph.Snap ss = graph.snapToRoad(sLat, sLon);
        RoadGraph.Snap st = graph.snapToRoad(eLat, eLon);
        if (ss == null || st == null) throw new Exception("도로망이 비어 있습니다");
        if (ss.distM > MAX_SNAP_METERS) throw new Exception("도로망 범위 밖입니다");
        if (ss.arc == st.arc && Math.abs(ss.frac - st.frac) * graph.length[ss.arc] < 5) {
            throw new Exception("출발지와 도착지가 너무 가깝습니다");
        }

        if ("safest2".equals(kind)) {
            LatLng origin = new LatLng(ss.lat, ss.lon), dest = new LatLng(st.lat, st.lon);
            final long deadline = System.nanoTime() + BUDGET_NANOS;
            SafeRoute.RouteProvider provider = wps -> {
                if (System.nanoTime() > deadline) throw new Exception("시간 예산 초과");
                return viaProxy(wps);
            };
            try {
                SafeRoute.PlanResult plan =
                        SafeRoute.planSafeRoute(origin, dest, safeIdx, provider, SAFE_OPT);
                List<LatLng> c = plan.best().path();
                return new Built(c, plan.best().score().distance(), PROXY_GUIDES.get().get(c));
            } catch (Exception e) {
                System.out.println("[track] 안심 B 재탐색 실패, 안심 A 로 대신함: " + e.getMessage());
                kind = "safest";
            }
        }

        double[] w = "shortest".equals(kind) ? graph.length : graph.safeCost;
        Dijkstra.Result d = Dijkstra.shortestPath(graph, ss, st, w);
        if (d == null) throw new Exception("두 지점이 도로망에서 이어지지 않습니다");
        return new Built(toCoords(graph.routeCoords(d)), d.lengthM, null);
    }

    private static void handleSearch(HttpExchange ex) throws IOException {
        if (KAKAO_KEY == null || KAKAO_KEY.isBlank()) {
            error(ex, 503, "장소 검색이 설정되지 않았습니다 (KAKAO_REST_KEY 없음)");
            return;
        }
        String q = query(ex.getRequestURI().getRawQuery()).getOrDefault("q", "").trim();
        if (q.isEmpty()) {
            error(ex, 422, "검색어(q)가 필요합니다");
            return;
        }

        try {
            String url = KAKAO_URL
                    + "?query=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                    + "&x=" + Json.num(SEARCH_LON, 6)
                    + "&y=" + Json.num(SEARCH_LAT, 6)
                    + "&radius=" + SEARCH_RADIUS
                    + "&sort=distance&size=15";

            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", "KakaoAK " + KAKAO_KEY)
                    .GET()
                    .build();
            HttpResponse<String> res =
                    HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (res.statusCode() != 200) {
                System.out.println("[search] 카카오 " + res.statusCode() + " — "
                        + res.body().substring(0, Math.min(200, res.body().length())));
                error(ex, 502, "장소 검색에 실패했습니다 (카카오 " + res.statusCode() + ")");
                return;
            }

            JsonArray docs = JsonParser.parseString(res.body())
                    .getAsJsonObject().getAsJsonArray("documents");

            StringBuilder b = new StringBuilder(1 << 12);
            b.append("{\"places\":[");
            int n = 0;
            for (JsonElement e : docs) {
                JsonObject d = e.getAsJsonObject();
                double lon = Double.parseDouble(d.get("x").getAsString());
                double lat = Double.parseDouble(d.get("y").getAsString());

                RoadGraph.Snap sn = graph.snapToRoad(lat, lon);
                boolean inRange = sn != null && sn.distM <= MAX_SNAP_METERS;

                String addr = str(d, "road_address_name");
                if (addr.isEmpty()) addr = str(d, "address_name");

                if (n++ > 0) b.append(',');
                b.append("{\"name\":").append(Json.quote(str(d, "place_name")));
                b.append(",\"address\":").append(Json.quote(addr));
                b.append(",\"category\":").append(Json.quote(str(d, "category_group_name")));
                b.append(",\"lat\":").append(Json.num(lat, 6));
                b.append(",\"lon\":").append(Json.num(lon, 6));
                b.append(",\"in_range\":").append(inRange);
                b.append('}');
            }
            b.append("]}");
            System.out.printf("[search] \"%s\" → %d건%n", q, n);
            send(ex, 200, b.toString());

        } catch (Exception e) {
            System.out.println("[search] 실패: " + e.getMessage());
            error(ex, 502, "장소 검색에 실패했습니다: " + e.getMessage());
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? "" : e.getAsString();
    }

    private static void handleHealth(HttpExchange ex) throws IOException {
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
        double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (int i = 0; i < graph.nodeCount; i++) {
            minLat = Math.min(minLat, graph.lat[i]); maxLat = Math.max(maxLat, graph.lat[i]);
            minLon = Math.min(minLon, graph.lon[i]); maxLon = Math.max(maxLon, graph.lon[i]);
        }

        StringBuilder b = new StringBuilder();
        b.append("{\"status\":\"ok\"");
        b.append(",\"nodes\":").append(graph.nodeCount);
        b.append(",\"arcs\":").append(graph.arcCount);
        b.append(",\"saferoute_facilities\":").append(safeIdx.facilities.size());
        b.append(",\"route_proxy\":").append(Json.quote(PROXY));
        b.append(",\"facility_layers\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : facilities.layerSizes().entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append(Json.quote(e.getKey())).append(':').append(e.getValue());
        }
        b.append("},\"bounds\":{");
        b.append("\"min_lat\":").append(Json.num(minLat, 6));
        b.append(",\"max_lat\":").append(Json.num(maxLat, 6));
        b.append(",\"min_lon\":").append(Json.num(minLon, 6));
        b.append(",\"max_lon\":").append(Json.num(maxLon, 6));
        b.append("}}");
        send(ex, 200, b.toString());
    }

    private static void handleRoute(HttpExchange ex) throws IOException {
        long t0 = System.nanoTime();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());

        Double sLat = num(q.get("start_lat")), sLon = num(q.get("start_lon"));
        Double eLat = num(q.get("end_lat")),   eLon = num(q.get("end_lon"));
        if (sLat == null || sLon == null || eLat == null || eLon == null) {
            error(ex, 422, "start_lat, start_lon, end_lat, end_lon 이 모두 필요합니다");
            return;
        }
        boolean withFacilities = !"false".equalsIgnoreCase(q.getOrDefault("facilities", "true"));

        RoadGraph.Snap ss = graph.snapToRoad(sLat, sLon);
        RoadGraph.Snap st = graph.snapToRoad(eLat, eLon);
        if (ss == null || st == null) {
            error(ex, 500, "도로망이 비어 있습니다");
            return;
        }
        if (ss.distM > MAX_SNAP_METERS || st.distM > MAX_SNAP_METERS) {
            error(ex, 400, String.format("도로망 범위 밖입니다 (출발 %.0fm, 도착 %.0fm)",
                    ss.distM, st.distM));
            return;
        }
        if (ss.arc == st.arc && Math.abs(ss.frac - st.frac) * graph.length[ss.arc] < 5) {
            error(ex, 400, "출발지와 도착지가 너무 가깝습니다");
            return;
        }

        Dijkstra.Result shortest = Dijkstra.shortestPath(graph, ss, st, graph.length);
        Dijkstra.Result safest   = Dijkstra.shortestPath(graph, ss, st, graph.safeCost);
        if (shortest == null || safest == null) {
            error(ex, 404, "두 지점이 도로망에서 이어지지 않습니다");
            return;
        }

        PROXY_GUIDES.get().clear();

        LatLng origin = new LatLng(sLat, sLon), dest = new LatLng(eLat, eLon);
        List<LatLng> coords2 = null;
        List<Guide> guides2 = null;
        double length2 = 0;
        String source2 = null, label2 = null;

        // Tmap 을 먼저 쓰고, 안 되면 우리 도로망으로 대신 계산한다
        for (String which : new String[]{"tmap", "graph"}) {
            final long deadline = System.nanoTime() + BUDGET_NANOS;
            final boolean proxy = "tmap".equals(which);
            SafeRoute.RouteProvider provider = wps -> {
                // 한 요청이 하염없이 늘어지지 않게 예산을 둔다
            if (System.nanoTime() > deadline) throw new Exception("시간 예산 초과 — 후보 건너뜀");
                return proxy ? viaProxy(wps) : viaGraph(wps);
            };
            try {
                SafeRoute.PlanResult plan =
                        SafeRoute.planSafeRoute(origin, dest, safeIdx, provider, SAFE_OPT);
                for (SafeRoute.RouteResult r : plan.all()) {
                    SafeRoute.Score s = r.score();
                    System.out.printf("[saferoute:%s] %-12s %5.0fm  평균안전도 %.2f  취약 %.2f  total %6.0f%s%n",
                            which, r.label(), s.distance(), s.meanSafety(), s.weakRatio(), s.total(),
                            r == plan.best() ? "  <= 채택" : "");
                }
                coords2 = plan.best().path();
                guides2 = PROXY_GUIDES.get().get(coords2);
                length2 = plan.best().score().distance();
                source2 = which;
                label2  = plan.best().label();
                break;
            } catch (Exception e) {
                System.out.println("[saferoute:" + which + "] 실패: " + e.getMessage());
            }
        }

        boolean same = shortest.sameRoute(safest);

        List<LatLng> cShort = toCoords(graph.routeCoords(shortest));
        List<LatLng> cSafe  = toCoords(graph.routeCoords(safest));

        StringBuilder b = new StringBuilder(1 << 16);
        b.append("{\"shortest\":");
        appendRoute(b, cShort, shortest.lengthM, withFacilities, true, null,
                putNav("shortest", cShort, dest));
        b.append(",\"safest\":");
        appendRoute(b, cSafe, safest.lengthM, withFacilities, true, null,
                putNav("safest", cSafe, dest));
        b.append(",\"safest2\":");
        if (coords2 == null) {
            appendRoute(b, cSafe, safest.lengthM, withFacilities, true, null,
                    putNav("safest", cSafe, dest));
            source2 = "none";
            label2  = "안심 A 와 동일 (경로를 받지 못함)";
            length2 = safest.lengthM;
        } else {
            appendRoute(b, coords2, length2, withFacilities, true, guides2,
                    putNav("safest2", coords2, dest));
        }
        b.append(",\"same\":").append(same);
        // 탭한 지점을 도로 위로 옮긴 결과. 앱이 출발·도착 마커를 여기로 옮기면
        // 마커와 경로선이 떨어져 보이지 않는다.
        b.append(",\"snap\":{\"start\":[").append(Json.num(ss.lat, 6)).append(',')
                .append(Json.num(ss.lon, 6)).append("],\"end\":[")
                .append(Json.num(st.lat, 6)).append(',').append(Json.num(st.lon, 6))
                .append("],\"start_off_m\":").append(Json.num(ss.distM, 1))
                .append(",\"end_off_m\":").append(Json.num(st.distM, 1)).append('}');
        b.append(",\"safest2_source\":").append(Json.quote(source2));
        b.append(",\"safest2_label\":").append(Json.quote(label2));
        b.append(",\"extra_m\":").append(Json.num(safest.lengthM - shortest.lengthM, 1));
        b.append(",\"extra2_m\":").append(Json.num(length2 - shortest.lengthM, 1));
        b.append(",\"elapsed_ms\":").append(Json.num((System.nanoTime() - t0) / 1e6, 1));
        b.append('}');
        send(ex, 200, b.toString());
    }

    private static List<LatLng> toCoords(List<double[]> raw) {
        List<LatLng> out = new ArrayList<>(raw.size());
        for (double[] c : raw) out.add(new LatLng(c[0], c[1]));
        return out;
    }

    private static void appendRoute(StringBuilder b, List<LatLng> coords, double lengthM,
                                    boolean withFacilities) {
        appendRoute(b, coords, lengthM, withFacilities, false, null);
    }

    private static void appendRoute(StringBuilder b, List<LatLng> coords, double lengthM,
                                    boolean withFacilities, boolean withTurns,
                                    List<Guide> guides) {
        appendRoute(b, coords, lengthM, withFacilities, withTurns, guides, null);
    }

    private static void appendRoute(StringBuilder b, List<LatLng> coords, double lengthM,
                                    boolean withFacilities, boolean withTurns,
                                    List<Guide> guides, String navId) {
        List<double[]> raw = new ArrayList<>(coords.size());
        for (LatLng p : coords) raw.add(new double[]{p.lat(), p.lng()});
        Map<String, List<Facilities.Hit>> fac =
                withFacilities ? facilities.nearPolyline(raw) : new HashMap<>();

        b.append("{\"length_m\":").append(Json.num(lengthM, 1));
        if (navId != null) b.append(",\"nav_id\":").append(Json.quote(navId));
        b.append(",\"cctv\":").append(count(fac, "cctv"));
        b.append(",\"light\":").append(count(fac, "light"));
        b.append(",\"bell\":").append(count(fac, "bell"));
        b.append(",\"police\":").append(count(fac, "police"));

        b.append(",\"coords\":[");
        for (int i = 0; i < coords.size(); i++) {
            if (i > 0) b.append(',');
            b.append('[').append(Json.num(coords.get(i).lat(), 6)).append(',')
             .append(Json.num(coords.get(i).lng(), 6)).append(']');
        }
        b.append(']');

        b.append(",\"facilities\":{");
        boolean firstLayer = true;
        for (Map.Entry<String, List<Facilities.Hit>> e : fac.entrySet()) {
            if (!firstLayer) b.append(',');
            firstLayer = false;
            b.append(Json.quote(e.getKey())).append(":[");
            List<Facilities.Hit> hits = e.getValue();
            for (int i = 0; i < hits.size(); i++) {
                if (i > 0) b.append(',');
                Facilities.Hit h = hits.get(i);
                b.append('[').append(Json.num(h.lat, 6)).append(',')
                 .append(Json.num(h.lon, 6)).append(',').append(h.cnt).append(']');
            }
            b.append(']');
        }
        b.append('}');

        // 좌·우회전 안내. 팀장이 준 TurnGuide 를 그대로 쓴다
        b.append(",\"turns\":[");
        if (withTurns) {
            List<TurnGuide.Turn> turns = new ArrayList<>();

            // Tmap 이 지명까지 담긴 안내를 보내 줬으면 그걸 쓰고, 없으면 좌표에서 직접 뽑는다
            if (guides != null) {
                for (Guide gd : guides) {
                    if (!gd.text().contains("회전") && !gd.text().contains("유턴")) continue;
                    // 좌표가 한국 밖이면 null 이 온다
                    TurnGuide.Turn t = TurnGuide.fromTmap(gd.lon(), gd.lat(), gd.text());
                    if (t != null) turns.add(t);
                }
            }
            if (turns.isEmpty()) {
                turns = TurnGuide.makeTurns(coords);
            }

            for (int i = 0; i < turns.size(); i++) {
                if (i > 0) b.append(',');
                TurnGuide.Turn t = turns.get(i);
                b.append("{\"lat\":").append(Json.num(t.lat, 6))
                 .append(",\"lon\":").append(Json.num(t.lon, 6))
                 .append(",\"text\":").append(Json.quote(t.text)).append('}');
            }
        }
        b.append("]}");
    }

    private static int count(Map<String, List<Facilities.Hit>> fac, String key) {
        List<Facilities.Hit> v = fac.get(key);
        if (v == null) return 0;
        int n = 0;
        for (Facilities.Hit h : v) n += h.cnt;
        return n;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            try {
                m.put(URLDecoder.decode(pair.substring(0, i), "UTF-8"),
                      URLDecoder.decode(pair.substring(i + 1), "UTF-8"));
            } catch (IOException ignored) { }
        }
        return m;
    }

    private static Double num(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Double.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static void error(HttpExchange ex, int code, String detail) throws IOException {
        send(ex, code, "{\"detail\":" + Json.quote(detail) + "}");
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
