#!/usr/bin/env bash
# =============================================================================
# setup-root.sh — JourVolt T04 公网入口幂等部署脚本（方案 A′）
# 由具备免密 sudo 的 jourvolt 账户在 ECS 上执行。
#
# 职责（架构文档 7.1 蓝本）：
#   R0  前置检查（sudo / nginx / star-photo 未占用 default_server）
#   R1  独立 nginx 配置（jourvolt.conf）+ 自签兜底证书，nginx -t 通过后 reload
#   R2  certbot certonly --webroot 签发（不碰任何 nginx 配置）；成功后切换 LE 片段
#   R3  证书自动续期（certbot-renew.timer，失败回落 cron）
#   护栏 star-photo.conf 备份 + diff 校验，被改动即回滚中止
#
# 用法：
#   ACME_EMAIL='ops@example.com' bash ./setup-root.sh            # 正式签发
#   SKIP_CERT=true bash ./setup-root.sh                          # 只做配置 + 自签兜底
#   ACME_EMAIL 未提供或为占位符时：自动跳过签发（自签兜底），LE 列为待办。
#
# 安全：本脚本不打印任何密钥 / token 值。
# =============================================================================
set -Eeuo pipefail
umask 022

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
NGINX_SRC_DIR="${REPO_ROOT}/deploy/nginx"

DOMAIN_SELFHOST="${DOMAIN_SELFHOST:-teslalink.joviluma.com}"
DOMAIN_API="${DOMAIN_API:-api.teslalink.joviluma.com}"
DOMAIN_APPLINK="${DOMAIN_APPLINK:-auth.teslalink.joviluma.com}"
CERT_NAME="jourvolt"
SKIP_CERT="${SKIP_CERT:-false}"
ACME_EMAIL="${ACME_EMAIL:-}"
CERTBOT_RETRIES="${CERTBOT_RETRIES:-3}"
TS="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="/root"
STAR_PHOTO_CONF="/etc/nginx/conf.d/star-photo.conf"
NGINX_CONF_DIR="/etc/nginx/conf.d"

log()  { printf '[setup-root] %s\n' "$*"; }
warn() { printf '[setup-root] WARN: %s\n' "$*" >&2; }
die()  { printf '[setup-root] ABORT: %s\n' "$*" >&2; exit 1; }

# 判断 ACME 邮箱是否可用（为空或含占位符 '<' 均视为不可用）
acme_email_usable() {
  [[ -n "$ACME_EMAIL" && "$ACME_EMAIL" != *'<'* && "$ACME_EMAIL" == *@* ]]
}

# -----------------------------------------------------------------------------
# R0 前置检查
# -----------------------------------------------------------------------------
log "R0 前置检查 ..."
sudo -n true || die "免密 sudo 不可用（sudo -n true 失败）；本脚本要求方案 A′ 前提"
command -v nginx >/dev/null 2>&1 || die "nginx 未安装"
nginx -v 2>&1

if sudo grep -q 'default_server' "$STAR_PHOTO_CONF"; then
  # default_server 只接管"未匹配任何 server_name"的请求；按名称精确匹配的
  # server 块不受影响，可安全共存。仅当 star-photo 抢注了本部署域名时才中止。
  warn "star-photo.conf 使用 default_server（已人工评审：不影响按 server_name 精确匹配）"
  if sudo grep -E '^\s*server_name[^;]*(teslalink\.joviluma\.com)' "$STAR_PHOTO_CONF" | grep -v '^\s*#' | grep -q .; then
    die "star-photo.conf 的 server_name 与本部署域名冲突，需人工评审"
  fi
  log "OK: star-photo 的 server_name 不含本部署域名，可共存"
fi

# 记录 nginx -t warning 基线（当前基线：2 条 conflicting server name "_"）
NGINX_T_BASELINE="$(sudo nginx -t 2>&1 | grep -c 'warn' || true)"
log "nginx -t warning 基线 = ${NGINX_T_BASELINE} 条"

# -----------------------------------------------------------------------------
# 护栏：备份 star-photo.conf（带时间戳，只备份不改写）
# -----------------------------------------------------------------------------
STAR_PHOTO_BAK="${BACKUP_DIR}/star-photo.conf.bak.${TS}"
sudo cp -a "$STAR_PHOTO_CONF" "$STAR_PHOTO_BAK"
log "已备份 star-photo.conf -> ${STAR_PHOTO_BAK}"

# -----------------------------------------------------------------------------
# R4 目录与权限（幂等）
# -----------------------------------------------------------------------------
log "R4 创建目录与权限 ..."
sudo install -d -o root -g jourvolt -m 2775 /srv/jourvolt/public
sudo install -d -o root -g jourvolt -m 2775 /srv/jourvolt/public/.well-known
sudo install -d -o root -g jourvolt -m 2775 /srv/jourvolt/public/.well-known/appspecific
sudo install -d -o root -g jourvolt -m 2775 /srv/jourvolt/acme
sudo install -d -o root -g jourvolt -m 0750 /srv/jourvolt-backups
sudo install -d -o root -g jourvolt -m 0750 /etc/jourvolt

