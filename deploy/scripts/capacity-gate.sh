#!/usr/bin/env bash
#
# capacity-gate.sh —— 容量盘点 + 准入门禁（PRD P0-1 / 硬约束 #8）
#
# 用途：
#   1) 盘点内存 / swap / 磁盘 / 容器占用 / 端口监听，产出一份基线快照；
#   2) 以 available >= 800 MB 作为自托管五容器的准入门槛，不满足则强制拦截。
#
# 退出码：
#   0 —— GATE=PASS（可以启动五容器）
#   1 —— GATE=FAIL（不得启动；脚本会给出具体原因）
#   2 —— 环境不满足（docker 不可用等）
#
# 用法：
#   bash ./scripts/capacity-gate.sh                 # 盘点 + 门禁
#   bash ./scripts/capacity-gate.sh --min 800       # 自定义门槛（MB）
#   bash ./scripts/capacity-gate.sh --print-only    # 只盘点，不改退出码
#
set -Eeuo pipefail

# ---------------------------------------------------------------- 参数解析
MIN_AVAILABLE_MB="${MIN_AVAILABLE_MB:-800}"
PRINT_ONLY="${PRINT_ONLY:-false}"
while (($# > 0)); do
  case "$1" in
    --min)
      MIN_AVAILABLE_MB="${2:?--min 需要一个 MB 数值}"
      shift 2
      ;;
    --print-only)
      PRINT_ONLY='true'
      shift
      ;;
    -h | --help)
      sed -n '2,22p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *)
      echo "未知参数：$1（用 --help 查看用法）" >&2
      exit 2
      ;;
  esac
done

# ---------------------------------------------------------------- 工具函数
log() { printf '%s\n' "$*"; }
rule() { printf '%s\n' '--------------------------------------------------------------'; }

# 读取系统内存，返回 "total_mb used_mb free_mb shared_mb buffcache_mb available_mb"
read_mem_mb() {
  free -m | awk '/^Mem:/{print $2, $3, $4, $5, $6, $7}'
}

# 读取 swap，返回 "total_mb used_mb free_mb"；无 swap 时返回 "0 0 0"
read_swap_mb() {
  local line
  line="$(free -m | awk '/^Swap:/{print $2, $3, $4}')"
  printf '%s\n' "${line:-0 0 0}"
}

assert_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "GATE=FAIL 原因：本机没有 docker 命令" >&2
    exit 2
  fi
  if ! docker info >/dev/null 2>&1; then
    echo "GATE=FAIL 原因：docker 守护进程不可访问（当前用户是否在 docker 组？）" >&2
    exit 2
  fi
}

# ---------------------------------------------------------------- 1. 环境
assert_docker

rule
log '== 1. 主机 =='
log "host      : $(hostname)"
log "kernel    : $(uname -sr)"
log "docker    : $(docker info --format '{{.ServerVersion}}' 2>/dev/null || echo unknown)"
log "compose   : $(docker compose version --short 2>/dev/null || echo unknown)"
log "cgroup v2 : $([ -f /sys/fs/cgroup/cgroup.controllers ] && echo yes || echo no)"

# ---------------------------------------------------------------- 2. 内存
rule
log '== 2. 内存（free -m） =='
free -m

read -r MEM_TOTAL MEM_USED MEM_FREE MEM_SHARED MEM_BUFFCACHE MEM_AVAILABLE <<<"$(read_mem_mb)"
read -r SWAP_TOTAL SWAP_USED SWAP_FREE <<<"$(read_swap_mb)"

log ''
log "available : ${MEM_AVAILABLE} MB / total ${MEM_TOTAL} MB"
log "swap      : used ${SWAP_USED} MB / total ${SWAP_TOTAL} MB"

# ---------------------------------------------------------------- 3. 磁盘
rule
log '== 3. 磁盘（df -h） =='
df -h /
df -h /var/lib/docker 2>/dev/null || true
DISK_AVAIL="$(df -Pm / | awk 'NR==2{print $4}')"
log "根分区可用 : ${DISK_AVAIL} MB"

# ---------------------------------------------------------------- 4. swap 明细
rule
log '== 4. swap 明细 =='
if command -v swapon >/dev/null 2>&1; then
  swapon --show 2>/dev/null || swapon -s 2>/dev/null || log '(无 swap)'
else
  log '(swapon 不可用)'
fi

# ---------------------------------------------------------------- 5. 容器占用
rule
log '== 5. 容器占用（docker stats --no-stream，含 star-photo 基线） =='
if docker ps -q | grep -q .; then
  docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}\t{{.CPUPerc}}\t{{.NetIO}}'
else
  log '(当前没有任何运行中的容器)'
fi

log ''
log '-- 运行中的容器清单 --'
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'

# ---------------------------------------------------------------- 6. 端口
rule
log '== 6. 端口监听（ss -lntp） =='
if command -v ss >/dev/null 2>&1; then
  ss -lntp
