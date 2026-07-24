#!/usr/bin/env bash

# Cold-started, repeatable performance gate for risk-service.
#
# Usage:
#   bash load-test/run-gate.sh baseline screen
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
KEEP_INFRA=${KEEP_INFRA:-0}
RISK_PORT=${RISK_PORT:-8083}
BASE_URL="http://localhost:${RISK_PORT}"
RUN_ID=${RUN_ID:-$(date '+%Y%m%d-%H%M%S')}
RESULT_ROOT=${RESULT_ROOT:-"${SCRIPT_DIR}/results/gate/${RUN_ID}"}
OUTPUT_DIR="${RESULT_ROOT}/${PROFILE}-${STAGE}/raw"
SERVICE_PID=''
INFRA_STARTED=0
KAFKA_TOPIC='bet.placed.v1'
KAFKA_GROUP='risk.bet-placed-consumer'
KAFKA_PRIMER_TOPIC='risk.gate.coordinator.prime'
KAFKA_PRIMER_GROUP='risk-gate-coordinator-primer'
KAFKA_METADATA_ERROR_PATTERN='UNKNOWN_TOPIC_OR_PARTITION|UnknownTopicOrPartition|NOT_COORDINATOR|NotCoordinatorException|UnknownHostException|UnknownHost|Error connecting to node (risk-load-kafka:9092|localhost:9094)'
EXPECTED_EVALSHA_PER_REQUEST=1
APPLICATION_COMMAND_METRIC='lettuce_command_completion_seconds_count'
EXPECTED_SOURCE_COMMIT=${EXPECTED_SOURCE_COMMIT:-}
EXPECTED_SOURCE_TREE=${EXPECTED_SOURCE_TREE:-}
EXPECTED_SHARED_SHA256=${EXPECTED_SHARED_SHA256:-}
MAVEN_REPO_LOCAL=${MAVEN_REPO_LOCAL:-}
SHARED_SOURCE_DIR=${SHARED_SOURCE_DIR:-}
EXPECTED_SHARED_SOURCE_COMMIT=${EXPECTED_SHARED_SOURCE_COMMIT:-}
SOURCE_COMMIT=$(git -C "${REPO_ROOT}" rev-parse HEAD)
SOURCE_TREE=$(git -C "${REPO_ROOT}" rev-parse 'HEAD^{tree}')

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

case "${KEEP_INFRA}" in
  0|1) ;;
  *)
    echo "KEEP_INFRA must be 0 or 1" >&2
    exit 2
    ;;
esac
if [[ "${STAGE}" == "final" && "${KEEP_INFRA}" != "0" ]]; then
  echo "KEEP_INFRA=1 is forbidden for the final release gate" >&2
  exit 2
fi

if [[ "${STAGE}" == "screen" ]]; then
  RUN_COUNT=3
else
  RUN_COUNT=5
fi

assert_source_binding() {
  local checkpoint=$1
  local actual_commit
  local actual_tree

  actual_commit=$(git -C "${REPO_ROOT}" rev-parse HEAD)
  actual_tree=$(git -C "${REPO_ROOT}" rev-parse 'HEAD^{tree}')
  if [[ "${actual_commit}" != "${SOURCE_COMMIT}" || "${actual_tree}" != "${SOURCE_TREE}" ]]; then
    echo "Source changed during the gate at ${checkpoint}" >&2
    return 1
  fi
  if [[ -n "$(git -C "${REPO_ROOT}" status --porcelain --untracked-files=all)" ]]; then
    echo "Source tree became dirty during the gate at ${checkpoint}" >&2
    return 1
  fi
}

assert_shared_binding() {
  local checkpoint=$1
  local actual_commit
  local actual_tree
  local installed_sha
  local source_jar_sha

  actual_commit=$(git -C "${SHARED_SOURCE_DIR}" rev-parse HEAD)
  actual_tree=$(git -C "${SHARED_SOURCE_DIR}" rev-parse 'HEAD^{tree}')
  if [[ "${actual_commit}" != "${SHARED_SOURCE_COMMIT}" \
    || "${actual_tree}" != "${SHARED_SOURCE_TREE}" ]]; then
    echo "Shared source changed during the gate at ${checkpoint}" >&2
    return 1
  fi
  if [[ -n "$(git -C "${SHARED_SOURCE_DIR}" status --porcelain --untracked-files=all)" ]]; then
    echo "Shared source tree became dirty during the gate at ${checkpoint}" >&2
    return 1
  fi
  if [[ ! -f "${MAVEN_SHARED_JAR}" || -L "${MAVEN_SHARED_JAR}" \
    || ! -f "${SHARED_SOURCE_JAR}" || -L "${SHARED_SOURCE_JAR}" ]]; then
    echo "Shared artifact disappeared or became a symlink at ${checkpoint}" >&2
    return 1
  fi
  installed_sha=$(shasum -a 256 "${MAVEN_SHARED_JAR}" | awk '{print $1}')
  source_jar_sha=$(shasum -a 256 "${SHARED_SOURCE_JAR}" | awk '{print $1}')
  if [[ "${installed_sha}" != "${EXPECTED_SHARED_SHA256}" \
    || "${source_jar_sha}" != "${EXPECTED_SHARED_SHA256}" ]]; then
    echo "Shared artifact binding changed during the gate at ${checkpoint}" >&2
    return 1
  fi
  if ! cmp -s "${SHARED_SOURCE_DIR}/pom.xml" "${MAVEN_SHARED_POM}"; then
    echo "Shared installed POM changed during the gate at ${checkpoint}" >&2
    return 1
  fi
}

record_host_snapshot() {
  local label=$1
  {
    echo "captured_at=$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "source_commit=${SOURCE_COMMIT}"
    echo "source_tree=${SOURCE_TREE}"
    uname -a
    uptime
    java -version
    k6 version
    docker version --format 'docker_client={{.Client.Version}} docker_server={{.Server.Version}}'
    docker info --format \
      'docker_cpus={{.NCPU}} docker_memory={{.MemTotal}} docker_driver={{.Driver}}'
    df -h "${REPO_ROOT}"
    if command -v sw_vers > /dev/null 2>&1; then
      sw_vers
    fi
    if command -v vm_stat > /dev/null 2>&1; then
      vm_stat
    fi
    docker stats --no-stream \
      --format 'container={{.Name}} cpu={{.CPUPerc}} memory={{.MemUsage}}' \
      risk-load-redis risk-load-kafka 2> /dev/null || true
    if [[ -n "${SERVICE_PID}" ]] && kill -0 "${SERVICE_PID}" > /dev/null 2>&1; then
      ps -p "${SERVICE_PID}" -o pid=,lstart=,%cpu=,%mem=,command=
    fi
  } > "${OUTPUT_DIR}/${label}-host.txt" 2>&1
}

