# 위험 관리 서비스

베팅 접수의 동기 경로에서 사용자 한도와 이상 패턴을 확인합니다. 실제 접수 경로는
2분짜리 Redis 예약을 먼저 만들고, 지갑 출금이 확인되면 그 예약을 확정합니다. 진단용
조회 API도 유지하며 접수된 베팅 이벤트는 이전 발행자와의 호환 경로로 소비합니다.

## 처리 흐름

```text
betting-service ──HTTP──▶ POST /internal/v1/risk/reservations
       │                         │              │
       │                         ├── PUT 확정 └── DELETE 해제
       │                         ▼
       └──Kafka: bet.placed.v1──▶ Redis 확정 집계·패턴 이력

admin-api ──HTTP──▶ 사용자 한도 조회와 변경
risk-service ──Kafka──▶ risk.limit.violated / risk.pattern.suspected
```

패턴용 Redis 스냅샷을 읽어 Java 규칙을 평가한 뒤 그 판정을 예약 Lua에 전달합니다.
Lua는 만료 예약 정리, 멱등 요청 지문 검사, 확정액과 예약액을 합친 일·주·월/분당
한도 검사, 전달받은 패턴 판정과 최종 `REJECTED` 또는 `RESERVED` 기록을 한 번에
수행합니다. 마지막 남은 한도를 향한 동시 요청과 최종 판정 기록은 Redis가
직렬화하지만, 패턴 스냅샷 조회와 Java 규칙 평가 자체가 같은 Lua 실행에 포함되는
것은 아닙니다.

`risk:reservation:<betId>` 해시는 활성 예약뿐 아니라 종료 상태도 보관합니다.
`REJECTED`, `RELEASED`, `EXPIRED`, `COMMITTED`도 기본 32일 동안 요청 지문과
결정 정보(`patternsFlagged` 포함)를 보존합니다. 이 기간에는 상태와 관계없이 같은
betId에 다른 요청 본문을 사용하면 409를 반환합니다. 같은 요청 본문의 `REJECTED`는
한도가 바뀌어도
최초 거절 응답을 재현하고, `RELEASED`는 `RISK_RESERVATION_RELEASED` 거절을 재현해
다시 활성화되지 않습니다. `EXPIRED`만 베팅 접수 복구를 위해 같은 요청 지문으로
재검사·재예약할 수 있습니다.

`bet.placed.v1`가 먼저 또는 다시 도착해도 예약이 있으면 원자적으로 확정하거나 이미
확정된 상태를 확인합니다. 예약이 없는 이벤트만 호환 집계 경로로 처리하므로
예약 API를 사용하지 않는 발행자도 지원합니다.

## 판단 항목

- 한 번의 최대 베팅 금액
- 일간·주간·월간 누적 금액
- 분당 선택 횟수
- 짧은 시간에 반복되는 베팅
- 평소보다 갑자기 커진 금액
- 같은 선택지의 반복

## 내부 API

| 메서드와 경로 | 용도 |
|---|---|
| `POST /internal/v1/risk/check` | 확정액과 현재 예약액을 포함한 진단용 가능 여부 확인 |
| `POST /internal/v1/risk/reservations` | 한도 확인과 2분 접수 예약 생성 |
| `PUT /internal/v1/risk/reservations/{betId}/commit` | 예약 확정; 재호출은 204 |
| `DELETE /internal/v1/risk/reservations/{betId}` | 예약 해제; 없음은 204, 확정 상태는 409 |
| `GET /internal/v1/risk/limits/{userId}` | 적용 중인 사용자 한도 조회 |
| `PATCH /internal/v1/risk/limits/{userId}` | 사용자 한도 변경 |
| `DELETE /internal/v1/risk/limits/{userId}/{limitType}/{currency}` | 해당 사용자의 별도 한도 해제 |

예약 생성 요청은 기존 한도 확인 요청과 같은
`{userId, betId, stake, selectionIds}` 형태입니다. 승인 응답에는
`reservationState`와 `expiresAt`이 추가됩니다. 만료되거나 존재하지 않는 예약의
확정 요청은 `RISK_RESERVATION_NOT_FOUND` 404이며, 같은 betId에 다른 요청 본문을 쓰면
`DUPLICATE_BET` 409입니다.

확정 요청은 `RESERVED`를 `COMMITTED`로 옮기며 재호출은 204입니다. `REJECTED`,
`RELEASED`, `EXPIRED` 상태의 확정 요청은 기존의 누락·만료 계약과 같은 404를
반환합니다. 해제 요청은 `RESERVED`를 `RELEASED`로 바꾸고 재호출은 204이며,
`COMMITTED` 상태를 해제하려는 경우에만 409를 반환합니다.

## 빌드와 실행

```sh
(cd ../sportsbook-shared-protocol && ./mvnw -DskipTests install)
./mvnw clean verify
./mvnw spring-boot:run
```

Redis와 Kafka가 필요합니다. 전체 서비스 구성은 `sportsbook-orchestration`의
Compose 설정과 아키텍처 색인을 참고해 주세요.

예약 스크립트는 동적 키로 상태를 원자적으로 옮기므로 V1 배포 대상은 단일 노드
Redis입니다. `risk_reservation_requests_total`,
`risk_reservation_transitions_total`, `risk_reservation_lua_latency_seconds`,
`risk_reservation_expirations_total`, `risk_reservations_active`로
생성·재실행·거절·충돌·확정·해제·만료와 현재 예약 수를 관찰할 수 있습니다.
`risk_reservations_active`는 예약·확정·해제 또는 스냅샷 조회가 만료 항목을 정리할
때 갱신되는 값입니다. 만료 시각이 지났다는 이유만으로 즉시 감소하는 실시간 gauge는
아닙니다.

## 설계와 문제 해결 기록

- [위험 판정과 Redis 키 경계](architecture/risk-admission-and-redis-keyspace.md)
- [마지막 용량을 지키는 원자 예약](devlog/01-atomic-capacity-reservation.md)
- [일관된 스냅샷과 만료 카운터 보정](devlog/02-atomic-snapshots-and-counter-repair.md)
- [베팅 이벤트 재전달과 패턴 이력](devlog/03-bet-placed-redelivery-and-pattern-history.md)

## 검증 결과

기능 검증은 마지막 남은 한도를 향한 20개 동시 요청 중 정확히 한 건만 예약되는지,
동일 요청 100회가 예약 한 건으로 수렴하는지, 요청 본문 충돌, 해제·만료·확정,
이벤트 재전달과 Redis 스냅샷 일관성을 포함합니다.

지연 시간이나 지속 처리량은 아직 제시하지 않습니다. 측정 도구와 결과 채택 조건은
[부하 검증 문서](load-test/README.md)에 있습니다.

## 현재 범위

카운터 키에는 통화가 들어가지 않으므로 배포 검증 범위는 KRW입니다. USD나 한
사용자의 혼합 통화를 지원하려면 키 이행과 기존 이벤트 재처리 절차가 먼저 필요합니다.
마켓 한도와 미정산 노출액은 설정·관리 자료만 남아 있고 접수 입력에 마켓 및
미정산 노출 정보가 없으므로 V1에서 보장하지 않습니다.
