#!/usr/bin/env bash

# Cold-started, repeatable performance gate for risk-service.
#
# Usage:
#   bash load-test/run-gate.sh baseline final
#   bash load-test/run-gate.sh redis-pool-disabled screen
#   bash load-test/run-gate.sh tomcat-min-spare32 screen
#   bash load-test/run-gate.sh redis-idle16 screen
#   bash load-test/run-gate.sh <green-candidate> final

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd "${SCRIPT_DIR}/.." && pwd)
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
CHECK_SCRIPT="${SCRIPT_DIR}/scenarios/check_gate.js"

PROFILE=${1:-baseline}
STAGE=${2:-final}
RISK_PORT=${RISK_PORT:-8083}
BASE_URL="http://localhost:${RISK_PORT}"
RUN_ID=${RUN_ID:-$(date '+%Y%m%d-%H%M%S')}
RESULT_ROOT=${RESULT_ROOT:-"${SCRIPT_DIR}/results/gate/${RUN_ID}"}
OUTPUT_DIR="${RESULT_ROOT}/${PROFILE}-${STAGE}/raw"
SERVICE_PID=''

case "${PROFILE}" in
  baseline|redis-pool-disabled|tomcat-min-spare32|redis-idle16) ;;
  *)
    echo "Unknown profile: ${PROFILE}" >&2
    exit 2
    ;;
esac

case "${STAGE}" in
  screen|final) ;;
  *)
    echo "Unknown stage: ${STAGE}" >&2
    exit 2
    ;;
esac

if [[ "${PROFILE}" == "baseline" && "${STAGE}" != "final" ]]; then
  echo "Baseline is always the five-run final gate" >&2
  exit 2
fi

if [[ "${STAGE}" == "screen" ]]; then
  RUN_COUNT=3
else
  RUN_COUNT=5
fi

for command in docker k6 curl jq java redis-cli; do
  if ! command -v "${command}" > /dev/null 2>&1; then
    echo "Missing required command: ${command}" >&2
    exit 2
  fi
done

if [[ -e "${OUTPUT_DIR}" ]]; then
  echo "Refusing to overwrite an existing evidence directory: ${OUTPUT_DIR}" >&2
  exit 2
fi
mkdir -p "${OUTPUT_DIR}"

cleanup() {
  local exit_code=$?
  set +e
  if [[ -n "${SERVICE_PID}" ]] && kill -0 "${SERVICE_PID}" > /dev/null 2>&1; then
    kill "${SERVICE_PID}"
    wait "${SERVICE_PID}" > /dev/null 2>&1
  fi
  docker compose -f "${COMPOSE_FILE}" ps --all > "${OUTPUT_DIR}/compose-ps.txt" 2>&1
  docker compose -f "${COMPOSE_FILE}" logs --no-color > "${OUTPUT_DIR}/compose.log" 2>&1
  if [[ "${KEEP_INFRA:-0}" != "1" ]]; then
    docker compose -f "${COMPOSE_FILE}" down -v --remove-orphans \
      > "${OUTPUT_DIR}/compose-down.log" 2>&1
  fi
  return "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

if [[ -n "${MAVEN_REPO_LOCAL:-}" ]]; then
  "${REPO_ROOT}/mvnw" -q -B -Dmaven.repo.local="${MAVEN_REPO_LOCAL}" -DskipTests package \
    > "${OUTPUT_DIR}/maven-package.log" 2>&1
else
  "${REPO_ROOT}/mvnw" -q -B -DskipTests package \
    > "${OUTPUT_DIR}/maven-package.log" 2>&1
fi

JAR_PATH="${REPO_ROOT}/target/risk-service-0.1.0-SNAPSHOT.jar"
if [[ ! -f "${JAR_PATH}" ]]; then
  echo "Expected jar was not built: ${JAR_PATH}" >&2
  exit 1
fi

{
  echo "source_commit=$(git -C "${REPO_ROOT}" rev-parse HEAD)"
  echo "profile=${PROFILE}"
  echo "stage=${STAGE}"
  echo "measured_runs=${RUN_COUNT}"
  echo "cold_restart=true"
  echo "warmup_rps=1000"
  echo "warmup_seconds=60"
  echo "measured_rps=1000"
  echo "measured_seconds=60"
  echo "threshold_p50_ms_lt=5"
  echo "threshold_p95_ms_lt=15"
  echo "threshold_p99_ms_lt=30"
  echo "threshold_error_rate_lt=0.001"
  echo "threshold_checks_rate_gt=0.999"
  echo "threshold_dropped_iterations_eq=0"
  shasum -a 256 "${JAR_PATH}"
} > "${OUTPUT_DIR}/manifest.txt"

case "${PROFILE}" in
  baseline)
    echo "override=none" > "${OUTPUT_DIR}/profile.txt"
    ;;
  redis-pool-disabled)
    echo "spring.data.redis.lettuce.pool.enabled=false" > "${OUTPUT_DIR}/profile.txt"
    ;;
  tomcat-min-spare32)
    echo "server.tomcat.threads.min-spare=32" > "${OUTPUT_DIR}/profile.txt"
    ;;
  redis-idle16)
    {
      echo "spring.data.redis.lettuce.pool.min-idle=16"
      echo "spring.data.redis.lettuce.pool.max-idle=16"
    } > "${OUTPUT_DIR}/profile.txt"
    ;;
