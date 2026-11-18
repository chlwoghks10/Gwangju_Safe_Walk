# 경로 비교 API 서버 (Java)

앱이 실제로 붙는 서버입니다. 외부 라이브러리는 Gson 하나뿐이고, 나머지는 JDK 표준 `com.sun.net.httpserver` 로 구현했습니다.

`../server/` 의 FastAPI 는 설계를 검증한 프로토타입이며, 현재 운영 중인 것은 이쪽입니다.

---

## 구성

서버는 두 개의 프로세스로 나뉩니다.

| 클래스 | 주소 | 역할 | 필요한 키 |
|---|---|---|---|
| `Server` | `0.0.0.0:8000` | 앱이 붙는 본체 | `KAKAO_REST_KEY` |
| `RouteServer` | `127.0.0.1:3000` | Tmap 보행자 경로 중계 | `TMAP_APP_KEY` |

```
 앱 ──▶ Server :8000 ──▶ RouteServer :3000 ──▶ Tmap
                  │
                  ├─ Dijkstra (OSM 보행 도로망)
                  ├─ Facilities (반경 50m 시설 집계)
                  └─ AnomalyDetector (정지·신호 두절 판정)
```

`RouteServer` 는 외부에 공개하지 않고 루프백에만 바인딩합니다.
`RouteServer` 가 떠 있지 않으면 최단 경로만 실패하고, 안심 A·B 는 내장 다익스트라로 계산되므로 그대로 동작합니다.

### 소스

| 파일 | 내용 |
|---|---|
| `Server.java` | HTTP 엔드포인트, 세션 관리, 장소 검색 |
| `RouteServer.java` | Tmap 중계 (팀장 작성, 원본 유지) |
| `SafeRoute.java` | 경로 3종 산출과 비용 함수 |
| `Dijkstra.java` | 최단 경로 탐색 |
| `RoadGraph.java` | CSV 로 만든 보행 도로망 |
| `Facilities.java` | 경로 주변 시설 조회 (격자 색인) |
| `TurnGuide.java` | 회전 지점 추출과 안내 문구 |
| `AnomalyDetector.java` | 이상 상황 판정 (팀장 작성) |
| `Json.java` `LatLng.java` | 보조 |

---

## 엔드포인트

| 경로 | 설명 |
|---|---|
| `GET /health` | 상태 확인 |
| `GET /route` | 출발·도착을 받아 최단 / 안심 A / 안심 B 와 시설을 반환 |
| `GET /track` | 안내 중 이탈 시 따라가던 경로 하나만 재탐색 (`force=1`) |
| `GET /watch` | 5초마다 좌표를 받아 이상 여부 판정 |
| `GET /watch/start` `GET /watch/ok` `GET /watch/stop` | 감시 세션 시작·확인·종료 |
| `GET /search` | 카카오 로컬 장소 검색 |

---

## 빌드와 실행

JDK 21 이상이 필요합니다 (`AnomalyDetector` 가 `record` 를 사용).

Gson 은 저장소에 포함하지 않습니다. [Maven Central](https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar) 에서 `gson-2.11.0.jar` 를 받아 `lib/` 에 둡니다.

```bash
mkdir -p lib out
curl -L -o lib/gson-2.11.0.jar \
  https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar

javac -encoding UTF-8 -cp lib/gson-2.11.0.jar -d out src/*.java

export TMAP_APP_KEY=발급받은키
export KAKAO_REST_KEY=발급받은키

java -cp out:lib/gson-2.11.0.jar RouteServer &
java -cp out:lib/gson-2.11.0.jar Server 8000 ../server/data
```

`Server` 의 두 번째 인자는 데이터 폴더입니다. 저장소에서는 `../server/data` 를 그대로 씁니다.

| 파일 | 쓰임 |
|---|---|
| `graph_nodes.csv` `graph_edges.csv` | 보행 도로망 |
| `cctv.geojson` `light.geojson` `bell.geojson` `police.geojson` | 안전시설 |

정상 기동 시 출력은 다음과 같습니다.

```
[graph] 노드 53,122 / 간선 143,704
[facilities] 시설 23,133지점
[saferoute] 경로 제공자 1순위: http://127.0.0.1:3000/api/route
[server] http://0.0.0.0:8000 에서 대기 중
```

---

## 배포 (systemd)

`systemd/` 의 두 파일을 사용합니다.

```bash
sudo cp systemd/*.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now safewalk-tmap safewalk
systemctl status safewalk --no-pager
```

---

## API 키

키는 소스에 넣지 않고 환경변수로 읽습니다. systemd 는 `EnvironmentFile` 로 주입합니다.

```bash
cp safewalk.env.example /home/ubuntu/safewalk.env
# 편집해서 실제 키를 넣은 뒤
chmod 600 /home/ubuntu/safewalk.env
```

`safewalk.env` 는 `.gitignore` 에 있으며 저장소에 올리지 않습니다.
