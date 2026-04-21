# 위험 서비스 반복 성능 검사

`run-gate.sh`는 위험 확인 API의 소스와 공통 계약을 고정한 뒤 독립된 Redis·Kafka에서
반복 성능 조건을 검사합니다.

## 실행 준비

Docker, Java, k6, `curl`, `jq`, `redis-cli`, `shasum`이 필요합니다. 태그가 아니라
검증할 `main` 체크아웃의 정확한 커밋과 트리를 지정합니다.

```sh
export EXPECTED_SOURCE_COMMIT=<risk-main-commit>
export EXPECTED_SOURCE_TREE=<risk-main-tree>
export SHARED_SOURCE_DIR=/absolute/path/to/sportsbook-shared-protocol
export EXPECTED_SHARED_SOURCE_COMMIT=<shared-main-commit>
export MAVEN_REPO_LOCAL=/absolute/path/to/run-specific-m2

"${SHARED_SOURCE_DIR}/mvnw" -B \
  -Dmaven.repo.local="${MAVEN_REPO_LOCAL}" clean install

export EXPECTED_SHARED_SHA256=<installed-shared-jar-sha256>

bash load-test/run-gate.sh baseline screen
bash load-test/run-gate.sh baseline final
```

예비 검사는 세 번, 최종 검사는 다섯 번 측정합니다. 최종 검사에서는 진단을 위해
인프라를 남기는 `KEEP_INFRA=1`을 허용하지 않습니다.

## 합격 조건

60초, 초당 1,000회 예열은 측정에서 제외합니다. 이어지는 각 60초 측정은 다음 조건을
모두 만족해야 합니다.

- p50 5ms 미만, p95 15ms 미만, p99 30ms 미만
- HTTP 오류율 0.1% 미만
- 검사 성공률 99.9% 초과
- 누락 반복 0건
- Kafka 토픽·코디네이터 오류 0건

실행기는 시작·종료 커밋과 트리, 서비스 JAR, 공통 계약 JAR, 실행기, k6 시나리오,
Compose 파일의 해시를 남깁니다. 수정된 작업 트리나 전역 `~/.m2`에서 섞인 공통 계약을
거부합니다.

## 2026-07-13 결과

별도 패치 후보는 예비 검사 3회와 최종 검사 5회를 통과했습니다. 최종 p99는
11.217~21.326ms였고 오류와 누락 반복은 없었습니다. 이 결과는 당시 소스와 패치에
묶여 있으며 이후 커밋의 성능을 대신하지 않습니다.

정확한 행은 `results/2026-07-13/risk-check-gate.tsv`에 있습니다.
