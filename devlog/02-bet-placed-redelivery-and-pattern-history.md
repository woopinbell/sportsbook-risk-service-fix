# 재전달되는 베팅 이벤트와 패턴 이력

## 새 발행자와 기존 발행자를 함께 받는다

예약 경로를 사용한 bet의 `bet.placed.v1`가 오면 consumer는 해당 예약을 COMMITTED로
옮긴다. 예약이 없는 이벤트는 전환기 발행자로 보고 일·주·월 stake와 selection
카운터를 직접 기록한다.

```java
ReservationTransition reservation = reservations.commit(betId, clock.instant());
if (reservation == ReservationTransition.NOT_FOUND) {
  recordLegacyCounters(userId, betId, stakeAmount, selectionIds.size(), now);
} else if (reservation == ReservationTransition.COMMITTED_CONFLICT) {
  throw new IllegalStateException(
      "Reservation commit returned a release-only transition");
}
history.recordBet(userId, betId, stakeAmount, selectionIds, now);
acknowledgment.acknowledge();
```

이 구분이 없으면 새 bet은 예약 확정과 legacy counter 기록이 모두 적용되어 두 번
집계된다. 반대로 EXPIRED·RELEASED·REJECTED tombstone을 NOT_FOUND처럼 취급하면
보상됐거나 만료된 예약이 legacy 이벤트로 되살아난다. 이 상태들은 counter를 만들지
않고 history 확인 경로로만 간다.

## offset은 Redis 반영 뒤 확인한다

consumer는 예약 전이 또는 legacy 카운터, 패턴 이력 기록이 모두 성공한 뒤 Kafka
acknowledgment를 수행한다. `enable-auto-commit=false`,
`ack-mode=manual_immediate`이므로 중간 Redis 오류에서는 offset을 남겨 broker가 다시
전달하게 한다.

재전달 안전성은 betId에 의존한다.

- 예약 commit은 이미 COMMITTED면 replay
- sliding counter member는 betId와 금액을 사용하며 `ZADD NX`로 합계를 한 번만 증가
- 패턴 bet member는 `betId|amount`, selection member는 betId여서 같은 기록을 갱신

payload를 처리하기 전에 ack하거나 매번 임의 member를 만들면 이 보장이 사라진다.
같은 betId에 다른 금액이 들어오면 counter member 문자열도 달라질 수 있으므로 upstream
계약이 betId payload 불변성을 지켜야 한다. risk consumer 자체에는 별도 payload
fingerprint dedup 저장소가 없다.

## 패턴 판정과 이력 기록 시점

예약 시 패턴은 이미 확정된 베팅 이력을 기준으로 평가한다. 승인된 예약 자체를 즉시
이력에 넣지 않고 `bet.placed`가 온 뒤 기록한다. 미완료·보상된 접수가 사용자 행동
이력에 남지 않게 하기 위해서다.

[`RedisUserBetHistory.recordBet()`](../src/main/java/com/sportsbook/risk/pattern/RedisUserBetHistory.java)은
사용자 bet ZSET과 selection별 ZSET을 여러 Redis
명령으로 쓴다. 하나의 Lua 트랜잭션은 아니다. 중간 실패 뒤 재전달되면 같은 member의
`ZADD`가 이미 쓴 부분을 덮어 최종적으로 수렴하지만, 처리 도중에는 일부 이력만 보일
수 있다.

7일 TTL은 현재 rapid, stake lookback, repeated-selection 창보다 길다. 정책 창을 7일
이상으로 늘리면서 TTL을 그대로 두면 규칙이 필요한 이력을 먼저 잃는다.

## 내부 거절과 외부 이벤트는 일대일이 아니다

공통 `RiskLimitType`에 없는 weekly, monthly, single-bet 거절은 Kafka
`risk.limit.violated`로 발행되지 않는다. 이 경우도 `risk_limit_violations_total`과
응답에는 남는다. 이벤트 수만으로 전체 거절 수를 계산하면 실제보다 작다.

## 재전달 검증과 남는 운영 공백

[`BetPlacedConsumerIntegrationTest`](../src/test/java/com/sportsbook/risk/event/BetPlacedConsumerIntegrationTest.java)는
첫 처리의 Redis 실패 뒤 같은 Kafka record가
다시 전달돼 counter와 history가 수렴하는지, 예약된 bet의 재전달이 확정 카운터를
두 번 늘리지 않는지 확인한다.
[`BetPlacedConsumerTest`](../src/test/java/com/sportsbook/risk/event/BetPlacedConsumerTest.java)는
ack가 성공 뒤에만 호출되는지,
tombstone이 legacy counter로 떨어지지 않는지를 분리해 검사한다.

현재 risk 서비스에는 명시적인 `DefaultErrorHandler`나 DLT publisher 구성이 없다.
계속 실패하는 payload의 격리·재처리 절차를 서비스가 제공한다고 볼 수 없다. 또한
통합 검사는 같은 betId에 서로 다른 payload가 재전달되는 위반과 history 여러 키
사이의 부분 상태를 동시에 읽는 규칙 평가까지 다루지 않는다.
