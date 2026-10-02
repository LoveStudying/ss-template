# 项目协作说明

## 目标与当前状态

- 本项目准备以开源 RuoYi-Vue-Plus / plus-ui 为基础，改造成公司内部小型 Spring Boot 项目的模板，并支持后续 AI 辅助开发。
- 当前已移除演示模块、AI 业务/SnailAI/MCP、WarmFlow/LiteFlow、独立 Boot Admin、Elasticsearch 和 MQTT；保留系统管理、认证、Redis、OSS、消息推送、代码生成器与 SnailJob。
- 开发原则：理解真实调用链后做最小改动，优先复用现有能力；不为未来需求新增抽象、依赖、服务或框架。
- 公司统一登录、审批流程、文件存储、数据库及部署规模尚未最终确定；会影响这些边界的变更先确认需求。
- 本文件是项目导航和协作约定，具体行为以当前代码和构建配置为准。目录、命令或架构变化后同步更新本文件。

## 工作区与技术栈

根目录是统一 Git 仓库，包含下面两个子项目及本文件。检查差异、提交或查看历史时在根目录执行；构建、测试和启动命令仍在对应子项目目录执行。忽略规则统一维护在根目录 `.gitignore`。

| 目录 | 职责 | 当前技术栈 |
| --- | --- | --- |
| `RuoYi-Vue-Plus/` | 后端 Maven 多模块工程 | JDK 21、Spring Boot 4.1.1、MyBatis-Plus、Sa-Token、Redis/Redisson，当前默认数据库驱动为 MySQL |
| `plus-ui/` | 管理后台前端 | Vue 3、TypeScript、Vite、Element Plus、Pinia、Vue Router、pnpm |

- 后端版本以 [pom.xml](RuoYi-Vue-Plus/pom.xml) 为准；使用 Maven Wrapper，版本以 [.mvn/wrapper/maven-wrapper.properties](RuoYi-Vue-Plus/.mvn/wrapper/maven-wrapper.properties) 为准。
- 前端版本和脚本以 [package.json](plus-ui/package.json) 为准，锁文件为 `pnpm-lock.yaml`。当前指定 `pnpm@10.34.5`，Node 要求以 `engines` 和实际依赖要求为准。
- 不凭框架名称猜版本，不擅自升级主版本、替换包管理器或改写无关锁文件。

## 目录导航

### 后端

| 路径（相对 `RuoYi-Vue-Plus/`） | 用途 |
| --- | --- |
| `ruoyi-admin/` | 应用入口、认证控制器、登录策略、验证码；启动类 `org.dromara.DromaraApplication` |
| `ruoyi-api/` | 模块间共享接口、登录模型、DTO；不是另一个独立 HTTP 服务 |
| `ruoyi-common/` | 基础能力及其 BOM：core、web、json、mybatis、satoken、security、redis、log、excel 等，以及可选扩展 |
| `ruoyi-modules/ruoyi-system/` | 用户、角色、菜单、部门、岗位、字典、参数、日志、文件、客户端和消息等系统功能 |
| `ruoyi-modules/ruoyi-gen/` | 代码生成器，模板位于 `src/main/resources/fm/` |
| `ruoyi-modules/ruoyi-job/` | 任务调度业务 |
| `ruoyi-extend/ruoyi-snailjob-server/` | 独立 SnailJob 调度中心 |
| `script/sql/` | 初始化 SQL，含不同数据库版本；执行前确认目标数据库和脚本影响 |
| `script/docker/` | 原框架部署示例，包含多个应用实例和扩展服务，不代表公司最终部署方案 |

### 前端

| 路径（相对 `plus-ui/`） | 用途 |
| --- | --- |
| `src/views/` | 页面，含 system、monitor、tool 等目录 |
| `src/api/` | 后端 API 与业务 `types.ts` |
| `src/utils/request.ts` | 统一请求、令牌、错误提示、加解密、防重复提交及下载封装 |
| `src/router/`、`src/permission.ts` | 基础路由和登录导航守卫 |
| `src/store/modules/permission.ts` | 根据后端菜单生成动态路由并映射页面组件 |
| `src/store/modules/user.ts` | 登录态、用户信息及角色权限 |
| `src/components/`、`src/hooks/` | 通用组件与页面组合式函数 |
| `src/layout/`、`src/assets/styles/` | 后台布局及公共样式 |
| `gen/` | 前端 CRUD 的 API、类型、列表页、树表页生成模板 |

## 开始修改前

