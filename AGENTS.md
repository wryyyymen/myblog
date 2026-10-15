# MyBlog 开发约定

本文件适用于此目录下的博客项目。用户要求后续开发持续遵循当前后端分层标准；具体说明见 [docs/backend-architecture.md](docs/backend-architecture.md)。

## 后端职责

- Controller 负责路由、请求绑定、Bean Validation、OpenAPI 文档以及 HTTP 状态/响应组织。通过构造方法注入业务服务，不直接注入 Repository，也不编写密码校验、数据库操作或资源权限规则。
- 请求对象独立放在 dto/<模块>/，使用 XxxRequest；新增接口的返回对象使用 XxxResponse。DTO 保留参数校验及 @Schema 注解，不作为 JPA 实体使用。
- Service 组织业务规则、资源操作权限、事务和实体到响应对象的转换。Service 不接收 HttpServletRequest、HttpSession，也不返回 ResponseEntity。会话细节集中在 auth 包。
- 当前只需要单一实现的业务服务用普通 @Service 类；有明确替换实现需求时再引入接口。
- Repository 负责 JPA 数据访问；domain 放数据库映射实体。
- CurrentUserService 统一读取、建立和清除 Session。身份及角色来自服务端和数据库；资源权限在对应 Service 检查。
- 可预期业务失败抛 BusinessException，由 GlobalExceptionHandler 输出对应 HTTP 状态和 message。扩展异常处理时避免泄露密码、堆栈或 SQL。
- 写操作按业务单元设置事务；需要在方法内转换数据库约束异常时显式刷新，并让失败事务回滚。避免吞掉异常后返回成功。
- 继续使用 Session、JPA 和独立 BCrypt 密码编码器。用户已移除 Spring Security 认证框架和 Redis，新增基础设施以用户明确需求为依据。

## 修改与验证

- 重构时保留前端已使用的路径、JSON 字段、状态码和权限语义；涉及契约变化时明确说明并同步调用方。
- 接口文档使用 OpenAPI 3 的 @Tag、@Operation、@Parameter、@Schema；补充功能、参数、权限及返回状态说明。
- 对业务和权限变更增加或调整有意义的测试，覆盖允许和拒绝路径。只涉及样式或文案时按影响选择验证方式。
- 连接开发数据库的测试使用 ddl-auto=validate 和 spring.sql.init.mode=never。写入测试优先使用隔离数据库或可回滚事务；不要为了测试清空开发数据。
- 不提交本地数据库密码、application.yaml、日志、依赖或构建产物。
- 现有文章查询仍返回实体、部分文章响应仍使用 Map，是待逐步改进的存量代码；新功能优先采用明确的响应 DTO。