esac

docker compose -f "${COMPOSE_FILE}" down -v --remove-orphans \
  > "${OUTPUT_DIR}/compose-cold-down.log" 2>&1
docker compose -f "${COMPOSE_FILE}" up -d --wait --wait-timeout 180 \
  > "${OUTPUT_DIR}/compose-up.log" 2>&1

if curl -fsS "${BASE_URL}/actuator/health/readiness" > /dev/null 2>&1; then
  echo "A service is already answering on ${BASE_URL}; refusing a contaminated run" >&2
  exit 1
fi

start_service() {
  case "${PROFILE}" in
    baseline)
      env -u SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED \
        -u SERVER_TOMCAT_THREADS_MIN_SPARE \
        -u SPRING_DATA_REDIS_LETTUCE_POOL_MIN_IDLE \
        -u SPRING_DATA_REDIS_LETTUCE_POOL_MAX_IDLE \
        -u SPRING_APPLICATION_JSON \
        -u SPRING_CONFIG_LOCATION \
        -u SPRING_CONFIG_ADDITIONAL_LOCATION \
        SERVER_PORT="${RISK_PORT}" REDIS_HOST=localhost REDIS_PORT=6390 \
        KAFKA_BOOTSTRAP=localhost:9094 java -jar "${JAR_PATH}" \
        > "${OUTPUT_DIR}/service.log" 2>&1 &
      ;;
    redis-pool-disabled)
      env -u SERVER_TOMCAT_THREADS_MIN_SPARE \
        -u SPRING_DATA_REDIS_LETTUCE_POOL_MIN_IDLE \
        -u SPRING_DATA_REDIS_LETTUCE_POOL_MAX_IDLE \
        -u SPRING_APPLICATION_JSON \
        -u SPRING_CONFIG_LOCATION \
        -u SPRING_CONFIG_ADDITIONAL_LOCATION \
        SERVER_PORT="${RISK_PORT}" REDIS_HOST=localhost REDIS_PORT=6390 \
        KAFKA_BOOTSTRAP=localhost:9094 \
        SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED=false \
        java -jar "${JAR_PATH}" > "${OUTPUT_DIR}/service.log" 2>&1 &
      ;;
    tomcat-min-spare32)
      env -u SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED \
        -u SPRING_DATA_REDIS_LETTUCE_POOL_MIN_IDLE \
        -u SPRING_DATA_REDIS_LETTUCE_POOL_MAX_IDLE \
        -u SPRING_APPLICATION_JSON \
        -u SPRING_CONFIG_LOCATION \
        -u SPRING_CONFIG_ADDITIONAL_LOCATION \
        SERVER_PORT="${RISK_PORT}" REDIS_HOST=localhost REDIS_PORT=6390 \
        KAFKA_BOOTSTRAP=localhost:9094 SERVER_TOMCAT_THREADS_MIN_SPARE=32 \
        java -jar "${JAR_PATH}" > "${OUTPUT_DIR}/service.log" 2>&1 &
      ;;
    redis-idle16)
      env -u SPRING_DATA_REDIS_LETTUCE_POOL_ENABLED \
        -u SERVER_TOMCAT_THREADS_MIN_SPARE \
        -u SPRING_APPLICATION_JSON \
        -u SPRING_CONFIG_LOCATION \
        -u SPRING_CONFIG_ADDITIONAL_LOCATION \
        SERVER_PORT="${RISK_PORT}" REDIS_HOST=localhost REDIS_PORT=6390 \
        KAFKA_BOOTSTRAP=localhost:9094 \
        SPRING_DATA_REDIS_LETTUCE_POOL_MIN_IDLE=16 \
        SPRING_DATA_REDIS_LETTUCE_POOL_MAX_IDLE=16 \
        java -jar "${JAR_PATH}" > "${OUTPUT_DIR}/service.log" 2>&1 &
      ;;
  esac
  SERVICE_PID=$!
}

