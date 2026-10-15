# 后端结构与后续开发标准

## 当前目录

```text
com.syf.blog
├── controller       HTTP 接口、参数绑定和 OpenAPI 文档
├── service          文章、用户、分类业务及操作权限
├── dto
│   ├── article      创建、编辑文章的请求
│   ├── user         注册、登录请求及公开用户响应
│   └── category     分类响应
├── auth             Session 的读取、建立、退出及账号有效性检查
├── config           可复用的框架组件配置，例如 PasswordEncoder
├── exception        业务异常和统一 HTTP 错误转换
├── repository       JPA 数据访问
└── domain           JPA 数据库实体
```

Controller → Service → Repository → 数据库。Controller 需要登录身份时调用 CurrentUserService，再把可信用户传入对应业务服务。

## 具体例子

- 注册：UserController 接收 RegisterRequest → UserService 检查重名、编码密码并保存 → 返回 RegisterResponse。
- 登录：UserService 校验密码和账号状态 → CurrentUserService 建立会话并更换会话 ID → 返回 UserResponse。
- 查询当前用户：CurrentUserService 读取会话并查询最新账号状态 → 转为 UserResponse；用户实体和密码哈希不直接返回。
- 分类管理查询：Controller 获取当前用户 → CategoryService 检查 ADMIN → 查询全部分类 → 转为 CategoryResponse。
- 编辑文章：Controller 绑定 UpdateArticleRequest → ArticleService 检查文章存在及管理员/作者权限 → 事务内更新。

身份信息只保存在服务端会话中，接口请求不得决定自己的用户 ID、作者 ID 或角色。

## 新功能的实施顺序

1. 定义路径、请求和响应字段、状态码与权限规则。
2. 根据实际输入创建请求 DTO，写校验和字段说明；只有查询且无复杂输入的接口不强行创建请求 DTO。
3. 在 Service 实现业务规则、操作权限和必要的事务。
4. 在 Repository 添加数据访问方法。
5. 编写简洁的 Controller，并补齐接口文档。
6. 添加响应 DTO，明确对外字段；简单字段映射可以直接写在 DTO 的静态 from 方法中。
7. 验证成功、未登录、无权限、参数不合法、资源不存在以及本功能涉及的数据库约束场景。
8. 完成后端接口后，再接入前端页面。

## 当前实现中的边界

- Session 相关的 Servlet 依赖集中在 auth 包；业务 Service 接收已确认的身份和业务参数。
- PasswordConfig 只提供现有 BCrypt 编码器，没有配置 SecurityFilterChain。
- BusinessException 暂时携带 HttpStatus，方便这个学习项目处理错误。更复杂系统可用独立业务错误码再映射 HTTP 状态。
- GlobalExceptionHandler 目前集中处理 BusinessException；参数解析和校验异常仍由 Spring 的默认机制处理。
- HTTP 成功和失败状态有真实含义，不要求所有响应都包装为 code/data 或都返回 200。
- 单一实现的 Service 使用普通类，接口 + Impl 不是当前项目的必选规则。
- 当前按技术层分包；业务模块多起来后，可以逐步按 article/user/category 分包，每个模块内部再分层。

## 待逐步补齐

- 文章列表、详情、写入及分页响应 DTO。
- 参数校验错误的统一格式、稳定业务错误码、日志追踪。
- 数据库初始化及版本迁移；注册等操作的并发冲突处理。
- 独立测试数据库、真实数据库集成测试和持续集成。
- 在上线前完善部署环境配置和会话相关安全措施。

这些是后续工作的方向，本次重构主要建立可持续维护的职责边界。

## 本次验证方式

从 backend 目录执行：

```powershell
.\mvnw.cmd test "-Dspring.jpa.hibernate.ddl-auto=validate" "-Dspring.sql.init.mode=never" "-Ddebug=false"
```

上下文测试需要能连接现有 MySQL。Controller 回归测试使用真实业务服务和模拟 Repository，不修改开发数据。
