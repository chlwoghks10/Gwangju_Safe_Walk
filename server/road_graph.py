"""
보행 도로망 그래프.

조원이 작성한 RoadGraph.java / Dijkstra.java 를 파이썬으로 옮긴 것으로,
같은 CSV(graph_nodes.csv, graph_edges.csv)를 읽고 같은 결과를 낸다.
"""

import csv
import heapq
import math
from dataclasses import dataclass
from typing import List, Optional, Tuple


@dataclass
class RouteResult:
    """탐색 결과"""
    arcs: List[int]
    cost: float          # 사용한 가중치 기준 총 비용
    length_m: float      # 총 거리(m)
    cctv: int            # 경로 구간들의 CCTV 합
    light: int           # 경로 구간들의 보안등 합


class RoadGraph:
    def __init__(self, nodes_csv: str, edges_csv: str):
        # ── 노드 ──
        id_to_index = {}
        self.lat: List[float] = []
        self.lon: List[float] = []

        with open(nodes_csv, encoding="utf-8-sig", newline="") as f:
            for row in csv.DictReader(f):
                id_to_index[int(row["id"])] = len(self.lat)
                self.lat.append(float(row["lat"]))
                self.lon.append(float(row["lon"]))

        self.node_count = len(self.lat)

        # ── 간선 ──
        self.frm: List[int] = []
        self.to: List[int] = []
        self.length: List[float] = []
        self.safe_cost: List[float] = []
        self.cctv: List[int] = []
        self.light: List[int] = []
        self.geom: List[Optional[List[Tuple[float, float]]]] = []

        with open(edges_csv, encoding="utf-8-sig", newline="") as f:
            for row in csv.DictReader(f):
                u = id_to_index.get(int(row["u"]))
                v = id_to_index.get(int(row["v"]))
                if u is None or v is None:
                    continue
                self.frm.append(u)
                self.to.append(v)
                self.length.append(float(row["length"]))
                self.safe_cost.append(float(row["safe_cost"]))
                self.cctv.append(int(round(float(row["cctv"]))))
                self.light.append(int(round(float(row["light"]))))
                self.geom.append(self._parse_geom(row.get("geom")))

        self.arc_count = len(self.frm)

        # ── 인접 리스트 ──
        self.adj: List[List[int]] = [[] for _ in range(self.node_count)]
        for a in range(self.arc_count):
            self.adj[self.frm[a]].append(a)

    # ───────────────────────── 보조 ─────────────────────────

    @staticmethod
    def _parse_geom(s: Optional[str]):
        """'lat lon|lat lon|...' → [(lat, lon), ...]"""
        if not s:
            return None
        s = s.strip()
        if not s:
            return None
        pts = []
        for part in s.split("|"):
            ll = part.split()
            if len(ll) >= 2:
                pts.append((float(ll[0]), float(ll[1])))
        return pts or None

    @staticmethod
    def distance_meters(la1: float, lo1: float, la2: float, lo2: float) -> float:
        """근거리용 등장방형 근사 거리(m). 자바 구현과 동일."""
        d_lat = math.radians(la2 - la1)
        d_lon = math.radians(lo2 - lo1) * math.cos(math.radians((la1 + la2) / 2))
        return 6371000.0 * math.sqrt(d_lat * d_lat + d_lon * d_lon)

    def nearest_node(self, la: float, lo: float) -> int:
        """좌표에서 가장 가까운 노드 인덱스"""
        best, best_d = -1, float("inf")
        for i in range(self.node_count):
            d = self.distance_meters(la, lo, self.lat[i], self.lon[i])
            if d < best_d:
                best, best_d = i, d
        return best

    def distance_to_node(self, node: int, la: float, lo: float) -> float:
        return self.distance_meters(la, lo, self.lat[node], self.lon[node])

    # ───────────────────────── 탐색 ─────────────────────────

    def shortest_path(self, src: int, dst: int, weight: List[float]) -> Optional[RouteResult]:
        """다익스트라. weight 는 간선 id 별 비용(모두 0 이상)."""
        INF = float("inf")
        dist = [INF] * self.node_count
        prev_arc = [-1] * self.node_count
        dist[src] = 0.0

        pq = [(0.0, src)]
        while pq:
            d, u = heapq.heappop(pq)
            if d > dist[u]:
                continue
            if u == dst:
                break
            for a in self.adj[u]:
                v = self.to[a]
                nd = d + weight[a]
                if nd < dist[v]:
                    dist[v] = nd
                    prev_arc[v] = a
                    heapq.heappush(pq, (nd, v))

        if dist[dst] == INF:
            return None

        arcs = []
        n = dst
        while n != src:
            a = prev_arc[n]
            arcs.append(a)
            n = self.frm[a]
        arcs.reverse()

        return RouteResult(
            arcs=arcs,
            cost=dist[dst],
            length_m=sum(self.length[a] for a in arcs),
            cctv=sum(self.cctv[a] for a in arcs),
            light=sum(self.light[a] for a in arcs),
        )

    # ───────────────────────── 좌표 변환 ─────────────────────────

    def coords_of(self, arcs: List[int]) -> List[List[float]]:
        """간선 경로를 지도에 그릴 [lat, lon] 목록으로. 연속 중복점 제거."""
        out: List[List[float]] = []

        def add(la: float, lo: float):
            if out and out[-1][0] == la and out[-1][1] == lo:
                return
            out.append([la, lo])

        for a in arcs:
            g = self.geom[a]
            if not g or len(g) < 2:
                add(self.lat[self.frm[a]], self.lon[self.frm[a]])
                add(self.lat[self.to[a]], self.lon[self.to[a]])
            else:
                for la, lo in g:
                    add(la, lo)
        return out
