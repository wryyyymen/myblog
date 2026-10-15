# MyBlog

一个逐步完善中的博客学习项目，使用 Vue 3 + TypeScript + Spring Boot + MySQL。

## 已有功能

- 用户注册、登录、退出与 Session 登录状态恢复。
- 文章列表、详情、分页和分类筛选。
- 登录后发布文章，作者本人或管理员可以编辑、删除文章。
- 前端响应式页面、表单校验和请求状态提示。

## 项目目录

```text
backend/                 Spring Boot 后端（默认端口 8081）
  scripts/               开发用测试数据脚本
  src/main/              接口、实体、Repository 和配置模板
  src/test/              后端测试
frontend/                Vue 前端（Vite）
  src/views/             页面
  src/components/        组件
  src/stores/            登录状态等状态管理
  src/assets/            公共样式
```

## 本地启动

环境要求：Java 17 或更高版本、MySQL 8，以及满足 `frontend/package.json` 中 engines 要求的 Node.js（`^22.18.0 || >=24.12.0`）。后端提供 Maven Wrapper，无需单独安装 Maven。

### 1. 配置数据库

准备本地 `myblog` 数据库。当前项目使用现有开发数据库，仓库尚未提供完整的数据库初始化脚本或数据导出。JPA 的 `ddl-auto: update` 仅管理已映射的实体，不能完整还原开发数据库中的所有表及约束。

在项目根目录执行（PowerShell）：

```powershell
Copy-Item backend/src/main/resources/application.example.yaml backend/src/main/resources/application.yaml
```

在生成的 `application.yaml` 中调整数据库地址和账号。密码可在本地配置中填写，或在启动后端的终端中设置 `DB_PASSWORD` 环境变量。实际 `application.yaml` 已加入 Git 忽略规则。

### 2. 启动后端

```powershell
cd backend
$env:DB_PASSWORD = '填写你的本地数据库密码'
.\mvnw.cmd spring-boot:run
```

后端地址：`http://localhost:8081`。

### 3. 启动前端

另开一个终端，在项目根目录执行：

```powershell
cd frontend
npm ci
npm run dev
```

访问终端输出的地址。Vite 已将 `/api` 请求代理到 `http://127.0.0.1:8081`。

### 构建与测试

前端生产构建：

```powershell
cd frontend
npm run build
```

后端测试（上下文测试需要可连接且表结构匹配的 MySQL 数据库）：

```powershell
cd backend
.\mvnw.cmd test "-Dspring.jpa.hibernate.ddl-auto=validate" "-Dspring.sql.init.mode=never" "-Ddebug=false"
```

`backend/scripts/seed-blog-test-data.sql` 是开发过程中的示例数据脚本，依赖已有表结构和名为 `potato` 的用户，不能作为完整初始化脚本直接使用。

## 后端开发规范

后续功能遵循 Controller、Service、Repository 分层，使用独立 DTO、集中会话管理和业务异常处理。

详细职责及新增功能流程见 [后端结构与开发标准](docs/backend-architecture.md)。项目开发助手约定见 [AGENTS.md](AGENTS.md)。
