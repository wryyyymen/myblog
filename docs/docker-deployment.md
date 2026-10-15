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
docker compose --env-file /root/myblog-db.env up -d --build
docker compose --env-file /root/myblog-db.env ps
curl -f http://127.0.0.1:8081/api/categories
```

`config --quiet` 检查 Compose 文件和必需的环境变量；`up -d --build` 从源码构建镜像并在后台启动。Dockerfile 从 AWS Public ECR 获取 Docker 官方 Maven 和 Eclipse Temurin 镜像，因为当前服务器无法连接 Docker Hub。构建时 Maven 使用宿主机网络，以访问服务器可达的 Maven 仓库。首次构建需要下载镜像和 Maven 依赖，服务器内存较小时可能需要额外的交换空间。构建过程不会运行测试；部署前应在开发环境运行后端测试。

如果启动失败，查看日志：

```bash
docker compose --env-file /root/myblog-db.env logs --tail=100 backend
```

更新代码后，在仓库目录执行 `git pull`，再执行 `docker compose --env-file /root/myblog-db.env up -d --build`。不要在 Compose 中新增 MySQL 容器，否则会与已迁移的数据库变成两套数据。
