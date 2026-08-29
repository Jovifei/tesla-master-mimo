# jourvolt-ssl.le.inc — Let's Encrypt 证书片段
# certbot certonly --webroot 签发成功后，由 setup-root.sh 把
# /etc/nginx/conf.d/jourvolt-ssl.inc 切换为本片段内容并 reload。
# 续期由 certbot-renew.timer 自动完成（deploy-hook: systemctl reload nginx）。
ssl_certificate     /etc/letsencrypt/live/jourvolt/fullchain.pem;
ssl_certificate_key /etc/letsencrypt/live/jourvolt/privkey.pem;
