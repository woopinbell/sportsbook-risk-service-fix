# 위험 서비스 반복 성능 검사

`run-gate.sh`는 진단용 위험 확인 API의 소스와 공통 계약을 고정한 뒤 독립된
Redis·Kafka에서 성능과 Redis 스냅샷 계약을 확인합니다. 실제 admission 경로인
`/internal/v1/risk/reservations`의 정확성은 `RedisRiskReservationStoreTest`의 동시
예약·재실행·만료·확정 시나리오로 검증합니다. 예약 경로의 지속 처리량은 아직 별도
성능 수치로 인증하지 않습니다.

## 실행 준비

Docker, Java, k6, `curl`, `jq`, `redis-cli`, `shasum`이 필요합니다. 태그가 아니라
검증할 `main` 체크아웃의 정확한 커밋과 트리를 지정합니다.

```sh
export EXPECTED_SOURCE_COMMIT=<risk-main-commit>
export EXPECTED_SOURCE_TREE=<risk-main-tree>
export SHARED_SOURCE_DIR=/absolute/path/to/sportsbook-shared-protocol
export EXPECTED_SHARED_SOURCE_COMMIT=<shared-main-commit>
export MAVEN_REPO_LOCAL=/absolute/path/to/run-specific-m2

"${SHARED_SOURCE_DIR}/mvnw" -B \
  -Dmaven.repo.local="${MAVEN_REPO_LOCAL}" clean install

export EXPECTED_SHARED_SHA256=<installed-shared-jar-sha256>

bash load-test/run-gate.sh baseline screen
bash load-test/run-gate.sh baseline final
```

예비 검사는 세 번, 최종 검사는 다섯 번 측정합니다. 최종 검사에서는 진단을 위해
인프라를 남기는 `KEEP_INFRA=1`을 허용하지 않습니다.

## 실행기가 확인하는 내용

1. 위험 서비스와 공통 계약의 커밋·트리·JAR을 고정합니다.
2. 전용 Redis와 Kafka를 볼륨까지 비운 뒤 새로 시작합니다.
3. 토픽, 소비자 코디네이터, 파티션 할당이 준비됐는지 확인합니다.
4. 초당 1,000회로 60초 예열한 뒤 60초 측정을 반복합니다.
5. 승인 요청마다 서버와 클라이언트에서 `EVALSHA`가 정확히 한 번 실행됐는지
   확인합니다.
6. 실행 중 소스 변경, Kafka 메타데이터 오류, 다른 Redis 명령, 정리 실패가 있으면
   수치와 관계없이 실패합니다.

각 측정은 p50 5ms 미만, p95 15ms 미만, p99 30ms 미만, 오류율 0.1% 미만,
검사 성공률 99.9% 초과, 누락 반복 0건을 모두 만족해야 합니다.

## 결과 해석

`results/<날짜>/`의 저장 결과는 모두 포트폴리오 hardening 이전 소스 또는 당시의
별도 패치 후보를 측정한 역사 자료입니다. 일부 후보의 통과·실패를 비교하는 데에는
사용할 수 있지만 현재 릴리스의 성능을 대신하지 않습니다.

현재 기능과 Redis 원자성은 132개 테스트로 검증했습니다. hardening 소스의 예약
경로와 진단 경로는 지속 처리량을 다시 측정하지 않았으므로 이 릴리스에는 처리량이나
p95/p99 주장이 없습니다. 현재 판정과 역사 자료의 범위는
[검증·측정 상태](results/BEST.md)에 있습니다.

## 보조 검사

`check_latency.js`는 2026-05-29 pre-hardening 지연·포화도 비교에 사용했습니다.
`consumer_throughput.sh`는 합성 byte를 사용해 producer에서 broker ack까지를
측정하는 probe입니다. 유효한 Avro 이벤트를 처리하지 않으므로 consumer 처리량을
측정하지 않습니다. 소비자의 계약과 중복 반영 방지는
`BetPlacedConsumerIntegrationTest`가 검증합니다.

```sh
docker compose -f load-test/docker-compose.yml down -v --remove-orphans
```
