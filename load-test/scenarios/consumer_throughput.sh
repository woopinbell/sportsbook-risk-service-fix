#!/usr/bin/env bash
# Kafka producer/broker acknowledgement probe.
#
# k6 has no first-party Kafka producer, so we use the Confluent kafka-producer-perf-test that
# ships inside the cp-kafka container instead. The script publishes a fixed number of records
# into an isolated probe topic at a configurable target rate and reports producer-to-broker
# acknowledgement throughput and latency.
#
# The payload is synthetic bytes, not a valid BetPlaced Avro record. Consequently this script
# does not exercise risk-service, poll consumer lag, or support a consumer-throughput claim.
# Consumer throughput requires an Avro-aware producer plus lag and final Redis-state checks.
#
# Usage:
#   ./scenarios/consumer_throughput.sh                # 10000 records at full speed
#   RATE=5000 RECORDS=50000 ./scenarios/consumer_throughput.sh
#
# Env:
#   RECORDS      total records to publish        (default 10000)
#   RATE         producer rate cap, recs/sec     (default -1, unlimited)
#   PAYLOAD_SIZE bytes per record                (default 256)
#   TOPIC        isolated probe topic             (default risk.producer-probe)
#   INTERNAL_BOOTSTRAP broker address in container (default risk-load-kafka:9092)

set -euo pipefail

RECORDS=${RECORDS:-10000}
RATE=${RATE:--1}
PAYLOAD_SIZE=${PAYLOAD_SIZE:-256}
TOPIC=${TOPIC:-risk.producer-probe}
# The perf-test runs inside the kafka container; use the INTERNAL listener.
INTERNAL_BOOTSTRAP=${INTERNAL_BOOTSTRAP:-risk-load-kafka:9092}

if ! docker ps --format '{{.Names}}' | grep -q '^risk-load-kafka$'; then
  echo "load-test docker stack must be up. Run: docker compose -f load-test/docker-compose.yml up -d" >&2
  exit 1
fi

echo "Producer/broker probe: ${RECORDS} records of ${PAYLOAD_SIZE}B to ${TOPIC} at rate=${RATE}"
docker exec risk-load-kafka kafka-producer-perf-test \
  --topic "${TOPIC}" \
  --num-records "${RECORDS}" \
  --record-size "${PAYLOAD_SIZE}" \
  --throughput "${RATE}" \
  --producer-props bootstrap.servers="${INTERNAL_BOOTSTRAP}" acks=all linger.ms=5 batch.size=65536