else
  netstat -lntp 2>/dev/null || log '(ss / netstat 均不可用)'
fi

# 本轮红线：4000 / 8080 / 5432 / 1883 不得出现在宿主机监听里
FORBIDDEN_PORTS=(4000 8080 5432 1883)
PORT_CONFLICTS=()
for port in "${FORBIDDEN_PORTS[@]}"; do
  if ss -lnt 2>/dev/null | grep -qE "[:.]${port}[[:space:]]"; then
    PORT_CONFLICTS+=("${port}")
  fi
done
if ((${#PORT_CONFLICTS[@]} > 0)); then
  log "WARN: 以下端口已被监听（裁剪版不得发布它们）：${PORT_CONFLICTS[*]}"
else
  log 'OK  : 4000 / 8080 / 5432 / 1883 均未被监听'
fi

# 18080 是否被占用
if ss -lnt 2>/dev/null | grep -qE '[^0-9]18080[[:space:]]'; then
  OWNER_18080="$(ss -lntp 2>/dev/null | grep -E '[^0-9]18080[[:space:]]' | head -n1 | tr -s ' ')"
  log "WARN: 18080 已被占用 -> ${OWNER_18080}"
  log '      （自托管 Adapter 需要该端口；若属本项目自身的旧容器属正常）'
else
  log 'OK  : 18080 空闲'
fi

# ---------------------------------------------------------------- 7. 预算核算
rule
log '== 7. 本次部署的内存预算 =='
BUDGET_POSTGRES=232
BUDGET_TESLAMATE=288
BUDGET_ADAPTER=76
BUDGET_TESLAMATEAPI=64
BUDGET_MOSQUITTO=40
BUDGET_TOTAL=$((BUDGET_POSTGRES + BUDGET_TESLAMATE + BUDGET_ADAPTER + BUDGET_TESLAMATEAPI + BUDGET_MOSQUITTO))
log "postgres ${BUDGET_POSTGRES}m + teslamate ${BUDGET_TESLAMATE}m + adapter ${BUDGET_ADAPTER}m + teslamateapi ${BUDGET_TESLAMATEAPI}m + mosquitto ${BUDGET_MOSQUITTO}m = ${BUDGET_TOTAL} MB（mem_limit 天花板）"
log "实测 RSS 目标   : <= 480 MB"
log "降级触发线      : > 600 MB（先停 jourvolt-staging，再考虑 DISABLE_MQTT）"
log "准入门槛        : available >= ${MIN_AVAILABLE_MB} MB"

# ---------------------------------------------------------------- 8. 门禁判定
rule
log '== 8. 门禁判定 =='

FAIL_REASONS=()

if ((MEM_AVAILABLE < MIN_AVAILABLE_MB)); then
  FAIL_REASONS+=("available=${MEM_AVAILABLE}MB 低于门槛 ${MIN_AVAILABLE_MB}MB")
fi

if ((DISK_AVAIL < 4096)); then
  FAIL_REASONS+=("根分区可用 ${DISK_AVAIL}MB 低于 4096MB 安全线")
fi

# swap 已用超过一半给出强提示（不直接判 FAIL，因为 swap 已用不代表不能跑）
SWAP_WARN=''
if ((SWAP_TOTAL > 0)) && ((SWAP_USED * 100 / SWAP_TOTAL >= 50)); then
  SWAP_WARN="swap 已用 ${SWAP_USED}/${SWAP_TOTAL} MB（>=50%），机器已处于内存压力状态"
fi

if ((${#FAIL_REASONS[@]} > 0)); then
  log 'GATE=FAIL'
  for reason in "${FAIL_REASONS[@]}"; do
    log "  - ${reason}"
  done
  if [[ -n "${SWAP_WARN}" ]]; then
    log "  ! ${SWAP_WARN}"
  fi
  log ''
  log '处置建议（按序执行，不要硬上）：'
  log '  1) 停掉本轮不验证的 jourvolt-staging（只 stop，绝不 down / 不删卷）：'
  log '       cd /home/jourvolt/jourvolt-staging && docker compose stop'
  log '  2) 重新执行：bash ./scripts/capacity-gate.sh'
  log '  3) 仍不足则考虑 DISABLE_MQTT=true 并移除 mosquitto 服务（代价：App 的锁车/门窗/充电状态不可用）'
  rule
  [[ "${PRINT_ONLY}" == 'true' ]] && exit 0
  exit 1
fi

log 'GATE=PASS'
log "  available ${MEM_AVAILABLE} MB >= ${MIN_AVAILABLE_MB} MB"
log "  根分区可用 ${DISK_AVAIL} MB"
if [[ -n "${SWAP_WARN}" ]]; then
  log "  警告：${SWAP_WARN}"
fi
rule
exit 0
