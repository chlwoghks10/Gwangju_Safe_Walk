광주 안심길 — 경로 비교 API 서버

[폴더 구성]
  main.py            FastAPI 엔드포인트
  road_graph.py      도로망 그래프 + 다익스트라 (조원 자바 코드의 파이썬 이식)
  requirements.txt   필요한 패키지
  data/
    graph_nodes.csv
    graph_edges.csv

[로컬에서 실행]
  pip install -r requirements.txt
  uvicorn main:app --host 0.0.0.0 --port 8000

[확인]
  http://localhost:8000/health
  http://localhost:8000/docs

[호출 예시]
  /route?start_lat=35.1445&start_lon=126.9245&end_lat=35.1360&end_lon=126.9310
