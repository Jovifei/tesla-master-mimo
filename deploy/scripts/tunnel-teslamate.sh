#!/usr/bin/env bash
#
# tunnel-teslamate.sh —— 输出 TeslaMate 的容器 IP 与可直接复制的 ssh -L 命令
#
# 背景（架构文档 4.1）：TeslaMate 的 4000 端口【不发布】到宿主机，
# 需要授权时只能通过 SSH 隧道直连容器 IP 访问一次。
#
# 用法（在 ECS 上执行）：
#   bash ./scripts/tunnel-teslamate.sh [本地端口，默认 14000]
# 输出中的 ssh 命令复制到【Jovi 自己的电脑】上执行，然后浏览器打开 http://127.0.0.1:14000
#
set -Eeuo pipefail

PROJECT_DIR="${PROJECT_DIR:-/home/jourvolt/matelink-selfhost}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.selfhost.yml}"
SERVICE="${SERVICE:-teslamate}"
CONTAINER_PORT="${CONTAINER_PORT:-4000}"
LOCAL_PORT="${1:-${LOCAL_PORT:-14000}}"
SSH_KEY="${SSH_KEY:-~/.ssh/joviluma_jourvolt_deploy_ed25519}"
ECS_HOST="${ECS_HOST:-jourvolt@120.55.64.11}"

cd "${PROJECT_DIR}"

log() { printf '%s\n' "$*"; }
rule() { printf '%s\n' '=============================================================='; }

[[ -f "${COMPOSE_FILE}" ]] || { echo "ERROR: 找不到 ${COMPOSE_FILE}" >&2; exit 2; }

compose() { docker compose -f "${COMPOSE_FILE}" "$@"; }

# ---------------------------------------------------------------- 取容器 ID 与 IP
CID="$(compose ps -q "${SERVICE}" 2>/dev/null | head -n1 || true)"
if [[ -z "${CID}" ]]; then
  echo "ERROR: 服务 ${SERVICE} 未运行，先执行 bash ./scripts/deploy-selfhost.sh" >&2
  exit 1
fi

CIP="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "${CID}" | head -n1)"
if [[ -z "${CIP}" ]]; then
  echo "ERROR: 取不到 ${SERVICE} 的容器 IP" >&2
  exit 1
fi

STATE="$(docker inspect -f '{{.State.Status}}' "${CID}")"

# ---------------------------------------------------------------- 输出
rule
log "服务          : ${SERVICE}"
log "容器 ID       : ${CID:0:12}"
log "容器状态      : ${STATE}"
log "容器 IP       : ${CIP}"
log "容器内端口    : ${CONTAINER_PORT}"
log "宿主机监听    : 未发布（符合硬约束）"
rule
log '请把下面这一行复制到【你自己的电脑】上执行：'
log ''
log "ssh -i ${SSH_KEY} -N -L ${LOCAL_PORT}:${CIP}:${CONTAINER_PORT} ${ECS_HOST}"
log ''
rule
log '隧道建立后，在你自己的浏览器里打开：'
log ''
log "  http://127.0.0.1:${LOCAL_PORT}"
log ''
log 'TeslaMate 授权要点：'
log '  1) 走的是 sshd -> docker bridge 的宿主机内路由，不需要给 teslamate 发布任何宿主机端口；'
log '  2) 若 Phoenix 的 origin 校验拦截登录，在 .env 里临时设 TESLAMATE_CHECK_ORIGIN=false'
log '     （4000 不对外，风险可控），授权完成后再改回 true 并执行 docker compose up -d teslamate；'
log '  3) 授权完成后执行 docker compose exec -T postgres psql -U jourvolt -d teslamate \\'
log "       -tAc 'select count(*) from cars;'  —— 期望 >= 1；"
log '  4) 隧道用完即关（Ctrl-C），不要长期保留。'
rule
