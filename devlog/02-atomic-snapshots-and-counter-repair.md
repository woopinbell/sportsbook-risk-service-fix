# 일관된 스냅샷과 만료 카운터 보정

## 여러 GET은 서로 다른 시점을 섞는다

일간 합계를 읽은 뒤 분당 selection과 패턴 이력을 읽는 사이 다른 베팅이 확정될 수
있다. 진단 결과가 어느 한 시점의 상태도 아니게 된다. Java에서 pipeline으로
왕복 횟수만 줄여도 명령 사이에 다른 writer가 들어오는 문제는 남는다.

`RedisRiskSnapshotReader.read()`는 한 사용자의 카운터, override, 활성 예약과 패턴
이력을 `risk-snapshot.lua` 한 번으로 읽는다. 한도만 필요한 진단은
`risk-limit-snapshot.lua`, 패턴만 필요한 예약 replay는
`risk-pattern-snapshot.lua`를 사용한다.

```java
RiskWire wire = readWire(snapshotScript, keys, args, RiskWire.class);
expirationCounter.increment(wire.expired());
return new RiskSnapshot(
    toLimitSnapshot(userId, currency, wire.limits()),
    toPatternSnapshot(context, wire.patterns()));
```

스크립트 cache가 준비된 정상 경로는 `EVALSHA` 한 번이다.
`approvedReadPathUsesExactlyOneSteadyStateEvalshaCall`이 실제 connection의 명령 수를
검사한다. 최초 적재나 Redis의 script cache가 사라진 경우에는 Spring Data Redis가
script를 다시 적재할 수 있으므로 모든 호출이 언제나 한 명령이라는 뜻은 아니다.

각 slot은 값과 오류 상태를 함께 반환한다. 하나의 잘못된 Redis type 때문에 이미
읽은 다른 값을 숫자 0으로 바꾸지 않고, Java 계층이 의사결정 순서에서 해당 slot에
도달했을 때 실패시킨다. 전체 JSON이 깨졌거나 필수 slot이 빠졌으면 즉시 실패한다.

```java
private static SnapshotSlot<Long> longSlot(WireSlot wire) {
  if (!wire.ok()) {
    return SnapshotSlot.failure(wire.error());
  }
  if (wire.value() == null || wire.value().isBlank()) {
    return SnapshotSlot.success(0L);
  }
  try {
    return SnapshotSlot.success(Long.parseLong(wire.value()));
  } catch (NumberFormatException e) {
    throw new IllegalStateException(
        "Redis snapshot value is not a long: '" + wire.value() + "'", e);
  }
}
```

오류를 0으로 대체하는 fail-open은 용량을 초과 승인할 수 있다. 반대로 하나의 사용하지
않는 override 오류 때문에 모든 요청을 선제 거절하면 실제 의사결정 순서와 다르다.
slot 단위 결과를 둔 이유다.

## 만료는 접근할 때 정리한다

Redis key TTL만 기다리면 사용자 예약 합계와 전역 active count에서 만료 금액을 뺄 수
없다. reserve와 snapshot Lua는 사용자 예약 목록을 보면서 만료된 RESERVED를 EXPIRED로
옮기고 합계·active count를 보정한다. commit과 release도 대상 lifecycle의 만료를
확인해 terminal 상태로 수렴시킨다.

따라서 `risk_reservations_active`는 시계가 만료 시각을 지나는 순간 자동으로 줄지
않는다. 관련 사용자나 예약을 처리하는 스크립트가 만료를 발견한 뒤 갱신된다. 이
gauge를 초 단위 실시간 lease 수로 해석하지 않는다. `activeCount()`는 값이 없거나
파싱·Redis 오류가 나면 0을 반환하므로 장애 탐지용 권위 지표로도 쓸 수 없다.

## 보조 합계는 원본과 함께 검증한다

합계 키는 매번 ZSET 전체를 합산하지 않기 위한 최적화다. ZSET이 비었는데 sum만 남은
경우 snapshot Lua는 고아 합계를 지우며, 만료 member를 제거할 때 인코딩된 금액만큼
sum을 줄인다. 그러나 손상된 member를 언제나 완전히 복구하는 일반 validator는
아니다.

`RedisRiskSnapshotReaderTest`는 빈 조회가 키를 만들지 않는지, 만료 정리와 고아 합계
보정, TTL 갱신, wrong-type slot, writer와 snapshot의 전후 원자성을 검사한다.
`RedisRiskReservationStoreTest`는 예약 생성·재실행·확정·해제·만료 뒤 합계 키와 활성
수가 예상값으로 돌아오는지 확인한다.

```sh
./mvnw \
  -Dtest=RedisRiskSnapshotReaderTest,RedisRiskReservationStoreTest test
```

활성 예약 ZSET을 직접 다시 합산해 보조 합계와 주기적으로 대조하는 운영 검사는 없다.
lifecycle hash 전체를 열거해 재구축하는 절차도 제공하지 않는다. snapshot은 한
사용자의 한 시점을 일관되게 읽지만, 그 결과를 Java에서 평가하고 reserve Lua를
실행하는 사이의 변경까지 잠그지 않는다.
