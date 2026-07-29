# 개발 기록

정책과 기간 카운터로 시작한 판정기는 이벤트 소비와 패턴 이력을 거쳐, 한 시점의
Redis 상태를 읽는 구조로 바뀌었다. 마지막에는 조회와 승인 사이의 경쟁을 없애기
위해 판정 자체가 예약 수명 주기를 만들도록 확장됐다.

1. [숫자 한도에서 위험 판정기로 넓혀 간 과정](01-limits-overrides-and-pattern-rules.md)
2. [재전달되는 베팅 이벤트와 패턴 이력](02-bet-placed-redelivery-and-pattern-history.md)
3. [한 시점으로 읽는 스냅샷과 만료 카운터 보정](03-atomic-snapshots-and-counter-repair.md)
4. [마지막 용량 하나를 지키는 원자 예약](04-atomic-capacity-reservation.md)

Redis 키와 상태 관계는
[위험 판정과 Redis 키 경계](../architecture/risk-admission-and-redis-keyspace.md)에
이어 정리했다.
