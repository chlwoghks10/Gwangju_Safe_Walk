
import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * 다익스트라 최단경로 (우선순위 큐 사용, O((V+E) log V)).
 * 같은 그래프에 weight 배열만 바꿔서 호출하면 최단경로 / 안전경로를 각각 구할 수 있다.
 *
 * 출발·도착은 노드가 아니라 "도로 구간 위의 점(Snap)"으로 받는다.
 * 출발점이 놓인 구간의 양 끝 노드를 동시에 시작점으로 넣고(각각 구간의 일부 비용을 안고 출발),
 * 도착점이 놓인 구간의 양 끝 노드에 닿으면 남은 구간 비용을 더해 도착한다.
 */
public final class Dijkstra {

    private Dijkstra() {}

    /** 탐색 결과: 지나간 간선 id 목록과 구간 합계 */
    public static final class Result {
        public final int[] arcs;          // 전체를 지난 구간들 (출발·도착 쪽의 일부 구간은 제외)
        public final double cost;         // 사용한 weight 기준 총 비용
        public final double lengthM;      // 총 거리(m)
        public final int cctv;            // 구간 합계 CCTV (중복 포함, 참고용)
        public final int light;           // 구간 합계 보안등 (중복 포함, 참고용)

        public final RoadGraph.Snap from, to;   // 스냅 방식일 때 출발·도착 지점 (노드 방식이면 null)
        public final int startNode, endNode;    // arcs 의 첫 노드·끝 노드
        public final boolean direct;            // 출발·도착이 같은 구간 위라 구간 일부만 걷는 경우
        public final boolean startViaFrom;      // 출발 지점에서 그 구간의 시작 노드 쪽으로 나가는가 (아니면 끝 노드 쪽)
        public final boolean endViaFrom;        // 도착 구간의 시작 노드 쪽으로 들어오는가 (아니면 끝 노드 쪽)

        Result(RoadGraph g, int[] arcs, double cost) {
            this(g, arcs, cost, null, null, -1, -1, false, true, true, 0);
        }

        Result(RoadGraph g, int[] arcs, double cost, RoadGraph.Snap from, RoadGraph.Snap to,
               int startNode, int endNode, boolean direct, boolean startViaFrom, boolean endViaFrom,
               double extraLen) {
            this.arcs = arcs;
            this.cost = cost;
            this.from = from;
            this.to = to;
            this.startNode = startNode;
            this.endNode = endNode;
            this.direct = direct;
            this.startViaFrom = startViaFrom;
            this.endViaFrom = endViaFrom;
            double len = extraLen;
            double c = 0, l = 0;
            for (int a : arcs) {
                len += g.length[a];
                c += g.cctv[a];
                l += g.light[a];
            }
            this.lengthM = len;
            this.cctv = (int) Math.round(c);
            this.light = (int) Math.round(l);
        }

        /** 다른 결과와 같은 경로인지 */
        public boolean sameRoute(Result o) {
            return direct == o.direct && startNode == o.startNode && endNode == o.endNode
                    && startViaFrom == o.startViaFrom && endViaFrom == o.endViaFrom
                    && Arrays.equals(arcs, o.arcs);
        }
    }

    private static final class Entry implements Comparable<Entry> {
        final int node;
        final double dist;
        Entry(int node, double dist) { this.node = node; this.dist = dist; }
        @Override public int compareTo(Entry o) { return Double.compare(dist, o.dist); }
    }

    /**
     * 노드에서 노드까지 (기본형).
     * @param weight 간선 id 별 비용 (모두 0 이상이어야 함)
     * @return 경로 결과, 도달 불가능하면 null
     */
    public static Result shortestPath(RoadGraph g, int src, int dst, double[] weight) {
        double[] dist = new double[g.nodeCount];
        int[] prevArc = new int[g.nodeCount];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prevArc, -1);

        PriorityQueue<Entry> pq = new PriorityQueue<>();
        dist[src] = 0;
        pq.add(new Entry(src, 0));

        while (!pq.isEmpty()) {
            Entry cur = pq.poll();
            if (cur.dist > dist[cur.node]) continue;   // 이미 더 짧은 경로로 확정된 낡은 항목
            if (cur.node == dst) break;                // 목적지 확정 → 조기 종료
            for (int i = g.outStart(cur.node); i < g.outEnd(cur.node); i++) {
                int a = g.adjArcAt(i);
                int next = g.to[a];
                double nd = cur.dist + weight[a];
                if (nd < dist[next]) {
                    dist[next] = nd;
                    prevArc[next] = a;
                    pq.add(new Entry(next, nd));
                }
            }
        }
        if (Double.isInfinite(dist[dst])) return null;

