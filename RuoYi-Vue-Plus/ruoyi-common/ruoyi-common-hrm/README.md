# 泛微人力资源接口客户端

本模块封装 `getHrmSubcompanyInfo`、`getHrmDepartmentInfo`、`getHrmJobTitleInfo`、`getHrmUserInfo` 四个只读方法，负责 SOAP 请求和结果解析，并通过受权限保护的 HTTP 接口提供联调查询。没有数据库写入或定时同步。

## 加载条件

模块位于 `RuoYi-Vue-Plus/ruoyi-common/ruoyi-common-hrm`，只提供协议客户端、查询服务和联调控制器；`ruoyi-system` 中的 `HrmOrgSyncSource` 负责把查询结果转换为组织同步的中性模型。

- 仅当 `org-sync.source=hrm` 时，自动配置才会注册 `HrmProperties`、`HrmClient`、`HrmQueryService` 和 `HrmQueryController`；`org-sync.source=none` 时不加载本模块的任何 Bean，也不要求任何 `hrm.*` 配置。该配置项在启动模块的 `application.yml` 中声明。
- 未配置 `org-sync.source` 时按默认的 `hrm` 处理（条件带 `matchIfMissing=true`），与配置对象 `OrgSyncProperties.source` 的默认值保持一致，避免两处对「默认值」理解不同。
- `HrmQueryController` 本身也带有相同的 `@ConditionalOnProperty`。启动类扫描 `org.dromara` 包时，组件扫描会按该条件跳过控制器，因此 `none` 模式下即使模块在类路径上，也不会出现缺少 `HrmQueryService` 的启动失败，`/hrm/*` 同样不存在。
- `HrmClient` 的资源释放由自动配置的 Bean 定义（`destroyMethod = "close"`）负责，客户端本身不再声明 `@PreDestroy`。
- 创建 `HrmClient` 和启动应用均不会主动访问 OA；只有实际调用四个查询方法才会发起请求。
- 本模块不自动创建用户、同步角色或写入数据库，也不提供定时任务。

## 配置

| 配置 | 环境变量 | 说明 |
| --- | --- | --- |
| `hrm.endpoint` | `HRM_ENDPOINT` | SOAP 服务地址，不包含 `?wsdl`；dev 与 prod 的具体地址由启动模块的 `application-dev.yml`、`application-prod.yml` 配置 |
| `hrm.client-ip` | `HRM_CLIENT_IP` | 必填，传给 OA 的白名单客户端 IPv4 地址 |
| `hrm.connect-timeout-seconds` | — | 连接超时，默认 10 秒 |
| `hrm.read-timeout-seconds` | — | 请求超时，默认 30 秒 |
| `hrm.log-response-body` | — | 脱敏响应正文日志开关，未配置时为 false，诊断结束后应关闭 |

在 PowerShell 中为当前启动进程设置：

```powershell
$env:HRM_CLIENT_IP = '<白名单IPv4>'
```

`<白名单IPv4>` 必须是已由接口方加入 OA 白名单的当前机器地址，部署服务器与开发电脑的取值不同。IPv4 配置不自动选择，避免误用 VPN 或虚拟网卡地址。

## Postman 调用

1. 配置 `hrm.client-ip` 为已获 OA 白名单许可的 IPv4（需要时可通过 `HRM_CLIENT_IP` 环境变量注入配置），按仓库根目录 `AGENTS.md` 启动后端。默认本地地址为 `http://localhost:8080`。启动不会主动访问 OA。
2. 使用后台管理端正常登录，从登录响应取得 `data.access_token`。`/auth/login` 要求 `encrypt-key` 加密请求头，不能在 Postman 中直接发送明文密码 JSON；可从已登录的后台管理端取得令牌。不要把令牌存入共享集合或提交仓库。
3. 在 Postman 为下面四个请求统一设置请求头：`Authorization: Bearer <access_token>`、`clientid: <登录时使用的 clientId>`。本地后台管理端的 `clientId` 取自 `plus-ui/.env.development` 的 `VITE_APP_CLIENT_ID`。令牌绑定客户端 ID；若该客户端配置了访问路径或来源 IP 限制，还需允许 `/hrm/**` 和当前来源 IP。
4. 使用有对应 `hrm:*:query` 权限的管理账号。超级管理员拥有通配权限；普通账号需另行授予下表中的权限。本模块不自动修改角色或菜单数据。

| 方法 | 路径 | Query 参数 | 权限 |
| --- | --- | --- | --- |
| GET | `/hrm/subcompanies` | 无 | `hrm:subcompany:query` |
| GET | `/hrm/departments` | `subcompanyId`，可选，多个 ID 用英文逗号分隔 | `hrm:department:query` |
| GET | `/hrm/job-titles` | `subcompanyId`、`departmentId`，均可选 | `hrm:jobtitle:query` |
| GET | `/hrm/users` | `workcode`、`subcompanyId`、`departmentId`、`jobtitleId`、`lastChangeDate` | `hrm:user:query` |

