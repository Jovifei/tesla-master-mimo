#!/usr/bin/env bash
#
# migrate-teslamate.sh —— 【在 ECS 上执行】把源 TeslaMate 历史恢复到共享 PostgreSQL
#
# 流程：
#   1) 版本兼容检查（pg_dump 只能「低版本 -> 高版本」，反之明确报错并给出处置指令）
#   2) 分卷合并 + sha256 校验
#   3) 幂等建双库双角色（init-shared-db.sh）
#   4) pg_restore 到 teslamate 库（口令不进 argv）
#   5) 属主修正（--no-owner 恢复的场景下把对象还回 teslamate 角色）
#   6) 恢复后条数校验，与源库基线逐项比对；明确输出 tokens 条数
#
# 用法：
#   bash ./scripts/migrate-teslamate.sh --src-major 18
#   bash ./scripts/migrate-teslamate.sh --src-major 18 --dump ./import/teslamate-20260828-120000.dump
#   bash ./scripts/migrate-teslamate.sh --verify-only   # 只做条数校验，不恢复
#
set -Eeuo pipefail
umask 077

PROJECT_DIR="${PROJECT_DIR:-/home/jourvolt/matelink-selfhost}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.selfhost.yml}"
SUPERUSER="${SUPERUSER:-jourvolt}"
IMPORT_DIR="${IMPORT_DIR:-${PROJECT_DIR}/import}"

SRC_MAJOR="${SRC_MAJOR:-}"
DUMP_FILE="${DUMP_FILE:-}"
BASELINE_FILE="${BASELINE_FILE:-}"
VERIFY_ONLY="${VERIFY_ONLY:-false}"
# 默认保留原属主（不用 --no-owner），因为 ECS 侧角色名与源端一致（teslamate）。
# 若源端角色名不同，用 PGRESTORE_NO_OWNER=true 强制去掉属主，脚本会在恢复后修正属主。
PGRESTORE_NO_OWNER="${PGRESTORE_NO_OWNER:-false}"

while (($# > 0)); do
  case "$1" in
    --src-major) SRC_MAJOR="${2:?--src-major 需要数字，如 16/17/18}"; shift 2 ;;
    --dump) DUMP_FILE="${2:?--dump 需要 dump 文件路径}"; shift 2 ;;
    --baseline) BASELINE_FILE="${2:?--baseline 需要基线文件路径}"; shift 2 ;;
    --verify-only) VERIFY_ONLY='true'; shift ;;
    -h | --help) sed -n '2,18p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$1（用 --help 查看用法）" >&2; exit 2 ;;
  esac
done

cd "${PROJECT_DIR}"

log() { printf '%s\n' "$*"; }
rule() { printf '%s\n' '=============================================================='; }
die() { printf 'ABORT: %s\n' "$*" >&2; exit 1; }
warn() { printf 'WARN: %s\n' "$*" >&2; }

# 安全的 .env 加载器
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

compose() { docker compose -f "${COMPOSE_FILE}" "$@"; }

command -v docker >/dev/null 2>&1 || die '缺少 docker 命令'
docker info >/dev/null 2>&1 || die 'docker 守护进程不可访问'
[[ -f "${COMPOSE_FILE}" ]] || die "找不到 ${COMPOSE_FILE}"
load_env "${PROJECT_DIR}/.env"

# ---------------------------------------------------------------- 建立数据库会话
DEADLINE=$((SECONDS + 120))
PG_READY='false'
while ((SECONDS < DEADLINE)); do
  cid="$(compose ps -q postgres 2>/dev/null | head -n1 || true)"
  if [[ -n "${cid}" ]]; then
    st="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "${cid}" 2>/dev/null || echo missing)"
    [[ "${st}" == 'healthy' ]] && { PG_READY='true'; break; }
  fi
  sleep 3
done
[[ "${PG_READY}" == 'true' ]] || die 'postgres 未 healthy，先执行 docker compose up -d postgres'

PSQL_MODE='trust'
if ! compose exec -T postgres psql -U "${SUPERUSER}" -d postgres -X -q -tAc 'select 1' >/dev/null 2>&1; then
  JOURVOLT_DB_PASSWORD="${JOURVOLT_DB_PASSWORD:-}"
  [[ -n "${JOURVOLT_DB_PASSWORD}" ]] || die '非 trust 认证且缺少 JOURVOLT_DB_PASSWORD'
  compose exec -T postgres sh -c 'umask 077; cat > "$1"; chmod 600 "$1"' sh /tmp/.jourvolt.pgpass <<EOF
