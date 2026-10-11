"""
경로 주변 안전시설 조회.

앱 assets 와 같은 GeoJSON 을 읽어, 경로 꺾은선에서 MATCH_RADIUS 미터 안에 있는
시설을 찾아 돌려준다. 그래프를 만들 때와 같은 방식(점-선분 최단거리)이다.

주의 — 그래프의 cctv/light 값은 간선마다 따로 센 것이라, 경로 전체로 합하면
인접 간선에 걸친 시설이 여러 번 세어진다. 여기서는 중복을 제거한 고유 개수를
돌려주므로 지도에 표시되는 시설 수와 카드의 숫자가 일치한다.
"""

import json
import math
import os
from typing import Dict, List, Tuple

MATCH_RADIUS = 50.0          # 그래프 생성 시 사용한 값과 동일

LAYER_FILES = {
    "cctv":   "cctv.geojson",
    "light":  "light.geojson",
    "bell":   "bell.geojson",
    "police": "police.geojson",
}

_LAT0 = 35.14
_M_LAT = 111320.0
_M_LON = 111320.0 * math.cos(math.radians(_LAT0))
_CELL = 100.0                # 격자 한 칸 크기(m). 반경 50m 조회에 맞춘 값


def _to_meters(lat: float, lon: float) -> Tuple[float, float]:
    return ((lon - 126.927) * _M_LON, (lat - _LAT0) * _M_LAT)


class Facilities:
    def __init__(self, data_dir: str):
        self.layers: Dict[str, List[Tuple[float, float, int]]] = {}
        self._xy: Dict[str, List[Tuple[float, float]]] = {}
        self._grid: Dict[str, Dict[Tuple[int, int], List[int]]] = {}

        for key, fname in LAYER_FILES.items():
            path = os.path.join(data_dir, fname)
            if not os.path.exists(path):
                print(f"[facilities] {fname} 없음 — {key} 건너뜀")
                continue
            pts = self._load_geojson(path)
            self.layers[key] = pts
            xy = [_to_meters(la, lo) for la, lo, _ in pts]
            self._xy[key] = xy

            # 격자 색인 — 전체를 훑지 않고 주변 칸만 보기 위한 것
            grid: Dict[Tuple[int, int], List[int]] = {}
            for i, (x, y) in enumerate(xy):
                grid.setdefault((int(x // _CELL), int(y // _CELL)), []).append(i)
            self._grid[key] = grid

            print(f"[facilities] {key} {len(pts):,}지점")

    @staticmethod
    def _load_geojson(path: str) -> List[Tuple[float, float, int]]:
        with open(path, encoding="utf-8") as f:
            root = json.load(f)
        out: List[Tuple[float, float, int]] = []
        for ft in root.get("features", []):
            geom = ft.get("geometry") or {}
            if geom.get("type") != "Point":
                continue
            c = geom.get("coordinates")
            if not c or len(c) < 2:
                continue
            props = ft.get("properties") or {}
            cnt = 1
            for k in ("cnt", "count", "카메라대수", "설치개수"):
                if k in props:
                    try:
                        cnt = int(float(props[k]))
                    except (TypeError, ValueError):
                        cnt = 1
                    break
            out.append((float(c[1]), float(c[0]), cnt))   # GeoJSON 은 [경도, 위도]
        return out

    def near_polyline(self, poly: List[List[float]],
                      radius: float = MATCH_RADIUS) -> Dict[str, List[List[float]]]:
        """
        꺾은선 주변 시설을 종류별로 중복 없이 돌려준다.
        반환: {"cctv": [[lat, lon, cnt], ...], ...}
        """
        if len(poly) < 2:
            return {k: [] for k in self.layers}

        # 선분 목록과, 조회 대상이 될 격자 칸 목록
        segs = []
        cells = set()
        span = int(radius // _CELL) + 1

        for (la1, lo1), (la2, lo2) in zip(poly[:-1], poly[1:]):
            ax, ay = _to_meters(la1, lo1)
            bx, by = _to_meters(la2, lo2)
            abx, aby = bx - ax, by - ay
            segs.append((ax, ay, abx, aby, abx * abx + aby * aby))

            # 선분을 따라가며 지나는 칸과 그 주변을 모은다
            steps = max(1, int(math.hypot(abx, aby) // (_CELL / 2)) + 1)
            for s in range(steps + 1):
                t = s / steps
                cx = int((ax + abx * t) // _CELL)
                cy = int((ay + aby * t) // _CELL)
                for dx in range(-span, span + 1):
                    for dy in range(-span, span + 1):
                        cells.add((cx + dx, cy + dy))

        r2 = radius * radius
        result: Dict[str, List[List[float]]] = {}

        for key, pts in self.layers.items():
            xy = self._xy[key]
            grid = self._grid[key]

            candidates = set()
            for c in cells:
                idxs = grid.get(c)
                if idxs:
                    candidates.update(idxs)

            found: List[List[float]] = []
            for i in candidates:
                px, py = xy[i]
                for ax, ay, abx, aby, l2 in segs:
                    dx, dy = px - ax, py - ay
                    if l2 == 0.0:
                        d2 = dx * dx + dy * dy
                    else:
                        t = (dx * abx + dy * aby) / l2
                        t = 0.0 if t < 0.0 else (1.0 if t > 1.0 else t)
                        ex, ey = dx - t * abx, dy - t * aby
                        d2 = ex * ex + ey * ey
                    if d2 <= r2:
                        la, lo, cnt = pts[i]
                        found.append([la, lo, cnt])
                        break
            result[key] = found

        return result
