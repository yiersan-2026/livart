#!/bin/sh
set -eu

WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-120}"

wait_for_tcp() {
  host="$1"
  port="$2"
  name="$3"

  if [ -z "$host" ] || [ -z "$port" ]; then
    return 0
  fi

  echo "Waiting for ${name} at ${host}:${port}..."
  end_time=$(( $(date +%s) + WAIT_TIMEOUT_SECONDS ))
  while [ "$(date +%s)" -lt "$end_time" ]; do
    if nc -z "$host" "$port" >/dev/null 2>&1; then
      echo "${name} is reachable."
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for ${name} at ${host}:${port}." >&2
  return 1
}

wait_for_http() {
  url="$1"
  name="$2"

  if [ -z "$url" ]; then
    return 0
  fi

  echo "Waiting for ${name} at ${url}..."
  end_time=$(( $(date +%s) + WAIT_TIMEOUT_SECONDS ))
  while [ "$(date +%s)" -lt "$end_time" ]; do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "${name} is reachable."
      return 0
    fi
    sleep 2
  done

  echo "Timed out waiting for ${name} at ${url}." >&2
  return 1
}

wait_for_tcp "${DB_HOST:-}" "${DB_PORT:-5432}" "PostgreSQL"
wait_for_tcp "${RABBITMQ_HOST:-}" "${RABBITMQ_PORT:-5672}" "RabbitMQ"

if [ -n "${MINIO_ENDPOINT:-}" ]; then
  wait_for_http "$(printf '%s' "$MINIO_ENDPOINT" | sed 's#/*$##')/minio/health/live" "MinIO"
fi

exec java -jar /app/livart.jar
