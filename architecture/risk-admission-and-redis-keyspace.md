# 위험 판정과 Redis 키 경계

## 접수와 진단을 분리한다

`POST /internal/v1/risk/check`는 현재 카운터와 활성 예약을 읽어 진단 결과를
돌려주지만 용량을 차지하지 않는다. 실제 betting admission은
`POST /internal/v1/risk/reservations`이며 Redis Lua가 판정과 예약을 한 번에
기록한다. check 성공 뒤 별도로 예약하면 그 사이 다른 요청이 용량을 사용할 수
있으므로 check 응답을 승인으로 사용하면 안 된다.

두 HTTP 경계는 각각
[`RiskCheckController`](../src/main/java/com/sportsbook/risk/api/RiskCheckController.java)와
[`RiskReservationController`](../src/main/java/com/sportsbook/risk/api/RiskReservationController.java)에
있다. 읽기 판정은
[`RiskCheckService`](../src/main/java/com/sportsbook/risk/service/RiskCheckService.java),
수명 주기를 만드는 판정은
[`RiskReservationService`](../src/main/java/com/sportsbook/risk/reservation/RiskReservationService.java)가
맡는다.

## 예약 Lua가 함께 바꾸는 값

예약 스크립트는 다음 키를 한 Redis 실행 안에서 다룬다.

- `risk:reservation:<betId>`: 상태, fingerprint, 판정과 보존 기한
- 사용자 활성 예약 집합
- 사용자 예약 stake 합계
- 사용자 예약 selection 합계
- 일·주·월 stake, 분당 selection 확정 카운터
- 사용자 override
- 전체 활성 예약 수

확정 카운터와 아직 확정되지 않은 예약 합계를 더해 한도를 검사한다. 두 요청이 마지막 용량을 동시에 읽어도 Lua 실행은 직렬화되므로 둘 다 RESERVED가 될 수 없다.

동적 사용자 키를 사용하므로 Redis Cluster의 단일 hash slot을 보장하지 않는다. 배포 범위는 단일 노드 Redis다.

## 상태 보존과 재실행

예약 hash는 종료 상태도 retention 동안 남긴다.

- 같은 fingerprint의 RESERVED/COMMITTED/REJECTED/RELEASED: 저장된 결정 재생
- 다른 fingerprint: 충돌
- EXPIRED와 같은 fingerprint: 새 시점의 한도와 패턴으로 재예약 가능

RELEASED를 다시 활성화하지 않는 이유는 betting-service가 보상 분기로 확정한 요청이 나중에 forward 경로로 돌아가는 일을 막기 위해서다.

키 구성은 [`ReservationKeys`](../src/main/java/com/sportsbook/risk/reservation/ReservationKeys.java),
상태 전이는
[`RedisRiskReservationStore`](../src/main/java/com/sportsbook/risk/reservation/RedisRiskReservationStore.java)와
[`risk-reserve.lua`](../src/main/resources/scripts/risk-reserve.lua),
[`risk-commit.lua`](../src/main/resources/scripts/risk-commit.lua),
[`risk-release.lua`](../src/main/resources/scripts/risk-release.lua)가 함께 소유한다. Java
열거형만 읽고 상태 전이를 바꾸면 Redis에 남는 tombstone 의미와 어긋난다.

## 패턴 판정의 경계

패턴 이력 스냅샷은 Lua로 원자적으로 읽지만 Java `RuleEngine`에서 평가한다. 결과인 match 목록과 blocking rejection을 예약 Lua에 전달해 최종 상태와 함께 저장한다. 따라서 저장된 판정과 용량 예약은 원자적이지만, 스냅샷 조회부터 Java 평가까지 하나의 Redis 원자 구간은 아니다.

강한 실시간 직렬화가 필요한 규칙은 예약 Lua 안에서 계산하거나 버전 비교를 추가해야 한다. 지금 규칙은 짧은 시간 반복, 급격한 stake 증가, 같은 selection 반복을 과거 확정 이력으로 평가한다.

## 이벤트 계약

내부 한도 종류와 공통 프로토콜 enum이 같지 않다. Kafka `risk.limit.violated`는 `STAKE_DAILY`와 `SELECTIONS_PER_MINUTE`만 매핑된다. `STAKE_WEEKLY`, `STAKE_MONTHLY`, `SINGLE_BET_MAX`는 로그와 metric에만 남는다. 모든 거절을 이벤트 수만으로 집계하면 실제보다 적게 보인다.

[`BetPlacedConsumer`](../src/main/java/com/sportsbook/risk/event/BetPlacedConsumer.java)는
예약을 확정하거나 전환기 카운터를 기록한 뒤 패턴 이력을 남기고 마지막에 offset을
확인한다. 이벤트 직렬화와 발행은
[`AvroCodec`](../src/main/java/com/sportsbook/risk/event/AvroCodec.java)과
[`RiskEventPublisher`](../src/main/java/com/sportsbook/risk/event/RiskEventPublisher.java)가
맡는다. Kafka 재전달을 허용하는 대신 betId가 Redis member와 예약 키에서 같은
작업을 가리키도록 맞춘 구조다.
