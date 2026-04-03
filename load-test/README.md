# 위험 서비스 부하 검사

위험 확인 API의 지연과 Kafka 발행 처리량을 로컬 환경에서 측정합니다.

## 환경 실행

```sh
cd load-test
docker compose up -d
```

다른 터미널에서 공통 계약과 서비스를 빌드해 실행합니다.

```sh
(cd ../sportsbook-shared-protocol && ./mvnw -DskipTests install)
./mvnw -DskipTests package

SERVER_PORT=8083 REDIS_HOST=localhost REDIS_PORT=6390 \
KAFKA_BOOTSTRAP=localhost:9094 \
java -jar target/risk-service-0.1.0-SNAPSHOT.jar
```

## 지연과 포화도

```sh
k6 run --summary-export load-test/results/check_latency.json \
  load-test/scenarios/check_latency.js
```

2026-05-29의 예열 없는 실행은 p50 2.34ms, p95 11.03ms, p99 30.21ms였습니다.
p99 30ms 목표를 0.21ms 넘었으므로 통과로 표시하지 않습니다. 5,000 가상 사용자
포화 구간은 약 1,970 RPS, p95 1.46초, p99 1.73초, 오류율 0.04%였습니다.

## Kafka 기준

```sh
RECORDS=10000 load-test/scenarios/consumer_throughput.sh
```

256바이트 합성 메시지 100,000건 기준으로 초당 173,010건, 42.24MB/s를
기록했습니다. 이 값은 발행기와 브로커의 비교 기준이며 위험 서비스 소비 처리량이나
도메인 계약을 증명하지 않습니다.

## 정리

```sh
docker compose -f load-test/docker-compose.yml down -v --remove-orphans
```

