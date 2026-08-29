#!/usr/bin/env bash
#
# deploy-selfhost.sh —— 自托管五容器（matelink-selfhost）编排部署脚本
#
# 编排顺序：
#   容量门禁 -> Compose 校验 -> 拉镜像（重试 + 进度）-> 构建 Adapter（重试）
#   -> 起 postgres 并等 healthy -> 幂等建双库双角色 -> 全量 up -d -> 自检
#
# 设计要点：
#   * 1 Mbps 带宽下镜像拉取是最慢的一步，因此拉取全部带重试与退避，并输出实时进度；
#   * 容量门禁不通过时，默认自动 stop（不是 down！）jourvolt-staging 腾内存后重试一次；
#   * 全程不打印任何密钥值。
#
# 用法：
#   bash ./scripts/deploy-selfhost.sh                  # 完整部署
#   bash ./scripts/deploy-selfhost.sh --skip-pull      # 镜像已在本地，跳过拉取
#   bash ./scripts/deploy-selfhost.sh --skip-build     # Adapter 镜像已构建，跳过构建
#   bash ./scripts/deploy-selfhost.sh --no-stop-staging # 门禁失败时不要动 staging
#   bash ./scripts/deploy-selfhost.sh --self-check-only # 只做自检，不变更任何东西
#
set -Eeuo pipefail
umask 077

PROJECT_DIR="${PROJECT_DIR:-/home/jourvolt/matelink-selfhost}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.selfhost.yml}"
STAGING_DIR="${STAGING_DIR:-/home/jourvolt/jourvolt-staging}"

PULL_RETRIES="${PULL_RETRIES:-5}"
PULL_BACKOFF="${PULL_BACKOFF:-15}"      # 秒，按 attempt 线性放大
AUTO_STOP_STAGING="${AUTO_STOP_STAGING:-true}"
SKIP_PULL="${SKIP_PULL:-false}"
SKIP_BUILD="${SKIP_BUILD:-false}"
SKIP_GATE="${SKIP_GATE:-false}"
SELF_CHECK_ONLY="${SELF_CHECK_ONLY:-false}"
ADAPTER_URL="${ADAPTER_URL:-http://127.0.0.1:18080}"

while (($# > 0)); do
  case "$1" in
    --skip-pull) SKIP_PULL='true'; shift ;;
    --skip-build) SKIP_BUILD='true'; shift ;;
    --skip-gate) SKIP_GATE='true'; shift ;;
    --no-stop-staging) AUTO_STOP_STAGING='false'; shift ;;
    --self-check-only) SELF_CHECK_ONLY='true'; shift ;;
    -h | --help) sed -n '2,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$1（用 --help 查看用法）" >&2; exit 2 ;;
  esac
done

cd "${PROJECT_DIR}"

log() { printf '%s\n' "$*"; }
rule() { printf '%s\n' '=============================================================='; }
warn() { printf 'WARN: %s\n' "$*" >&2; }

