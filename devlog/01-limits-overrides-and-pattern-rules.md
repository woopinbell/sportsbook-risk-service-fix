# 숫자 한도에서 위험 판정기로 넓혀 간 과정

위험 서비스의 첫 구현은 “이 베팅을 받아도 되는가”를 여러 종류의 숫자로 답하는
작업이었다. 정책은 [`RiskLimitProperties`](../src/main/java/com/sportsbook/risk/policy/RiskLimitProperties.java)에
묶고, 통화별 단건·일간·주간·월간 금액과 분당 selection 수를
[`application.yml`](../src/main/resources/application.yml)에서 받았다. 잘못된 값으로
서버가 뜬 뒤 모든 요청을 거절하거나 무제한 승인하지 않도록 설정 객체를 Bean
Validation으로 검사한다.

단건 상한은 후보 금액만 보면 되지만 기간 한도는 이미 확정된 사용량이 필요하다.
[`LimitType`](../src/main/java/com/sportsbook/risk/counter/LimitType.java)은 Redis에
쌓을 네 기간 카운터를 구분하고,
[`SlidingWindowCounter`](../src/main/java/com/sportsbook/risk/counter/SlidingWindowCounter.java)는
시간순 ZSET과 합계 키를 함께 관리한다. 최초 구현의 Lua는 만료 member를 제거하고,
남은 합계를 읽고, 새 값이 있으면 `ZADD NX`와 `INCRBY`를 한 번에 수행했다.

```lua
local inserted = redis.call('ZADD', KEYS[1], 'NX', nowMs, member)
if inserted == 1 then
    current = redis.call('INCRBY', KEYS[2], amount)
end
```

같은 베팅 이벤트가 다시 와도 member가 같으면 `ZADD NX`가 0을 반환하므로 보조
합계를 두 번 올리지 않는다. ZSET만 멱등하게 만들고 `INCRBY`를 매번 실행하면
member 수와 합계가 갈라진다. 반대로 매번 ZSET 전체를 합산하면 읽기 비용이 기간
안의 베팅 수에 비례한다. 합계 키는 그 비용을 줄이는 대신 원본 ZSET과 함께
정리해야 하는 파생 상태가 됐다.

## 기본 정책과 운영자 재정의를 겹쳐 읽었다

모든 사용자에게 같은 값을 적용하는 설정만으로는 사고 대응이나 개별 제한을
처리하기 어렵다. [`RedisLimitOverrideStore`](../src/main/java/com/sportsbook/risk/limit/RedisLimitOverrideStore.java)는
사용자·마켓별 재정의를 Redis에 두고,
[`LimitResolver`](../src/main/java/com/sportsbook/risk/limit/LimitResolver.java)는
다음 순서로 값을 고른다.

```java
return overrides
    .findUserOverride(userId, type, currency)
    .orElseGet(() -> policyDefault(type, currency));
```

사용자 한도는 재정의가 없으면 정책 기본값으로 돌아간다. 마켓 한도에는 전역
기본값이 없어서 재정의 자체가 없으면 무제한이라는 별도 의미를 갖는다.
[`LimitController`](../src/main/java/com/sportsbook/risk/api/LimitController.java)는
조회·수정·삭제 API를 제공하며 응답에 `OVERRIDE`인지 `POLICY`인지도 적는다.

selection 수는 통화와 무관하지만 Redis 키 형식을 금액 한도와 맞추기 위해 내부에서
KRW를 sentinel로 쓴다. 이 약속을 운영 호출자에게 노출하지 않도록 컨트롤러가
요청과 응답에서 통화를 정규화한다. count 한도까지 각 통화별 값처럼 내보내면
서로 다른 설정이 존재하는 것처럼 보일 수 있다.

## 숫자 하나로 설명되지 않는 행동도 규칙으로 분리했다

카운터 한도 뒤에는 사용자 행동 이력을 읽는 세 규칙을 추가했다.

- [`RapidBettingRule`](../src/main/java/com/sportsbook/risk/pattern/rule/RapidBettingRule.java)은
  현재 후보까지 포함한 짧은 시간의 베팅 수를 본다.
- [`SuddenStakeIncreaseRule`](../src/main/java/com/sportsbook/risk/pattern/rule/SuddenStakeIncreaseRule.java)은
  최근 금액의 중앙값과 후보 금액을 비교한다.
- [`RepeatedSameSelectionRule`](../src/main/java/com/sportsbook/risk/pattern/rule/RepeatedSameSelectionRule.java)은
  후보에 포함된 selection별 반복 횟수를 본다.

급격한 금액 증가에 평균이 아니라 중앙값을 사용한 것은 과거의 큰 값 하나가 기준
자체를 과도하게 올리지 않게 하기 위해서다. 최근 표본이 설정한 개수보다 적으면
비교 기준이 없다고 보고 건너뛴다. 반복 규칙은 여러 selection 중 처음 기준을 넘긴
항목을 이유에 넣어, 이후 소비자가 같은 규칙을 다시 계산하지 않아도 원인을 알 수
있게 했다.

각 규칙은 `BLOCK`, `REVIEW`, `SUSPECT` 중 설정된 행동과 이유를
[`PatternMatch`](../src/main/java/com/sportsbook/risk/pattern/PatternMatch.java)로
돌려준다. [`RuleEngine`](../src/main/java/com/sportsbook/risk/pattern/RuleEngine.java)은
Spring이 모은 규칙을 실행해 모든 match를 보존할 뿐, 하나를 골라 승인 여부를
정하지 않는다. 정책 행동을 서비스 밖으로 밀어내지 않으면서 규칙 계산 자체는
재사용할 수 있는 경계다.

## 판정 순서가 응답과 비용을 함께 정한다

[`RiskCheckService`](../src/main/java/com/sportsbook/risk/service/RiskCheckService.java)는
싼 검사부터 실행한다.

```text
단건 금액
  → 일·주·월 금액
  → 분당 selection 수
  → 행동 패턴
```

앞의 숫자 한도에서 거절되면 뒤의 규칙을 계산하지 않는다. 한도 거절은 첫 번째
원인만 응답하지만, 패턴까지 도달하면 일치한 규칙을 모두 발행하고 그중 `BLOCK`이
있을 때 거절한다. 이 차이를 모르고 응답의 `patternsFlagged`를 항상 완전한 진단
목록으로 해석하면 안 된다.

[`RiskCheckController`](../src/main/java/com/sportsbook/risk/api/RiskCheckController.java)의
`POST /internal/v1/risk/check`는 이 결과를 읽기 전용 진단으로 제공한다. 이후 원자
예약 API가 추가되면서 이 경계의 의미가 더 분명해졌다. check가 승인됐다는 것은
그 시점의 값으로 한도를 넘지 않았다는 뜻이지, 다음 요청을 위한 용량을 확보했다는
뜻은 아니다. 실제 베팅 접수는 마지막 단계에서 추가된 예약 경로를 사용한다.

초기 판단은 [`RiskCheckServiceTest`](../src/test/java/com/sportsbook/risk/service/RiskCheckServiceTest.java),
재정의 우선순위는 [`LimitResolverTest`](../src/test/java/com/sportsbook/risk/limit/LimitResolverTest.java),
세 규칙의 경계는 [`pattern/rule`](../src/test/java/com/sportsbook/risk/pattern/rule) 아래
테스트에 남아 있다. 이 테스트들은 Redis 원자성보다 먼저, 어떤 값을 거절하고 어떤
이유를 내보내야 하는지를 고정한 기록이다.
