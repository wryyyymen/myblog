# 后端 Docker 部署

本方案只把 Spring Boot 后端放进容器。MySQL 使用服务器上已经迁移好的 `myblog`，前端仍由宿主机 Nginx 提供。容器通过 Linux 的 host 网络访问 `127.0.0.1:3306`，后端只监听服务器的 `127.0.0.1:8081`，供 Nginx 转发 `/api/`。

## 本地准备

在仓库根目录执行：

```powershell
Copy-Item .env.example .env
```

编辑 `.env`，设置实际的 `DB_PASSWORD`。`.env` 已被 Git 忽略，不要提交密码。服务器已经保存了专用账号的密码，可直接使用服务器上的 `/root/myblog-db.env`，无须把它下载到本机。

## 服务器构建和启动

在 Ubuntu 服务器执行：

```bash
git clone https://github.com/wryyyymen/myblog.git
cd myblog
docker compose --env-file /root/myblog-db.env config --quiet
docker buildx build --allow=network.host --network=host --load -t myblog-backend:local ./backend
docker compose --env-file /root/myblog-db.env up -d
docker compose --env-file /root/myblog-db.env ps
curl -f http://127.0.0.1:8081/api/categories
```

`config --quiet` 检查 Compose 文件和必需的环境变量；`docker buildx build` 从源码构建镜像；`docker compose up -d` 在后台启动容器。Dockerfile 从 AWS Public ECR 获取 Docker 官方 Maven 和 Eclipse Temurin 镜像，因为当前服务器无法连接 Docker Hub。构建时 Maven 使用宿主机网络，以访问服务器可达的 Maven 仓库。首次构建需要下载镜像和 Maven 依赖，服务器内存较小时可能需要额外的交换空间。构建过程不会运行测试；部署前应在开发环境运行后端测试。

如果启动失败，查看日志：

```bash
docker compose --env-file /root/myblog-db.env logs --tail=100 backend
```

更新代码后，在仓库目录执行 `git pull`，再按上面的 `docker buildx build` 和 `docker compose up -d` 两条命令重新构建和启动。不要在 Compose 中新增 MySQL 容器，否则会与已迁移的数据库变成两套数据。

## 前端与 Nginx

服务器需要安装 Nginx，并准备至少 2 GiB 交换空间供前端构建使用。当前服务器已配置交换空间。在仓库目录执行：

```bash
apt-get install -y nginx
bash deploy/build-frontend.sh
install -d -m 755 /var/www/myblog
cp -a frontend/dist/. /var/www/myblog/
install -d -m 755 /etc/nginx/snippets
cp deploy/nginx/myblog-websocket.conf /etc/nginx/snippets/myblog-websocket.conf
cp deploy/nginx/myblog.conf /etc/nginx/sites-available/myblog
ln -sfn /etc/nginx/sites-available/myblog /etc/nginx/sites-enabled/myblog
rm -f /etc/nginx/sites-enabled/default
nginx -t
systemctl reload nginx
```

前端使用相对路径 `/api/...`；Nginx 将这些请求转发到本机后端 `127.0.0.1:8081`，其余路径作为 Vue 页面处理。访问文章详情等地址时，`try_files` 会返回前端的 `index.html`。更新前端后重新运行构建脚本，并复制新的 `dist` 文件，再重载 Nginx。

### 已有宝塔 HTTPS 站点

当前 `stefanblog.top` 由 `/www/server/nginx/sbin/nginx` 管理，实际站点配置位于 `/www/server/panel/vhost/nginx/stefanblog.top.conf`，它已包含 `extension/stefanblog.top/*.conf`。此时保留原域名和证书配置，把 `deploy/nginx/myblog-websocket.conf` 安装为 `/www/server/panel/vhost/nginx/extension/stefanblog.top/tank-websocket.conf`，使用该 Nginx 的 `-t` 和 `-s reload`，不要用另一套 systemd Nginx 替换 HTTPS 站点。

浏览器使用 HTTPS 访问站点，Nginx 通过本机 HTTP 转发到后端。`myblog-websocket.conf` 保留原 `Host`，并用 `X-Forwarded-Proto $scheme` 告诉后端外部协议。Compose 的 `SERVER_FORWARD_HEADERS_STRATEGY=native` 让 Tomcat 读取来自本机代理的转发头，把请求协议和端口还原为 HTTPS 和 443；这样 `Origin: https://stefanblog.top` 才能通过 WebSocket 的同源检查。后端继续只监听 `127.0.0.1`，不同来源的连接仍会被拒绝。

WS 学习源码仍由 Git 管理。本地 Vite 显示 `/game/ws-test`；生产前端不注册该路由或导航，容器也不携带 `ws-client.html`，Compose 使用 `DEMO_WEBSOCKET_ENABLED=false` 关闭 `/myWs` 示例及定时任务。Nginx 对学习页面和 `/myWs` 返回 404，仅放行正式坦克联机的 `/ws/`。

### 首次部署坦克地图表

现有生产库缺少 `game_map` 时，先在服务器备份数据库，再执行 `backend/scripts/2026-11-19-game-map.sql`。脚本只创建新表，并在地图 1 不存在时加入默认地图；不修改现有博客文章、用户或已有地图。继续保持 `ddl-auto=validate` 和 `spring.sql.init.mode=never`。

## 公网访问

当前服务器位于华东 2（上海），公网 IP 是 `8.153.39.56`。阿里云安全组 `sg-uf60jmnfvrzhlhbjymo7` 需要允许入方向 TCP `80/80`、来源 `0.0.0.0/0`。后端 `8081` 和 MySQL `3306` 只在服务器本机使用，不需要对公网开放。

放行后检查 `http://8.153.39.56/`、`http://8.153.39.56/articles/1` 和 `http://8.153.39.56/api/categories`。目前使用公网 IP 提供 HTTP 服务；以后有域名时再配置 HTTPS。