# 安全的 .env 加载器（只认 KEY=VALUE，跳过注释/空行，去掉成对引号）
load_env() {
  local file="$1" line key value
  [[ -f "${file}" ]] || return 0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line}" ]] && continue
    [[ "${line}" =~ ^[[:space:]]*# ]] && continue
    [[ "${line}" != *=* ]] && continue
    key="${line%%=*}"; value="${line#*=}"
    key="$(printf '%s' "${key}" | tr -d '[:space:]')"
    [[ -z "${key}" ]] && continue
    if [[ "${value}" == \"*\" && "${value}" == *\" ]]; then value="${value:1:${#value}-2}"; fi
    if [[ "${value}" == \'*\' && "${value}" == *\' ]]; then value="${value:1:${#value}-2}"; fi
    printf -v "${key}" '%s' "${value}"
    export "${key}"
  done < "${file}"
}

# 带重试的命令执行器
retry() {
  local desc="$1"; shift
  local attempt=1 rc=0
  while ((attempt <= PULL_RETRIES)); do
    log "[$(date +%H:%M:%S)] ${desc} —— 第 ${attempt}/${PULL_RETRIES} 次"
    set +e
    "$@"
    rc=$?
    set -e
    if ((rc == 0)); then
      log "[$(date +%H:%M:%S)] ${desc} —— 成功"
      return 0
    fi
    warn "${desc} 第 ${attempt} 次失败（退出码 ${rc}）"
    attempt=$((attempt + 1))
    ((attempt <= PULL_RETRIES)) && sleep $((PULL_BACKOFF * (attempt - 1)))
  done
  return "${rc}"
}

compose() { docker compose -f "${COMPOSE_FILE}" "$@"; }

# ---------------------------------------------------------------- 0. 前置检查
rule
log '== 0. 前置检查 =='
command -v docker >/dev/null 2>&1 || { echo 'ERROR: 缺少 docker' >&2; exit 2; }
docker info >/dev/null 2>&1 || { echo 'ERROR: docker 守护进程不可访问' >&2; exit 2; }
[[ -f "${COMPOSE_FILE}" ]] || { echo "ERROR: 找不到 ${COMPOSE_FILE}" >&2; exit 2; }

if [[ ! -f ./.env ]]; then
  echo "ERROR: 缺少 ${PROJECT_DIR}/.env" >&2
  echo "       执行：cp .env.selfhost.example .env && chmod 600 .env && 填入真实值" >&2
  exit 2
fi
ENV_PERM="$(stat -c '%a' ./.env)"
log ".env 权限：${ENV_PERM}（要求 600）"
[[ "${ENV_PERM}" == '600' ]] || { echo 'ERROR: .env 权限不是 600，先执行 chmod 600 .env' >&2; exit 2; }

load_env "${PROJECT_DIR}/.env"
mkdir -p "${PROJECT_DIR}/import" "${PROJECT_DIR}/logs"
chmod 700 "${PROJECT_DIR}/import"

# ---------------------------------------------------------------- 1. 容量门禁
if [[ "${SKIP_GATE}" != 'true' ]]; then
  rule
  log '== 1. 容量门禁 =='
  if bash ./scripts/capacity-gate.sh; then
    log 'GATE=PASS（首次）'
  else
    warn '首次门禁未通过'
    if [[ "${AUTO_STOP_STAGING}" == 'true' ]] && [[ -d "${STAGING_DIR}" ]]; then
      log "尝试 stop（不 down、不删卷）jourvolt-staging 腾出内存：${STAGING_DIR}"
      (cd "${STAGING_DIR}" && docker compose stop) || warn 'stop staging 失败，继续'
      sleep 5
      if bash ./scripts/capacity-gate.sh; then
        log 'GATE=PASS（停掉 staging 之后）'
      else
        echo 'ERROR: 停掉 staging 后门禁仍未通过，中止部署（不要硬上）' >&2
        exit 1
      fi
    else
      echo 'ERROR: 门禁未通过且未授权停 staging，中止部署' >&2
      exit 1
    fi
  fi
else
  warn '--skip-gate：跳过容量门禁（仅限排障时使用）'
fi

# ---------------------------------------------------------------- 2. 自检（仅自检模式）
self_check() {
  rule
  log '== 自检 =='
  log '-- docker compose ps --'
  compose ps

  log ''
  log '-- 端口暴露面（期望：4000/8080/5432/1883 无监听）--'
  if ss -lnt 2>/dev/null | grep -E '[.:](4000|8080|5432|1883)[[:space:]]'; then
    warn '发现 4000/8080/5432/1883 被监听，违反硬约束'
    PORT_OK='false'
  else
    log 'OK  : 4000 / 8080 / 5432 / 1883 均无监听'
    PORT_OK='true'
  fi

  log ''
  log '-- 18080 监听情况（期望：仅 127.0.0.1:18080）--'
  ss -lntp 2>/dev/null | grep 18080 || log '(无输出，异常)'

  log ''
  log '-- HTTP 自检 --'
  local code_no_token code_with_token code_cars
  code_no_token="$(curl -s -o /dev/null -m 10 -w '%{http_code}' \
    "${ADAPTER_URL}/api/matelink/v1/capabilities" || echo 000)"
  log "capabilities 不带 token          -> HTTP ${code_no_token}（期望 401）"

  code_with_token="$(curl -s -o /dev/null -m 10 -w '%{http_code}' \
    "${ADAPTER_URL}/api/matelink/v1/capabilities" \
    -H "Authorization: Bearer ${MATE_LINK_API_TOKEN}" || echo 000)"
  log "capabilities 带 Bearer token     -> HTTP ${code_with_token}（期望 200）"

  code_cars="$(curl -s -o /dev/null -m 15 -w '%{http_code}' \
    "${ADAPTER_URL}/api/v1/cars" \
    -H "Authorization: Bearer ${MATE_LINK_API_TOKEN}" || echo 000)"
  log "/api/v1/cars 带 Bearer token     -> HTTP ${code_cars}（R-2：期望 200；401 表示 teslamateapi 的 Bearer 前缀问题）"

  log ''
  log '-- docker stats（matelink-selfhost 五容器 RSS 之和目标 <= 480 MB）--'
  docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}'

  # MemUsage 形如 "12.34MiB / 76MiB"；只取第一列并统一换算成 MB
  mem_usage_to_mb() {
    awk '{
      v = $1; u = v;
      sub(/[0-9.]*/, "", u);
      sub(/[^0-9.].*/, "", v);
      if (u == "GiB") v = v * 1024; else if (u == "KiB") v = v / 1024;
      printf "%.2f\n", v;
    }'
  }

  local selfhost_rss all_rss
  selfhost_rss="$(
    for cid in $(compose ps -q); do
      docker stats --no-stream --format '{{.MemUsage}}' "${cid}"
    done | awk '{print $1}' | mem_usage_to_mb | awk '{s+=$1} END{printf "%.0f", s+0}'
  )"
  all_rss="$(docker stats --no-stream --format '{{.MemUsage}}' | awk '{print $1}' | mem_usage_to_mb \
    | awk '{s+=$1} END{printf "%.0f", s+0}')"
  log "matelink-selfhost 五容器 RSS 合计：${selfhost_rss} MB（目标 <= 480 MB，> 600 MB 触发降级）"
  log "全部容器 RSS 合计（含 star-photo 与 staging 基线）：${all_rss} MB"
  if ((selfhost_rss > 600)); then
    warn "五容器 RSS ${selfhost_rss} MB 已超 600 MB 降级线：先停 jourvolt-staging，再考虑 DISABLE_MQTT=true"
  fi

  rule
  if [[ "${code_no_token}" == '401' && "${code_with_token}" == '200' && "${PORT_OK}" == 'true' ]]; then
    log 'SELFHOST_DEPLOY=PASS'
    [[ "${code_cars}" == '200' ]] || log 'NOTE: /api/v1/cars 非 200，见风险 R-2（需按架构文档 5.5 做配置兜底后再复测）'
    return 0
  fi
  log 'SELFHOST_DEPLOY=FAIL'
  return 1
}

if [[ "${SELF_CHECK_ONLY}" == 'true' ]]; then
  [[ -n "${MATE_LINK_API_TOKEN:-}" ]] || { echo 'ERROR: 缺少 MATE_LINK_API_TOKEN' >&2; exit 2; }
  self_check
  exit $?
fi

# ---------------------------------------------------------------- 3. Compose 校验
rule
log '== 2. Compose 配置校验 =='
compose config --quiet && log 'OK  : docker compose config --quiet 通过'
log "服务清单：$(compose config --services | tr '\n' ' ')"
GRAFANA_COUNT="$(compose config --services | grep -c '^grafana$' || true)"
log "grafana 数量：${GRAFANA_COUNT}（必须为 0）"
[[ "${GRAFANA_COUNT}" == '0' ]] || { echo 'ERROR: 裁剪版不得包含 grafana' >&2; exit 1; }

# ---------------------------------------------------------------- 4. 拉镜像
if [[ "${SKIP_PULL}" != 'true' ]]; then
  rule
  log '== 3. 拉取镜像（1 Mbps，带重试与进度输出）=='
  # --ignore-buildable：跳过本地构建的服务（matelink-adapter），
  # 否则 compose 会尝试从 registry 拉 matelink-selfhost-matelink-adapter 这种本地镜像名并必然失败。
  retry 'docker compose pull --ignore-buildable' compose pull --ignore-buildable
else
  warn '--skip-pull：跳过镜像拉取'
fi

# ---------------------------------------------------------------- 5. 构建 Adapter
if [[ "${SKIP_BUILD}" != 'true' ]]; then
  rule
  log '== 4. 构建 matelink-adapter 镜像（需拉 golang:1.24-alpine，带重试）=='
  retry 'docker compose build matelink-adapter' compose build matelink-adapter
else
  warn '--skip-build：跳过 Adapter 构建'
fi

# ---------------------------------------------------------------- 6. 起 postgres + 建库
rule
log '== 5. 启动 postgres 并等待 healthy =='
compose up -d postgres
bash ./scripts/init-shared-db.sh

# ---------------------------------------------------------------- 7. 全量 up
rule
log '== 6. 全量 up -d =='
compose up -d

log ''
log '等待 15 秒让应用容器完成首次启动…'
sleep 15

# ---------------------------------------------------------------- 8. 自检
[[ -n "${MATE_LINK_API_TOKEN:-}" ]] || { echo 'ERROR: 缺少 MATE_LINK_API_TOKEN' >&2; exit 2; }
if self_check; then
  rule
  log '部署完成。证据等级：LOCAL MOCK PASS（服务端自测），不得宣称公网可用。'
  log '下一步：'
  log '  * bash ./scripts/tunnel-teslamate.sh     # 输出 SSH 隧道命令，用于 TeslaMate 授权'
  log '  * bash ./scripts/migrate-teslamate.sh    # 拿到源库 dump 后做历史迁移'
  exit 0
fi

rule
warn '自检未全绿，请按上方输出逐项排查；不要进入 T04（公网入口）。'
exit 1