# SELinux 上下文（Alibaba Cloud Linux 3 通常为 disabled/permissive；有则修，无则跳过）
if command -v getenforce >/dev/null 2>&1 && [[ "$(getenforce)" == 'Enforcing' ]]; then
  if command -v semanage >/dev/null 2>&1; then
    sudo semanage fcontext -a -t httpd_sys_content_t '/srv/jourvolt/public(/.*)?' || true
    sudo restorecon -Rv /srv/jourvolt/public >/dev/null || true
    sudo semanage fcontext -a -t httpd_sys_content_t '/srv/jourvolt/acme(/.*)?' || true
    sudo restorecon -Rv /srv/jourvolt/acme >/dev/null || true
  else
    warn "SELinux Enforcing 但 semanage 不可用，跳过 fcontext 修正"
  fi
fi

# -----------------------------------------------------------------------------
# R1 nginx 独立配置 + 证书 include 片段
# -----------------------------------------------------------------------------
log "R1 写入证书 include 片段 ..."

sudo tee "${NGINX_CONF_DIR}/jourvolt-ssl.selfsigned.inc" >/dev/null <<'INC'
ssl_certificate     /etc/nginx/jourvolt-selfsigned.crt;
ssl_certificate_key /etc/nginx/jourvolt-selfsigned.key;
INC

sudo tee "${NGINX_CONF_DIR}/jourvolt-ssl.le.inc" >/dev/null <<'INC'
ssl_certificate     /etc/letsencrypt/live/jourvolt/fullchain.pem;
ssl_certificate_key /etc/letsencrypt/live/jourvolt/privkey.pem;
INC

# 自签兜底证书（幂等：已存在则不再生成）
if [[ ! -f /etc/nginx/jourvolt-selfsigned.crt || ! -f /etc/nginx/jourvolt-selfsigned.key ]]; then
  log "生成自签占位证书（CN=jourvolt-placeholder，有效期 3650 天）..."
  sudo openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
    -subj '/CN=jourvolt-placeholder' \
    -keyout /etc/nginx/jourvolt-selfsigned.key \
    -out    /etc/nginx/jourvolt-selfsigned.crt 2>/dev/null
  sudo chmod 600 /etc/nginx/jourvolt-selfsigned.key
  sudo chmod 644 /etc/nginx/jourvolt-selfsigned.crt
fi

# 当前生效片段：默认自签（LE 签发成功后切换）
sudo cp -a "${NGINX_CONF_DIR}/jourvolt-ssl.selfsigned.inc" "${NGINX_CONF_DIR}/jourvolt-ssl.inc"

# 渲染模板（仅替换三个域名占位符）
[[ -f "${NGINX_SRC_DIR}/jourvolt.conf.template" ]] || die "模板缺失：${NGINX_SRC_DIR}/jourvolt.conf.template"
log "渲染 jourvolt.conf ..."
sed -e "s/%SELFHOST%/${DOMAIN_SELFHOST}/g" \
    -e "s/%API%/${DOMAIN_API}/g" \
    -e "s/%APPLINK%/${DOMAIN_APPLINK}/g" \
    "${NGINX_SRC_DIR}/jourvolt.conf.template" | sudo tee "${NGINX_CONF_DIR}/jourvolt.conf" >/dev/null

sudo nginx -t 2>&1 | sed 's/^/[nginx -t] /'
NGINX_T_AFTER="$(sudo nginx -t 2>&1 | grep -c 'warn' || true)"
if (( NGINX_T_AFTER > NGINX_T_BASELINE )); then
  die "nginx -t warning 数从 ${NGINX_T_BASELINE} 增加到 ${NGINX_T_AFTER}，中止"
fi
log "R1 生效：nginx -t 通过（warning ${NGINX_T_AFTER} 条，未超基线），reload nginx"
sudo systemctl reload nginx

# -----------------------------------------------------------------------------
# R2 证书签发（certbot certonly --webroot，完全不读不写 nginx 配置）
# -----------------------------------------------------------------------------
CERT_ISSUED='false'
if [[ "${SKIP_CERT}" == 'true' ]]; then
  log "SKIP_CERT=true：跳过签发，证书仍为自签占位"
elif ! acme_email_usable; then
  log "ACME_EMAIL 未提供或为占位符（值不打印）：跳过签发；"
  log "Let's Encrypt 签发列为待办——请提供真实联系邮箱后重跑："
  log "  ACME_EMAIL='ops@example.com' bash ./setup-root.sh"