人员接口至少填写 `workcode`、`subcompanyId`、`departmentId`、`jobtitleId` 中的一个，避免误查全量；首次联调建议只填接口方提供的测试 `workcode`。`lastChangeDate` 格式为 `yyyy-MM-dd`，不能单独作为人员筛选条件。筛选项由本服务原样传给 OA，实际筛选语义以 OA 为准。

例如，在 Postman 中创建 `GET http://localhost:8080/hrm/users?workcode=EMP001`，不需要 Body。成功响应使用项目统一的 `R` 格式：

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "method": "getHrmUserInfo",
    "nullResult": false,
    "records": [
      { "userid": "7", "workcode": "EMP001", "lastname": "测试人员", "departmentid": "20" }
    ]
  }
}
```

实际字段只在 OA 返回时出现。分部、部门、岗位、人员四个接口均不进行字段白名单筛选，返回客户端获取到的全部字段，包括 OA 实际返回的组织扩展字段、人员证件号、联系方式、住址及其他扩展字段。密码字段仍在客户端解析时直接丢弃，不进入 HTTP 响应。字段原名、字符串值、空字符串和 XML null 均保留。`nullResult=true` 表示 OA 返回整个 `out` 为 null，不代表查询成功但无数据；`nullResult=false` 且 `records=[]` 才是空数组。数组中的单个 null 记录会保留位置。

若配置无效、网络失败、返回 SOAP Fault 或 XML 结构错误，接口返回项目统一失败响应，其 `msg` 为不含 OA 正文的错误说明。HTTP 接口返回数据以当前 OA 状态为准，不能根据本地测试推断线上会有记录。

## Java 调用

在调用方注入 `HrmClient`：

```java
HrmQueryResult companies = hrmClient.getHrmSubcompanyInfo();
HrmQueryResult departments = hrmClient.getHrmDepartmentInfo("分部ID");
HrmQueryResult jobTitles = hrmClient.getHrmJobTitleInfo("分部ID", "部门ID");