*:*:*:${SUPERUSER}:${JOURVOLT_DB_PASSWORD}
EOF
  PSQL_MODE='passfile'
fi

run_psql() {
  local db="$1"
  if [[ "${PSQL_MODE}" == 'trust' ]]; then
    compose exec -T postgres psql -U "${SUPERUSER}" -d "${db}" -X -q -v ON_ERROR_STOP=1
  else
    compose exec -T postgres psql -w -X -q -v ON_ERROR_STOP=1 \
      "host=/var/run/postgresql user=${SUPERUSER} dbname=${db} passfile=/tmp/.jourvolt.pgpass"
  fi
}

# 取单个标量值；SQL 失败时以非零码返回，让调用方能区分「0 条」与「查询失败」
db_scalar() {
  local db="$1" sql="$2" out
  out="$(run_psql "${db}" <<SQL 2>/dev/null
${sql}
SQL
)" || return 1
  printf '%s' "${out}" | tr -d '[:space:]'
}

# ---------------------------------------------------------------- 1. 版本兼容检查
rule
log '== 1. 版本兼容检查 =='

CUR_NUM="$(db_scalar postgres "select current_setting('server_version_num');")"
CUR_MAJOR=$((CUR_NUM / 10000))
CUR_VERSION="$(db_scalar postgres 'SHOW server_version;')"
log "ECS  PostgreSQL : ${CUR_VERSION}（大版本 ${CUR_MAJOR}）"

# 源大版本：命令行 -> 环境变量 -> baseline 文件 -> 报错
if [[ -z "${SRC_MAJOR}" && -z "${BASELINE_FILE}" ]]; then
  for candidate in "${IMPORT_DIR}"/baseline-*.txt; do
    [[ -f "${candidate}" ]] || continue
    BASELINE_FILE="${candidate}"
  done
fi
if [[ -z "${SRC_MAJOR}" && -n "${BASELINE_FILE}" && -f "${BASELINE_FILE}" ]]; then
  while IFS='=' read -r key value || [[ -n "${key}" ]]; do
    [[ "${key}" == 'src_major' ]] && SRC_MAJOR="${value}"
  done < "${BASELINE_FILE}"
  log "从基线文件读取源大版本：${BASELINE_FILE}"
fi
[[ -n "${SRC_MAJOR}" ]] || die '缺少源库大版本，用 --src-major <数字> 指定（dump-teslamate-local.sh 的输出里会给出）'

log "源库 PostgreSQL : 大版本 ${SRC_MAJOR}"
log "规则             : pg_dump 只能「低版本 -> 高版本」"

if ((SRC_MAJOR > CUR_MAJOR)); then
  log ''
  die "源库 PG${SRC_MAJOR} 高于 ECS PG${CUR_MAJOR}，pg_restore 会失败。
处置（二选一）：
  1) 把 .env 的 POSTGRES_IMAGE 设为 postgres:${SRC_MAJOR}-alpine，
     然后执行：docker compose down postgres && docker volume rm matelink-selfhost_selfhost-postgres
     && docker compose up -d postgres   # 注意：这会销毁共享实例里的全部数据
  2) 保持 ECS 版本不变，改走 Plan B 冷启动（ECS 从今天开始采集，历史留在手机与家用机）"
fi
log 'VERSION_CHECK=PASS'

# ---------------------------------------------------------------- 2. 定位 dump
rule
log '== 2. 定位并校验 dump =='

if [[ -z "${DUMP_FILE}" ]]; then
  for candidate in "${IMPORT_DIR}"/teslamate-*.dump; do
    [[ -f "${candidate}" ]] || continue
    DUMP_FILE="${candidate}"
  done
fi
[[ -n "${DUMP_FILE}" && -f "${DUMP_FILE}" ]] || die "在 ${IMPORT_DIR} 下找不到 teslamate-*.dump，先用 --dump 指定路径"
log "dump 文件：${DUMP_FILE}（$(du -h "${DUMP_FILE}" | cut -f1)）"

