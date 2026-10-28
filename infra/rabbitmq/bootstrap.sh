#!/bin/sh
set -eu
rabbitmq-server &
broker_pid=$!
trap 'kill -TERM "$broker_pid"; wait "$broker_pid"' TERM INT
attempt=0
until rabbitmq-diagnostics -q ping >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ] || ! kill -0 "$broker_pid" 2>/dev/null; then
    kill -TERM "$broker_pid" 2>/dev/null || true
    exit 1
  fi
  sleep 2
done
if ! rabbitmqctl import_definitions /etc/rabbitmq/definitions.json; then
  kill -TERM "$broker_pid"
  wait "$broker_pid"
  exit 1
fi
wait "$broker_pid"
