#!/usr/bin/env bash
#
# dump-teslamate-local.sh —— 【在 Jovi 自己的电脑上执行】（源 TeslaMate 所在 Docker 主机）
#
# 作用：
#   1) 自动探测源 PostgreSQL 大版本（这决定 ECS 侧 POSTGRES_IMAGE 的 tag）；
#   2) 输出 cars / drives / charges / tokens 的基线条数，作为迁移后校验的基准；
#   3) 提示 TESLAMATE_ENCRYPTION_KEY 所在的文件路径（【绝不打印 key 的值】）；
#   4) pg_dump 出 custom 格式压缩包，并按需分卷（1 Mbps 传输需要断点续传）。
#
# 兼容性：同时支持 Docker Desktop（Windows / macOS）与 Linux Docker（compose v1 与 v2）。
#
# 用法：
#   bash ./dump-teslamate-local.sh
#   bash ./dump-teslamate-local.sh --dir /path/to/teslamate-home-docker
#   bash ./dump-teslamate-local.sh --service database --out ./dump --split-size 20M
#   bash ./dump-teslamate-local.sh --no-split          # dump 很小时不分卷
#
# 红线：
#   * 本脚本【不打印】TESLAMATE_ENCRYPTION_KEY 的值，只打印它所在的文件路径；
#   * 源端 docker-compose.yml 是正在运行的模板，本脚本对它只读，绝不修改。
#
set -Eeuo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="${COMPOSE_DIR:-$(cd "${SCRIPT_DIR}/../teslamate-home-docker" && pwd)}"
DB_SERVICE="${DB_SERVICE:-database}"
DB_USER="${DB_USER:-teslamate}"
DB_NAME="${DB_NAME:-teslamate}"
SPLIT_SIZE="${SPLIT_SIZE:-20M}"
DO_SPLIT="${DO_SPLIT:-true}"

while (($# > 0)); do
  case "$1" in
    --dir) COMPOSE_DIR="${2:?--dir 需要目录路径}"; shift 2 ;;
    --service) DB_SERVICE="${2:?--service 需要 compose 服务名}"; shift 2 ;;
    --user) DB_USER="${2:?--user 需要数据库用户}"; shift 2 ;;
    --db) DB_NAME="${2:?--db 需要数据库名}"; shift 2 ;;
    --out) OUT_DIR="${2:?--out 需要输出目录}"; shift 2 ;;
    --split-size) SPLIT_SIZE="${2:?--split-size 需要分卷大小，如 20M}"; shift 2 ;;
    --no-split) DO_SPLIT='false'; shift ;;
    -h | --help) sed -n '2,26p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$1（用 --help 查看用法）" >&2; exit 2 ;;
  esac
done

OUT_DIR="${OUT_DIR:-${COMPOSE_DIR}/dump}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BASELINE_FILE="${OUT_DIR}/baseline-${STAMP}.txt"

