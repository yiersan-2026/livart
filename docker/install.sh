#!/bin/sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
MODE="${1:-auto}"

log() {
  printf '%s\n' "$*"
}

die() {
  printf '%s\n' "$*" >&2
  exit 1
}

compose() {
  if docker compose version >/dev/null 2>&1; then
    docker compose "$@"
    return
  fi

  if command -v docker-compose >/dev/null 2>&1; then
    docker-compose "$@"
    return
  fi

  die "没有找到 docker compose，请先安装 Docker Desktop 或 Docker Compose。"
}

ensure_docker_ready() {
  if ! command -v docker >/dev/null 2>&1; then
    die "没有找到 docker，请先安装并启动 Docker Desktop。"
  fi

  if ! docker info >/dev/null 2>&1; then
    die "Docker 还没有启动，请打开 Docker Desktop 后再试。"
  fi
}

running_containers() {
  docker ps --format '{{.Image}} {{.Names}} {{.Ports}}' 2>/dev/null || true
}

has_running_service() {
  pattern="$1"
  running_containers | grep -Eiq "$pattern"
}

has_existing_middlewares() {
  has_running_service '(^|[ /:])postgres|postgres:|->5432/tcp|5432/tcp' \
    && has_running_service '(^|[ /:])rabbitmq|rabbitmq:|->5672/tcp|5672/tcp' \
    && has_running_service '(^|[ /:])minio|minio/minio|->9000/tcp'
}

env_value() {
  key="$1"
  value="$(printenv "$key" 2>/dev/null || true)"

  if [ -z "$value" ] && [ -f .env ]; then
    value="$(awk -F= -v key="$key" '
      $0 !~ /^[[:space:]]*#/ && $1 == key {
        sub(/^[^=]*=/, "")
        gsub(/^["'\'']|["'\'']$/, "")
        print
        exit
      }
    ' .env)"
  fi

  printf '%s' "$value"
}

has_real_value() {
  key="$1"
  value="$(env_value "$key")"

  [ -n "$value" ] \
    && ! printf '%s' "$value" | grep -Eiq 'replace-with|change_me|change-me|your-|example'
}

has_reuse_config() {
  has_real_value DB_PASSWORD \
    && has_real_value RABBITMQ_PASSWORD \
    && has_real_value MINIO_ACCESS_KEY \
    && has_real_value MINIO_SECRET_KEY \
    && has_real_value JWT_SECRET
}

ensure_reuse_env_hint() {
  if [ ! -f .env ]; then
    cp .env.reuse.example .env
    log "已创建 .env（来自 .env.reuse.example）。"
  fi

  die "检测到已有 PostgreSQL / RabbitMQ / MinIO，但复用所需口令还没配置完整。请补全 .env 后运行：./docker/install.sh reuse"
}

start_full_stack() {
  log "使用完整模式：启动 livart + PostgreSQL + RabbitMQ + MinIO。"
  compose up -d --build
}

start_reuse_stack() {
  log "使用复用模式：只启动 livart，复用你 Docker 里已有的 PostgreSQL / RabbitMQ / MinIO。"
  compose -f docker-compose.reuse.yml --env-file .env up -d --build
}

cd "$ROOT_DIR"
ensure_docker_ready

case "$MODE" in
  auto)
    if has_existing_middlewares; then
      if has_reuse_config; then
        start_reuse_stack
      else
        ensure_reuse_env_hint
      fi
    else
      start_full_stack
    fi
    ;;
  full)
    start_full_stack
    ;;
  reuse)
    if ! has_reuse_config; then
      ensure_reuse_env_hint
    fi
    start_reuse_stack
    ;;
  *)
    die "用法：./docker/install.sh [auto|full|reuse]"
    ;;
esac

app_port="$(env_value LIVART_PORT)"
[ -n "$app_port" ] || app_port=8080

log "启动命令已执行。稍等片刻后访问：http://localhost:${app_port}"
