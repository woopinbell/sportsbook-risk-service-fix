# 위험 관리 서비스

베팅 접수의 동기 경로에서 사용자 한도와 이상 패턴을 확인합니다. 실제 접수 경로는
2분짜리 Redis 예약을 먼저 만들고, 지갑 출금이 확인되면 그 예약을 확정합니다. 진단용
조회 API도 유지하며 접수된 베팅 이벤트는 이전 발행자와의 호환 경로로 소비합니다.

## 처리 흐름

```text
betting-service ──HTTP──▶ POST /internal/v1/risk/reservations
       │                         │              │
       │                         ├── PUT commit └── DELETE release
       │                         ▼
       └──Kafka: bet.placed.v1──▶ Redis 확정 집계·패턴 이력

admin-api ──HTTP──▶ 사용자·마켓 한도 조회와 변경
risk-service ──Kafka──▶ risk.limit.violated / risk.pattern.suspected
```

패턴 verdict를 먼저 계산한 뒤 예약 생성 Lua 스크립트가 만료 예약 정리, 멱등
fingerprint 검사, 확정액과 예약액을 합친 일·주·월/분당 한도 검사, 최종
`REJECTED` 또는 `RESERVED` 기록을 한 번에 수행합니다. 따라서 pattern BLOCK을
판정하는 도중 같은 요청이 임시 승인되는 구간이 없고, 마지막 남은 한도를 향한 동시
요청도 Redis가 직렬화합니다.

`risk:reservation:<betId>` hash는 활성 lease만이 아니라 lifecycle 기록입니다.
`REJECTED`, `RELEASED`, `EXPIRED`, `COMMITTED`도 기본 32일 동안 fingerprint와
결정 정보(`patternsFlagged` 포함)를 보존합니다. 이 기간에는 상태와 관계없이 같은
betId의 다른 payload가 409를 반환합니다. 같은 payload의 `REJECTED`는 한도가 바뀌어도
최초 거절 응답을 재현하고, `RELEASED`는 `RISK_RESERVATION_RELEASED` 거절을 재현해
다시 활성화되지 않습니다. `EXPIRED`만 betting 복구를 위해 같은 fingerprint로
재검사·재예약할 수 있습니다.

`bet.placed.v1`가 먼저 또는 다시 도착해도 예약이 있으면 원자적으로 확정하거나 이미
확정된 상태를 확인합니다. 예약이 없는 기존 이벤트만 과거 방식으로 집계하므로
전환기 발행자도 지원합니다.

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
| `POST /internal/v1/risk/reservations` | 한도 확인과 2분 admission lease 생성 |
| `PUT /internal/v1/risk/reservations/{betId}/commit` | 예약 확정; 재호출은 204 |
| `DELETE /internal/v1/risk/reservations/{betId}` | 예약 해제; 없음은 204, 확정 상태는 409 |
| `GET /internal/v1/risk/limits/{userId}` | 적용 중인 사용자 한도 조회 |
| `PATCH /internal/v1/risk/limits/{userId}` | 사용자 한도 변경 |
| `DELETE /internal/v1/risk/limits/{userId}/{limitType}/{currency}` | 해당 사용자 한도 override 해제 |

예약 생성 요청은 기존 check 요청과 같은
`{userId, betId, stake, selectionIds}` 형태입니다. 승인 응답에는
`reservationState`와 `expiresAt`이 추가됩니다. 만료되거나 존재하지 않는 예약의
commit은 `RISK_RESERVATION_NOT_FOUND` 404이며, 같은 betId에 다른 payload를 쓰면
`DUPLICATE_BET` 409입니다.

commit은 `RESERVED`를 `COMMITTED`로 옮기며 재호출은 204입니다. `REJECTED`,
`RELEASED`, `EXPIRED` tombstone의 commit은 기존 missing/expired 계약과 같은
404입니다. release는 `RESERVED`를 `RELEASED`로 바꾸고 재호출은 204이며,
`COMMITTED` release만 409입니다.

## 빌드와 실행

```sh
(cd ../sportsbook-shared-protocol && ./mvnw -DskipTests install)
./mvnw clean verify
./mvnw spring-boot:run
```

Redis와 Kafka가 필요합니다. 전체 서비스 구성은
[통합 저장소](https://github.com/woopinbell/sportsbook-orchestration)를 참고해
주세요.

예약 스크립트는 동적 키로 상태를 원자적으로 옮기므로 V1 배포 대상은 standalone
Redis입니다. `risk_reservation_requests_total`,
`risk_reservation_transitions_total`, `risk_reservation_lua_latency_seconds`,
`risk_reservation_expirations_total`, `risk_reservations_active`로
생성·재실행·거절·충돌·확정·해제·만료와 현재 예약 수를 관찰할 수 있습니다.

## 검증 결과

현재 소스는 132개 기능 테스트와 Spotless, Checkstyle 검증을 통과했습니다. 기능
검증은 마지막 남은 한도를 향한 20개 동시 요청 중 정확히 한 건만 예약되는지, 동일
요청 100회가 예약 한 건으로 수렴하는지, payload 충돌, 해제·만료·확정, 기존 이벤트
호환성, Redis 스냅샷 일관성을 포함합니다.

포트폴리오 hardening 이후의 소스로 지연 시간이나 지속 처리량을 다시 측정하지
않았습니다. 따라서 이 릴리스에는 RPS, p95/p99 또는 Kafka consumer 처리량 주장이
없습니다. `load-test/results/<날짜>/`의 수치는 모두 hardening 이전 소스에서 만든
역사적 비교 자료이며 현재 릴리스의 성능 증거가 아닙니다. 측정 도구와 재검증 조건은
[부하 검증 문서](load-test/README.md)에 있습니다.

## 현재 범위

카운터 키에는 통화가 들어가지 않으므로 배포 검증 범위는 KRW입니다. USD나 한
사용자의 혼합 통화를 지원하려면 키 이행과 기존 이벤트 재처리 절차가 먼저 필요합니다.
마켓 한도와 open exposure는 설정·관리 자료만 남아 있고 admission 입력에 market 및
미정산 노출 정보가 없으므로 V1에서 보장하지 않습니다.
