#!/usr/bin/env bash
# =============================================================================
# verify-public.sh — JourVolt 公网入口自检（T04）
#
# 在 ECS 服务器上执行（也可从任意外部机器执行 DNS/443 部分）。
# 检查项：
#   1. DNS：三个域名经公共 DNS（223.5.5.5）解析到本机公网 IP
#   2. 443 可达 + 严格 TLS 链/名称/SNI/前后 peer 一致性
#   3. HTTP -> HTTPS 301 重定向
#   4. 四个静态 URL（assetlinks / terms / privacy / Tesla 3p 公钥）
#   5. /api/matelink/v1/capabilities 行为：无 token 401，绝不发送 token
#   6. 4000/8080/5432/1883/18080/18090 未对外（本机监听 + 外部连接双检）
#
# 环境变量：
#   PUBLIC_IP          公网 IP（默认 120.55.64.11）
#   本脚本不接受/发送凭据，认证请求验收另行授权
#
# 退出码：0 = 全部通过（或仅有预期内 WARN）；1 = 存在 FAIL。
# =============================================================================
set -u -o pipefail

PUBLIC_IP="${PUBLIC_IP:-120.55.64.11}"
DOMAIN_SELFHOST="${DOMAIN_SELFHOST:-teslalink.joviluma.com}"
DOMAIN_API="${DOMAIN_API:-api.teslalink.joviluma.com}"
DOMAIN_APPLINK="${DOMAIN_APPLINK:-auth.teslalink.joviluma.com}"
DOMAINS=("$DOMAIN_SELFHOST" "$DOMAIN_API" "$DOMAIN_APPLINK")

PASS=0; FAIL=0; WARN=0
ok()   { printf '  [PASS] %s\n' "$*"; PASS=$((PASS+1)); }
bad()  { printf '  [FAIL] %s\n' "$*"; FAIL=$((FAIL+1)); }
warnk(){ printf '  [WARN] %s\n' "$*"; WARN=$((WARN+1)); }

# Mandatory TLS trust/hostname validation. No insecure -k for public HTTPS.
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CURL=(curl -sS --max-time 15 --max-filesize 16384)
if ! python3 - "$PUBLIC_IP" <<'PY'
import ipaddress, sys
try:
    value = ipaddress.IPv4Address(sys.argv[1])
    assert str(value) == sys.argv[1]
except (ValueError, AssertionError):
    sys.exit(2)
PY
then
  echo "VERIFY_PUBLIC=INVALID_PUBLIC_IP" >&2
  exit 2
fi


echo "=== 1. DNS（公共 DNS 223.5.5.5，排除本机 fake-IP）==="
for d in "${DOMAINS[@]}"; do
  resolved=''
  if command -v nslookup >/dev/null 2>&1; then
    resolved="$(nslookup "$d" 223.5.5.5 2>/dev/null \
      | awk '/^Address/ && $NF !~ /223\.5\.5\.5/ {print $NF; exit}')"
  fi
  if [[ -z "$resolved" ]] && command -v getent >/dev/null 2>&1; then
    resolved="$(getent ahostsv4 "$d" 2>/dev/null | awk 'NR==1{print $1}')"
  fi
  if [[ "$resolved" == "$PUBLIC_IP" ]]; then
    ok "DNS $d -> ${resolved}"
  elif [[ "$resolved" == 198.18.* || "$resolved" == 198.19.* ]]; then
    bad "DNS $d -> ${resolved}（fake-IP，判定失败）"
  else
    bad "DNS $d -> ${resolved:-无解析}（期望 ${PUBLIC_IP}）"
  fi
done

echo "=== 2. Bounded public TLS and socket qualification ==="
if python3 "${SCRIPT_DIR}/check-socket-port.py" "$PUBLIC_IP" 443; then
  ok "TCP 443 reachable at configured public address"
else
  bad "TCP 443 unreachable at configured public address"
fi
# Each named TLS peer is verified with normal system CA, hostname and SNI.
# The API additionally requires two validated unauthenticated health 200s.
for hostname in "$DOMAIN_SELFHOST" "$DOMAIN_API" "$DOMAIN_APPLINK"; do
  if [[ "$hostname" == "$DOMAIN_API" ]]; then
    if timeout 20s python3 "${SCRIPT_DIR}/../../tools/energy-stage/qualify-public-tls.py" \
         --live --host "$hostname" --samples 2; then
      ok "API validated peer and bounded public health"
    else
      bad "API peer/health validation failed"
    fi
  else
    if timeout 20s python3 "${SCRIPT_DIR}/../../tools/energy-stage/qualify-public-tls.py" \
         --live --host "$hostname" --tls-only --samples 2; then
      ok "Public TLS hostname and chain validated"
    else
      bad "Public TLS hostname/chain validation failed"
    fi
  fi
