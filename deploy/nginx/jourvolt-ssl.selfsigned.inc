# jourvolt-ssl.selfsigned.inc — 自签占位证书片段
# 首次部署 / Let's Encrypt 签发成功前的兜底，保证任何时刻 nginx -t 通过。
# setup-root.sh 首次运行时若证书不存在会自动用 openssl 生成（CN=jourvolt-placeholder）。
ssl_certificate     /etc/nginx/jourvolt-selfsigned.crt;
ssl_certificate_key /etc/nginx/jourvolt-selfsigned.key;
