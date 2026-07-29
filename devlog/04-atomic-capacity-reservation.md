# 마지막 용량 하나를 지키는 원자 예약

## check-then-write는 한도를 넘긴다

두 요청이 각각 “남은 한도 1건”을 읽고 둘 다 통과한 뒤 예약을 쓰면 총량이 한도를
넘는다. Redis transaction 밖에서 여러 GET과 ZSET 읽기를 조합해도 애플리케이션
판단과 쓰기 사이 경쟁은 남는다. WATCH/MULTI로 충돌 때 전체 요청을 다시 시작할 수도
있지만, 만료 정리와 네 종류의 한도를 읽고 여러 키를 갱신하는 재시도 경로가
복잡해진다.

[`risk-reserve.lua`](../src/main/resources/scripts/risk-reserve.lua)는 만료 항목 정리,
fingerprint 판정, 확정 카운터와 예약 합계 계산,
한도 비교, RESERVED 또는 REJECTED hash 저장을 한 번에 수행한다. 마지막 용량을 노린
동시 요청은 Redis가 스크립트를 실행한 순서대로 하나만 승인된다.

```lua
local current = readCommitted(
    KEYS[keyIndex], KEYS[keyIndex + 1],
    tonumber(ARGV[argIndex]), tonumber(ARGV[argIndex + 1]))
local limit = effectiveLimit(names[i] .. ':' .. currency, ARGV[10 + i])
if current + reservedStake + stake > limit then
    return persistRejection(
        names[i] .. '_LIMIT_EXCEEDED',
        current + reservedStake, limit, stake, currency, 'BLOCK', emptyPatterns)
end
```

승인 때는 lifecycle hash, 사용자 활성 예약 ZSET, 예약 stake·selection 합계와 전역
활성 수를 같은 스크립트에서 바꾼다. hash만 먼저 쓰거나 합계만 나중에 늘리면 다음
요청이 실제 사용량보다 작은 값을 읽는 창이 생긴다.

## 확정과 예약을 따로 센다

접수 중 예약은 아직 `bet.placed` 확정 카운터에 들어가지 않는다. 둘 중 하나만
검사하면 이미 승인한 베팅이나 진행 중 요청을 빠뜨린다.

```text
사용 가능 여부 =
  confirmed window sum
  + active reservation sum
  + requested amount
  <= resolved limit
```

selection 횟수도 같은 방식으로 합친다. single bet max는 요청 하나만 비교하고
sliding window key에는 저장하지 않는다. commit 스크립트는 RESERVED를 COMMITTED로
바꾸면서 활성 합계를 빼고 일·주·월 stake와 분당 selection ZSET에 betId member를
넣는다. `ZADD ... NX` 결과가 1일 때만 sum을 늘려 재호출을 한 번의 효과로 만든다.

```lua
local inserted = redis.call(
    'ZADD', zsetKey, 'NX', nowMs, betId .. '|' .. tostring(amount))
if inserted == 1 then
    redis.call('INCRBY', sumKey, amount)
end
```

## fingerprint는 금액만으로 만들지 않는다

같은 betId에 사용자, stake, currency나 selection 목록이 달라지면 충돌이다. 상태가
종료된 뒤에도 retention 동안 fingerprint를 남겨 키 재사용을 막는다. EXPIRED만 같은
요청으로 다시 예약할 수 있다. betting 서비스가 lease 만료 뒤 같은 접수를 복구할 수
있어야 하기 때문이다. RELEASED나 REJECTED까지 다시 활성화하면 보상된 접수가
되살아난다.

[`RedisRiskReservationStore.needsPatternEvaluation()`](../src/main/java/com/sportsbook/risk/reservation/RedisRiskReservationStore.java)은
기존 hash의 상태와 fingerprint를
먼저 읽어 replay에 불필요한 패턴 평가를 줄인다. 이 읽기는 권위 판정이 아니다. 직후
상태가 달라져도 reserve Lua가 fingerprint와 lifecycle을 다시 확인한다.

## 패턴 계산까지 한 원자 구간은 아니다

패턴 이력 snapshot은 Lua로 읽지만 규칙 계산은 Java `RuleEngine`에서 수행한다.
blocking 결과와 전체 match 목록을 reserve Lua의 인자로 넘겨 같은 lifecycle 판정에
저장한다. 따라서 용량 비교와 최종 예약 저장은 원자적이지만, 패턴 snapshot을 읽은
시점부터 예약까지는 하나의 Redis 원자 구간이 아니다. 그 사이 들어온 bet history가
이번 패턴 판정에는 반영되지 않을 수 있다.

이 구조는 규칙을 Java로 유지해 테스트와 변경을 쉽게 하는 대신 완전 직렬화된 패턴
판정을 포기한 선택이다. 패턴까지 강한 직렬화가 필요하면 규칙을 Lua로 옮기거나
version을 함께 비교해 재시도해야 한다.

## 경쟁 검증과 지원 경계

[`RedisRiskReservationStoreTest`](../src/test/java/com/sportsbook/risk/reservation/RedisRiskReservationStoreTest.java)는
다음 상태를 실제 Redis에서 확인한다.

- 마지막 용량을 놓고 경쟁하는 20개 요청 중 하나만 승인
- 같은 fingerprint 요청 100개가 예약 하나와 최초 패턴 결과로 수렴
- 다른 fingerprint 충돌
- commit·release·expiry 뒤 합계와 lifecycle tombstone
- tombstone TTL이 끝나기 전 betId 재사용 거절

검사는 standalone Redis를 사용한다. 스크립트가 lifecycle에서 사용자별 키를
동적으로 구성하므로 Redis Cluster의 hash slot 계약을 충족하지 않으며 현재 지원
범위도 standalone이다. Lua가 오래 실행되면 같은 Redis의 다른 명령도 지연되므로
활성 예약 수와 사용자별 정리 비용을 무제한으로 키우면 안 된다.
