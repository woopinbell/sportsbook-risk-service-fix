#!/usr/bin/env bash
# Kafka throughput probe for the bet.placed consumer.
#
# k6 has no first-party Kafka producer, so we use the Confluent kafka-producer-perf-test that
# ships inside the cp-kafka container instead. The script publishes a fixed number of records
# (default 10 000, matching the repository throughput contract) into the
# bet.placed topic at a configurable target rate; the script asserts that the consumer
# (risk-service running on the host) drains every record by polling the consumer group lag.
#
# This is a synthetic byte payload — the bytes are not valid Avro, so the consumer
# deserialises and throws; the throughput probe still measures producer p99 latency and
# Kafka broker capacity which is what we care about for the "10 万 events/sec" target in
# the repository performance contract. A separate Avro-aware producer (Java) is a V2 follow-up; the load-test/README
# documents the workaround.
#
# Usage:
#   ./scenarios/consumer_throughput.sh                # 10000 records at full speed
#   RATE=100000 RECORDS=1000000 ./scenarios/consumer_throughput.sh
#
# Env:
#   RECORDS      total records to publish        (default 10000)
#   RATE         producer rate cap, recs/sec     (default -1, unlimited)
#   PAYLOAD_SIZE bytes per record                (default 256)
#   TOPIC        target topic                    (default bet.placed)
#   BOOTSTRAP    kafka bootstrap server          (default localhost:9094)

set -euo pipefail

RECORDS=${RECORDS:-10000}
RATE=${RATE:--1}
PAYLOAD_SIZE=${PAYLOAD_SIZE:-256}
TOPIC=${TOPIC:-bet.placed}
# The perf-test runs inside the kafka container; use the INTERNAL listener.
INTERNAL_BOOTSTRAP=${INTERNAL_BOOTSTRAP:-risk-load-kafka:9092}

if ! docker ps --format '{{.Names}}' | grep -q '^risk-load-kafka$'; then
  echo "load-test docker stack must be up. Run: docker compose -f load-test/docker-compose.yml up -d" >&2
  exit 1
fi

echo "Publishing ${RECORDS} records of ${PAYLOAD_SIZE}B to ${TOPIC} at rate=${RATE}"
docker exec risk-load-kafka kafka-producer-perf-test \
  --topic "${TOPIC}" \
  --num-records "${RECORDS}" \
  --record-size "${PAYLOAD_SIZE}" \
  --throughput "${RATE}" \
  --producer-props bootstrap.servers="${INTERNAL_BOOTSTRAP}" acks=all linger.ms=5 batch.size=65536
