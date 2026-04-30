#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPLOY_ENV_FILE="${LIVART_DEPLOY_ENV_FILE:-$ROOT_DIR/.env.deploy}"
MEMORY_FILE="${LIVART_MEMORY_FILE:-$HOME/.codex/MEMORY.md}"

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

optional_memory_field() {
  read_memory_field "$1" "$2" 2>/dev/null || true
}

json_value() {
  local expression="$1"
  python3 -c '
import json
import sys

expression = sys.argv[1]
data = json.load(sys.stdin)
value = data
for part in expression.split("."):
    if not part:
        continue
    if isinstance(value, dict):
        value = value.get(part)
    else:
        value = None
        break
if value is None:
    print("")
elif isinstance(value, bool):
    print("true" if value else "false")
else:
    print(value)
' "$expression"
}

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "缺少命令：$command_name" >&2
    exit 1
  fi
}

read_remote_jenkins_token() {
  local remote_host="${JENKINS_TOKEN_REMOTE_HOST:-}"
  local remote_user="${JENKINS_TOKEN_REMOTE_USER:-root}"
  local remote_path="${JENKINS_TOKEN_REMOTE_PATH:-/etc/livart/jenkins/api_token}"
  local remote_password="${JENKINS_TOKEN_REMOTE_PASSWORD:-}"

  if [[ -z "$remote_host" && "${LIVART_READ_CODEX_MEMORY:-true}" == "true" ]]; then
    remote_host="$(optional_memory_field '### Production Server' 'IP')"
    remote_user="$(optional_memory_field '### Production Server' 'User')"
    remote_password="$(optional_memory_field '### Production Server' 'Password')"
  fi

  if [[ -z "$remote_host" ]]; then
    return 1
  fi

  if [[ -n "$remote_password" ]]; then
    require_command sshpass
    sshpass -p "$remote_password" ssh \
      -o StrictHostKeyChecking=no \
      -o UserKnownHostsFile=/dev/null \
      -o LogLevel=ERROR \
      "$remote_user@$remote_host" \
      "cat '$remote_path'"
    return 0
  fi

  ssh \
    -o StrictHostKeyChecking=accept-new \
    "$remote_user@$remote_host" \
    "cat '$remote_path'"
}

jenkins_get() {
  local url="$1"
  curl --http1.1 -g -fsS --user "$JENKINS_USER:$JENKINS_TOKEN" "$url"
}

jenkins_post() {
  local url="$1"
  local crumb_json crumb_field crumb_value
  crumb_json="$(curl --http1.1 -fsS --user "$JENKINS_USER:$JENKINS_TOKEN" "$JENKINS_URL/crumbIssuer/api/json" 2>/dev/null || true)"
  if [[ -n "$crumb_json" ]]; then
    crumb_field="$(printf '%s' "$crumb_json" | json_value "crumbRequestField")"
    crumb_value="$(printf '%s' "$crumb_json" | json_value "crumb")"
    curl --http1.1 -g -fsS --user "$JENKINS_USER:$JENKINS_TOKEN" -H "$crumb_field: $crumb_value" -X POST "$url" >/dev/null
    return 0
  fi

  curl --http1.1 -g -fsS --user "$JENKINS_USER:$JENKINS_TOKEN" -X POST "$url" >/dev/null
}

wait_for_jenkins_build() {
  local last_before="$1"
  local started_at build_json build_number building result
  started_at="$(date +%s)"

  while true; do
    build_json="$(jenkins_get "$JENKINS_URL/job/$JENKINS_JOB/api/json?tree=lastBuild[number,building,result,url,timestamp]")"
    build_number="$(printf '%s' "$build_json" | json_value "lastBuild.number")"
    building="$(printf '%s' "$build_json" | json_value "lastBuild.building")"
    result="$(printf '%s' "$build_json" | json_value "lastBuild.result")"

    if [[ -n "$build_number" && "$build_number" != "$last_before" ]]; then
      if [[ "$building" == "true" ]]; then
        echo "Jenkins #$build_number 构建中..."
      elif [[ -n "$result" ]]; then
        echo "Jenkins #$build_number 结果：$result"
        [[ "$result" == "SUCCESS" ]]
        return
      fi
    fi

    if (( "$(date +%s)" - started_at >= JENKINS_WAIT_TIMEOUT_SECONDS )); then
      echo "Jenkins 构建等待超时" >&2
      return 1
    fi
    sleep "$JENKINS_POLL_INTERVAL_SECONDS"
  done
}

wait_for_health() {
  if [[ -z "$LIVART_HEALTH_URL" ]]; then
    return 0
  fi

  local started_at
  started_at="$(date +%s)"
  echo "验证生产健康：$LIVART_HEALTH_URL"
  while true; do
    if curl --http1.1 -fsS "$LIVART_HEALTH_URL" >/dev/null; then
      echo "生产健康检查通过"
      return 0
    fi

    if (( "$(date +%s)" - started_at >= LIVART_HEALTH_TIMEOUT_SECONDS )); then
      echo "生产健康检查超时：$LIVART_HEALTH_URL" >&2
      return 1
    fi
    sleep 3
  done
}

load_env_file "$DEPLOY_ENV_FILE"

require_command curl
require_command python3

JENKINS_URL="${JENKINS_URL:-https://jenkins.ai987654321.com}"
JENKINS_JOB="${JENKINS_JOB:-livart-deploy}"
JENKINS_USER="${JENKINS_USER:-admin}"
JENKINS_WAIT_TIMEOUT_SECONDS="${JENKINS_WAIT_TIMEOUT_SECONDS:-600}"
JENKINS_POLL_INTERVAL_SECONDS="${JENKINS_POLL_INTERVAL_SECONDS:-5}"
LIVART_HEALTH_URL="${LIVART_HEALTH_URL:-https://livart.suntools.pro/api/health}"
LIVART_HEALTH_TIMEOUT_SECONDS="${LIVART_HEALTH_TIMEOUT_SECONDS:-120}"

if [[ -z "${JENKINS_TOKEN:-}" && -n "${JENKINS_TOKEN_FILE:-}" && -r "$JENKINS_TOKEN_FILE" ]]; then
  JENKINS_TOKEN="$(<"$JENKINS_TOKEN_FILE")"
fi

if [[ -z "${JENKINS_TOKEN:-}" ]]; then
  JENKINS_TOKEN="$(read_remote_jenkins_token 2>/dev/null || true)"
fi

if [[ -z "${JENKINS_TOKEN:-}" ]]; then
  cat >&2 <<'EOF'
缺少 Jenkins Token。
请设置 JENKINS_TOKEN，或在 .env.deploy 中设置：
  JENKINS_TOKEN=...
也可以设置 JENKINS_TOKEN_FILE，或设置 JENKINS_TOKEN_REMOTE_HOST 让脚本从远端读取 token。
EOF
  exit 1
fi

last_build_json="$(jenkins_get "$JENKINS_URL/job/$JENKINS_JOB/api/json?tree=lastBuild[number]")"
last_build_number="$(printf '%s' "$last_build_json" | json_value "lastBuild.number")"
last_build_number="${last_build_number:-0}"

echo "触发 Jenkins：$JENKINS_URL/job/$JENKINS_JOB"
echo "当前构建号：$last_build_number"
jenkins_post "$JENKINS_URL/job/$JENKINS_JOB/build"

wait_for_jenkins_build "$last_build_number"
wait_for_health

echo "部署完成"
