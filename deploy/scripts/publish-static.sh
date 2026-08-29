#!/usr/bin/env bash
# =============================================================================
# publish-static.sh — 同步静态资产到 /srv/jourvolt/public（T04）
#
# 同步内容：
#   1. /terms/index.html、/privacy/index.html（来自 web_matelink/public）
#   2. /.well-known/assetlinks.json（App Link 校验文件，指纹由参数给出；
#      未提供时写入占位指纹 REPLACE_WITH_FORMAL_RELEASE_CERT_SHA256）
#   3. /.well-known/appspecific/com.tesla.3p.public-key.pem（Tesla 3p 公钥，
#      仅当 --pubkey 指向真实文件时发布）
#
# 用法：
#   bash publish-static.sh                                  # 占位指纹，仅同步法律页
#   bash publish-static.sh --fingerprint AA:BB:...:FF       # 正式指纹
#   bash publish-static.sh --pubkey /path/to/public-key.pem
#   bash publish-static.sh --src /path/to/web_matelink/public --dest /srv/jourvolt/public
#
# 权限约定：目录 root:jourvolt 2775（setgid），文件 644，nginx worker 可读。
# =============================================================================
set -Eeuo pipefail
umask 022

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"

SRC_PUBLIC="${REPO_ROOT}/web_matelink/public"
DEST_PUBLIC="/srv/jourvolt/public"
FINGERPRINT="${ASSETLINKS_FINGERPRINT:-}"
PUBKEY_SRC="${TESLA_PUBLIC_KEY_SRC:-}"
APK_SRC="${APK_SRC:-}"
APP_VERSION="${APP_VERSION:-0.0.0-dev}"

log()  { printf '[publish-static] %s\n' "$*"; }
warn() { printf '[publish-static] WARN: %s\n' "$*" >&2; }
die()  { printf '[publish-static] ABORT: %s\n' "$*" >&2; exit 1; }

usage() {
  printf 'Usage: %s [--src DIR] [--dest DIR] [--fingerprint HEX_OR_COLON] [--pubkey PATH] [--apk PATH] [--version X.Y.Z]\n' "$0" >&2
}