else
  if ! command -v certbot >/dev/null 2>&1; then
    log "安装 certbot ..."
    sudo dnf install -y certbot \
      || { sudo dnf install -y epel-release && sudo dnf install -y certbot; } \
      || die "certbot 安装失败"
  fi

  CERT_OK='false'
  attempt=1
  while (( attempt <= CERTBOT_RETRIES )); do
    log "certbot 签发第 ${attempt}/${CERTBOT_RETRIES} 次尝试 ..."
    if sudo certbot certonly --webroot -w /srv/jourvolt/acme \
        --cert-name "${CERT_NAME}" \
        -d "${DOMAIN_SELFHOST}" -d "${DOMAIN_API}" -d "${DOMAIN_APPLINK}" \
        --agree-tos -m "${ACME_EMAIL}" --no-eff-email \
        --keep-until-expiring --non-interactive \
        --deploy-hook 'systemctl reload nginx'; then
      CERT_OK='true'
      break
    fi
    warn "第 ${attempt} 次签发失败（若表现为 5xx/拦截页，可能是备案同步延迟）"
    (( attempt < CERTBOT_RETRIES )) && sleep 30
    attempt=$((attempt + 1))
  done

  if [[ "$CERT_OK" == 'true' ]]; then
    CERT_ISSUED='true'
    log "签发成功：切换 include 片段为 Let's Encrypt 路径并 reload"
    sudo cp -a "${NGINX_CONF_DIR}/jourvolt-ssl.le.inc" "${NGINX_CONF_DIR}/jourvolt-ssl.inc"
    sudo nginx -t 2>&1 | sed 's/^/[nginx -t] /'
    sudo systemctl reload nginx
  else
    warn "签发连续 ${CERTBOT_RETRIES} 次失败：保持自签兜底，443 可用但浏览器将告警"
    warn "待办：备案同步完成 / 邮箱就绪后重跑本脚本（幂等，已签发则 --keep-until-expiring 自动保留）"
  fi
fi

# -----------------------------------------------------------------------------
# 护栏：确认 star-photo.conf 未被改动，否则回滚并中止
# -----------------------------------------------------------------------------
if ! sudo diff -q "$STAR_PHOTO_BAK" "$STAR_PHOTO_CONF" >/dev/null; then
  warn "star-photo.conf 被意外修改，回滚！"
  sudo cp -a "$STAR_PHOTO_BAK" "$STAR_PHOTO_CONF"
  sudo nginx -t && sudo systemctl reload nginx
  die "已回滚 star-photo.conf 并中止"
fi
log "护栏通过：star-photo.conf 与备份无差异"

# -----------------------------------------------------------------------------
# R3 证书自动续期
# -----------------------------------------------------------------------------
if sudo systemctl list-unit-files 2>/dev/null | grep -q '^certbot-renew\.timer'; then
  sudo systemctl enable --now certbot-renew.timer
  log "certbot-renew.timer 已启用"
else
  sudo tee /etc/cron.d/jourvolt-certbot-renew >/dev/null <<'CRON'
0 3 * * * root certbot renew --quiet --deploy-hook "systemctl reload nginx"
CRON
  log "certbot-renew.timer 不存在，已写入 /etc/cron.d/jourvolt-certbot-renew（每日 03:00）"
fi
if [[ "$CERT_ISSUED" == 'true' ]]; then
  log "certbot renew --dry-run 校验续期链路 ..."
  sudo certbot renew --dry-run >/dev/null 2>&1 && log "续期 dry-run 通过" \
    || warn "续期 dry-run 失败（不阻塞部署，需人工检查）"
fi

# -----------------------------------------------------------------------------
# 服务端自检（安全组 R6 只能人工在阿里云控制台操作）
# -----------------------------------------------------------------------------
log "监听端口自检（期望：无 0.0.0.0/[::] 上的 4000/8080/5432/1883/18080/18090）"
# 只看 Local Address 列（$4）；回环绑定（127.0.0.1）不算对外
EXTERNAL_LEAK="$(sudo ss -lntp | awk '$4 ~ /:(4000|8080|5432|1883|18080|18090)$/ {print $4}' \
  | grep -E '^(0\.0\.0\.0|\*|\[::\]):' || true)"
if [[ -z "$EXTERNAL_LEAK" ]]; then
  log "OK：以上端口均未对外监听（18080/18090 仅绑定 127.0.0.1）"
else
  warn "发现对外监听：${EXTERNAL_LEAK}"
fi
log "443 监听确认："
sudo ss -lntp | grep ':443 ' || warn "443 未监听（异常，检查 nginx）"

echo "=============================================================="
if [[ "$CERT_ISSUED" == 'true' ]]; then
  echo "SETUP_ROOT=PASS (TLS=letsencrypt)"
elif [[ "${SKIP_CERT}" == 'true' || -z "${ACME_EMAIL}" ]]; then
  echo "SETUP_ROOT=PASS_WITH_SELFSIGNED (TLS=pending, 原因: SKIP_CERT 或缺邮箱)"
else
  echo "SETUP_ROOT=PASS_WITH_SELFSIGNED (TLS=pending, 原因: 签发失败，重跑脚本即可)"
fi
echo "TODO(人工·阿里云控制台): 放行 443；确认 4000/8080/5432/1883/18080/18090 未放行"
echo "=============================================================="
