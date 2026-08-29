#!/usr/bin/env bash
# =============================================================================
# verify-public.sh — JourVolt 公网入口自检（T04）
#
# 在 ECS 服务器上执行（也可从任意外部机器执行 DNS/443 部分）。
# 检查项：
#   1. DNS：三个域名经公共 DNS（223.5.5.5）解析到本机公网 IP
#   2. 443 可达 + TLS 证书 issuer / 有效期
#   3. HTTP -> HTTPS 301 重定向
#   4. 四个静态 URL（assetlinks / terms / privacy / Tesla 3p 公钥）
#   5. /api/matelink/v1/capabilities 行为：无 token 401；带 token（可选）200
#   6. 4000/8080/5432/1883/18080/18090 未对外（本机监听 + 外部连接双检）
#
# 环境变量：
#   PUBLIC_IP          公网 IP（默认 120.55.64.11）
#   MATE_LINK_API_TOKEN 可选；设置后额外校验带 token 的 capabilities 200
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

# curl：-k（自签兜底阶段浏览器会告警属预期；LE 签发后同样可用）
CURL="curl -sk --max-time 15"

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

echo "=== 2. 443 可达 + 证书 ==="
for d in "${DOMAINS[@]}"; do
  if timeout 8 bash -c "echo > /dev/tcp/${PUBLIC_IP}/443" 2>/dev/null; then
    ok "TCP 443 可达（${PUBLIC_IP}）"
  else
    bad "TCP 443 不可达（检查 nginx reload / 安全组 R6）"
    break
  fi
done
CERT_CHECK="$(echo | openssl s_client -connect "${PUBLIC_IP}:443" \
  -servername "${DOMAIN_SELFHOST}" 2>/dev/null | openssl x509 -noout -issuer -enddate 2>/dev/null || true)"
if [[ -z "$CERT_CHECK" ]]; then
  bad "无法读取证书（TLS 握手失败）"
else
  echo "  $CERT_CHECK" | tr '\n' ' '; echo
  if grep -q "Let's Encrypt" <<<"$CERT_CHECK"; then
    ok "证书 issuer 为 Let's Encrypt"
  elif grep -q 'jourvolt-placeholder' <<<"$CERT_CHECK"; then
    warnk "证书仍为自签占位（Let's Encrypt 签发待办中，属预期内过渡状态）"
  else
    warnk "证书 issuer 非预期：$(grep issuer <<<"$CERT_CHECK" || true)"
  fi
  ENDDATE="$(grep notAfter <<<"$CERT_CHECK" | cut -d= -f2 || true)"
  if [[ -n "$ENDDATE" ]]; then
    END_EPOCH="$(date -d "$ENDDATE" +%s 2>/dev/null || echo 0)"
    DAYS_LEFT=$(( (END_EPOCH - $(date +%s)) / 86400 ))
    if (( DAYS_LEFT > 60 )); then
      ok "证书剩余 ${DAYS_LEFT} 天（> 60 天）"
    else
      warnk "证书剩余 ${DAYS_LEFT} 天（LE 证书要求 > 60 天；自签为 3650 天不会触发）"
    fi
  fi
fi

echo "=== 3. HTTP -> HTTPS 301 ==="
for d in "${DOMAINS[@]}"; do
  code="$($CURL -o /dev/null -w '%{http_code}' "http://${d}/" 2>/dev/null || echo 000)"
  if [[ "$code" == '301' ]]; then
    ok "http://${d}/ -> 301"
  else
    bad "http://${d}/ 期望 301，实际 ${code}（备案拦截也可能表现为 000/5xx）"
  fi
done

echo "=== 4. 静态 URL（auth 主机）==="
check_url() { # $1=url $2=期望内容关键词（可空）
  local url="$1" keyword="$2" body code
  code="$($CURL -o /tmp/verify-body.$$ -w '%{http_code}' "$url" 2>/dev/null || echo 000)"
  body="$(cat /tmp/verify-body.$$ 2>/dev/null || true)"; rm -f /tmp/verify-body.$$
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
code="$($CURL -o /dev/null -w '%{http_code}' \
  "https://${DOMAIN_SELFHOST}/api/matelink/v1/capabilities" 2>/dev/null || echo 000)"
if [[ "$code" == '401' ]]; then
  ok "无 token -> 401（鉴权生效，反代链路通）"
elif [[ "$code" == '000' ]]; then
  bad "无响应（TLS / 反代 / 安全组问题）"
elif [[ "$code" == '502' || "$code" == '504' ]]; then
  bad "502/504：nginx 反代不通，检查 127.0.0.1:18080 上 Adapter 是否在跑"
else
  warnk "无 token 期望 401，实际 ${code}（需人工复核）"
fi
if [[ -n "${MATE_LINK_API_TOKEN:-}" ]]; then
  code="$($CURL -o /dev/null -w '%{http_code}' \
    "https://${DOMAIN_SELFHOST}/api/matelink/v1/capabilities" \
    -H "Authorization: Bearer ${MATE_LINK_API_TOKEN}" 2>/dev/null || echo 000)"
  if [[ "$code" == '200' ]]; then
    ok "带 token -> 200"
  else
    bad "带 token 期望 200，实际 ${code}"
  fi
else
  echo "  [SKIP] 未设置 MATE_LINK_API_TOKEN，跳过带 token 校验"
fi

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
  if timeout 4 bash -c "echo > /dev/tcp/127.0.0.1/${port}" 2>/dev/null && \
     timeout 4 bash -c "exec 3<>/dev/tcp/${PUBLIC_IP}/${port}" 2>/dev/null; then
    bad "端口 ${port} 对 ${PUBLIC_IP} 可建立连接（不应放行）"
  else
    ok "端口 ${port} 公网不可达（预期）"
  fi
done

echo "=============================================================="
echo "VERIFY_PUBLIC: PASS=${PASS} FAIL=${FAIL} WARN=${WARN}"
echo "=============================================================="
(( FAIL == 0 )) && exit 0 || exit 1