done

echo "=== 3. HTTP -> HTTPS 301 ==="
for d in "${DOMAINS[@]}"; do
  code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' "http://${d}/" 2>/dev/null || echo 000)"
  if [[ "$code" == '301' ]]; then
    ok "http://${d}/ -> 301"
  else
    bad "http://${d}/ 期望 301，实际 ${code}（备案拦截也可能表现为 000/5xx）"
  fi
done

echo "=== 4. 静态 URL（auth 主机）==="
check_url() { # $1=url $2=期望内容关键词（可空）
  local url="$1" keyword="$2" body code file
  file="$(mktemp)" || { bad "Secure temporary output unavailable"; return; }
  code="$("${CURL[@]}" -o "$file" -w '%{http_code}' "$url" 2>/dev/null || echo 000)"
  body="$(cat "$file" 2>/dev/null || true)"
  rm -f -- "$file"
  if [[ "$code" == '200' ]]; then
    if [[ -z "$keyword" || "$body" == *"$keyword"* ]]; then
      ok "200 ${url}"
    else
      bad "200 但内容缺少 '${keyword}'：${url}"
    fi
  else
    bad "期望 200，实际 ${code}：${url}"
  fi
}
check_url "https://${DOMAIN_APPLINK}/.well-known/assetlinks.json" 'com.matelink'
check_url "https://${DOMAIN_APPLINK}/terms/" '<html'
check_url "https://${DOMAIN_APPLINK}/privacy/" '<html'
# Tesla 公钥：源文件未提供时 404 属预期（待办），不算 FAIL
PUBKEY_LOCAL="/srv/jourvolt/public/.well-known/appspecific/com.tesla.3p.public-key.pem"
if [[ -f "$PUBKEY_LOCAL" ]]; then
  check_url "https://${DOMAIN_APPLINK}/.well-known/appspecific/com.tesla.3p.public-key.pem" ''
else
  warnk "Tesla 3p 公钥未发布（publish-static.sh --pubkey 待办）；auth 主机该 URL 当前 404 属预期"
fi
check_url "https://${DOMAIN_APPLINK}/download/" 'MateLink'

echo "=== 5. capabilities 端点行为（https://${DOMAIN_SELFHOST}）==="
code="$("${CURL[@]}" -o /dev/null -w '%{http_code}' \
  "https://${DOMAIN_SELFHOST}/api/matelink/v1/capabilities" 2>/dev/null || echo 000)"
if [[ "$code" == '401' ]]; then
  ok "无 token -> 401（鉴权生效，反代链路通）"
elif [[ "$code" == '000' ]]; then
  bad "无响应（TLS / 反代 / 安全组问题）"
elif [[ "$code" == '502' || "$code" == '504' ]]; then
  bad "502/504：nginx 反代不通，检查 127.0.0.1:18080 上 Adapter 是否在跑"
else
  bad "Unauthenticated capabilities returned unexpected status (expected 401)"
fi
# Public TLS qualification is credential-free by design. An authenticated
# capabilities assertion belongs to a separately authorized device/API check.
echo "  [SKIP] Authenticated capabilities NOT_RUN (no credentials sent)"

echo "=== 6. 内部端口未对外 ==="
if command -v ss >/dev/null 2>&1; then
  # 只取 Local Address 列（$4）判断绑定地址；不能整行 grep，否则会误匹配 peer 列
  leak="$(ss -lntpH 2>/dev/null | awk '$4 ~ /:(4000|8080|5432|1883|18080|18090)$/ {print $4}' \
    | grep -E '^(0\.0\.0\.0|\*|\[::\]):' || true)"
  if [[ -z "$leak" ]]; then
    ok "本机无 4000/8080/5432/1883/18080/18090 的对外监听（仅回环绑定）"
  else
    bad "发现对外监听：${leak}"
  fi
else
  warnk "ss 不可用，跳过本机监听检查"
fi
for port in 4000 8080 5432 1883; do
  # Check only when loopback service exists; never interpolate shell code.
  if python3 "${SCRIPT_DIR}/check-socket-port.py" 127.0.0.1 "$port" &&
     python3 "${SCRIPT_DIR}/check-socket-port.py" "$PUBLIC_IP" "$port"; then
    bad "Private port unexpectedly accessible externally"
  else
    ok "Private port not exposed by paired loopback/public check"
  fi
done

echo "=============================================================="
echo "VERIFY_PUBLIC: PASS=${PASS} FAIL=${FAIL} WARN=${WARN}"
echo "=============================================================="
(( FAIL == 0 )) && exit 0 || exit 1
