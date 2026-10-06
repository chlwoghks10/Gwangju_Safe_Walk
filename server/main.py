"""
광주 안심길 — 경로 비교 API 서버

실행
    uvicorn main:app --host 0.0.0.0 --port 8000
확인
    http://<서버주소>:8000/docs
"""

import os
import time
from typing import Dict, List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from road_graph import RoadGraph
from facilities import Facilities

DATA_DIR = os.environ.get("GRAPH_DIR", "data")
NODES_CSV = os.path.join(DATA_DIR, "graph_nodes.csv")
EDGES_CSV = os.path.join(DATA_DIR, "graph_edges.csv")

MAX_SNAP_METERS = 300.0

app = FastAPI(title="광주 안심길 경로 API", version="1.1")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

_t0 = time.time()
GRAPH = RoadGraph(NODES_CSV, EDGES_CSV)
print(f"[graph] 노드 {GRAPH.node_count:,} / 간선 {GRAPH.arc_count:,}  ({(time.time()-_t0)*1000:.0f}ms)")
FACILITIES = Facilities(DATA_DIR)


class RouteInfo(BaseModel):
    length_m: float = Field(..., description="총 거리(m)")
    cctv: int = Field(..., description="경로 주변 CCTV 지점 수 (중복 제거)")
    light: int = Field(..., description="경로 주변 보안등 지점 수 (중복 제거)")
    bell: int = Field(0, description="경로 주변 안전비상벨 수")
    police: int = Field(0, description="경로 주변 경찰서·지구대 수")
    coords: List[List[float]] = Field(..., description="[[위도, 경도], ...]")
    facilities: Dict[str, List[List[float]]] = Field(
        default_factory=dict,
        description="종류별 시설 좌표 [[위도, 경도, 개수], ...]")


class RouteResponse(BaseModel):
    shortest: RouteInfo
    safest: RouteInfo
    same: bool
    extra_m: float
    elapsed_ms: float


class HealthResponse(BaseModel):
    status: str
    nodes: int
    arcs: int
    facility_layers: Dict[str, int]
    bounds: dict


@app.get("/health", response_model=HealthResponse)
def health():
    return HealthResponse(
        status="ok",
        nodes=GRAPH.node_count,
        arcs=GRAPH.arc_count,
        facility_layers={k: len(v) for k, v in FACILITIES.layers.items()},
        bounds={
            "min_lat": min(GRAPH.lat), "max_lat": max(GRAPH.lat),
            "min_lon": min(GRAPH.lon), "max_lon": max(GRAPH.lon),
        },
    )


def _build(result, with_facilities: bool) -> RouteInfo:
    coords = GRAPH.coords_of(result.arcs)
    fac = FACILITIES.near_polyline(coords) if with_facilities else {}
    return RouteInfo(
        length_m=round(result.length_m, 1),
        cctv=len(fac.get("cctv", [])),
        light=len(fac.get("light", [])),
        bell=len(fac.get("bell", [])),
        police=len(fac.get("police", [])),
        coords=coords,
        facilities=fac,
    )


@app.get("/route", response_model=RouteResponse)
def route(
    start_lat: float,
    start_lon: float,
    end_lat: float,
    end_lon: float,
    hour: Optional[int] = None,
    facilities: bool = True,       # 시설 좌표를 함께 받을지
):
    """출발지와 도착지를 받아 최단 경로와 안전 우선 경로를 함께 돌려준다."""
    t0 = time.time()

    src = GRAPH.nearest_node(start_lat, start_lon)
    dst = GRAPH.nearest_node(end_lat, end_lon)

    d_src = GRAPH.distance_to_node(src, start_lat, start_lon)
    d_dst = GRAPH.distance_to_node(dst, end_lat, end_lon)
    if d_src > MAX_SNAP_METERS or d_dst > MAX_SNAP_METERS:
        raise HTTPException(
            status_code=400,
            detail=f"도로망 범위 밖입니다 (출발 {d_src:.0f}m, 도착 {d_dst:.0f}m)")

    if src == dst:
        raise HTTPException(status_code=400, detail="출발지와 도착지가 너무 가깝습니다")

    shortest = GRAPH.shortest_path(src, dst, GRAPH.length)
    safest = GRAPH.shortest_path(src, dst, GRAPH.safe_cost)
    if shortest is None or safest is None:
        raise HTTPException(status_code=404, detail="두 지점이 도로망에서 이어지지 않습니다")

    return RouteResponse(
        shortest=_build(shortest, facilities),
        safest=_build(safest, facilities),
        same=shortest.arcs == safest.arcs,
        extra_m=round(safest.length_m - shortest.length_m, 1),
        elapsed_ms=round((time.time() - t0) * 1000, 1),
    )
