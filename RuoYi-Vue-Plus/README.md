# 公司内部 Spring Boot 项目模板

本模板基于 [Dromara RuoYi-Vue-Plus](https://github.com/dromara/RuoYi-Vue-Plus) 与 [plus-ui](https://github.com/CrazyLionCat/plus-ui) 精简，用于小型内部管理系统及 AI 辅助编码。沿用上游 MIT 协议，保留 [LICENSE](LICENSE) 与上游版权声明。

## 保留能力

- 系统管理：用户、角色、菜单、部门、岗位、字典、参数、日志、客户端与数据权限。
- 认证：Sa-Token、验证码、第三方登录、注册与接口加解密。
- 基础设施：Redis/Redisson、MyBatis-Plus、OSS 文件存储、消息管理、SSE/WebSocket、邮件与短信。
- 开发支持：后端与前端代码生成器、OpenAPI 文档、现有 AI 编码规范。
- 任务调度：`ruoyi-job` 与独立 `ruoyi-snailjob-server`。

已移除演示模块、AI 业务/SnailAI/MCP、WarmFlow/LiteFlow 工作流、独立 Boot Admin、Elasticsearch 与 MQTT。AI 编码规范参考保留的 `ruoyi-system` 实现，不会依赖这些已移除模块。

## 目录

| 路径 | 用途 |
| --- | --- |
| `ruoyi-admin` | 主应用入口与认证 |
| `ruoyi-api` | 模块间共享接口与 DTO |
| `ruoyi-common` | 公共基础设施 |
| `ruoyi-modules/ruoyi-system` | 系统管理 |
| `ruoyi-modules/ruoyi-gen` | 代码生成器，`gen` profile 默认启用 |
| `ruoyi-modules/ruoyi-job` | 任务调度业务 |
| `ruoyi-extend/ruoyi-snailjob-server` | SnailJob 调度中心 |
| `script/sql` | MySQL、Oracle、PostgreSQL、SQL Server 初始化脚本 |
| `script/docker` | 数据库、Redis、MinIO、Nginx、两应用实例及 SnailJob 的部署示例 |
| `../plus-ui` | Vue 3 管理后台 |

## 本地开发

后端使用 JDK 21 与仓库 Maven Wrapper。先配置 `JAVA_HOME`，并确认 Wrapper 使用的 Java 版本：

```powershell
.\mvnw.cmd -version
.\mvnw.cmd -pl ruoyi-admin -am test
.\mvnw.cmd -pl ruoyi-admin -am package
```

根据实际数据库选择 `script/sql` 对应的 `*_ry_vue.sql`（MySQL 为 `ry_vue.sql`）；使用 SnailJob 时另需对应的 `*_ry_job.sql`。这些是新数据库初始化脚本，需先核对目标库和内容，不用于清理现有数据库。

在 `ruoyi-admin/src/main/resources/application*.yml` 配置数据库、Redis、OSS、第三方登录和消息等保留能力。默认构建 profile 为 `dev`，采用 Maven 配置过滤；启动前准备数据库与 Redis。Actuator 保留独立 Basic 鉴权，通过 `ACTUATOR_USERNAME`、`ACTUATOR_PASSWORD` 设置 `management.auth` 的用户名与密码，未配置时拒绝监控访问。Nginx 示例继续拒绝外部访问 Actuator 路径。

```powershell
java -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=dev
```

前端在 `../plus-ui` 使用 `pnpm@10.34.5`，Node 版本以其 `package.json` 为准：

```powershell
pnpm install --frozen-lockfile
pnpm dev
pnpm exec vue-tsc --noEmit
pnpm lint
pnpm build
```

在后端目录执行以下静态检查，验证模板裁剪后的引用和配置；它不连接数据库，也不代替构建或运行验证：

```powershell
.\script\check-template.ps1
```

Docker 配置是部署示例，保留原有两个应用实例与宿主机网络模式。部署前核对挂载目录、端口及全部凭据；公司实际部署规模和统一登录方案仍需另行确定。

详细协作约定见统一仓库根目录 [AGENTS.md](../AGENTS.md)。
