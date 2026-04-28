#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MEMORY_FILE="${LIVART_MEMORY_FILE:-$HOME/.codex/MEMORY.md}"
LOG_DIR="${LIVART_DEV_LOG_DIR:-$ROOT_DIR/.codex-run/dev}"
BACKEND_PORT="${LIVART_BACKEND_PORT:-8080}"
FRONTEND_PORT="${LIVART_FRONTEND_PORT:-3000}"
FRONTEND_HOST="${LIVART_FRONTEND_HOST:-0.0.0.0}"

mkdir -p "$LOG_DIR"

load_env_file() {
  local env_file="$1"
  if [[ ! -f "$env_file" ]]; then
    return 0
  fi

  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
}

read_memory_field() {
  local heading="$1"
  local key="$2"
  if [[ ! -f "$MEMORY_FILE" ]]; then
    return 1
  fi
  python3 - "$MEMORY_FILE" "$heading" "$key" <<'PY'
from pathlib import Path
import re
import sys

memory_path, heading, key = sys.argv[1], sys.argv[2], sys.argv[3]
text = Path(memory_path).read_text(errors="ignore")
if heading not in text:
    raise SystemExit(1)
section = text.split(heading, 1)[1]
end_positions = [pos for pos in (section.find("\n### "), section.find("\n## ")) if pos >= 0]
if end_positions:
    section = section[:min(end_positions)]
pattern = re.compile(r"^-\s*" + re.escape(key) + r":\s*`?([^`\n]+)`?\s*$", re.M)
match = pattern.search(section)
if not match:
    raise SystemExit(2)
print(match.group(1).strip())
PY
}

read_any_memory_field() {
  local key="$1"
  if [[ ! -f "$MEMORY_FILE" ]]; then
    return 1
  fi
  python3 - "$MEMORY_FILE" "$key" <<'PY'
from pathlib import Path
import re
import sys

memory_path, key = sys.argv[1], sys.argv[2]
text = Path(memory_path).read_text(errors="ignore")
pattern = re.compile(r"^-\s*" + re.escape(key) + r":\s*`?([^`\n]+)`?\s*$", re.M)
match = pattern.search(text)
if not match:
    raise SystemExit(2)
print(match.group(1).strip())
PY
}

optional_memory_field() {
  read_memory_field "$1" "$2" 2>/dev/null || true
}

optional_any_memory_field() {
  read_any_memory_field "$1" 2>/dev/null || true
}

stop_listening_port() {
  local port="$1"
  local pids
  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -z "$pids" ]]; then
    return 0
  fi

  echo "停止端口 $port: $pids"
  kill $pids 2>/dev/null || true
  sleep 2

  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [[ -n "$pids" ]]; then
    echo "强制停止端口 $port: $pids"
    kill -9 $pids 2>/dev/null || true
  fi
}

wait_for_backend() {
  for second in $(seq 1 90); do
    if curl -fsS "http://localhost:$BACKEND_PORT/api/health" >/dev/null 2>&1; then
      echo "后端已启动：http://localhost:$BACKEND_PORT"
      return 0
    fi
    if grep -q "APPLICATION FAILED TO START\|BUILD FAILURE" "$LOG_DIR/backend.log" 2>/dev/null; then
      echo "后端启动失败，最近日志：" >&2
      tail -160 "$LOG_DIR/backend.log" >&2
      return 1
    fi
    sleep 1
    if (( second % 10 == 0 )); then
      echo "等待后端启动中... ${second}s"
    fi
  done

  echo "后端 90 秒内未启动，最近日志：" >&2
  tail -180 "$LOG_DIR/backend.log" >&2
  return 1
}

wait_for_frontend() {
  for second in $(seq 1 30); do
    if lsof -tiTCP:"$FRONTEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "前端已启动：http://localhost:$FRONTEND_PORT"
      return 0
    fi
    sleep 1
  done

  echo "前端 30 秒内未监听端口，最近日志：" >&2
  tail -120 "$LOG_DIR/frontend.log" >&2
  return 1
}

if ! command -v mvn >/dev/null 2>&1; then
  echo "找不到 mvn，请先安装 Maven 或配置 PATH。" >&2
  exit 1
fi

