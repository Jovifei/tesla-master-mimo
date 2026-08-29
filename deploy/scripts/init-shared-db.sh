#!/usr/bin/env bash
#
# init-shared-db.sh —— 幂等创建共享 PostgreSQL 的「双库双角色」
#
# 目标形态（架构文档 3.2.1：共用实例分库）：
#   * 超级用户角色 jourvolt（由 POSTGRES_USER 建好，这里保证幂等 + 刷新口令）
#   * 应用角色      teslamate（TeslaMate / TeslaMateApi / Adapter 共用）
#   * 数据库        jourvolt（P1 Go API 用）
#   * 数据库        teslamate（TeslaMate 用，也是 pg_restore 的目标库）
#
# 安全约束：
#   * 所有口令只经【stdin 文件流】注入 psql，绝不出现在命令行 argv，因此 `ps` 看不到；
#   * 默认走容器内的 Unix socket（官方 postgres 镜像对 local 连接是 trust），连口令都不需要；
#   * 本脚本不打印任何密钥值。
#
# 幂等性：可重复执行任意次数，结果一致；已存在的角色只刷新口令，已存在的库跳过创建。
#
# 用法：
#   bash ./scripts/init-shared-db.sh
#
set -Eeuo pipefail
umask 077

PROJECT_DIR="${PROJECT_DIR:-/home/jourvolt/matelink-selfhost}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.selfhost.yml}"
SUPERUSER="${SUPERUSER:-jourvolt}"

cd "${PROJECT_DIR}"

