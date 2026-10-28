#!/bin/sh
set -eu
rabbitmq-server &
broker_pid=$!
trap 'kill -TERM "$broker_pid"; wait "$broker_pid"' TERM INT
if ! rabbitmqctl await_startup --timeout 120; then
  kill -TERM "$broker_pid"
  wait "$broker_pid"
  exit 1
fi
if ! rabbitmqctl import_definitions /etc/rabbitmq/definitions.json; then
  kill -TERM "$broker_pid"
  wait "$broker_pid"
  exit 1
fi
wait "$broker_pid"