if ! command -v npm >/dev/null 2>&1; then
  echo "找不到 npm，请先安装 Node.js/npm 或配置 PATH。" >&2
  exit 1
fi

echo "重启 livart 开发环境..."
echo "日志目录：$LOG_DIR"

load_env_file "$ROOT_DIR/.env"
load_env_file "$ROOT_DIR/backend/.env"
load_env_file "$ROOT_DIR/frontend/.env.local"

stop_listening_port "$FRONTEND_PORT"
stop_listening_port "$BACKEND_PORT"

MEMORY_DB_HOST="$(optional_memory_field '## 开发环境：PostgreSQL 本地连接' 'Host')"
MEMORY_DB_PORT="$(optional_memory_field '## 开发环境：PostgreSQL 本地连接' 'Port')"
MEMORY_DB_NAME="$(optional_memory_field '## 开发环境：PostgreSQL 本地连接' 'Database')"
MEMORY_DB_USER="$(optional_memory_field '## 开发环境：PostgreSQL 本地连接' 'User')"
MEMORY_DB_PASSWORD="$(optional_memory_field '## 开发环境：PostgreSQL 本地连接' 'Password')"
MEMORY_RABBITMQ_ADDRESS="$(optional_memory_field '### RabbitMQ' 'AMQP Address')"
MEMORY_RABBITMQ_HOST="${MEMORY_RABBITMQ_ADDRESS%%:*}"
MEMORY_RABBITMQ_PORT="${MEMORY_RABBITMQ_ADDRESS##*:}"

DB_HOST_VALUE="${DB_HOST:-${POSTGRES_HOST:-${MEMORY_DB_HOST:-localhost}}}"
DB_PORT_VALUE="${DB_PORT:-${POSTGRES_PORT:-${MEMORY_DB_PORT:-5432}}}"
DB_NAME_VALUE="${DB_NAME:-${POSTGRES_DB:-${MEMORY_DB_NAME:-app}}}"
DB_USER_VALUE="${DB_USER:-${POSTGRES_USER:-${MEMORY_DB_USER:-postgres}}}"
DB_PASSWORD_VALUE="${DB_PASSWORD:-${POSTGRES_PASSWORD:-${MEMORY_DB_PASSWORD:-}}}"
RABBITMQ_HOST_VALUE="${RABBITMQ_HOST:-${MEMORY_RABBITMQ_HOST:-localhost}}"
RABBITMQ_PORT_VALUE="${RABBITMQ_PORT:-${MEMORY_RABBITMQ_PORT:-5672}}"
RABBITMQ_USER_VALUE="${RABBITMQ_USER:-${RABBITMQ_DEFAULT_USER:-$(optional_memory_field '### RabbitMQ' 'User')}}"
RABBITMQ_PASSWORD_VALUE="${RABBITMQ_PASSWORD:-${RABBITMQ_DEFAULT_PASS:-$(optional_memory_field '### RabbitMQ' 'Password')}}"
MINIO_ENDPOINT_VALUE="${MINIO_ENDPOINT:-$(optional_memory_field '### MinIO' 'API')}"
MINIO_ACCESS_KEY_VALUE="${MINIO_ACCESS_KEY:-${MINIO_ROOT_USER:-$(optional_memory_field '### MinIO' 'User')}}"
MINIO_SECRET_KEY_VALUE="${MINIO_SECRET_KEY:-${MINIO_ROOT_PASSWORD:-$(optional_memory_field '### MinIO' 'Password')}}"
LIVART_DEFAULT_API_BASE_URL_VALUE="${LIVART_DEFAULT_API_BASE_URL:-$(optional_memory_field '### Livart Development' 'AI Base URL')}"
LIVART_DEFAULT_API_KEY_VALUE="${LIVART_DEFAULT_API_KEY:-$(optional_memory_field '### Livart Development' 'AI API Key')}"
LIVART_DEFAULT_IMAGE_MODEL_VALUE="${LIVART_DEFAULT_IMAGE_MODEL:-$(optional_memory_field '### Livart Development' 'Image Model')}"
LIVART_DEFAULT_CHAT_MODEL_VALUE="${LIVART_DEFAULT_CHAT_MODEL:-$(optional_memory_field '### Livart Development' 'Chat Model')}"
LIVART_KNOWLEDGE_EMBEDDING_BASE_URL_VALUE="${LIVART_KNOWLEDGE_EMBEDDING_BASE_URL:-$(optional_memory_field '### Livart Development' 'Knowledge Embedding Base URL')}"
LIVART_KNOWLEDGE_EMBEDDING_MODEL_VALUE="${LIVART_KNOWLEDGE_EMBEDDING_MODEL:-$(optional_memory_field '### Livart Development' 'Knowledge Embedding Model')}"
LIVART_KNOWLEDGE_EMBEDDING_API_KEY_VALUE="${LIVART_KNOWLEDGE_EMBEDDING_API_KEY:-$(optional_any_memory_field 'Knowledge Embedding API Key')}"
LIVART_EXTERNAL_IMAGES_API_KEY_VALUE="${LIVART_EXTERNAL_IMAGES_API_KEY:-$(optional_any_memory_field 'External images API key')}"