# 若有分卷且完整 dump 缺失/为空，则先合并
DUMP_BASE="$(basename "${DUMP_FILE}")"
DUMP_DIR="$(cd "$(dirname "${DUMP_FILE}")" && pwd)"
DUMP_FILE="${DUMP_DIR}/${DUMP_BASE}"
PARTS=( "${DUMP_DIR}/${DUMP_BASE}.part-"* )
if (( ${#PARTS[@]} > 0 )) && [[ -f "${PARTS[0]}" ]]; then
  log "检测到 ${#PARTS[@]} 个分卷，校验后合并"
  if [[ -f "${DUMP_DIR}/${DUMP_BASE}.sha256" ]]; then
    (cd "${DUMP_DIR}" && sha256sum -c --ignore-missing "${DUMP_BASE}.sha256") \
      || die '分卷 sha256 校验失败，说明传输不完整，请重跑 rsync 续传'
    log 'sha256 校验通过'
  else
    warn '没有 sha256 文件，跳过校验（不推荐）'
  fi
  cat "${PARTS[@]}" > "${DUMP_FILE}"
  log "合并完成：${DUMP_FILE}（$(du -h "${DUMP_FILE}" | cut -f1)）"
fi

[[ -s "${DUMP_FILE}" ]] || die "dump 文件为空：${DUMP_FILE}"

if [[ -f "${DUMP_FILE}.sha256" ]]; then
  (cd "${DUMP_DIR}" && sha256sum -c --ignore-missing "${DUMP_BASE}.sha256") \
    && log '完整 dump 的 sha256 校验通过' \
    || warn '完整 dump 的 sha256 校验未通过（若分卷阶段已校验过可忽略）'
fi

# ---------------------------------------------------------------- 3. 只做校验
count_table() {
  local db="$1" table="$2"
  local n
  n="$(db_scalar "${db}" "select count(*) from ${table};" 2>/dev/null || echo NA)"
  printf '%s' "${n}"
}

verify_counts() {
  log ''
  log '-- 恢复后条数 --'
  local n_cars n_drives n_charges n_tokens n_positions
  n_cars="$(count_table teslamate cars)"
  n_drives="$(count_table teslamate drives)"
  n_charges="$(count_table teslamate charges)"
  n_tokens="$(count_table teslamate tokens)"
  n_positions="$(count_table teslamate positions)"

  log "cars      = ${n_cars}"
  log "drives    = ${n_drives}"
  log "charges   = ${n_charges}"
  log "tokens    = ${n_tokens}"
  log "positions = ${n_positions}（参考值）"

  log ''
  if [[ "${n_tokens}" =~ ^[0-9]+$ ]] && ((n_tokens >= 1)); then
    log 'TOKENS=OK：tokens 表非空 —— Tesla token 继承成功。'
    log '           只要 TESLAMATE_ENCRYPTION_KEY 与源端一致，TeslaMate 直接用既有 token 采集，'
    log '           P0-9（TeslaMate 首次授权）可跳过，不再阻塞于备案 + 公网 443。'
  else
    log 'TOKENS=EMPTY：tokens 表为空 —— 没有继承到 token。'
    log '              需要重新授权，P0-9 转为阻塞于备案 + 443 + Tesla 公钥，请立即上报主理人。'
  fi

  if [[ -n "${BASELINE_FILE}" && -f "${BASELINE_FILE}" ]]; then
    log ''
    log "-- 与源库基线比对（${BASELINE_FILE}）--"
    local key expected actual verdict='MATCH'
    for key in cars drives charges tokens; do
      expected="$(grep -E "^${key}=" "${BASELINE_FILE}" | head -n1 | cut -d= -f2- || true)"
      case "${key}" in
        cars) actual="${n_cars}" ;;
        drives) actual="${n_drives}" ;;
        charges) actual="${n_charges}" ;;
        tokens) actual="${n_tokens}" ;;
      esac
      if [[ -z "${expected}" ]]; then
        log "  ${key}: 基线缺失，跳过"
        continue
      fi
      if [[ "${expected}" == "${actual}" ]]; then
        log "  ${key}: 源=${expected} 目标=${actual}  OK"
      else
        log "  ${key}: 源=${expected} 目标=${actual}  MISMATCH"
        verdict='MISMATCH'
      fi
    done
    log "COUNT_CHECK=${verdict}"
  else
    warn '没有基线文件，跳过条数比对（把 dump-teslamate-local.sh 产出的 baseline-*.txt 一起传过来）'
  fi
}