while (($# > 0)); do
  case "$1" in
    --src)         shift; (($# > 0)) || { usage; exit 2; }; SRC_PUBLIC="$1" ;;
    --dest)        shift; (($# > 0)) || { usage; exit 2; }; DEST_PUBLIC="$1" ;;
    --fingerprint) shift; (($# > 0)) || { usage; exit 2; }; FINGERPRINT="$1" ;;
    --pubkey)      shift; (($# > 0)) || { usage; exit 2; }; PUBKEY_SRC="$1" ;;
    --apk)         shift; (($# > 0)) || { usage; exit 2; }; APK_SRC="$1" ;;
    --version)     shift; (($# > 0)) || { usage; exit 2; }; APP_VERSION="$1" ;;
    -h|--help)     usage; exit 0 ;;
    *)             usage; exit 2 ;;
  esac
  shift
done

[[ -d "$SRC_PUBLIC" ]] || die "静态源目录不存在：${SRC_PUBLIC}"

# 确保目标目录骨架存在（setup-root.sh 已建，这里幂等兜底）
if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
  sudo install -d -o root -g jourvolt -m 2775 "$DEST_PUBLIC"
  sudo install -d -o root -g jourvolt -m 2775 "$DEST_PUBLIC/.well-known"
  sudo install -d -o root -g jourvolt -m 2775 "$DEST_PUBLIC/.well-known/appspecific"
  sudo install -d -o root -g jourvolt -m 2775 "$DEST_PUBLIC/terms"
  sudo install -d -o root -g jourvolt -m 2775 "$DEST_PUBLIC/privacy"
  sudo install -d -o root -g jourvolt -m 2775 "$DEST_PUBLIC/download"
else
  mkdir -p "$DEST_PUBLIC/.well-known/appspecific" "$DEST_PUBLIC/terms" "$DEST_PUBLIC/privacy" "$DEST_PUBLIC/download"
fi

# -----------------------------------------------------------------------------
# 1) 法律页 terms / privacy（静态直出，不经后端）
# -----------------------------------------------------------------------------
for page in terms privacy; do
  [[ -f "${SRC_PUBLIC}/${page}/index.html" ]] || die "缺少法律页：${SRC_PUBLIC}/${page}/index.html"
  install -m 0644 "${SRC_PUBLIC}/${page}/index.html" "${DEST_PUBLIC}/${page}/index.html"
  log "已同步 ${page}/index.html"
done

# -----------------------------------------------------------------------------
# 2) assetlinks.json（Android App Link 校验）
# -----------------------------------------------------------------------------
normalized=''
fingerprint_status='PLACEHOLDER'
if [[ -n "$FINGERPRINT" ]]; then
  hex="$(printf '%s' "$FINGERPRINT" | tr -d ' :-' | tr '[:lower:]' '[:upper:]')"
  [[ "$hex" =~ ^[0-9A-F]{64}$ ]] || die "指纹必须是 32 字节十六进制（支持冒号/连字符分隔）"
  for ((i = 0; i < ${#hex}; i += 2)); do
    [[ -z "$normalized" ]] || normalized+=':'
    normalized+="${hex:i:2}"
  done
  fingerprint_status='REAL'
else
  normalized='REPLACE_WITH_FORMAL_RELEASE_CERT_SHA256'
  warn "未提供 --fingerprint：assetlinks.json 写入占位指纹，App Link 公网校验不生效（P1-3 待办）"
fi

install -m 0644 /dev/null "${DEST_PUBLIC}/.well-known/assetlinks.json"
cat > "${DEST_PUBLIC}/.well-known/assetlinks.json" <<JSON
[
  {
    "relation": [
      "delegate_permission/common.handle_all_urls"
    ],
    "target": {
      "namespace": "android_app",
      "package_name": "com.matelink",
      "sha256_cert_fingerprints": [
        "${normalized}"
      ]
    }
  }
]
JSON
log "已发布 assetlinks.json（指纹状态：${fingerprint_status}）"

# -----------------------------------------------------------------------------
# 3) Tesla 3p 公钥
# -----------------------------------------------------------------------------
if [[ -n "$PUBKEY_SRC" ]]; then
  [[ -f "$PUBKEY_SRC" ]] || die "Tesla 公钥源文件不存在：${PUBKEY_SRC}"
  install -m 0644 "$PUBKEY_SRC" \
    "${DEST_PUBLIC}/.well-known/appspecific/com.tesla.3p.public-key.pem"
  log "已发布 Tesla 3p 公钥"
else
  warn "未提供 --pubkey：Tesla 3p 公钥未发布（影响 Fleet API 授权，P0-9 相关待办）"
fi

# -----------------------------------------------------------------------------
# 4) APK 下载页（极简，noindex；正式签名 APK 由后续构建流程提供）
# -----------------------------------------------------------------------------
apk_status='PLACEHOLDER'
if [[ -n "$APK_SRC" ]]; then
  [[ -f "$APK_SRC" ]] || die "APK 源文件不存在：${APK_SRC}"
  install -m 0644 "$APK_SRC" "${DEST_PUBLIC}/download/app-release.apk"
  apk_status='REAL'
else
  if [[ ! -f "${DEST_PUBLIC}/download/app-release.apk" ]]; then
    install -m 0644 /dev/null "${DEST_PUBLIC}/download/app-release.apk"
    printf 'PLACEHOLDER: replace this file with the formal signed com.matelink release APK (build-pilot-apk.ps1 output).\n' \
      > "${DEST_PUBLIC}/download/app-release.apk"
  fi
  warn "未提供 --apk：app-release.apk 为占位文件，无法安装（待正式构建流程提供）"
fi

cat > "${DEST_PUBLIC}/download/index.html" <<HTML
<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex,nofollow">
<title>MateLink 下载</title>
<style>body{font-family:system-ui,sans-serif;max-width:28rem;margin:3rem auto;padding:0 1rem;color:#222}
button{font-size:1.1rem;padding:.8rem 2rem;border-radius:.5rem;border:0;background:#1a73e8;color:#fff;width:100%}
small{color:#666;display:block;margin-top:1rem;line-height:1.6}</style></head>
<body><h1>MateLink</h1><p>版本：${APP_VERSION}</p>
<a href="app-release.apk" download><button>下载 APK</button></a>
<small>Android 8.0+。安装时如提示"未知来源"，请在系统设置中允许本浏览器安装应用。<br>
本页为邀请制内测分发页面，请勿外传链接。</small></body></html>
HTML
log "已发布 download/index.html（版本 ${APP_VERSION}，APK 状态：${apk_status}）"

# -----------------------------------------------------------------------------
# 5) 权限收口：目录 2775 root:jourvolt，文件 644（nginx worker 可读）
# -----------------------------------------------------------------------------
if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
  sudo find "$DEST_PUBLIC" -type d -exec chown root:jourvolt {} + 2>/dev/null || true
  sudo find "$DEST_PUBLIC" -type f -exec chown root:jourvolt {} + 2>/dev/null || true
  sudo find "$DEST_PUBLIC" -type d -exec chmod 2775 {} + 2>/dev/null || true
  sudo find "$DEST_PUBLIC" -type f -exec chmod 0644 {} + 2>/dev/null || true
  log "权限已收口（root:jourvolt，目录 2775 / 文件 644）"
else
  chmod -R a+rX "$DEST_PUBLIC"
  warn "无免密 sudo：已按 a+rX 兜底，属主修正跳过"
fi

echo "PUBLISH_STATIC=PASS (fingerprint=${fingerprint_status}, pubkey=$([[ -n "$PUBKEY_SRC" ]] && echo yes || echo pending), apk=${apk_status})"