(
  cd "$ROOT_DIR/backend"
  env \
    SERVER_PORT="$BACKEND_PORT" \
    DB_HOST="$DB_HOST_VALUE" \
    DB_PORT="$DB_PORT_VALUE" \
    DB_NAME="$DB_NAME_VALUE" \
    DB_USER="$DB_USER_VALUE" \
    DB_PASSWORD="$DB_PASSWORD_VALUE" \
    SPRING_PROFILES_ACTIVE=dev \
    RABBITMQ_HOST="$RABBITMQ_HOST_VALUE" \
    RABBITMQ_PORT="$RABBITMQ_PORT_VALUE" \
    RABBITMQ_USER="${RABBITMQ_USER_VALUE:-guest}" \
    RABBITMQ_PASSWORD="${RABBITMQ_PASSWORD_VALUE:-guest}" \
    MINIO_ENDPOINT="${MINIO_ENDPOINT_VALUE:-http://localhost:9000}" \
    MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY_VALUE:-livartminio}" \
    MINIO_SECRET_KEY="${MINIO_SECRET_KEY_VALUE:-livart_minio_change_me}" \
    MINIO_BUCKET="${MINIO_BUCKET:-livart}" \
    LIVART_DEFAULT_API_BASE_URL="$LIVART_DEFAULT_API_BASE_URL_VALUE" \
    LIVART_DEFAULT_API_KEY="$LIVART_DEFAULT_API_KEY_VALUE" \
    LIVART_DEFAULT_IMAGE_MODEL="${LIVART_DEFAULT_IMAGE_MODEL_VALUE:-gpt-image-2}" \
    LIVART_DEFAULT_CHAT_MODEL="${LIVART_DEFAULT_CHAT_MODEL_VALUE:-gpt-5.4-mini}" \
    LIVART_KNOWLEDGE_EMBEDDING_BASE_URL="${LIVART_KNOWLEDGE_EMBEDDING_BASE_URL_VALUE:-https://api.siliconflow.cn/v1}" \
    LIVART_KNOWLEDGE_EMBEDDING_MODEL="${LIVART_KNOWLEDGE_EMBEDDING_MODEL_VALUE:-BAAI/bge-m3}" \
    LIVART_KNOWLEDGE_EMBEDDING_API_KEY="$LIVART_KNOWLEDGE_EMBEDDING_API_KEY_VALUE" \
    LIVART_EXTERNAL_IMAGES_API_KEY="$LIVART_EXTERNAL_IMAGES_API_KEY_VALUE" \
    mvn spring-boot:run -Dspring-boot.run.profiles=dev
) > "$LOG_DIR/backend.log" 2>&1 &
echo "$!" > "$LOG_DIR/backend.pid"
echo "后端启动进程：$(cat "$LOG_DIR/backend.pid")"

(
  cd "$ROOT_DIR/frontend"
  npm run dev -- --host "$FRONTEND_HOST" --port "$FRONTEND_PORT"
) > "$LOG_DIR/frontend.log" 2>&1 &
echo "$!" > "$LOG_DIR/frontend.pid"
echo "前端启动进程：$(cat "$LOG_DIR/frontend.pid")"

wait_for_backend
wait_for_frontend

echo "livart 开发环境重启完成。"
echo "前端：http://localhost:$FRONTEND_PORT"
echo "后端：http://localhost:$BACKEND_PORT"
