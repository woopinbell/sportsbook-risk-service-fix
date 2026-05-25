# 위험 관리 서비스

베팅 접수의 동기 경로에서 사용자·마켓 한도와 이상 패턴을 확인합니다. 승인 여부를
즉시 반환하고, 접수된 베팅 이벤트를 소비해 다음 판단에 사용할 Redis 집계와 기록을
갱신합니다.

## 처리 흐름

```text
betting-service ──HTTP──▶ POST /internal/v1/risk/check ──▶ 승인 또는 거부
       │                                  │
       └──Kafka: bet.placed.v1────────────┴──▶ Redis 스냅샷

admin-api ──HTTP──▶ 사용자·마켓 한도 조회와 변경
risk-service ──Kafka──▶ risk.limit.violated / risk.pattern.suspected
```

기간별 한도와 패턴 판단에 필요한 값을 Lua 스크립트 한 번으로 읽습니다. 같은 시점의
한도 합계와 패턴 이력을 사용하므로 요청 도중 여러 Redis 조회 사이에서 상태가
달라지는 문제를 피합니다. 접수 이벤트는 베팅 식별자를 함께 기록해 재전달되어도 한
번만 반영합니다.

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
| `POST /internal/v1/risk/check` | 베팅 접수 가능 여부 확인 |
| `GET /internal/v1/risk/limits/{userId}` | 적용 중인 사용자 한도 조회 |
| `PATCH /internal/v1/risk/limits/{userId}` | 사용자 한도 변경 |
| `DELETE /internal/v1/risk/limits/{userId}` | 사용자 한도 초기화 |

## 빌드와 실행

```sh
(cd ../sportsbook-shared-protocol && ./mvnw -DskipTests install)
./mvnw clean verify
./mvnw spring-boot:run
```

Redis와 Kafka가 필요합니다. 전체 서비스 구성은
[통합 저장소](https://github.com/woopinbell/sportsbook-orchestration)를 참고해
주세요.

## 검증 결과

기능 검증은 중복 이벤트, 동시 갱신, 부분·전체 만료, Redis 스냅샷 일관성을 포함한
99개 테스트를 통과했습니다.

성능 수치는 환경별 결과를 구분해 해석합니다.

- 2026-05-29의 예열 없는 기준 측정은 p99 30.21ms로 30ms 목표를 0.21ms
  넘었습니다.
- 2026-07-13의 별도 패치 후보는 초당 1,000회 측정을 다섯 번 통과했지만, 당시
  소스와 패치에 묶인 비교 자료입니다.
- 2026-07-14의 현재 단일 스냅샷 후보는 첫 예비 검사에서 p50 0.802ms,
  p95 13.060ms, p99 268.450ms, 누락 반복 1,030건을 기록해 실패했습니다.

따라서 현재 릴리스는 기능 정확성, 원자성, 재전달 안전성을 검증하지만 초당 1,000회
운영 용량을 보장하지 않습니다. 같은 조건의 예비 검사 3회와 최종 검사 5회를 통과한
환경에서만 해당 용량을 주장할 수 있습니다. 자세한 조건과 원본은
[부하 검증 문서](load-test/README.md)에 있습니다.

## 현재 범위

카운터 키에는 통화가 들어가지 않으므로 배포 검증 범위는 KRW입니다. USD나 한
사용자의 혼합 통화를 지원하려면 키 이행과 기존 이벤트 재처리 절차가 먼저 필요합니다.
