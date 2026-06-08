# 위험 서비스 검증·측정 상태

## 현 릴리스 판정

기능 정확성과 성능 인증을 분리합니다. 포트폴리오 hardening 소스는 132개 테스트,
Spotless, Checkstyle을 통과했습니다. 예약 생성은 한 번의 Redis Lua 연산으로 만료
정리, fingerprint 재실행 검사, 확정액과 예약액을 합친 한도 검사, 예약 기록을
수행합니다.

현재 소스로 예약 경로나 진단용 check 경로의 지연 시간·지속 처리량을 재측정하지
않았습니다. 따라서 이 릴리스에는 RPS, p95/p99, 최대 사용자 수 또는 Kafka consumer
처리량 주장이 없습니다.

## 현재 정확성 증거

- 마지막 남은 한도에 대한 20개 동시 요청 중 한 건만 예약됩니다.
- 동일 betId와 동일 fingerprint를 100회 요청해도 예약은 한 건입니다.
- 다른 fingerprint는 충돌하고 release, expiry, commit 뒤 합계가 일치합니다.
- 기존 `bet.placed.v1` 재전달은 확정 집계와 패턴 이력을 중복 반영하지 않습니다.

이 항목들은 기능·원자성 증거이며 처리량 측정으로 해석하지 않습니다.

## Pre-hardening 역사 자료

날짜별 디렉터리의 모든 결과는 현재 hardening 이전 소스 또는 별도 패치 후보에서
생성됐습니다. 변경 전후 비교와 측정 도구 보존용이며 현 릴리스의 대표 수치가
아닙니다.

- `2026-05-29/`: 초기 진단 API와 합성 Kafka producer/broker probe
- `2026-07-13/`: 당시 소스에 적용한 별도 패치 후보
- `2026-07-14/`: 스냅샷 구현 후보와 qualification 시도

특히 `2026-05-29/consumer_throughput.txt`는 유효한 Avro 이벤트를 소비시키지 않은
합성 byte producer 결과입니다. producer에서 broker ack까지의 probe일 뿐 risk
consumer 처리량 증거가 아닙니다.

## 다시 측정할 때

현재 소스와 shared 계약 SHA를 고정하고 fresh Redis·Kafka에서 예약 경로를 포함해
다시 측정해야 합니다. Kafka 수치는 유효한 Avro 이벤트, broker ack, consumer lag와
최종 Redis 반영을 함께 확인해야 consumer 처리량으로 기록할 수 있습니다. 카운터는
KRW만 검증했으므로 다른 통화의 안전성 또는 용량 증거로 사용하지 않습니다.
