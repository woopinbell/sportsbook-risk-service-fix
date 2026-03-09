# 위험 관리 서비스

베팅 접수 전에 사용자 한도와 이상 패턴을 확인하는 Spring Boot 서비스입니다.

## 기술 구성

- Java 17, Spring Boot 3.2, Maven
- Redis, Kafka, Avro
- JUnit 5, Testcontainers

## 빌드

```sh
(cd ../sportsbook-shared-protocol && ./mvnw -DskipTests install)
./mvnw verify
```