1. 先检查目标目录适用的 `AGENTS.md` / `AGENTS.override.md`、Git 状态和相关配置，保护已有未提交修改。
2. 阅读最近似实现，追踪 controller → service → mapper → 数据库，以及前端页面 → API → 请求封装的真实调用链。
3. 新增 CRUD 或修改相关代码时，按需读取已有规范，不一次展开全部资料：
   - [后端 AI 编码规范](RuoYi-Vue-Plus/.codex/skills/ruoyi-plus-ai-coding/SKILL.md)
   - [后端详细约定](RuoYi-Vue-Plus/.codex/skills/ruoyi-plus-ai-coding/references/backend.md)
   - [前端 AI 编码规范](plus-ui/.codex/skills/frontend-crud-coding/SKILL.md)
   - [前端详细约定](plus-ui/.codex/skills/frontend-crud-coding/references/frontend.md)
4. 规范取样顺序：目标模块最近似实现 → 现有公共能力 → 生成器模板 → 通用框架习惯。不得用旧文档覆盖用户要求或更具体的项目规则。
5. 修复公共能力前搜索全部调用方；不要只修报告中出现的一个页面或接口。

## 编码约定

### Java 后端

- 沿用 `org.dromara` 包结构；未明确要求时不全仓替换包名和框架品牌。
- 标准分层为 `domain`、`domain.bo`、`domain.vo`、`mapper`、`service`、`service.impl`、`controller`，保持 Entity、输入 BO、输出 VO 的职责边界。
- 复用 `BaseController`、`R<T>`、`PageQuery`、`PageResult`、`BaseMapperPlus<Entity, Vo>`、`MapstructUtils` 和现有 `QueryBuilder`，不要另建同义基础类。
- 分页等返回包装遵循附近接口，不强行把所有响应改成同一种结构；前后端共同确认契约。
- 沿用 Sa-Token，权限标识通常为 `module:business:action`；保留现有参数校验、操作日志、防重提交、数据权限、缓存失效和删除前校验。
- 业务编排放 service；跨表写入按现有模式使用事务。不能用吞异常、关闭校验或忽略权限制造成功结果。
- 新增实体类、实体字段和 public 方法须有简洁、准确的中文 JavaDoc，说明业务含义及必要约束。
- 金额使用 `BigDecimal` 并明确精度和舍入；日期时间类型及序列化遵循现有接口、数据库和时区约定。
- 格式以 `.editorconfig` 和附近代码为准：后端一般为 4 空格，JSON/YAML 为 2 空格，UTF-8、LF。

### Vue / TypeScript 前端

- 沿用 Vue 3 Composition API 和现有 `<script setup lang="ts">` 风格，保留组件命名约定。
- API 和类型通常放在 `src/api/<module>/<business>/index.ts`、`types.ts`，页面放在 `src/views/<module>/<business>/`。
- 请求统一使用 `@/utils/request`；`AxiosPromise` 使用项目的 `@/utils/api-types`，不要直接从 axios 引入同名类型。
- 复用已有权限指令、字典、分页、上传、下载和页面 hooks，包括 `useLoading`、`useFormDialog`、`useSearchReset`、`useTableSelection` 等；采用前先核对真实调用方式。
- 新代码保持类型安全，不随意添加 `any`、`@ts-ignore`、非空断言或关闭检查；既有宽松类型逐步收敛，不借任务进行大范围重写。
- 处理加载、失败、空状态、重复提交、异步竞态和状态回滚，保持键盘操作及基本可访问性。
- 沿用 Element Plus、现有页面壳与样式，前端一般为 2 空格、UTF-8、LF；使用 oxlint/oxfmt，不新增另一套格式化工具。

## 删除与改造的耦合边界

- Redis 当前参与验证码、登录失败计数、认证基础设施及在线用户信息，不能直接作为“可选缓存”移除。
- 登录依赖 `sys_client` 的授权类型和客户端配置；安全拦截器还校验客户端 ID、路径及 IP 规则。删除客户端管理必须同步处理这些调用和前端请求头。
- 认证控制器涉及第三方登录、注册、接口加密和登录欢迎推送；短信、邮件、社交认证或消息模块不能只删依赖。
- 前后端均有接口加解密和 SSE/WebSocket 消息配置，调整时同步修改请求封装、登录/退出逻辑与配置。
- 菜单来自后端并生成动态路由。删除页面要同步清理 API、菜单、角色权限关联和初始化数据，不能只隐藏前端入口。
- 当前审阅未发现完整多租户业务模块，MyBatis-Plus 拦截器链仍有数据权限。不要套用通用“去多租户”教程，也不要误删部门数据隔离。
- `ruoyi-admin` 保留系统管理与 SnailJob 任务业务依赖，`gen` profile 默认激活。关闭功能开关不等于移除构建依赖或运行接口。
- 删除模块时同步检查父 POM 的 modules、dependencyManagement、BOM、启动模块依赖、自动配置、业务调用、配置、SQL、前端和部署脚本。
- 生成器、真实业务代码与 AI 规范需保持一致；规范以 `ruoyi-system` 的真实实现为参照，避免 AI 恢复已经移除的能力。