if [[ "${VERIFY_ONLY}" == 'true' ]]; then
  rule
  log '== --verify-only：只做条数校验 =='
  verify_counts
  rule
  exit 0
fi

# ---------------------------------------------------------------- 4. 建库建角色
rule
log '== 3. 幂等建双库双角色 =='
bash ./scripts/init-shared-db.sh >/dev/null 2>&1 \
  && log 'INIT_SHARED_DB=PASS' \
  || die 'init-shared-db.sh 执行失败，单独执行查看详细输出'

# ---------------------------------------------------------------- 5. pg_restore
rule
log '== 4. pg_restore 到 teslamate 库 =='

RESTORE_ARGS=(--no-privileges --clean --if-exists --exit-on-error)
if [[ "${PGRESTORE_NO_OWNER}" == 'true' ]]; then
  RESTORE_ARGS+=(--no-owner)
  log 'PGRESTORE_NO_OWNER=true：忽略原属主，恢复后统一修正为 teslamate'
else
  log '保留原属主（角色名 teslamate 与源端一致）'
fi

# 目标库用 -d 指定；trust 模式下给库名，passfile 模式下 -d 接受完整 conninfo
# （pg_restore 的位置参数是归档文件路径，不接受 conninfo，因此绝不能把 conninfo 当位置参数传）
if [[ "${PSQL_MODE}" == 'trust' ]]; then
  RESTORE_TARGET=('--dbname=teslamate' "--username=${SUPERUSER}")
else
  RESTORE_TARGET=("--dbname=host=/var/run/postgresql user=${SUPERUSER} dbname=teslamate passfile=/tmp/.jourvolt.pgpass")
fi

# dump 经 stdin 注入：口令不出现在 argv / ps
compose exec -T postgres pg_restore "${RESTORE_ARGS[@]}" "${RESTORE_TARGET[@]}" -v \
  < "${DUMP_FILE}" 2>&1 | tail -n 30
log 'pg_restore 完成'

# ---------------------------------------------------------------- 6. 属主修正
rule
log '== 5. 属主修正（幂等：只处理仍属 jourvolt 的对象）=='
run_psql teslamate <<'SQL'
DO $$
DECLARE obj record;
BEGIN
  FOR obj IN
    SELECT nspname
    FROM pg_namespace
    WHERE nspname NOT LIKE 'pg\_%' AND nspname <> 'information_schema'
      AND nspowner = (SELECT oid FROM pg_roles WHERE rolname = 'jourvolt')
  LOOP
    EXECUTE format('ALTER SCHEMA %I OWNER TO teslamate', obj.nspname);
  END LOOP;
END
$$;

DO $$
DECLARE obj record;
BEGIN
  FOR obj IN
    SELECT n.nspname AS sch, c.relname AS rel, c.relkind AS kind
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'
      AND c.relkind IN ('r','v','S','m','p')
      AND c.relowner = (SELECT oid FROM pg_roles WHERE rolname = 'jourvolt')
  LOOP
    EXECUTE format('ALTER %s %I.%I OWNER TO teslamate',
      CASE obj.kind WHEN 'S' THEN 'SEQUENCE' WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END,
      obj.sch, obj.rel);
  END LOOP;
END
$$;
SQL
log '属主修正完成'

# ---------------------------------------------------------------- 7. 校验
rule
log '== 6. 恢复后校验 =='
verify_counts

if [[ "${PSQL_MODE}" == 'passfile' ]]; then
  compose exec -T postgres sh -c 'rm -f /tmp/.jourvolt.pgpass' >/dev/null 2>&1 || true
fi

# ---------------------------------------------------------------- 8. 收尾提示
rule
log '== 7. 收尾动作 =='
log '执行下面两条命令让 TeslaMate 用继承到的 token 开始采集：'
log '  docker compose up -d teslamate'
log '  docker compose logs -f --tail=100 teslamate'
log '期望：日志不再要求登录/授权，出现车辆在线并开始写入。'
log '若 token 解开失败（key 不一致，或源端用 Fleet API 且绑定了源域名/IP），'
log '退回重新授权 —— 此时 P0-9 阻塞于备案 + 443 + Tesla 公钥，须立即上报，不要自行绕过。'
rule
log 'MIGRATE=PASS（请以上方 COUNT_CHECK / TOKENS 结论为准）'