# ---------------------------------------------------------------- 通用工具
log() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# 安全的 .env 加载器：只认 KEY=VALUE，跳过注释与空行，去掉成对引号；
# 用 printf -v 赋值，避免未加引号的值破坏语法。
load_env() {
  local file="$1" line key value
  [[ -f "${file}" ]] || return 0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line}" ]] && continue
    [[ "${line}" =~ ^[[:space:]]*# ]] && continue
    [[ "${line}" != *=* ]] && continue
    key="${line%%=*}"
    value="${line#*=}"
    key="$(printf '%s' "${key}" | tr -d '[:space:]')"
    [[ -z "${key}" ]] && continue
    if [[ "${value}" == \"*\" && "${value}" == *\" ]]; then value="${value:1:${#value}-2}"; fi
    if [[ "${value}" == \'*\' && "${value}" == *\' ]]; then value="${value:1:${#value}-2}"; fi
    printf -v "${key}" '%s' "${value}"
    export "${key}"
  done < "${file}"
}

# SQL 字面量转义：单引号翻倍
sql_quote() {
  local s="${1//\'/\'\'}"
  printf "'%s'" "${s}"
}

compose() { docker compose -f "${COMPOSE_FILE}" "$@"; }

# ---------------------------------------------------------------- 前置检查
command -v docker >/dev/null 2>&1 || die '缺少 docker 命令'
docker info >/dev/null 2>&1 || die 'docker 守护进程不可访问'
[[ -f "${COMPOSE_FILE}" ]] || die "在 ${PROJECT_DIR} 下找不到 ${COMPOSE_FILE}"

load_env "${PROJECT_DIR}/.env"

JOURVOLT_DB_PASSWORD="${JOURVOLT_DB_PASSWORD:-}"
TESLAMATE_DB_PASSWORD="${TESLAMATE_DB_PASSWORD:-}"
[[ -n "${JOURVOLT_DB_PASSWORD}" ]] || die '缺少 JOURVOLT_DB_PASSWORD（检查 .env 是否已填）'
[[ -n "${TESLAMATE_DB_PASSWORD}" ]] || die '缺少 TESLAMATE_DB_PASSWORD（检查 .env 是否已填）'

# ---------------------------------------------------------------- 等待 postgres 就绪
log '== 等待 postgres 容器进入 healthy =='
DEADLINE=$((SECONDS + 180))
PG_READY='false'
while ((SECONDS < DEADLINE)); do
  status="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' \
    "$(compose ps -q postgres 2>/dev/null | head -n1)" 2>/dev/null || echo missing)"
  if [[ "${status}" == 'healthy' ]]; then
    PG_READY='true'
    break
  fi
  sleep 3
done
[[ "${PG_READY}" == 'true' ]] || die 'postgres 容器 180 秒内未进入 healthy，先执行 docker compose up -d postgres'

# ---------------------------------------------------------------- 建立连接方式
# 官方 postgres 镜像的 pg_hba 对 Unix socket（local）连接默认为 trust，
# 因此默认无需口令；口令也就不会出现在任何 argv 里。
# 若环境被改成非 trust，则退回 passfile：口令经 stdin 写进容器内 0600 文件。
PSQL_MODE='trust'
if ! compose exec -T postgres psql -U "${SUPERUSER}" -d postgres -X -q -tAc 'select 1' >/dev/null 2>&1; then
  log '提示：Unix socket 非 trust 认证，改用容器内 passfile（口令经 stdin 写入，不进 argv）'
  compose exec -T postgres sh -c 'umask 077; cat > "$1"; chmod 600 "$1"' sh /tmp/.jourvolt.pgpass <<EOF
*:*:*:${SUPERUSER}:${JOURVOLT_DB_PASSWORD}
EOF
  PSQL_MODE='passfile'
fi

# 在 postgres 容器内执行 SQL；SQL 内容经 stdin 注入。
run_psql() {
  local db="$1"
  if [[ "${PSQL_MODE}" == 'trust' ]]; then
    compose exec -T postgres psql -U "${SUPERUSER}" -d "${db}" -X -q -v ON_ERROR_STOP=1
  else
    compose exec -T postgres psql -w -X -q -v ON_ERROR_STOP=1 \
      "host=/var/run/postgresql user=${SUPERUSER} dbname=${db} passfile=/tmp/.jourvolt.pgpass"
  fi
}

# ---------------------------------------------------------------- 创建角色与库
log '== 幂等创建角色与数据库 =='
run_psql postgres <<SQL
-- 应用角色 teslamate（存在则只刷新口令，保证幂等）
DO \$\$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'teslamate') THEN
    ALTER ROLE teslamate WITH LOGIN SUPERUSER PASSWORD $(sql_quote "${TESLAMATE_DB_PASSWORD}");
  ELSE
    CREATE ROLE teslamate WITH LOGIN SUPERUSER PASSWORD $(sql_quote "${TESLAMATE_DB_PASSWORD}");
  END IF;
END
\$\$;

-- 超级用户角色 jourvolt（POSTGRES_USER 通常已建，这里兜底 + 刷新口令）
DO \$\$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jourvolt') THEN
    ALTER ROLE jourvolt WITH LOGIN SUPERUSER PASSWORD $(sql_quote "${JOURVOLT_DB_PASSWORD}");
  ELSE
    CREATE ROLE jourvolt WITH LOGIN SUPERUSER PASSWORD $(sql_quote "${JOURVOLT_DB_PASSWORD}");
  END IF;
END
\$\$;

-- CREATE DATABASE 不能在事务块内执行，用 \gexec 让 psql 逐条发出
SELECT 'CREATE DATABASE teslamate OWNER teslamate'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'teslamate')\gexec

SELECT 'CREATE DATABASE jourvolt OWNER jourvolt'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'jourvolt')\gexec

GRANT ALL PRIVILEGES ON DATABASE teslamate TO teslamate;
GRANT ALL PRIVILEGES ON DATABASE teslamate TO jourvolt;
GRANT ALL PRIVILEGES ON DATABASE jourvolt TO jourvolt;
SQL

# ---------------------------------------------------------------- 预置扩展
# TeslaMate 的首次迁移（20190925152807 CreateGeoExtensions）会依次执行：
#   CREATE EXTENSION IF NOT EXISTS cube WITH SCHEMA public;
#   CREATE EXTENSION IF NOT EXISTS earthdistance WITH SCHEMA public;
#   ALTER FUNCTION ll_to_earth SET search_path = public;
# 这三步在纯 least-privilege 角色下都会失败：
#   * earthdistance 不是 trusted 扩展 -> 建扩展需要超级用户；
#   * 扩展由超级用户建好后，ll_to_earth 属主是超级用户 -> ALTER FUNCTION 需要属主或超级用户。
# 因此这里的处理是：由超级用户 jourvolt 预先建好两个扩展（之后 TeslaMate 的 IF NOT EXISTS
# 走 no-op 分支，不再触发建扩展的权限检查），并把 teslamate 提升为超级用户，
# 使后续 ALTER FUNCTION 也能通过。
#
# 注：PostgreSQL 的 ALTER EXTENSION 没有 OWNER TO 子句，扩展成员对象的属主只能逐个
# ALTER FUNCTION / ALTER TYPE 去改；既然 teslamate 已是超级用户，就不必再做这一步。
#
# 同时给 teslamate 角色授予 SUPERUSER：这是官方 TeslaMate compose 的默认形态
# （POSTGRES_USER=teslamate），也与源端家用模板一致。理由有三：
#   1) 兼容源库：T1② 迁移过来的对象在源端就由超级用户 teslamate 拥有；
#   2) 消除上述整类迁移/扩展权限失败；
#   3) 收口可控：postgres 的 5432 未发布到宿主机，只能从 jourvolt-infra 项目内网访问。
log '== 预置 cube / earthdistance 扩展 =='
run_psql teslamate <<'SQL'
CREATE EXTENSION IF NOT EXISTS cube WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS earthdistance WITH SCHEMA public;
GRANT ALL ON SCHEMA public TO teslamate;
SQL

# ---------------------------------------------------------------- 校验
log '== 校验结果 =='
run_psql postgres <<'SQL'
SELECT datname AS database, pg_get_userbyid(datdba) AS owner
FROM pg_database
WHERE datname IN ('jourvolt', 'teslamate')
ORDER BY datname;
SQL

log ''
run_psql postgres <<'SQL'
SELECT rolname AS role, rolsuper AS is_superuser, rolcanlogin AS can_login
FROM pg_roles
WHERE rolname IN ('jourvolt', 'teslamate')
ORDER BY rolname;
SQL

# 清理临时 passfile（若用过）
if [[ "${PSQL_MODE}" == 'passfile' ]]; then
  compose exec -T postgres sh -c 'rm -f /tmp/.jourvolt.pgpass' >/dev/null 2>&1 || true
fi

log ''
log 'INIT_SHARED_DB=PASS'
log '下一步：bash ./scripts/migrate-teslamate.sh（有 dump 时）或直接 docker compose up -d（冷启动）'