## 本地命令与验证

以下命令为 PowerShell 示例，均须在对应子项目目录执行。具体配置变更后重新核对，不把列出的命令视为已通过验证。

### 后端：在 `RuoYi-Vue-Plus/` 执行

```powershell
java -version
.\mvnw.cmd -version

# 静态检查裁剪后的模块、配置、部署与初始化 SQL 引用
.\script\check-template.ps1

# 示例：只验证受影响的 MyBatis 公共模块及其依赖
.\mvnw.cmd -pl ruoyi-common/ruoyi-common-mybatis -am test

# 应用及其依赖的测试 / 打包，按任务范围选用
.\mvnw.cmd -pl ruoyi-admin -am test
.\mvnw.cmd -pl ruoyi-admin -am package

# 打包成功且数据库、Redis、配置已准备好后启动
java -jar ruoyi-admin/target/ruoyi-admin.jar --spring.profiles.active=dev
```

- 项目编译目标为 JDK 21；Wrapper 使用 `JAVA_HOME` 指向的 JDK。每次运行前以 Wrapper 的版本输出确认实际 Java；终端 `java` 的 PATH 版本可能不同，不通过降低编译版本绕过。
- 环境配置位于 `ruoyi-admin/src/main/resources/application*.yml`；当前 POM 默认 dev，并通过 Maven 过滤 `@profiles.active@`。不要假定已实现纯运行时环境管理。
- 使用已有 Spring Test、JUnit Jupiter、Mockito。测试主要在 common 模块，另有系统消息和 SnailJob 监控鉴权回归测试；不能据此推断登录和所有系统业务已覆盖。
- POM 当前 `maven.test.skip=false`，Surefire 排除 `exclude` 标签。不得通过跳过测试或降低断言处理失败。
- 未确认仓库有统一 Java lint/格式化命令，不凭经验新增插件；只改文档不必启动服务或跑全量构建。

### 前端：在 `plus-ui/` 执行

```powershell
# 仅在首次准备环境或依赖缺失时安装；保持锁文件
pnpm install --frozen-lockfile
pnpm dev

# 按改动范围验证
pnpm exec vue-tsc --noEmit
pnpm lint
pnpm build
pnpm exec vitest run src/utils/__tests__/push-message.test.ts
```

- 开发代理在 `vite.config.ts` 中指向 `http://localhost:8080`；前端端口和 API 前缀由 `.env.development` 等配置提供。
- 格式化脚本为 `pnpm run fmt`，会格式化整个项目；小改动优先限制文件范围，避免无关差异。
- 当前有消息回归测试 `src/utils/__tests__/push-message.test.ts`，但没有 package.json 测试脚本；使用上面的 Vitest 命令，不要声称 `pnpm test` 已可用。
- 首次检出时，先运行构建生成被忽略的自动导入和组件类型声明，再运行类型检查；不要用关闭检查来处理缺少生成声明。
- `tsconfig.json` 当前关闭部分严格检查，构建成功不能替代类型检查。

## 安全与交付

- 仅读取最小必要的敏感配置；不要打印、复制或提交密码、JWT 密钥、私钥、令牌及个人信息，示例和日志必须脱敏。
- 不沿用示例凭据作为生产凭据。数据库初始化可能含建表、删除或种子数据，执行前确认目标和范围。
- 主应用和 SnailJob 的 Actuator 保留 Basic 鉴权，凭据配置为 `management.auth.username/password`，通过 `ACTUATOR_USERNAME`、`ACTUATOR_PASSWORD` 提供；默认空且未配置时拒绝监控访问。Nginx 继续拒绝外部 Actuator 路径，修改监控配置时同步核对鉴权。
- `script/sql` 是新库初始化脚本，本次裁剪不提供或执行现有数据库的删除迁移。
- 未经用户明确要求，不删除数据、强制重置、覆盖历史、推送、发布、部署或修改外部资源。
- 更改品牌可替换页面展示，但保留上游 LICENSE 与版权声明；依赖许可另按实际使用核对。
- 完成前在根目录检查整个仓库的差异，排除调试输出、临时文件、敏感信息和无关锁文件变更。
- 默认中文反馈，说明改了什么、为什么、实际执行的验证及未解决事项。未运行或失败的检查明确说明，不声称“全部通过”。