start_service

readiness_deadline=$((SECONDS + 180))
until curl -fsS "${BASE_URL}/actuator/health/readiness" \
  > "${OUTPUT_DIR}/readiness.json" 2> /dev/null; do
  if ! kill -0 "${SERVICE_PID}" > /dev/null 2>&1; then
    echo "risk-service exited before readiness; see ${OUTPUT_DIR}/service.log" >&2
    exit 1
  fi
  if (( SECONDS >= readiness_deadline )); then
    echo "risk-service did not become ready within 180 seconds" >&2
    exit 1
  fi
  sleep 1
done

if ! jq -e '.status == "UP"' "${OUTPUT_DIR}/readiness.json" > /dev/null; then
  echo "Readiness endpoint did not report UP" >&2
  exit 1
fi

assignment_deadline=$((SECONDS + 60))
until docker exec risk-load-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --group risk.bet-placed-consumer \
  > "${OUTPUT_DIR}/consumer-group.txt" 2>&1 \
  && grep -Fq 'bet.placed.v1' "${OUTPUT_DIR}/consumer-group.txt"; do
  if (( SECONDS >= assignment_deadline )); then
    echo "risk-service consumer did not receive a bet.placed.v1 assignment" >&2
    exit 1
  fi
  sleep 1
done

if grep -Eiq 'UnknownHostException|UnknownHost|Error connecting to node risk-load-kafka:9092' \
  "${OUTPUT_DIR}/service.log"; then
  echo "Kafka listener metadata resolved to an unreachable internal hostname" >&2
  exit 1
fi

k6 run -e RISK_BASE_URL="${BASE_URL}" -e PHASE=warmup \
  --quiet --no-summary "${CHECK_SCRIPT}" \
  > "${OUTPUT_DIR}/warmup-k6.log" 2>&1

printf 'run\tstatus\tp50_ms\tp95_ms\tp99_ms\terror_rate\tchecks_rate\tdropped_iterations\n' \
  > "${OUTPUT_DIR}/gate.tsv"
gate_failed=0

for ((run = 1; run <= RUN_COUNT; run++)); do
  summary="${OUTPUT_DIR}/run-${run}-summary.json"
  log="${OUTPUT_DIR}/run-${run}-k6.log"
  k6_passed=0

  if k6 run -e RISK_BASE_URL="${BASE_URL}" -e PHASE=measure \
    --summary-export "${summary}" "${CHECK_SCRIPT}" 2>&1 | tee "${log}"; then
    k6_passed=1
  fi

  if [[ "${k6_passed}" == "1" && -f "${summary}" ]] \
    && jq -e '
      .metrics.http_req_duration["p(50)"] < 5 and
      .metrics.http_req_duration["p(95)"] < 15 and
      .metrics.http_req_duration["p(99)"] < 30 and
      .metrics.http_req_failed.value < 0.001 and
      .metrics.checks.value > 0.999 and
      ((.metrics.dropped_iterations.count // 0) == 0)
    ' "${summary}" > /dev/null; then
    status=PASS
  else
    status=FAIL
    gate_failed=1
  fi

  if [[ -f "${summary}" ]]; then
    jq -r --arg run "${run}" --arg status "${status}" '
      [
        $run,
        $status,
        .metrics.http_req_duration["p(50)"],
        .metrics.http_req_duration["p(95)"],
        .metrics.http_req_duration["p(99)"],
        .metrics.http_req_failed.value,
        .metrics.checks.value,
        (.metrics.dropped_iterations.count // 0)
      ] | @tsv
    ' "${summary}" >> "${OUTPUT_DIR}/gate.tsv"
  else
    printf '%s\t%s\tNA\tNA\tNA\tNA\tNA\tNA\n' "${run}" "${status}" \
      >> "${OUTPUT_DIR}/gate.tsv"
  fi
done

if grep -Eiq 'UnknownHostException|UnknownHost|Error connecting to node risk-load-kafka:9092' \
  "${OUTPUT_DIR}/service.log"; then
  echo "Kafka metadata errors appeared during the measured gate" >&2
  exit 1
fi

if [[ "${gate_failed}" != "0" ]]; then
  echo "One or more measured runs failed; evidence: ${OUTPUT_DIR}" >&2
  exit 1
fi

echo "All ${RUN_COUNT} measured runs passed; evidence: ${OUTPUT_DIR}"
