# 위험 관리 서비스

베팅 접수의 동기 경로에서 사용자·마켓 한도와 이상 패턴을 확인합니다. 승인 여부를
즉시 반환하고, 접수된 베팅 이벤트를 소비해 다음 판단에 사용할 집계와 기록을
갱신합니다.

## 처리 흐름

```text
betting-service ──HTTP──▶ POST /internal/v1/risk/check ──▶ 승인 또는 거부
       │                                  │
       └──Kafka: bet.placed.v1────────────┴──▶ Redis 집계와 이력

admin-api ──HTTP──▶ 사용자·마켓 한도 조회와 변경
risk-service ──Kafka──▶ risk.limit.violated / risk.pattern.suspected
```

Redis를 첫 릴리스의 유일한 상태 저장소로 사용합니다. 기간별 베팅 금액은 정렬 집합과
합계 키로 관리하며 Lua 스크립트가 만료 정리와 합계 갱신을 원자적으로 수행합니다.
사용자·마켓별 운영 한도는 기본 정책보다 우선합니다.

## 판단 항목

- 한 번의 최대 베팅 금액
- 일간·주간·월간 누적 금액
- 분당 선택 횟수
- 짧은 시간에 반복되는 베팅
- 평소보다 갑자기 커진 금액
- 같은 선택지의 반복

이상 패턴은 설정으로 켜고 끌 수 있는 규칙으로 구현합니다. 첫 릴리스에는 통계 모델이나
기계 학습 판정을 사용하지 않습니다.

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

실행하려면 Redis와 Kafka가 필요합니다. 전체 서비스 구성은
[통합 저장소](https://github.com/woopinbell/sportsbook-orchestration)를 참고해
주세요.

## 일관성

`bet.placed.v1`은 사용자 식별자를 파티션 키로 사용합니다. 소비자는 베팅 식별자를
함께 기록해 같은 이벤트가 다시 전달되어도 금액을 한 번만 더합니다. API 판단 경로는
집계를 수정하지 않으며, 실제 접수 이벤트만 카운터와 패턴 이력을 변경합니다.

현재 카운터 키에는 통화가 들어가지 않으므로 배포 검증 범위는 KRW로 제한합니다. USD나
한 사용자의 혼합 통화를 지원하려면 키 이행과 기존 이벤트 재처리 절차가 먼저 필요합니다.