HrmUserQuery query = new HrmUserQuery();
query.setWorkcode("员工编码");
HrmQueryResult users = hrmClient.getHrmUserInfo(query);
```

结果中的 `nullResult=true` 对应上游 `<out xsi:nil="true"/>`，表示返回 null，不能当作正常无数据。`nullResult=false` 且 `records` 为空才表示返回空数组；null 是否意味着无权限，需要接口方确认。

`records` 中的 `fields` 保留 WSDL 的原始字段名，包括 `_subcompanyid` 等前导下划线，保留字符串、空字符串与 XML null 的区别。数字及日期也先保留原文，不映射到本地部门、岗位和账号模型。

WSDL 允许数组中的单个 Bean 为 null，因此 `records` 也可能包含 null 项，保持原来的顺序与位置；读取字段前需要判断该项是否为 null。这与整个 `out` 为 null 是不同的状态。

密码字段在解析时直接丢弃。其他人员字段可能包含身份证号、联系方式、住址等，`/hrm/users` 在校验 `hrm:user:query` 权限后返回客户端获取到的全部人员字段；不要直接记录整个字段 Map。客户端未配置正文开关时不记录正文；结果对象的 `toString()` 只显示方法、null 状态及数量，记录对象只显示字段名。

响应正文开关通过启动模块配置文件的 `hrm.log-response-body` 配置，不使用环境变量。默认关闭；需要诊断时临时设为 true 并重新构建或重启，复现后使用 `grep -F 'HRM'` 从应用日志定位 `HRM 响应正文`，通过 requestId 关联异常，诊断结束后改回 false。

正文日志在 XML 安全解析之后、业务结构校验之前输出，保留标签、命名空间和 nil/type 属性；仅组织 Bean 的 ID、上级 ID、名称和编码文本保留值，人员字段、密码、SOAP Fault、未知字段、其他属性、注释等均脱敏。日志 XML 是脱敏后重新序列化的内容，不是逐字节原文。每次最多输出 32768 字符，`truncated=true` 表示截断，换行替换为下划线以便 grep。无法安全解析的 XML 不输出正文，只输出现有错误摘要。开关不会放宽校验，也不会自动重试或主动调用 OA。

## 服务器诊断日志

四个只读方法统一记录诊断日志，使用 `requestId` 关联同一次调用的请求、响应和解析结果。默认 INFO 级别即可查看，不需要打开全局 DEBUG：

- `HRM 请求开始`：方法、实际 endpoint、作为 `in0` 传入的客户端 IP、超时设置、SOAPAction、协议命名空间和各筛选参数是否填写。客户端 IP 是请求参数，不代表实际网络出口 IP；工号和组织筛选值以 `<redacted>` 替代。
- `HRM 响应接收`：OA 的 HTTP 状态、Content-Type、正文字节数和从调用开始计算的耗时。
- `HRM 解析成功`：整个结果是否为 null、记录数量和第一条非 null 记录的字段名称。
- ERROR 日志：配置、网络、超时和解析失败。字段结构异常额外记录从 1 开始的记录序号、字段序号，以及 `namespaceMismatch`（命名空间不符）、`nestedElements`（字段内有子元素）、`duplicate`（同记录内同名字段重复）。节点摘要包含实际命名空间、nil 状态、直接子节点数量及名称。

常规摘要日志不输出原始请求/响应正文、字段文本值、Cookie、Authorization 或 SOAP Fault 文本；开启正文开关时只输出上述脱敏 XML。底层异常仅记录类型，不输出可能含敏感 XML 的消息或堆栈。节点名称和命名空间限制为 200 字符，控制字符被替换；子节点摘要最多展示 20 项，超出时标注 `<truncated>`。

默认日志配置下，INFO 写入应用工作目录的 `logs/sys-info.log`，ERROR 写入 `logs/sys-error.log`，两者也进入控制台和 `logs/sys-console.log`。部署新包后调用 `/hrm/subcompanies`，先在错误日志中找到对应 `requestId`，再关联前面的请求、响应日志。

Linux 服务器可在应用工作目录查看：

```bash
tail -f logs/sys-console.log
# 已取得 requestId 后，替换示例值关联完整调用日志
grep -F 'requestId=替换为实际诊断ID' logs/sys-console.log
```

例如，`namespaceMismatch=true` 表示字段属于其他非空命名空间；字段的空命名空间现在允许兼容解析。`nestedElements=true` 表示应为文本的字段包含子元素；`duplicate=true` 表示同一条记录出现同名字段（即使两个字段分别使用 Bean 命名空间和空命名空间也拒绝）。诊断日志不修改接口返回结构，也不自动调用或重试 OA。

## 验证

在 `RuoYi-Vue-Plus/` 下使用项目 Maven Wrapper 和 JDK 21：

```powershell
.\mvnw.cmd -pl ruoyi-common/ruoyi-common-hrm -am test
```

普通测试只访问本机临时 HTTP 服务，不需要数据库或 OA；`HrmLiveTest` 默认跳过。

手动调用真实 OA 的四个只读方法：

```powershell
$env:HRM_CLIENT_IP = '<白名单IPv4>'
# 获得可用测试人员后设置；默认使用不存在的测试编码，避免全量人员查询。
$env:HRM_TEST_WORKCODE = '接口方提供的测试员工编码'
.\mvnw.cmd -pl ruoyi-common/ruoyi-common-hrm -am test '-Dtest=HrmLiveTest' '-Dhrm.live=true' '-Dsurefire.failIfNoSpecifiedTests=false'
```

可通过 `HRM_ENDPOINT` 指定 OA 地址，通过 `HRM_TEST_SUBCOMPANY_ID`、`HRM_TEST_DEPARTMENT_ID` 缩小部门、岗位及人员范围。联调测试只输出返回数量和字段名称，不输出人员值。若返回 null，测试会明确失败，防止把 HTTP 200 误报成数据联调成功。

## 协议依据与限制

- WSDL 地址为 `hrm.endpoint` 地址追加 `?wsdl`；endpoint 配置值不包含 `?wsdl`。
- SOAP 1.1，document/literal；服务命名空间为 `http://localhost/services/HrmService`，Bean 命名空间为 `http://webservice.hrm.weaver`。命名空间中的 `localhost` 是协议标识，不是请求目标。
- OA 实际响应可能在同一 Bean 内混用 Bean 命名空间和空命名空间，四个查询统一在字段层兼容这两种形式，保留字段名、文本、空字符串和 nil 语义；其他非空命名空间、嵌套元素和重复字段仍拒绝，SOAP、Response/out 和 Bean 类型/命名空间仍严格校验。
- 请求参数按 WSDL 的 `in0`、`in1` 等顺序发送；`SOAPAction` 为 `urn:weaver.hrm.webservice.HrmService.<方法名>`。
- 空筛选项发送空字符串；人员日期使用 `LocalDate` 按 `yyyy-MM-dd` 发送。实际筛选、状态枚举及增量边界仍以 OA 为准。
- 不自动重试、跟随重定向或调用 `Syn*`、密码修改等写方法。
- XML 解析禁用 DOCTYPE、外部实体、外部 DTD/XSD 和 XInclude；网络错误、HTTP 错误、SOAP Fault、结构错误分别报告。
- 四个方法返回完整数组，没有本地伪分页。首次人员联调应使用员工编码或限定组织范围。
- 本模块只负责协议与联调查询；部门、员工同步规则在 `ruoyi-system` 中，由 `org-sync.source` 选择来源。