record_redis_snapshot() {
  local label=$1
  redis-cli -h localhost -p 6390 --raw INFO commandstats \
    | tr -d '\r' > "${OUTPUT_DIR}/${label}-redis-commandstats.txt"
  redis-cli -h localhost -p 6390 --raw INFO stats \
    | tr -d '\r' > "${OUTPUT_DIR}/${label}-redis-stats.txt"
  redis-cli -h localhost -p 6390 --raw INFO clients \
    | tr -d '\r' > "${OUTPUT_DIR}/${label}-redis-clients.txt"
  redis-cli -h localhost -p 6390 --raw SLOWLOG LEN \
    > "${OUTPUT_DIR}/${label}-redis-slowlog-len.txt"
}

record_application_snapshot() {
  local label=$1
  local missing_policy=${2:-require}
  local snapshot="${OUTPUT_DIR}/${label}-application-prometheus.txt"

  curl -fsS "${BASE_URL}/actuator/prometheus" > "${snapshot}"
  if [[ "$(prometheus_counter_sum "${snapshot}" "${APPLICATION_COMMAND_METRIC}")" \
    == "MISSING" ]]; then
    if [[ "${missing_policy}" == "allow-zero-baseline" ]]; then
      echo "metric_family_absent=true zero_baseline=true" \
        > "${OUTPUT_DIR}/${label}-application-metric-baseline.txt"
      return 0
    fi
    echo "Application Lettuce command instrumentation is unavailable at ${label}" >&2
    return 1
  fi
}

record_kafka_assignment() {
  local label=$1

  docker exec risk-load-kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --describe --group "${KAFKA_GROUP}" --state \
    > "${OUTPUT_DIR}/${label}-consumer-state.txt" 2>&1 \
    && grep -Eq "${KAFKA_GROUP}.*Stable.*[[:space:]]1([[:space:]]|$)" \
      "${OUTPUT_DIR}/${label}-consumer-state.txt" \
    && docker exec risk-load-kafka kafka-consumer-groups \
      --bootstrap-server localhost:9092 \
      --describe --group "${KAFKA_GROUP}" --members --verbose \
      > "${OUTPUT_DIR}/${label}-consumer-members.txt" 2>&1 \
    && grep -Fq "${KAFKA_TOPIC}" \
      "${OUTPUT_DIR}/${label}-consumer-members.txt"
}

