# 三生 SSO 接入说明

本项目按照《三生 SSO 接口文档 V2》的授权码流程接入公司统一认证，支持配置默认进入系统登录页或自动跳转 SSO。本地账号密码登录保留；用户必须先登录本地账号，在「个人中心 → 第三方应用」绑定三生账号，然后才能使用三生 SSO 登录。不自动注册本地用户，不导入公司角色、权限或组织机构。

登录页沿用圆形图标按钮，三生图标取自[公司官网的站点图标](https://www.3sbio.com/favicon.ico)，原文件保存于前端 `src/assets/logo/3sbio.ico`，随应用构建发布。悬停提示和无障碍名称为「三生 SSO 登录」。

## 配置

### 默认登录模式

后端 `ruoyi-admin/src/main/resources/application.yml` 中配置管理后台的默认登录入口：

```yaml
auth:
  # system：系统登录页；sso：自动跳转三生 SSO
  login-mode: ${AUTH_LOGIN_MODE:system}
```

默认值为 `system`，也可通过环境变量 `AUTH_LOGIN_MODE=sso` 切换。仅接受小写 `system` 和 `sso`，空值或其他值会使后端启动校验失败。配置变更后重启后端；前端在每次进入登录入口时读取 `GET /auth/login-config`，无需因模式变更重新构建前端。该公开接口仅返回登录模式，不返回客户端凭据。

- `system`：未登录访问后台、直接访问 `/login`、退出登录或确认会话过期后，进入当前账号密码登录页，仍可手动点击三生 SSO 图标登录。
- `sso`：上述入口自动在当前标签页跳转三生 SSO；登录完成后返回原访问页面，保留查询参数和 URL 片段。首次授权不显示本地账号密码表单。
- 显式本地入口为 `https://应用域名/部署子路径/login?local=true`，无论默认模式如何都显示账号密码登录页，适用于首次绑定和管理员应急登录。入口保留现有验证码、密码校验和权限控制；该配置选择默认入口，不关闭本地登录接口。
- 配置接口或 SSO 授权失败时显示错误，提供重试和本地登录按钮，不自动反复授权。SSO 回调失败也提供本地登录入口；未绑定用户仍会被拒绝 SSO 登录。
- 回跳仅允许站内业务路径；外部地址和登录/回调页面回跳会回到首页。个人中心的绑定回调继续返回「第三方应用」。

### SSO 协议配置

后端配置位于 `ruoyi-admin/src/main/resources/application-dev.yml` 和 `application-prod.yml` 的 `justauth.type.sso`。

| 配置项 | 用途 | 当前状态 |
| --- | --- | --- |
| `server-url` | 公司 SSO 地址 | dev 为 `https://dev-login.3sbio.com`，prod 为 `https://login.3sbio.com` |
| `client-id` | SSO 管理员分配的客户端 ID | 预留，支持环境变量 `SSO_CLIENT_ID` |
| `client-secret` | SSO 管理员分配的客户端密钥，仅在后端使用 | 预留，支持环境变量 `SSO_CLIENT_SECRET` |
| `redirect-uri` | 已在 SSO 登记的前端回调地址 | 预留，支持环境变量 `SSO_REDIRECT_URI` |
| `scopes` | 授权范围列表 | 预留为空列表，确认后填写，例如 `[read]` |

回调地址格式为 `https://应用域名/部署子路径/social-callback?source=sso`。以实际前端域名和部署路径为准，不填写后端 API 地址。授权请求和换 Token 请求使用同一个回调地址。不要使用接口文档中的示例客户端 ID 或密钥。配置未填写完整时，点击入口会提示配置未完成，不影响本地账号密码登录。

登录客户端的 `sys_client.grant_type` 须允许 `social`，同时保留 `password`。项目初始化客户端已经包含该授权类型；已有数据库需由管理员核对，本次不执行数据库修改。

## 调用流程及身份映射

1. 前端请求 `GET /auth/binding/sso` 获取授权地址，将本次 `state` 及登录/绑定意图保存到当前标签页的会话存储。绑定请求额外传 `mode=binding`，后端要求已登录，并在 Redis 将该次授权关联到发起用户。
2. 浏览器访问 SSO 的 `GET /user/login`，使用 `response_type=code`、`client_id`、`redirect_uri`、`scope` 和 `state`。
3. SSO 回调前端后，前端检查返回的 `state` 是否匹配本标签页发起的授权；后端通过 Redis 缓存继续校验，状态默认三分钟过期。
4. 后端以 `application/x-www-form-urlencoded` 向 `POST /sss-sso/oauth2/token` 提交授权码及客户端凭据，再以 `Authorization: Bearer ...` 请求 `GET /sss-sso/oauth2/user/info`。
5. `adAccount` 为第三方唯一标识，平台标识固定为 `sso`，沿用 `sys_social` 保存其与本地用户的绑定关系。冻结账号（`status != 1`）或缺少 AD 账号时拒绝绑定和登录。
6. 绑定通过 `POST /auth/social/callback` 完成，必须有有效本地登录态，且当前用户与发起绑定的用户一致；绑定状态原子消费，过期、重复回调或切换账号时拒绝绑定。登录通过 `POST /auth/login` 的 `grantType=social` 完成，必须已有绑定。本地用户已删除或停用时仍拒绝登录。登录成功后签发本系统令牌，公司 SSO 令牌仅在服务端使用；个人中心绑定列表会屏蔽敏感授权字段。

## 联调前必须核实

- 文档明确描述携带 `code` 回调，但没有说明是否原样返回授权请求的 `state`。实现要求 SSO 原样返回 `state`；缺少或不匹配时拒绝回调，不关闭安全校验。请与 SSO 管理员核实并在测试环境验证。
- 回调必须回到发起授权的前端域名和同一浏览器标签页，否则无法读取会话存储中的授权记录。
- 公司 Token、用户信息接口路径和表单参数按文档固定，授权地址参数使用文档示例的下划线格式。未引入 OIDC ID Token、自动开户、统一退出或令牌刷新流程。
- 当前个人中心沿用既有绑定记录存储逻辑。历史第三方绑定记录不删除，但其他平台已无法发起新授权或登录。

## 验证

协议测试使用本地 HTTP 服务模拟 Token 和用户信息响应，不调用公司 SSO。覆盖表单参数、Bearer 请求头、AD 身份映射、授权状态、冻结账号、缺失字段、失败响应和非法令牌脱敏。另有绑定用户边界、Redis 实际编码往返及敏感字段序列化回归测试；前端测试覆盖登录/绑定意图、伪造或缺失状态、错误参数及重复回调。

实际联调需要填写上述配置，并验证「绑定 → 退出本地登录 → SSO 登录 → 解绑后 SSO 登录被拒绝」及本地停用用户等场景。