        int[] arcs = backtrack(g, prevArc, dst);
        return new Result(g, arcs, dist[dst]);
    }

    /**
     * 도로 구간 위의 점에서 점까지 (출발·도착 지점 보정 방식).
     * 구간 일부의 비용은 구간 전체 비용을 걷는 비율만큼으로 계산한다.
     * (양방향 구간의 비용이 같다고 본다 — 내보낸 CSV는 양방향 값이 같다.)
     */
    public static Result shortestPath(RoadGraph g, RoadGraph.Snap s, RoadGraph.Snap t, double[] weight) {
        double directCost = Double.POSITIVE_INFINITY;
        if (s.arc == t.arc) directCost = weight[s.arc] * Math.abs(s.frac - t.frac);

        int su = g.from[s.arc], sv = g.to[s.arc];
        double cu = weight[s.arc] * s.frac, cv = weight[s.arc] * (1 - s.frac);
        int tu = g.from[t.arc], tv = g.to[t.arc];
        double eu = weight[t.arc] * t.frac, ev = weight[t.arc] * (1 - t.frac);

        double[] dist = new double[g.nodeCount];
        int[] prevArc = new int[g.nodeCount];
        Arrays.fill(dist, Double.POSITIVE_INFINITY);
        Arrays.fill(prevArc, -1);

        PriorityQueue<Entry> pq = new PriorityQueue<>();
        dist[su] = cu;
        pq.add(new Entry(su, cu));
        if (cv < dist[sv]) {
            dist[sv] = cv;
            pq.add(new Entry(sv, cv));
        }

        double best = Double.POSITIVE_INFINITY;
        int bestEnd = -1;
        while (!pq.isEmpty()) {
            Entry cur = pq.poll();
            if (cur.dist > dist[cur.node]) continue;
            if (cur.dist >= best) break;               // 남은 후보는 모두 더 비싸다
            if (cur.node == tu && cur.dist + eu < best) { best = cur.dist + eu; bestEnd = tu; }
            if (cur.node == tv && cur.dist + ev < best) { best = cur.dist + ev; bestEnd = tv; }
            for (int i = g.outStart(cur.node); i < g.outEnd(cur.node); i++) {
                int a = g.adjArcAt(i);
                int next = g.to[a];
                double nd = cur.dist + weight[a];
                if (nd < dist[next]) {
                    dist[next] = nd;
                    prevArc[next] = a;
                    pq.add(new Entry(next, nd));
                }
            }
        }

        // 같은 구간 위라면 구간을 따라 바로 걷는 경우와 비교
        if (directCost <= best && !Double.isInfinite(directCost)) {
            double part = Math.abs(s.frac - t.frac);
            return new Result(g, new int[0], directCost, s, t, -1, -1, true, true, true, g.length[s.arc] * part);
        }
        if (bestEnd < 0) return null;

        int[] arcs = backtrack(g, prevArc, bestEnd);
        int startNode = (arcs.length == 0) ? bestEnd : g.from[arcs[0]];
        // 구간의 양 끝이 같은 노드(고리 모양 구간)면 비용이 더 작은 쪽으로 걷는다
        boolean startViaFrom = (su == sv) ? (cu <= cv) : (startNode == su);
        boolean endViaFrom = (tu == tv) ? (eu <= ev) : (bestEnd == tu);
        double startPart = startViaFrom ? s.frac : 1 - s.frac;
        double endPart = endViaFrom ? t.frac : 1 - t.frac;
        double extraLen = g.length[s.arc] * startPart + g.length[t.arc] * endPart;
        return new Result(g, arcs, best, s, t, startNode, bestEnd, false, startViaFrom, endViaFrom, extraLen);
    }

    /** 도착 노드에서 거슬러 올라가며 간선 복원 (출발 노드는 prevArc 가 -1 인 노드) */
    private static int[] backtrack(RoadGraph g, int[] prevArc, int end) {
        int count = 0;
        for (int n = end; prevArc[n] != -1; n = g.from[prevArc[n]]) count++;
        int[] arcs = new int[count];
        int n = end;
        for (int i = count - 1; i >= 0; i--) {
            arcs[i] = prevArc[n];
            n = g.from[prevArc[n]];
        }
        return arcs;
    }
}