log() { printf '%s\n' "$*"; }
rule() { printf '%s\n' '=============================================================='; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 0. 环境探测
rule
log '== 0. 环境探测 =='
command -v docker >/dev/null 2>&1 || die '缺少 docker 命令'
docker info >/dev/null 2>&1 || die 'docker 守护进程不可访问（Docker Desktop 是否已启动？）'

# compose CLI：优先 v2（docker compose），回退 v1（docker-compose）
if docker compose version >/dev/null 2>&1; then
  DC=(docker compose)
  log "compose CLI : docker compose $(docker compose version --short 2>/dev/null || echo '?')"
elif command -v docker-compose >/dev/null 2>&1; then
  DC=(docker-compose)
  log "compose CLI : docker-compose $(docker-compose version --short 2>/dev/null || echo '?')"
else
  die '既没有 docker compose（v2）也没有 docker-compose（v1）'
fi

[[ -d "${COMPOSE_DIR}" ]] || die "源 compose 目录不存在：${COMPOSE_DIR}"
log "源目录      : ${COMPOSE_DIR}"
log "数据库服务  : ${DB_SERVICE}"
log "数据库      : ${DB_NAME} (user=${DB_USER})"

cd "${COMPOSE_DIR}"
mkdir -p "${OUT_DIR}"

# 确认源容器在跑
log ''
log '-- docker compose ps --'
"${DC[@]}" ps || true

# 直接试探一次 exec：compose v1 / v2 的 ps --filter 语义不一致，用 exec 探测最可靠
if ! "${DC[@]}" exec -T "${DB_SERVICE}" psql -U "${DB_USER}" -d "${DB_NAME}" -X -q -tAc 'select 1' \
  >/dev/null 2>&1; then
  die "服务 ${DB_SERVICE} 未在运行，或无法以 ${DB_USER} 连接 ${DB_NAME}。
       先执行：cd \"${COMPOSE_DIR}\" && ${DC[*]} ps
       若服务名不是 ${DB_SERVICE}，用 --service <名字> 指定。"
fi
log "连通性检查  : ${DB_SERVICE} 可访问"

# 在源数据库容器内执行 SQL（-T 关闭 TTY，便于重定向）
db_query() {
  "${DC[@]}" exec -T "${DB_SERVICE}" psql -U "${DB_USER}" -d "${DB_NAME}" -X -q -tAc "$1"
}

# ---------------------------------------------------------------- 1. 大版本探测
rule
log '== 1. 源 PostgreSQL 大版本探测（决定 ECS 侧 POSTGRES_IMAGE）=='

RAW_VERSION="$(db_query 'SHOW server_version;' | head -n1 | tr -d '\r')"
NUM_VERSION="$(db_query "select current_setting('server_version_num');" | head -n1 | tr -d '[:space:]')"

SRC_MAJOR=''
if [[ "${NUM_VERSION}" =~ ^[0-9]{5,6}$ ]]; then
  SRC_MAJOR=$((NUM_VERSION / 10000))
elif [[ "${RAW_VERSION}" =~ ^([0-9]+)\. ]]; then
  SRC_MAJOR="${BASH_REMATCH[1]}"
fi
[[ -n "${SRC_MAJOR}" ]] || die "无法解析源库大版本（server_version=${RAW_VERSION} / server_version_num=${NUM_VERSION}）"

log "server_version      : ${RAW_VERSION}"
log "server_version_num  : ${NUM_VERSION}"
log "源库大版本          : PostgreSQL ${SRC_MAJOR}"
log ''
log ">>> ECS 侧 .env 的 POSTGRES_IMAGE 必须 >= postgres:${SRC_MAJOR}-alpine"
log ">>> 即：POSTGRES_IMAGE=postgres:${SRC_MAJOR}-alpine"
log '>>> （pg_dump 只能低版本 -> 高版本；ECS 侧选更高版本永远安全）'

# pg_dump 版本（用容器内的，必然与服务器版本一致）
DUMP_VERSION="$("${DC[@]}" exec -T "${DB_SERVICE}" pg_dump --version | head -n1 | tr -d '\r')"
log "容器内 pg_dump      : ${DUMP_VERSION}"

# ---------------------------------------------------------------- 2. 基线条数
rule
log '== 2. 源库基线条数（迁移后逐项比对）=='

count_table() {
  local table="$1" n
  n="$(db_query "select count(*) from ${table};" 2>/dev/null | head -n1 | tr -d '[:space:]')"
  printf '%s' "${n:-NA}"
}

C_CARS="$(count_table cars)"
C_DRIVES="$(count_table drives)"
C_CHARGES="$(count_table charges)"
C_TOKENS="$(count_table tokens)"
C_POSITIONS="$(count_table positions)"

{
  printf '# 源库基线（%s）\n' "$(date -Is 2>/dev/null || date)"
  printf 'src_major=%s\n' "${SRC_MAJOR}"
  printf 'src_server_version=%s\n' "${RAW_VERSION}"
  printf 'cars=%s\n' "${C_CARS}"
  printf 'drives=%s\n' "${C_DRIVES}"
  printf 'charges=%s\n' "${C_CHARGES}"
  printf 'tokens=%s\n' "${C_TOKENS}"
  printf 'positions=%s\n' "${C_POSITIONS}"
} > "${BASELINE_FILE}"
chmod 600 "${BASELINE_FILE}"

log "cars      = ${C_CARS}"
log "drives    = ${C_DRIVES}"
log "charges   = ${C_CHARGES}"
log "tokens    = ${C_TOKENS}"
log "positions = ${C_POSITIONS}（参考值）"
log ''
log "基线已写入：${BASELINE_FILE}（请把这个文件一起传到 ECS，迁移脚本会用它自动比对）"

if [[ "${C_TOKENS}" =~ ^[0-9]+$ ]] && ((C_TOKENS >= 1)); then
  log 'TOKENS=OK  ：tokens 表非空 -> 迁移成功后 ECS 侧 TeslaMate 可继承 token，P0-9 授权可跳过。'
else
  log 'TOKENS=WARN：tokens 表为空 -> 迁移后仍需重新授权（P0-9 会阻塞于备案 + 公网 443 + Tesla 公钥）。'
fi

# ---------------------------------------------------------------- 3. ENCRYPTION KEY 提示
rule
log '== 3. TESLAMATE_ENCRYPTION_KEY（只给路径，绝不打印值）=='

ENV_FILE="${COMPOSE_DIR}/.env"
if [[ -f "${ENV_FILE}" ]] && grep -qE '^[[:space:]]*TESLAMATE_ENCRYPTION_KEY=' "${ENV_FILE}"; then
  log "已找到                ：${ENV_FILE} 中存在 TESLAMATE_ENCRYPTION_KEY"
  log "本脚本不会打印它的值。"
  log ''
  log "请你【自己在自己的终端】里执行下面这条命令读取，并直接经 SSH 写进 ECS："
  log "    grep '^TESLAMATE_ENCRYPTION_KEY=' \"${ENV_FILE}\""
  log ''
  log '红线：这个值绝对不得粘贴进聊天、Obsidian、Git、日志或备份目录。'
  log '      不迁移这个 key，即使 dump 成功，TeslaMate 也读不出 token，必须重新授权。'
else
  log "警告：在 ${ENV_FILE} 中未找到 TESLAMATE_ENCRYPTION_KEY。"
  log '      请确认源端 .env 的路径；没有这个 key，迁移后的 token 无法解开。'
fi

# ---------------------------------------------------------------- 4. pg_dump
rule
log '== 4. pg_dump（custom 格式，compress=9）=='

DUMP_FILE="${OUT_DIR}/teslamate-${STAMP}.dump"
log "输出文件：${DUMP_FILE}"

"${DC[@]}" exec -T "${DB_SERVICE}" pg_dump \
  -U "${DB_USER}" -d "${DB_NAME}" \
  --format=custom --compress=9 --no-owner --no-privileges \
  > "${DUMP_FILE}"

chmod 600 "${DUMP_FILE}"
[[ -s "${DUMP_FILE}" ]] || die "dump 文件为空：${DUMP_FILE}"

DUMP_BYTES="$(wc -c < "${DUMP_FILE}" | tr -d '[:space:]')"
log "dump 大小：${DUMP_BYTES} 字节（$(du -h "${DUMP_FILE}" | cut -f1)）"

# ---------------------------------------------------------------- 5. 分卷与校验和
rule
log '== 5. 分卷与校验和（1 Mbps 断点续传用）=='

cd "${OUT_DIR}"
BASE_NAME="$(basename "${DUMP_FILE}")"

if [[ "${DO_SPLIT}" == 'true' ]] && command -v split >/dev/null 2>&1; then
  log "分卷大小：${SPLIT_SIZE}"
  rm -f "${BASE_NAME}.part-"*
  split -b "${SPLIT_SIZE}" -d -a 3 "${BASE_NAME}" "${BASE_NAME}.part-"
  PARTS=( "${BASE_NAME}.part-"* )
  log "分卷数量：${#PARTS[@]}"
  sha256sum "${BASE_NAME}.part-"* > "${BASE_NAME}.sha256"
  chmod 600 "${BASE_NAME}.sha256" "${BASE_NAME}.part-"*
  log "校验和文件：${OUT_DIR}/${BASE_NAME}.sha256"
  UPLOAD_ITEMS="${BASE_NAME}.part-* ${BASE_NAME}.sha256"
  # 完整 dump 自身的校验和也一并记录，供 ECS 侧合并后二次校验
  sha256sum "${BASE_NAME}" >> "${BASE_NAME}.sha256"
else
  log '不分卷（--no-split 或系统无 split 命令）'
  sha256sum "${BASE_NAME}" > "${BASE_NAME}.sha256"
  UPLOAD_ITEMS="${BASE_NAME} ${BASE_NAME}.sha256"
fi
chmod 600 "${BASE_NAME}.sha256"

# ---------------------------------------------------------------- 6. 传输命令
rule
log '== 6. 传输到 ECS（在你自己的电脑上执行；严禁把家用 5432 暴露公网）=='
log ''
log "cd \"${OUT_DIR}\""
log ''
log 'rsync -avP --partial --append-verify \'
log '  -e "ssh -i ~/.ssh/joviluma_jourvolt_deploy_ed25519" \'
log "  ${UPLOAD_ITEMS} baseline-${STAMP}.txt \\"
log '  jourvolt@120.55.64.11:/home/jourvolt/matelink-selfhost/import/'
log ''
log '（断线后可重复执行同一条 rsync，--partial --append-verify 会续传并校验）'
rule
log '== 7. ECS 侧后续动作 =='
log '在 ECS 上执行：'
log '  cd /home/jourvolt/matelink-selfhost'
log "  bash ./scripts/migrate-teslamate.sh --src-major ${SRC_MAJOR}"
log ''
log '迁移脚本会自动读取 baseline 文件做条数比对，并输出 tokens 条数：'
log '  tokens >= 1  -> 继承 token 成功，可跳过重新授权（P0-9 解除阻塞）'
log '  tokens == 0  -> 仍需重新授权，P0-9 转为阻塞于备案，立即上报'
rule
log "DUMP_LOCAL=PASS  产物目录：${OUT_DIR}"
