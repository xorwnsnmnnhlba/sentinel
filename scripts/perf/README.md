# 대용량 이력 성능 점검

1년치 실행 이력을 E2E 스택의 일회용 DB에 만들고, 화면이 쓰는 API 응답 시간을 측정하는 도구. 측정 결과와 개선 내역은 `docs/design.md` "대용량 이력 성능 점검"

- 실제 로컬 DB·팀 서버 DB에 사용 금지. `docker-compose.e2e.yml`의 `e2e-postgres`(외부 포트 없음, `down -v`로 삭제)에만 사용
- 로그인은 E2E 스택의 테스트 계정(`admin@sentinel.test`) 사용
- 1년치(DB 약 5.3GB)에 대량 UPDATE(수십만 행 이상)를 하지 않음. 2026-09-30 실행·결과 분포를 바꾸는 UPDATE 중 Docker Desktop이 멈춤 (추정: Docker VM 디스크 부족). 분포를 바꿔 보려면 45일치에서, 1년치는 조회만. Docker 디스크가 부족하면 `docker system df`로 확인하고 빌드 캐시 정리(`docker builder prune`)

## 절차

```bash
# 1. E2E 스택을 빈 DB로 기동
docker compose -f docker-compose.e2e.yml down -v
docker compose -f docker-compose.e2e.yml up -d --build --wait

# 2. 이력 생성 (days 생략 시 45일). 365일 약 1분, DB 약 5.3GB
docker compose -f docker-compose.e2e.yml exec -T e2e-postgres psql -U sentinel -d sentinel -v ON_ERROR_STOP=1 -v days=365 < scripts/perf/seed-history.sql

# 3. 측정 (요청마다 5회 호출, 중앙값)
scripts/perf/measure.sh

# 4. 정리 (5GB 이상 차지하므로 점검 후 삭제)
docker compose -f docker-compose.e2e.yml down -v
```

## 생성 데이터

| 항목 | 값 |
|------|-----|
| 규모 | 프로젝트 4 × 스위트 5 × 케이스 10, 스위트마다 10분 간격 스케줄 |
| 365일 | 실행 105만·결과 1,046만 건, DB 5.3GB |
| 판정 | 통과 96%, 실패 3%, 오류 1% (결과마다 난수) |
| 수동 실행 | 1%, 그중 절반은 케이스 단건 실행 |
| 원문 | 최근 30일만 보관, 그 이전은 보관기간 정리를 마친 상태 |
| 구조 지문 | 응답이 있는 결과 모두 |

- 실행·결과 ID는 앱과 같이 시작 순으로 증가하도록 시각 순으로 생성 (ID 순 정렬·범위 조건 쿼리의 실행 계획이 실제와 같아짐)
- 실행별 판정 수(V10)가 있는 스키마면 생성 후 함께 채움. V9 이하 스키마로 생성한 뒤 V10 이미지로 기동하면 마이그레이션의 채우기로 적용 시간 확인 가능

## 마이그레이션 적용 시간 확인

큰 테이블을 바꾸는 마이그레이션은 이전 버전 이미지로 이력을 만든 뒤 새 이미지로 기동하여 적용 시간 확인

```bash
git switch main
docker compose -f docker-compose.e2e.yml down -v
docker compose -f docker-compose.e2e.yml up -d --build --wait
docker compose -f docker-compose.e2e.yml exec -T e2e-postgres psql -U sentinel -d sentinel -v ON_ERROR_STOP=1 -v days=365 < scripts/perf/seed-history.sql
scripts/perf/measure.sh > /tmp/perf-before.txt

git switch <작업 브랜치>
docker compose -f docker-compose.e2e.yml up -d --build --wait e2e-api
docker compose -f docker-compose.e2e.yml logs e2e-api | grep -i "migrat"
scripts/perf/measure.sh > /tmp/perf-after.txt
```

- api 기동 대기(healthcheck)보다 마이그레이션이 길면 기동 실패로 보일 수 있으므로 로그로 확인
- 측정은 같은 PC에서 개선 전후를 연달아 비교 (절대값은 PC 성능에 따라 다름)