redis_command_field() {
  local snapshot=$1
  local command=$2
  local field=$3
  local value

  value=$(awk -F '[:,=]' -v command="cmdstat_${command}" -v field="${field}" '
    $1 == command {
      for (i = 2; i <= NF; i += 2) {
        if ($i == field) {
          print $(i + 1)
          exit
        }
      }
    }
  ' "${snapshot}")
  echo "${value:-0}"
}

prometheus_counter_sum() {
  local snapshot=$1
  local metric=$2
  local command=${3:-}

  awk -v metric="${metric}" -v command="${command}" '
    BEGIN {
      metric_found = 0
      total = 0
      command = toupper(command)
    }
    /^#/ { next }
    $1 == metric || index($1, metric "{") == 1 {
      metric_found = 1
      sample = toupper($1)
      if (command == "" || index(sample, "COMMAND=\"" command "\"") > 0) {
        total += $2
      }
    }
    END {
      if (!metric_found) {
        print "MISSING"
      } else {
        printf "%.0f\n", total
      }
    }
  ' "${snapshot}"
}

verify_snapshot_contract() {
  local phase=$1
  local summary=$2
  local redis_before=$3
  local redis_after=$4
  local application_before=$5
  local application_after=$6
  local http_reqs
  local iterations
  local evalsha_before
  local evalsha_after
  local eval_before
  local eval_after
  local failed_before
  local failed_after
  local evalsha_delta
  local eval_delta
  local failed_delta
  local client_total_before
  local client_total_after
  local client_evalsha_before
  local client_evalsha_after
  local client_total_delta
  local client_evalsha_delta
  local client_other_delta
  local expected
  local status=PASS

  if [[ ! -f "${summary}" ]]; then
    printf '%s\t0\t0\t0\t0\t0\t0\t0\t0\t0\tFAIL\n' "${phase}" \
      >> "${OUTPUT_DIR}/snapshot-contract.tsv"
    return 1
  fi

  http_reqs=$(jq -r '.metrics.http_reqs.count // 0' "${summary}")
  iterations=$(jq -r '.metrics.iterations.count // 0' "${summary}")
  evalsha_before=$(redis_command_field "${redis_before}" evalsha calls)
  evalsha_after=$(redis_command_field "${redis_after}" evalsha calls)
  eval_before=$(redis_command_field "${redis_before}" eval calls)
  eval_after=$(redis_command_field "${redis_after}" eval calls)
  failed_before=$(redis_command_field "${redis_before}" evalsha failed_calls)
  failed_after=$(redis_command_field "${redis_after}" evalsha failed_calls)
  client_total_before=$(prometheus_counter_sum \
    "${application_before}" "${APPLICATION_COMMAND_METRIC}")
  client_total_after=$(prometheus_counter_sum \
    "${application_after}" "${APPLICATION_COMMAND_METRIC}")
  client_evalsha_before=$(prometheus_counter_sum \
    "${application_before}" "${APPLICATION_COMMAND_METRIC}" EVALSHA)
  client_evalsha_after=$(prometheus_counter_sum \
    "${application_after}" "${APPLICATION_COMMAND_METRIC}" EVALSHA)
  evalsha_delta=$((evalsha_after - evalsha_before))
  eval_delta=$((eval_after - eval_before))
  failed_delta=$((failed_after - failed_before))
  expected=$((http_reqs * EXPECTED_EVALSHA_PER_REQUEST))

  if [[ "${phase}" == "warmup" \
    && "${client_total_before}" == "MISSING" \
    && "${client_evalsha_before}" == "MISSING" ]]; then
    client_total_before=0
    client_evalsha_before=0
  fi

  if [[ "${client_total_before}" == "MISSING" \
    || "${client_total_after}" == "MISSING" \
    || "${client_evalsha_before}" == "MISSING" \
    || "${client_evalsha_after}" == "MISSING" ]]; then
    status=FAIL
    client_total_delta=0
    client_evalsha_delta=0
    client_other_delta=0
  else
    client_total_delta=$((client_total_after - client_total_before))
    client_evalsha_delta=$((client_evalsha_after - client_evalsha_before))
    client_other_delta=$((client_total_delta - client_evalsha_delta))
  fi

  if (( http_reqs <= 0 \
    || iterations != http_reqs \
    || evalsha_delta != expected \
    || client_evalsha_delta != expected \
    || client_total_delta < client_evalsha_delta )); then
    status=FAIL
  fi
  if [[ "${phase}" != "warmup" ]] \
    && (( eval_delta != 0 || failed_delta != 0 || client_other_delta != 0 )); then
    status=FAIL
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "${phase}" "${http_reqs}" "${iterations}" "${evalsha_delta}" \
    "${client_evalsha_delta}" "${client_total_delta}" "${client_other_delta}" \
    "${expected}" "${eval_delta}" "${failed_delta}" "${status}" \
    >> "${OUTPUT_DIR}/snapshot-contract.tsv"
  [[ "${status}" == "PASS" ]]
}

write_evidence_checksums() {
  (
    cd "${OUTPUT_DIR}"
    find . -type f ! -name SHA256SUMS -print \
      | LC_ALL=C sort \
      | while IFS= read -r file; do
          shasum -a 256 "${file}"
        done
  ) > "${OUTPUT_DIR}/SHA256SUMS"
}

record_artifact_binding() {
  local jar_sha
  local jar_size
  local shared_entry
  local shared_count
  local shared_sha
  local artifact_count
  local script
  local source_script_sha
  local jar_script_sha

  assert_source_binding post-build
  git -C "${REPO_ROOT}" cat-file commit "${SOURCE_COMMIT}" \
    > "${OUTPUT_DIR}/source-commit.txt"
  git -C "${REPO_ROOT}" ls-tree -r "${SOURCE_COMMIT}" \
    > "${OUTPUT_DIR}/source-tree.txt"
  jar tf "${JAR_PATH}" > "${OUTPUT_DIR}/jar-contents.txt"
  unzip -p "${JAR_PATH}" META-INF/MANIFEST.MF \
    > "${OUTPUT_DIR}/jar-manifest.mf"

  if ! grep -E \
    '^BOOT-INF/classes/(com/sportsbook/risk/(counter|pattern|service|snapshot)/|scripts/).+' \
    "${OUTPUT_DIR}/jar-contents.txt" \
    > "${OUTPUT_DIR}/snapshot-artifact-entries.txt"; then
    echo "Measured jar contains no snapshot implementation artifacts" >&2
    return 1
  fi
  if ! grep -Fxq 'BOOT-INF/classes/scripts/risk-snapshot.lua' \
    "${OUTPUT_DIR}/snapshot-artifact-entries.txt" \
    || ! grep -Fxq \
      'BOOT-INF/classes/com/sportsbook/risk/snapshot/RedisRiskSnapshotReader.class' \
      "${OUTPUT_DIR}/snapshot-artifact-entries.txt" \
    || ! grep -Fxq 'BOOT-INF/classes/com/sportsbook/risk/service/RiskCheckService.class' \
      "${OUTPUT_DIR}/snapshot-artifact-entries.txt" \
    || ! grep -Fxq 'BOOT-INF/classes/com/sportsbook/risk/pattern/RuleEngine.class' \
      "${OUTPUT_DIR}/snapshot-artifact-entries.txt"; then
    echo "Measured jar is not the single-snapshot risk candidate" >&2
    return 1
  fi

  : > "${OUTPUT_DIR}/snapshot-artifact-sha256.txt"
  while IFS= read -r entry; do
    printf '%s  %s\n' \
      "$(unzip -p "${JAR_PATH}" "${entry}" | shasum -a 256 | awk '{print $1}')" \
      "${entry}" >> "${OUTPUT_DIR}/snapshot-artifact-sha256.txt"
  done < "${OUTPUT_DIR}/snapshot-artifact-entries.txt"

  printf 'source_path\tsource_sha256\tjar_entry\tjar_sha256\tstatus\n' \
    > "${OUTPUT_DIR}/snapshot-script-binding.tsv"
  local script="risk-snapshot.lua"
  source_script_sha=$(shasum -a 256 \
    "${REPO_ROOT}/src/main/resources/scripts/${script}" | awk '{print $1}')
  jar_script_sha=$(unzip -p "${JAR_PATH}" "BOOT-INF/classes/scripts/${script}" \
    | shasum -a 256 | awk '{print $1}')
  if [[ "${source_script_sha}" != "${jar_script_sha}" ]]; then
    printf '%s\t%s\t%s\t%s\tFAIL\n' \
      "src/main/resources/scripts/${script}" \
      "${source_script_sha}" \
      "BOOT-INF/classes/scripts/${script}" \
      "${jar_script_sha}" >> "${OUTPUT_DIR}/snapshot-script-binding.tsv"
    echo "Embedded ${script} does not match the measured source tree" >&2
    return 1
  fi
  printf '%s\t%s\t%s\t%s\tPASS\n' \
    "src/main/resources/scripts/${script}" \
    "${source_script_sha}" \
    "BOOT-INF/classes/scripts/${script}" \
    "${jar_script_sha}" >> "${OUTPUT_DIR}/snapshot-script-binding.tsv"

  shared_count=$(grep -Ec '^BOOT-INF/lib/shared-protocol-[^/]+\.jar$' \
    "${OUTPUT_DIR}/jar-contents.txt" || true)
  if [[ "${shared_count}" != "1" ]]; then
    echo "Expected exactly one embedded shared-protocol jar, got ${shared_count}" >&2
    return 1
  fi
  shared_entry=$(grep -E '^BOOT-INF/lib/shared-protocol-[^/]+\.jar$' \
    "${OUTPUT_DIR}/jar-contents.txt")
  shared_sha=$(unzip -p "${JAR_PATH}" "${shared_entry}" \
    | shasum -a 256 | awk '{print $1}')
  if [[ "${shared_sha}" != "${EXPECTED_SHARED_SHA256}" ]]; then
    echo "Embedded shared-protocol digest does not match EXPECTED_SHARED_SHA256" >&2
    return 1
  fi
  jar_sha=$(shasum -a 256 "${JAR_PATH}" | awk '{print $1}')
  jar_size=$(wc -c < "${JAR_PATH}" | tr -d ' ')
  artifact_count=$(wc -l < "${OUTPUT_DIR}/snapshot-artifact-entries.txt" | tr -d ' ')

  {
    echo "source_commit=${SOURCE_COMMIT}"
    echo "source_tree=${SOURCE_TREE}"
    echo "jar_path=${JAR_PATH}"
    echo "jar_sha256=${jar_sha}"
    echo "jar_size_bytes=${jar_size}"
    echo "shared_entry=${shared_entry}"
    echo "shared_sha256=${shared_sha}"
    echo "expected_shared_sha256=${EXPECTED_SHARED_SHA256}"
    echo "shared_source_dir=${SHARED_SOURCE_DIR}"
    echo "shared_source_commit=${SHARED_SOURCE_COMMIT}"
    echo "shared_source_tree=${SHARED_SOURCE_TREE}"
    echo "expected_shared_source_commit=${EXPECTED_SHARED_SOURCE_COMMIT}"
    echo "maven_repo_local=${MAVEN_REPO_LOCAL}"
    echo "maven_shared_jar=${MAVEN_SHARED_JAR}"
    echo "shared_source_jar=${SHARED_SOURCE_JAR}"
    echo "snapshot_artifact_count=${artifact_count}"
    echo "expected_evalsha_per_request=${EXPECTED_EVALSHA_PER_REQUEST}"
    shasum -a 256 \
      "${REPO_ROOT}/pom.xml" \
      "${REPO_ROOT}/mvnw" \
      "${REPO_ROOT}/.mvn/wrapper/maven-wrapper.properties"
  } > "${OUTPUT_DIR}/artifact-binding.txt"
}

for command in awk cmp cp curl docker find git grep jar java jq k6 nc redis-cli shasum tee tr unzip; do
  if ! command -v "${command}" > /dev/null 2>&1; then
    echo "Missing required command: ${command}" >&2
    exit 2
  fi
done

for variable in \
  EXPECTED_SOURCE_COMMIT \
  EXPECTED_SOURCE_TREE \
  EXPECTED_SHARED_SHA256 \
  MAVEN_REPO_LOCAL \
  SHARED_SOURCE_DIR \
  EXPECTED_SHARED_SOURCE_COMMIT; do
  if [[ -z "${!variable}" ]]; then
    echo "Missing mandatory gate input: ${variable}" >&2
    exit 2
  fi
done

if [[ ! "${EXPECTED_SHARED_SHA256}" =~ ^[[:xdigit:]]{64}$ ]]; then
  echo "EXPECTED_SHARED_SHA256 must be one complete SHA-256 digest" >&2
  exit 2
fi
EXPECTED_SHARED_SHA256=$(printf '%s' "${EXPECTED_SHARED_SHA256}" | tr '[:upper:]' '[:lower:]')

case "${MAVEN_REPO_LOCAL}" in
  /*) ;;
  *)
    echo "MAVEN_REPO_LOCAL must be an existing absolute directory" >&2
    exit 2
    ;;
esac
if [[ ! -d "${MAVEN_REPO_LOCAL}" ]]; then
  echo "MAVEN_REPO_LOCAL does not exist: ${MAVEN_REPO_LOCAL}" >&2
  exit 2
fi
MAVEN_REPO_LOCAL=$(cd "${MAVEN_REPO_LOCAL}" && pwd -P)
if [[ -n "${HOME:-}" ]]; then
  case "${MAVEN_REPO_LOCAL}" in
    "${HOME}/.m2"|"${HOME}/.m2/"*)
      echo "Global ~/.m2 is forbidden; use a run-specific Maven repository" >&2
      exit 2
      ;;
  esac
fi

case "${SHARED_SOURCE_DIR}" in
  /*) ;;
  *)
    echo "SHARED_SOURCE_DIR must be an existing absolute Git worktree" >&2
    exit 2
    ;;
esac
if [[ ! -d "${SHARED_SOURCE_DIR}" ]]; then
  echo "SHARED_SOURCE_DIR does not exist: ${SHARED_SOURCE_DIR}" >&2
  exit 2
fi
SHARED_SOURCE_DIR=$(cd "${SHARED_SOURCE_DIR}" && pwd -P)
if [[ "$(git -C "${SHARED_SOURCE_DIR}" rev-parse --show-toplevel 2> /dev/null)" \
  != "${SHARED_SOURCE_DIR}" ]]; then
  echo "SHARED_SOURCE_DIR must name the root of a shared-protocol worktree" >&2
  exit 2
fi
SHARED_SOURCE_COMMIT=$(git -C "${SHARED_SOURCE_DIR}" rev-parse HEAD)
SHARED_SOURCE_TREE=$(git -C "${SHARED_SOURCE_DIR}" rev-parse 'HEAD^{tree}')
if [[ "${SHARED_SOURCE_COMMIT}" != "${EXPECTED_SHARED_SOURCE_COMMIT}" ]]; then
  echo "Expected shared source commit ${EXPECTED_SHARED_SOURCE_COMMIT}, got ${SHARED_SOURCE_COMMIT}" >&2
  exit 2
fi
if [[ -n "$(git -C "${SHARED_SOURCE_DIR}" status --porcelain --untracked-files=all)" ]]; then
  echo "Refusing a shared-protocol worktree with source changes" >&2
  exit 2
fi

SHARED_VERSION='0.3.0'
MAVEN_SHARED_ROOT="${MAVEN_REPO_LOCAL}/com/sportsbook/shared-protocol"
MAVEN_SHARED_DIR="${MAVEN_SHARED_ROOT}/${SHARED_VERSION}"
MAVEN_SHARED_JAR="${MAVEN_SHARED_DIR}/shared-protocol-${SHARED_VERSION}.jar"
MAVEN_SHARED_POM="${MAVEN_SHARED_DIR}/shared-protocol-${SHARED_VERSION}.pom"
SHARED_SOURCE_JAR="${SHARED_SOURCE_DIR}/target/shared-protocol-${SHARED_VERSION}.jar"

if [[ ! -d "${MAVEN_SHARED_DIR}" ]]; then
  echo "Isolated Maven repository has no shared-protocol ${SHARED_VERSION}" >&2
  exit 2
fi
if [[ "$(cd "${MAVEN_SHARED_DIR}" && pwd -P)" != "${MAVEN_SHARED_DIR}" ]]; then
  echo "The shared-protocol path inside MAVEN_REPO_LOCAL must not traverse symlinks" >&2
  exit 2
fi
if [[ ! -d "${SHARED_SOURCE_DIR}/target" \
  || "$(cd "${SHARED_SOURCE_DIR}/target" && pwd -P)" != "${SHARED_SOURCE_DIR}/target" ]]; then
  echo "The shared-protocol source target directory is missing or symlinked" >&2
  exit 2
fi
unexpected_shared_version=$(find "${MAVEN_SHARED_ROOT}" -mindepth 1 -maxdepth 1 \
  -type d ! -name "${SHARED_VERSION}" -print -quit)
if [[ -n "${unexpected_shared_version}" ]]; then
  echo "Isolated Maven repository contains another shared-protocol version: ${unexpected_shared_version}" >&2
  exit 2
fi
for artifact in "${MAVEN_SHARED_JAR}" "${MAVEN_SHARED_POM}" "${SHARED_SOURCE_JAR}"; do
  if [[ ! -f "${artifact}" || -L "${artifact}" ]]; then
    echo "Missing or symlinked shared-protocol provenance artifact: ${artifact}" >&2
    exit 2
  fi
done
unexpected_shared_jar=$(find "${MAVEN_SHARED_DIR}" -maxdepth 1 -type f \
  -name 'shared-protocol-*.jar' \
  ! -name "shared-protocol-${SHARED_VERSION}.jar" \
  ! -name "shared-protocol-${SHARED_VERSION}-sources.jar" -print -quit)
if [[ -n "${unexpected_shared_jar}" ]]; then
  echo "Isolated Maven repository contains an unintended shared-protocol jar: ${unexpected_shared_jar}" >&2
  exit 2
fi
MAVEN_SHARED_SHA256=$(shasum -a 256 "${MAVEN_SHARED_JAR}" | awk '{print $1}')
SHARED_SOURCE_JAR_SHA256=$(shasum -a 256 "${SHARED_SOURCE_JAR}" | awk '{print $1}')
if [[ "${MAVEN_SHARED_SHA256}" != "${EXPECTED_SHARED_SHA256}" \
  || "${SHARED_SOURCE_JAR_SHA256}" != "${EXPECTED_SHARED_SHA256}" ]]; then
  echo "Expected shared digest does not bind both source build and isolated Maven artifact" >&2
  exit 2
fi
if ! cmp -s "${SHARED_SOURCE_DIR}/pom.xml" "${MAVEN_SHARED_POM}"; then
  echo "Installed shared-protocol POM differs from the exact shared source checkout" >&2
  exit 2
fi

if [[ -n "$(git -C "${REPO_ROOT}" status --porcelain --untracked-files=all)" ]]; then
  echo "Refusing to measure a dirty source tree" >&2
  exit 2
fi

if [[ "${SOURCE_COMMIT}" != "${EXPECTED_SOURCE_COMMIT}" ]]; then
  echo "Expected source commit ${EXPECTED_SOURCE_COMMIT}, got ${SOURCE_COMMIT}" >&2
  exit 2
fi
if [[ "${SOURCE_TREE}" != "${EXPECTED_SOURCE_TREE}" ]]; then
  echo "Expected source tree ${EXPECTED_SOURCE_TREE}, got ${SOURCE_TREE}" >&2
  exit 2
fi

if [[ -e "${OUTPUT_DIR}" ]]; then
  echo "Refusing to overwrite an existing evidence directory: ${OUTPUT_DIR}" >&2
  exit 2
fi
mkdir -p "${OUTPUT_DIR}"

cleanup() {
  local exit_code=$?
  local down_exit=0
  local volume
  local volume_residual=0
  trap - EXIT
  set +e
  if [[ -n "${SERVICE_PID}" ]] && kill -0 "${SERVICE_PID}" > /dev/null 2>&1; then
    kill "${SERVICE_PID}"
    wait "${SERVICE_PID}" > /dev/null 2>&1
  fi
  if [[ "${INFRA_STARTED}" == "1" ]]; then
    docker compose -f "${COMPOSE_FILE}" ps --all > "${OUTPUT_DIR}/compose-ps.txt" 2>&1
    docker compose -f "${COMPOSE_FILE}" logs --no-color > "${OUTPUT_DIR}/compose.log" 2>&1
    if [[ "${KEEP_INFRA}" != "1" ]]; then
      : > "${OUTPUT_DIR}/cleanup-volume-names.txt"
      for container in risk-load-redis risk-load-kafka; do
        docker inspect --format \
          '{{range .Mounts}}{{if eq .Type "volume"}}{{println .Name}}{{end}}{{end}}' \
          "${container}" >> "${OUTPUT_DIR}/cleanup-volume-names.txt" 2> /dev/null || true
      done
      docker compose -f "${COMPOSE_FILE}" down -v --remove-orphans \
        > "${OUTPUT_DIR}/compose-down.log" 2>&1
      down_exit=$?
      docker compose -f "${COMPOSE_FILE}" ps --all \
        > "${OUTPUT_DIR}/compose-ps-after-down.txt" 2>&1
      : > "${OUTPUT_DIR}/cleanup-residual-volumes.txt"
      while IFS= read -r volume; do
        if [[ -n "${volume}" ]] \
          && docker volume inspect "${volume}" > /dev/null 2>&1; then
          echo "${volume}" >> "${OUTPUT_DIR}/cleanup-residual-volumes.txt"
          volume_residual=1
        fi
      done < "${OUTPUT_DIR}/cleanup-volume-names.txt"
      if (( down_exit != 0 || volume_residual != 0 )) \
        || [[ -n "$(docker compose -f "${COMPOSE_FILE}" ps -aq)" ]] \
        || nc -z localhost "${RISK_PORT}" \
        || nc -z localhost 6390 \
        || nc -z localhost 9094; then
        echo "Risk load infrastructure remained after cleanup" \
          > "${OUTPUT_DIR}/cleanup-failure.txt"
        exit_code=1
      fi
    else
      echo "keep_infra=1 screen-only cleanup intentionally skipped" \
        > "${OUTPUT_DIR}/cleanup-skipped.txt"
    fi
  fi
  if ! assert_source_binding cleanup > "${OUTPUT_DIR}/source-binding-after.txt" 2>&1; then
    exit_code=1
  fi
  if ! assert_shared_binding cleanup > "${OUTPUT_DIR}/shared-binding-after.txt" 2>&1; then
    exit_code=1
  fi
  if [[ ! -f "${OUTPUT_DIR}/result.txt" ]]; then
    if (( exit_code == 0 )); then
      exit_code=1
    fi
    {
      echo "status=FAIL"
      echo "reason=preflight-build-or-runtime"
      echo "exit_code=${exit_code}"
      echo "source_commit=${SOURCE_COMMIT}"
      echo "source_tree=${SOURCE_TREE}"
      echo "expected_evalsha_per_request=${EXPECTED_EVALSHA_PER_REQUEST}"
      echo "keep_infra=${KEEP_INFRA}"
    } > "${OUTPUT_DIR}/result.txt"
  fi
  {
    if (( exit_code == 0 )); then
      echo "status=PASS"
    else
      echo "status=FAIL"
    fi
    echo "exit_code=${exit_code}"
    echo "source_commit=${SOURCE_COMMIT}"
    echo "source_tree=${SOURCE_TREE}"
    echo "keep_infra=${KEEP_INFRA}"
    echo "gate_result_sha256=$(shasum -a 256 "${OUTPUT_DIR}/result.txt" | awk '{print $1}')"
  } > "${OUTPUT_DIR}/overall-result.txt"
  if ! write_evidence_checksums; then
    exit_code=1
    {
      echo "status=FAIL"
      echo "exit_code=${exit_code}"
      echo "reason=checksum-generation"
      echo "source_commit=${SOURCE_COMMIT}"
      echo "source_tree=${SOURCE_TREE}"
      echo "keep_infra=${KEEP_INFRA}"
      echo "gate_result_sha256=$(shasum -a 256 "${OUTPUT_DIR}/result.txt" | awk '{print $1}')"
    } > "${OUTPUT_DIR}/overall-result.txt"
    write_evidence_checksums || true
  fi
  exit "${exit_code}"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

record_host_snapshot preflight
docker compose -f "${COMPOSE_FILE}" config \
  > "${OUTPUT_DIR}/compose-config.yml" 2> "${OUTPUT_DIR}/compose-config.err"

{
  echo "source_commit=${SOURCE_COMMIT}"
  echo "source_tree=${SOURCE_TREE}"
  echo "expected_source_commit=${EXPECTED_SOURCE_COMMIT}"
  echo "expected_source_tree=${EXPECTED_SOURCE_TREE}"
  echo "expected_evalsha_per_request=${EXPECTED_EVALSHA_PER_REQUEST}"
  echo "expected_shared_sha256=${EXPECTED_SHARED_SHA256}"
  echo "maven_repo_local=${MAVEN_REPO_LOCAL}"
  echo "maven_shared_jar=${MAVEN_SHARED_JAR}"
  echo "shared_source_dir=${SHARED_SOURCE_DIR}"
  echo "shared_source_commit=${SHARED_SOURCE_COMMIT}"
  echo "shared_source_tree=${SHARED_SOURCE_TREE}"
  echo "expected_shared_source_commit=${EXPECTED_SHARED_SOURCE_COMMIT}"
  echo "profile=${PROFILE}"
  echo "stage=${STAGE}"
  echo "keep_infra=${KEEP_INFRA}"
  echo "risk_port=${RISK_PORT}"
  echo "redis_port=6390"
  echo "kafka_host_port=9094"
} > "${OUTPUT_DIR}/preflight-contract.txt"

for port in "${RISK_PORT}" 6390 9094; do
  if nc -z localhost "${port}"; then
    echo "Host port ${port} is already in use; refusing a contaminated run" >&2
    exit 1
  fi
done

for container in risk-load-redis risk-load-kafka; do
  if [[ -n "$(docker ps -aq --filter "name=^/${container}$")" ]]; then
    echo "Container ${container} already exists; clean it explicitly before the gate" >&2
    exit 1
  fi
done

"${REPO_ROOT}/mvnw" -B -nsu -Dmaven.repo.local="${MAVEN_REPO_LOCAL}" clean verify \
  > "${OUTPUT_DIR}/maven-verify.log" 2>&1

if [[ -n "${HOME:-}" ]] \
  && grep -Fq "${HOME}/.m2/repository/com/sportsbook/shared-protocol" \
    "${OUTPUT_DIR}/maven-verify.log"; then
  echo "Maven resolved shared-protocol through global ~/.m2" >&2
  exit 1
fi
assert_shared_binding post-maven

SNAPSHOT_TEST_REPORT="${REPO_ROOT}/target/surefire-reports/TEST-com.sportsbook.risk.snapshot.RedisRiskSnapshotReaderTest.xml"
SERVICE_TEST_REPORT="${REPO_ROOT}/target/surefire-reports/TEST-com.sportsbook.risk.service.RiskCheckServiceTest.xml"
if [[ ! -f "${SNAPSHOT_TEST_REPORT}" || ! -f "${SERVICE_TEST_REPORT}" ]]; then
  echo "Maven verify did not produce the required snapshot test reports" >&2
  exit 1
fi
if ! grep -Fq 'name="approvedReadPathUsesExactlyOneSteadyStateEvalshaCall"' \
  "${SNAPSHOT_TEST_REPORT}" \
  || ! grep -Fq 'name="approvesWhenAllLimitsClearAndNoRulesFire"' \
    "${SERVICE_TEST_REPORT}"; then
  echo "Maven verify did not execute the approved single-snapshot request fixture" >&2
  exit 1
fi
cp "${SNAPSHOT_TEST_REPORT}" "${OUTPUT_DIR}/snapshot-reader-test-report.xml"
cp "${SERVICE_TEST_REPORT}" "${OUTPUT_DIR}/risk-check-service-test-report.xml"

JAR_PATH="${REPO_ROOT}/target/risk-service-0.1.0-SNAPSHOT.jar"
if [[ ! -f "${JAR_PATH}" ]]; then
  echo "Expected jar was not built: ${JAR_PATH}" >&2
  exit 1
fi

record_artifact_binding

{
  echo "source_commit=${SOURCE_COMMIT}"
  echo "source_tree=${SOURCE_TREE}"
  echo "working_tree_clean=true"
  echo "profile=${PROFILE}"
  echo "stage=${STAGE}"
  echo "keep_infra=${KEEP_INFRA}"
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
  echo "expected_evalsha_per_request=${EXPECTED_EVALSHA_PER_REQUEST}"
  echo "application_command_metric=${APPLICATION_COMMAND_METRIC}"
  echo "expected_shared_sha256=${EXPECTED_SHARED_SHA256}"
  echo "maven_repo_local=${MAVEN_REPO_LOCAL}"
  echo "shared_source_dir=${SHARED_SOURCE_DIR}"
  echo "shared_source_commit=${SHARED_SOURCE_COMMIT}"
  echo "shared_source_tree=${SHARED_SOURCE_TREE}"
  echo "expected_shared_source_commit=${EXPECTED_SHARED_SOURCE_COMMIT}"
  echo "artifact_binding_sha256=$(shasum -a 256 "${OUTPUT_DIR}/artifact-binding.txt" \
    | awk '{print $1}')"
  shasum -a 256 \
    "${JAR_PATH}" \
    "${BASH_SOURCE[0]}" \
    "${CHECK_SCRIPT}" \
    "${COMPOSE_FILE}" \
    "${REPO_ROOT}/pom.xml"
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
INFRA_STARTED=1
docker compose -f "${COMPOSE_FILE}" up -d --wait --wait-timeout 180 \
  > "${OUTPUT_DIR}/compose-up.log" 2>&1

if curl -fsS "${BASE_URL}/actuator/health/readiness" > /dev/null 2>&1; then
  echo "A service is already answering on ${BASE_URL}; refusing a contaminated run" >&2
  exit 1
fi

if ! nc -z localhost 6390 || ! nc -z localhost 9094; then
  echo "Redis or Kafka HOST port is unavailable after Compose readiness" >&2
  exit 1
fi
if [[ "$(redis-cli -h localhost -p 6390 --raw PING)" != "PONG" ]]; then
  echo "Redis HOST listener did not answer PONG" >&2
  exit 1
fi
redis-cli -h localhost -p 6390 --raw INFO cluster \
  | tr -d '\r' > "${OUTPUT_DIR}/redis-cluster.txt"
if ! grep -Fxq 'cluster_enabled:0' "${OUTPUT_DIR}/redis-cluster.txt"; then
  echo "Risk release evidence requires standalone Redis (cluster_enabled:0)" >&2
  exit 1
fi

docker exec risk-load-kafka kafka-broker-api-versions \
  --bootstrap-server localhost:9092 \
  > "${OUTPUT_DIR}/kafka-internal-api-versions.txt" 2>&1
docker exec risk-load-kafka kafka-broker-api-versions \
  --bootstrap-server host.docker.internal:9094 \
  > "${OUTPUT_DIR}/kafka-host-api-versions.txt" 2>&1
docker inspect risk-load-redis risk-load-kafka \
  > "${OUTPUT_DIR}/compose-container-inspect.json"

docker exec risk-load-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic "${KAFKA_TOPIC}" \
  --partitions 1 \
  --replication-factor 1 \
  > "${OUTPUT_DIR}/topic-create.txt" 2>&1

topic_deadline=$((SECONDS + 60))
until docker exec risk-load-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --describe --topic "${KAFKA_TOPIC}" \
  > "${OUTPUT_DIR}/topic-describe.txt" 2>&1 \
  && grep -Fq "Topic: ${KAFKA_TOPIC}" "${OUTPUT_DIR}/topic-describe.txt" \
  && grep -Eq 'Leader: [0-9]+' "${OUTPUT_DIR}/topic-describe.txt"; do
  if (( SECONDS >= topic_deadline )); then
    echo "Kafka topic ${KAFKA_TOPIC} did not obtain a leader within 60 seconds" >&2
    exit 1
  fi
  sleep 1
done

docker exec risk-load-kafka kafka-topics \
  --bootstrap-server host.docker.internal:9094 \
  --describe --topic "${KAFKA_TOPIC}" \
  > "${OUTPUT_DIR}/topic-describe-through-host-listener.txt" 2>&1
if ! grep -Fq "Topic: ${KAFKA_TOPIC}" \
  "${OUTPUT_DIR}/topic-describe-through-host-listener.txt" \
  || ! grep -Eq 'Leader: [0-9]+' \
    "${OUTPUT_DIR}/topic-describe-through-host-listener.txt"; then
  echo "Kafka HOST listener did not return usable topic metadata" >&2
  exit 1
fi

docker exec risk-load-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --create --if-not-exists \
  --topic "${KAFKA_PRIMER_TOPIC}" \
  --partitions 1 \
  --replication-factor 1 \
  > "${OUTPUT_DIR}/coordinator-primer-topic.txt" 2>&1

printf 'ready\n' | docker exec -i risk-load-kafka kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic "${KAFKA_PRIMER_TOPIC}" \
  > "${OUTPUT_DIR}/coordinator-primer-produce.txt" 2>&1

docker exec risk-load-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic "${KAFKA_PRIMER_TOPIC}" \
  --group "${KAFKA_PRIMER_GROUP}" \
  --from-beginning \
  --max-messages 1 \
  --timeout-ms 30000 \
  > "${OUTPUT_DIR}/coordinator-primer-consume.txt" 2>&1

coordinator_deadline=$((SECONDS + 60))
until docker exec risk-load-kafka kafka-topics \
  --bootstrap-server localhost:9092 \
  --describe --topic __consumer_offsets \
  > "${OUTPUT_DIR}/offsets-topic-describe.txt" 2>&1 \
  && grep -Fq 'Topic: __consumer_offsets' \
    "${OUTPUT_DIR}/offsets-topic-describe.txt" \
  && ! grep -Eq 'Leader: -1|Leader: none' \
    "${OUTPUT_DIR}/offsets-topic-describe.txt" \
  && docker exec risk-load-kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --describe --group "${KAFKA_PRIMER_GROUP}" \
    > "${OUTPUT_DIR}/coordinator-probe.txt" 2>&1 \
  && grep -Fq "${KAFKA_PRIMER_TOPIC}" \
    "${OUTPUT_DIR}/coordinator-probe.txt"; do
  if (( SECONDS >= coordinator_deadline )); then
    echo "Kafka consumer-group coordinator did not become ready within 60 seconds" >&2
    exit 1
  fi
  sleep 1
done

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
until record_kafka_assignment consumer-group; do
  if (( SECONDS >= assignment_deadline )); then
    echo "risk-service consumer did not receive a bet.placed.v1 assignment" >&2
    exit 1
  fi
  sleep 1
done

if grep -Eiq "${KAFKA_METADATA_ERROR_PATTERN}" "${OUTPUT_DIR}/service.log"; then
  grep -Ein "${KAFKA_METADATA_ERROR_PATTERN}" "${OUTPUT_DIR}/service.log" \
    > "${OUTPUT_DIR}/kafka-metadata-errors.txt" || true
  echo "Kafka metadata or coordinator errors appeared before measurement" >&2
  exit 1
fi

printf 'phase\thttp_reqs\titerations\tserver_evalsha_calls\tclient_evalsha_calls\tclient_total_commands\tclient_other_commands\texpected_evalsha_calls\tserver_eval_calls\tserver_evalsha_failed_calls\tstatus\n' \
  > "${OUTPUT_DIR}/snapshot-contract.tsv"
assert_source_binding before-warmup
record_host_snapshot warmup-before
record_redis_snapshot warmup-before
record_application_snapshot warmup-before allow-zero-baseline
if ! record_kafka_assignment warmup-before; then
  echo "Kafka consumer lost its stable assignment before warm-up" >&2
  exit 1
fi

warmup_summary="${OUTPUT_DIR}/warmup-summary.json"
k6 run -e RISK_BASE_URL="${BASE_URL}" -e PHASE=warmup \
  --quiet --summary-export "${warmup_summary}" "${CHECK_SCRIPT}" \
  > "${OUTPUT_DIR}/warmup-k6.log" 2>&1
record_redis_snapshot warmup-after
record_host_snapshot warmup-after
record_application_snapshot warmup-after
if ! record_kafka_assignment warmup-after; then
  echo "Kafka consumer lost its stable assignment during warm-up" >&2
  exit 1
fi
if ! verify_snapshot_contract \
  warmup \
  "${warmup_summary}" \
  "${OUTPUT_DIR}/warmup-before-redis-commandstats.txt" \
  "${OUTPUT_DIR}/warmup-after-redis-commandstats.txt" \
  "${OUTPUT_DIR}/warmup-before-application-prometheus.txt" \
  "${OUTPUT_DIR}/warmup-after-application-prometheus.txt"; then
  echo "Warm-up did not execute exactly one client/server EVALSHA command per request" >&2
  exit 1
fi
if ! jq -e '
  .metrics.http_reqs.count > 0 and
  .metrics.http_req_failed.value < 0.001 and
  .metrics.checks.value > 0.999
' "${warmup_summary}" > /dev/null; then
  echo "Warm-up produced no requests or failed HTTP/check correctness" >&2
  exit 1
fi

printf 'run\tstatus\tp50_ms\tp95_ms\tp99_ms\terror_rate\tchecks_rate\tdropped_iterations\tsnapshot_contract\tkafka_assignment\n' \
  > "${OUTPUT_DIR}/gate.tsv"
gate_failed=0

for ((run = 1; run <= RUN_COUNT; run++)); do
  summary="${OUTPUT_DIR}/run-${run}-summary.json"
  log="${OUTPUT_DIR}/run-${run}-k6.log"
  k6_passed=0
  snapshot_passed=0
  kafka_passed=0

  assert_source_binding "before-run-${run}"
  record_host_snapshot "run-${run}-before"
  record_redis_snapshot "run-${run}-before"
  record_application_snapshot "run-${run}-before"
  if ! record_kafka_assignment "run-${run}-before"; then
    echo "Kafka consumer lost its stable assignment before run ${run}" >&2
    exit 1
  fi

  if k6 run -e RISK_BASE_URL="${BASE_URL}" -e PHASE=measure \
    --summary-export "${summary}" "${CHECK_SCRIPT}" 2>&1 | tee "${log}"; then
    k6_passed=1
  fi

  record_redis_snapshot "run-${run}-after"
  record_host_snapshot "run-${run}-after"
  record_application_snapshot "run-${run}-after"
  if record_kafka_assignment "run-${run}-after"; then
    kafka_passed=1
  fi
  if verify_snapshot_contract \
    "run-${run}" \
    "${summary}" \
    "${OUTPUT_DIR}/run-${run}-before-redis-commandstats.txt" \
    "${OUTPUT_DIR}/run-${run}-after-redis-commandstats.txt" \
    "${OUTPUT_DIR}/run-${run}-before-application-prometheus.txt" \
    "${OUTPUT_DIR}/run-${run}-after-application-prometheus.txt"; then
    snapshot_passed=1
  fi

  if [[ "${k6_passed}" == "1" \
    && "${snapshot_passed}" == "1" \
    && "${kafka_passed}" == "1" \
    && -f "${summary}" ]] \
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
    jq -r \
      --arg run "${run}" \
      --arg status "${status}" \
      --arg snapshot "${snapshot_passed}" \
      --arg kafka "${kafka_passed}" '
      [
        $run,
        $status,
        .metrics.http_req_duration["p(50)"],
        .metrics.http_req_duration["p(95)"],
        .metrics.http_req_duration["p(99)"],
        .metrics.http_req_failed.value,
        .metrics.checks.value,
        (.metrics.dropped_iterations.count // 0),
        $snapshot,
        $kafka
      ] | @tsv
    ' "${summary}" >> "${OUTPUT_DIR}/gate.tsv"
  else
    printf '%s\t%s\tNA\tNA\tNA\tNA\tNA\tNA\t%s\t%s\n' \
      "${run}" "${status}" "${snapshot_passed}" "${kafka_passed}" \
      >> "${OUTPUT_DIR}/gate.tsv"
  fi
  assert_source_binding "after-run-${run}"
done

if grep -Eiq "${KAFKA_METADATA_ERROR_PATTERN}" "${OUTPUT_DIR}/service.log"; then
  grep -Ein "${KAFKA_METADATA_ERROR_PATTERN}" "${OUTPUT_DIR}/service.log" \
    > "${OUTPUT_DIR}/kafka-metadata-errors.txt" || true
  echo "Kafka metadata errors appeared during the measured gate" >&2
  exit 1
fi

if [[ "${gate_failed}" != "0" ]]; then
  {
    echo "status=FAIL"
    echo "reason=latency-snapshot-or-kafka-contract"
    echo "source_commit=${SOURCE_COMMIT}"
    echo "source_tree=${SOURCE_TREE}"
    echo "expected_evalsha_per_request=${EXPECTED_EVALSHA_PER_REQUEST}"
    echo "keep_infra=${KEEP_INFRA}"
  } > "${OUTPUT_DIR}/result.txt"
  echo "One or more measured runs failed; evidence: ${OUTPUT_DIR}" >&2
  exit 1
fi

assert_source_binding final
{
  echo "status=PASS"
  echo "measured_runs=${RUN_COUNT}"
  echo "source_commit=${SOURCE_COMMIT}"
  echo "source_tree=${SOURCE_TREE}"
  echo "expected_evalsha_per_request=${EXPECTED_EVALSHA_PER_REQUEST}"
  echo "keep_infra=${KEEP_INFRA}"
} > "${OUTPUT_DIR}/result.txt"
echo "All ${RUN_COUNT} measured runs passed; evidence: ${OUTPUT_DIR}"
