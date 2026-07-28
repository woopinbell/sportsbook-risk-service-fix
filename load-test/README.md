# 위험 서비스 반복 성능 검사

`run-gate.sh`는 독립 Redis와 Kafka에서 진단용 `/internal/v1/risk/check`의 지연과 Redis 스냅샷 계약을 확인합니다. 실제 접수 경로인 `/internal/v1/risk/reservations`의 동시성 정확성은 `RedisRiskReservationStoreTest`가 검증합니다. 예약 경로의 지속 처리량은 아직 별도 수치로 인증하지 않습니다.

## 준비와 실행

Docker, Java, k6, `curl`, `jq`, `redis-cli`, `shasum`이 필요합니다. 공통 프로토콜은 실행 전용 Maven 저장소에 설치합니다.

실행 전에는 스크립트의 시작 검사가 요구하는 필수 환경 변수를 모두 설정해야 합니다. 실행기는 위험 서비스와 공통 프로토콜에 수정하거나 새로 추가한 파일이 없는지, 설치한 공통 프로토콜 JAR이 소스 빌드 결과와 같은지 확인하고 조건이 맞지 않으면 측정을 시작하지 않습니다. 아래 명령은 이 사전 조건을 충족한 셸에서 실행합니다.

```sh
export SHARED_SOURCE_DIR=/absolute/path/to/sportsbook-shared-protocol
export MAVEN_REPO_LOCAL=/absolute/path/to/run-specific-m2

"${SHARED_SOURCE_DIR}/mvnw" -B \
  -Dmaven.repo.local="${MAVEN_REPO_LOCAL}" clean install

export EXPECTED_SOURCE_COMMIT="${BUILD_SOURCE_REVISION:?}"
export EXPECTED_SOURCE_TREE="${BUILD_SOURCE_TREE:?}"
export EXPECTED_SHARED_SOURCE_COMMIT="${BUILD_SHARED_SOURCE_REVISION:?}"
export EXPECTED_SHARED_SHA256="${BUILD_SHARED_JAR_SHA256:?}"

bash load-test/run-gate.sh baseline screen
bash load-test/run-gate.sh baseline final
```

`BUILD_*` 값은 신뢰할 수 있는 빌드 메타데이터나 명시적으로 승인한 값으로
준비해야 합니다. 네 `EXPECTED_*` 값 중 하나라도 비어 있거나 측정 대상과 다르면
실행기는 인프라를 올리기 전에 종료합니다.

예비 검사는 세 번, 최종 검사는 다섯 번 측정합니다. 실행기는 전용 Redis와 Kafka를 초기화하고 토픽과 소비자 그룹 준비를 확인한 뒤 예열과 측정을 수행합니다.

각 측정은 p50 5ms 미만, p95 15ms 미만, p99 30ms 미만, 오류율 0.1% 미만, 검사 성공률 99.9% 초과, 누락 반복 0건을 모두 만족해야 합니다. 승인 요청마다 서버와 클라이언트에서 `EVALSHA`가 정확히 한 번인지도 확인합니다.

이 gate는 진단 API의 읽기 경로를 측정합니다. 예약 Lua, Kafka consumer 처리량이나 전체 betting admission 용량으로 해석하면 안 됩니다. 예약 성능을 기록하려면 reservation API를 직접 호출하고 최종 Redis 상태와 멱등 개수를 함께 확인하는 별도 시나리오가 필요합니다.

현재 성능 판정과 결과 추가 조건은 [검증·측정 상태](results/BEST.md)에 정리했습니다.

```sh
docker compose -f load-test/docker-compose.yml down -v --remove-orphans
```
