#!/usr/bin/env bash
# 把官网（docs/ 目录里的静态站点）部署到自己的服务器。
#
# 用法：
#   ./tools/deploy-site.sh                      # 用下面的默认值
#   HOST=ser651 WEBROOT=/var/www/shizako ./tools/deploy-site.sh
#
# 依赖：本机有 ssh 与 scp（Windows 上 Git 自带的 OpenSSH 即可），
#       且已在 ~/.ssh/config 里配好对应 Host（免密登录）。
#
# 首次部署后，如果站点没起来，检查 nginx（见文件末尾的配置示例）。

set -euo pipefail

HOST="${HOST:-ser651}"
WEBROOT="${WEBROOT:-/var/www/shizako}"
# 站点在仓库里的位置（相对本脚本）
SITE_DIR="$(cd "$(dirname "$0")/.." && pwd)/docs"

if [ ! -f "$SITE_DIR/index.html" ]; then
  echo "找不到 $SITE_DIR/index.html —— 请在仓库根目录执行" >&2
  exit 1
fi

echo "==> 目标：$HOST:$WEBROOT"
ssh "$HOST" "mkdir -p '$WEBROOT'"

# 用 tar 管道传输：不依赖服务器上有 rsync，且能保留目录结构
echo "==> 上传静态文件"
tar -C "$SITE_DIR" -czf - . | ssh "$HOST" "tar -xzf - -C '$WEBROOT'"

# 顺手把权限设成 nginx 能读的样子
ssh "$HOST" "chmod -R a+rX '$WEBROOT' && find '$WEBROOT' -type d -exec chmod 755 {} + && find '$WEBROOT' -type f -exec chmod 644 {} +"

echo "==> 完成。文件清单："
ssh "$HOST" "ls -1 '$WEBROOT'"

cat <<'TIP'

提示：如果这是该域名的第一个站点，需要在服务器上放一份 nginx 配置，例如
/etc/nginx/conf.d/shizako.conf ：

    server {
        listen 80;
        server_name 你的域名;

        root /var/www/shizako;
        index index.html;

        # 静态站点：直接给文件，找不到就 404（不要回落到 index.html，
        # 否则拼错的资源路径会返回 HTML，反而更难排查）
        location / {
            try_files $uri $uri/ =404;
        }

        # SVG / 图片缓存久一点
        location ~* \.(svg|png|jpg|jpeg|webp|css|js)$ {
            expires 7d;
            add_header Cache-Control "public";
        }
    }

然后：

    nginx -t && systemctl reload nginx
    # HTTPS（可选，需要域名已解析到本机）：
    certbot --nginx -d 你的域名

TIP
