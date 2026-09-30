# 12 前端

由元数据驱动的后台前端（ROADMAP 阶段 10）。原则：**业务对象不写前端代码**——新增实体定义与数据视图后，列表页、表单页、历史页
都由元数据生成；前端只是元数据的解释器，不是权限的守门人（隐藏 ≠ 授权，接口照常检查）。约束性细则见【决策 D15】。

## 1. 技术与结构

| 项 | 选择 |
|---|---|
| 工具链 | pnpm、Vite、TypeScript |
| 界面 | React 19、Ant Design 5 + ProComponents（ProLayout / ProTable / ProForm），`@ant-design/v5-patch-for-react-19` |
| 数据 | TanStack Query（缓存键含界面语言）；请求经 `openapi-fetch`，类型由 OpenAPI 生成 |
| 路由、多语言 | React Router；i18next（zh / ja / en，应用可只选其中几种），antd 与 dayjs 的语言随之切换；可按区域（如 `en-US`）显示日期、数字与金额（第 10 节） |
| 测试 | Vitest + Testing Library（适配层、组件）；Playwright（端到端） |

```
frontend/
  openapi/openapi.json      后端 OpenAPI 快照（由后端测试写出并比对，见第 3 节）
  src/api/                  schema.d.ts（生成）、client.ts（令牌、语言、401 时刷新一次）、session.ts、problem.ts
  src/meta/                 适配层（纯函数）：kinds、listQuery、columns、entityForm、validation、decimal、processForm、history
  src/components/ pages/    通用页面：目录、列表、表单抽屉、历史、流程
  src/extension/            应用扩展的约定与加载（第 9 节）
  src/lib/index.ts          `@jabiz/admin`：扩展可用的全部内容（第 9 节）
  scripts/                  扩展的解析与检查（ext.mjs、extension.ts、extension-lint.mjs）
  e2e/                      Playwright
../spec/validation-cases.json  前后端共享的校验用例（第 5 节）
```

## 2. 元数据接口（前端的全部输入）

| 接口 | 内容 | 权限 |
|---|---|---|
| `GET /api/meta/entities/{name}` | 实体导出（02 §8）+ 按请求语言的 `label`（实体与字段）与 `messages`（本实体前端可能报出的错误码 → 文案模板） | 已认证 |
| `GET /api/meta/datasets` | 调用方**可读**的数据视图：`id`、`entity`、`label`、`isDefault`、`temporal`、`allowScheduled`、`readOnly`、`processOnlyWrites`、`allowTimeTravel`、`softDelete`、`listView`、`maxQueryBatchSize`、`canWrite` | 已认证，按视图读权限过滤 |
| `GET /api/meta/processes` | 调用方**可执行**、且不是 `internal()` 的流程：`name`、`version`、`latest`、`deprecated`、`label`、`description`、`input`（输入的 JSON Schema）、`actsOn`（`{entity, input, when?}`，16 §3） | 已认证，按流程权限过滤 |
| `GET /api/meta/imports` | 调用方可运行的导入（20 §6）：字段、版式、参数 Schema、控制合计、能否保存映射 | 已认证，按导入、行流程、文件策略的权限过滤 |
| `GET /api/auth/menus`、`/api/auth/me` | 动态菜单、当前操作人（10 §3） | 已认证 |
| `GET /api/dictionaries/{urn}` | 字典项（按语言） | 已认证 |

- 标签：消息资源键 `entity.<实体>`、`entity.<实体>.<字段>`、`dataset.<视图 id>`、`process.<流程名>`，缺失时回退为名字；
  系统字段（`effectStartTime` 等）由前端自己的文案命名。
- `canWrite` = 具备写权限 ∧ 非只读 ∧ 非 `processOnlyWrites` ∧ 实体可写（有版本字段）。未声明的权限只在 `dev` 下算数（默认拒绝，10 §5）。
- `ProcessDefinition.internal()`：只经专用入口或由平台执行的流程（`SPONSOR_SIGN_IN`、`SEC_BOOTSTRAP_ADMIN`、`ADD/UPDATE/DELETE_ENTITY`）
  不进目录；它们的权限检查不变。
- 流程输入 Schema（runtime `ProcessInputSchemas`，反射 record）：`String`/`UUID`/`Instant`/`LocalDate`/`BigDecimal`（`format: decimal`，以文本发送）/
  整数/布尔/枚举/列表/嵌套 record/`Map`/`Object`；Bean Validation 的 `@NotNull`/`@NotBlank`/`@NotEmpty` → `required`，`@Size`/`@Min`/`@Max`/
  `@Positive(OrZero)`/`@Email`/`@Pattern`（只收可移植的正则）→ 对应关键字；`@Sensitive` → `writeOnly` + `format: password`。
  描述不了的类型退化为任意 JSON，并由 `ProcessChecks` 给出 `PROCESS` 警告。

## 3. OpenAPI 与生成的类型

- runtime 引入 `springdoc-openapi-starter-webflux-api`（不带 UI）。平台默认属性（`JabizDefaultProperties`，最低优先级）：
  文档路径 `/api/meta/openapi`（位于 `/api/**` 下，**需要认证**；springdoc 默认的 `/v3/api-docs` 不开放）、启动时生成
  （生成过程扫描类路径，不能发生在事件循环上）、响应类型 `application/json`、按键排序。
- `OpenApiSnapshotIT`（app）取出文档、去掉 `servers`，与仓库中的 `frontend/openapi/openapi.json` 比较；不一致即失败。
  更新：`./gradlew :app:test --tests '*OpenApiSnapshotIT' -Dopenapi.update-snapshot=true`，再在 `frontend/` 下 `pnpm gen:api`，两者一起提交。
  `CI` 环境下快照缺失即失败。前端 CI 的 `pnpm check:api` 确认 `schema.d.ts` 与快照一致。
- 以 `Map` 返回的接口（实体导出、历史、操作详情）在 OpenAPI 中是自由对象，前端在 `src/meta/types.ts` 中声明其形状。

## 4. 登录与令牌

- 登录 `POST /api/auth/login`；登出调用 `/api/auth/logout` 并清除本地会话。
- **访问令牌只在内存中**；**刷新令牌在 `sessionStorage`**（刷新页面保持登录，关闭标签页即结束）。收到 401 时用刷新令牌刷新一次
  （并发请求共享同一次刷新：刷新令牌只能用一次），成功后重发原请求；刷新被服务端拒绝时清除会话回到登录页，网络失败时保留会话以便重试。
- 当前操作人与权限来自 `GET /api/auth/me`，不在前端解码令牌。
- 二次验证（10 §9，D28）：登录响应为 `MFA_REQUIRED` 时，登录页转入输入验证码（或恢复码）的一步（`/api/auth/challenge/verify`）；
  为 `MFA_ENROLLMENT_REQUIRED` 时先绑定（二维码与密钥、确认码、只显示一次的恢复码），然后重新登录。挑战令牌只在页面状态中，不存储。
- 按操作要求二次验证（10 §10）：任何请求收到 403 且违规为 `MFA_REQUIRED` 时，API 客户端请外框弹出输入框（`StepUpProvider`，并发请求共用一次），
  `POST /api/auth/step-up` 成功后以新的访问令牌重发原请求；取消则把原 403 交给调用方。未绑定时提示先到"安全设置"绑定。
- 单点登录（10 §12）：登录页列出 `/api/auth/oidc/providers` 的提供方按钮；点击后取得授权地址并整页跳转（登录后要回到的页面记在 `sessionStorage`，
  只接受本应用的路径）；身份提供方回到 `/login/oidc`，该页把 state 与授权码交给服务端一次，结果与密码登录相同（需要第二步时回到登录页的验证码或绑定步骤）。
- 闲置锁定（10 §11）：外框按键盘、指针、滚动与触摸活动计时（`idleTimeoutSeconds` 来自 `/api/auth/me`），到时登出并回到登录页，提示原因、填好用户名（`sessionStorage`，只是便利）。
- 数据期限（10 §13.2）：`/api/auth/me` 的 `dataFrom` / `dataTo` 不为空时，页头显示期限标签（列表与报表只含该期间，由服务端过滤）。
- 所有请求带 `Accept-Language`（界面语言），服务端据此返回错误文案、标签与字典。

## 5. 适配层

| 语义类型 | 输入控件 | 展示 | 列表筛选 |
|---|---|---|---|
| `text` | 文本框（`multiline` → 多行） | 原样 | 允许 `LIKE` → 包含；否则等于 |
| `monetary` / `numeric` | 数字框（文本模式，不取整，金额带币种后缀） | 按币种与小数位 / 精确小数 | 区间（`between` / `gte` / `lte`） |
| `temporal` | 日期时间 | 本地时间 | 时间区间 |
| `code` | 下拉（字典的启用项按顺序，否则固定值） | 字典标签 | 等于 |
| `bool` | 开关（新建时默认"否"） | 是 / 否 | 等于 |
| `version` | 整数 | 原样 | 区间 |
| `semanticIdentity` | 文本框 | 原样 | 等于 |
| `reference` | 可搜索下拉（目标实体声明了 `display` 且默认视图可读时，`lookup`；否则文本框） | 标签（`labels`，按页批量；取不到时主键） | 等于 |
| `custom`：`jabiz.file` | 上传按钮（`accept` 与大小提示来自导出的策略）+ 预览；移除即清空 | 图片缩略图（最窄的合适变体）/ 下载按钮 | 不提供 |
| `jabiz.i18n-text` | 每种语言一个标签页（必填语言带标记，`requiredLanguages`）；`markdown` 附预览 | 按语言回退选择，回退时带 `lang` 属性；Markdown 不渲染原始 HTML | —（只有 `isNull`） |
| 其他 `custom` / `none` | JSON 文本 | JSON | 等于 |

- 列：列表视图的 `columns`（没有列表视图时取前 8 个非系统字段）；筛选、排序只对白名单字段开放，默认排序取 `defaultSort`。
  敏感字段从不出现在列、表单与回看中。
- 遮蔽字段（`masked`，10 §13.1）：列表、历史、审计中显示服务端给出的遮蔽形式；持有其权限者在列表单元格中有"显示"按钮
  （`POST /api/datasets/{id}/reveal`，服务端每次留记录），明文只留在该单元格的状态中，按时间点回看时不提供；
  筛选与排序只对持有权限者开放；表单中没有权限时只读（遮蔽形式因"只发送改变的字段"而不会被写回）。
- 表单：不提供系统维护、生成与敏感字段；不可变字段编辑时只读。新建发送全部填写的值，修改**只发送改变的字段**；清空的输入发送 `null`。
  日期时间控件只到毫秒，因此判断"是否改变"时时间按毫秒比较（未改动的微秒时间不会被截断后发送）。
- 文件（14 §5）：访问令牌只在内存中，`<img>` 不能带令牌，因此预览与下载都以带会话的 `fetch` 取得内容，经 `URL.createObjectURL`
  显示并在卸载时释放（后台 CSP 的 `img-src` 已允许 `blob:`）；上传以 `FormData` 发送。选择文件时的类型过滤与大小检查只省一次往返，
  以服务端按内容的判定为准。
  时态实体另有"生效时间"（允许预定或具备 `temporal.backdate` 时）与"原因"。
- 提交：`POST /api/datasets/{id}/commit`。后端返回的 400/422 违规回填到对应字段，与前端校验的错误显示在同一处（带 `data-rule-code`）；409 提示重新加载。

### 5.1 前后端校验一致【D15】

- 可导出的规则只有六种（`RANGE` `SCALE` `LENGTH` `PATTERN` `NOT_FUTURE` `REQUIRED`，core `RuleKinds`），导出其他种类即构建失败。
  推荐用 core `Rules` 工厂声明：同一组参数同时生成导出的 `RuleSpec` 与服务端判断，两者不会漂移。`PATTERN` 只允许 Java 与 JavaScript
  读法相同的写法（白名单：转义限于 `\d \w \b` 及其反义、`\n` 等控制字符、四位 `\u` 转义、转义的语法字符、字符类内的 `\-`、
  `\p{..}` 的 Unicode 一般类别；分组限于 `(?:` `(?=` `(?!`；拒绝占有量词、字符类交集与嵌套字符类。`\s` 不可移植：JavaScript 把所有 Unicode 空白都算在内）。
  前端遇到仍无法编译的正则时不报错，交由服务端判断。
- 前端 `validation.ts` 逐步复刻服务端的唯一校验路径（`FieldValueCoercer` → `EntityValidator`）：转换失败 `INVALID_VALUE` → 必填 `REQUIRED`
  （新建，或显式清空）→ 语义类型约束（`TOO_LONG` / `NUMERIC_PRECISION` / `MONETARY_SCALE` / `NOT_IN_DICTIONARY`，只报第一个）→ 各导出规则（声明顺序）。
  小数用 BigInt 精确计算（与 `BigDecimal` 的比较、`stripTrailingZeros`、精度同口径），时间按 ISO-8601（须带偏移）解析到纳秒。
- 文案：导出中的 `messages` 模板与服务端同源，前端填入同名占位参数（`{field}` 为字段标签）。
- **共享用例** `spec/validation-cases.json`：字段元数据 + 输入值 + 期望的错误码。core `ValidationCasesTest` 断言①文件中的字段元数据
  与 `MetaModelExporter` 的导出**完全一致**（`-Dvalidation-cases.update=true` 重写）②服务端得到期望的错误码；
  前端 `validation.cases.test.ts` 读同一文件断言得到**相同**的错误码。新增规则种类或语义约束时先加用例。
- `jabiz.i18n-text` 的转换与语义约束（`INVALID_VALUE`、`TOO_LONG`（带 `lang`）、`TRANSLATION_REQUIRED`）在前端复刻，并在共享用例中（16 §1.2）。
- 仅服务端的规则、其他 `Custom` 类型（其规范类型由服务端 SPI 决定，前端只检查必填）、实体级校验、状态机、唯一性等需要服务端状态的检查不在前端重复，服务端的回答照常显示。
  `NOT_FUTURE` 在前端按浏览器时钟判断，仅作提示。

## 6. 页面

| 路径 | 页面 |
|---|---|
| `/login` | 登录（可切换语言） |
| `/data` | 数据视图目录（`/api/meta/datasets`）：不配菜单也能进入任何可读视图 |
| `/data/:datasetId` | 通用列表：远程分页、筛选、排序；时态实体可选"时间点"（`asOf`）与"按当时所知"（`knownAt`），此时只读 |
| `/data/:datasetId/:id/history` | 历史：版本时间线（动作、生效 / 记录时间、操作人、操作、原因、改动字段的前后值、预定标记）；任意时间点回看并与当前对比；操作详情（`operation.read`）；撤销（`temporal.revert`，填原因） |
| `/tasks` | 我的待办（18 §5.3）：审批待办就地批准或驳回（`ApprovalPanel`），其他待办链接到其页面；页头显示开放待办数 |
| `/reports`、`/reports/run?id=<模板>` | 报表（19 §3.3）：目录中声明了 `report` 的模板；参数表单、生效 / 记录时点、结果表格（分页、白名单内筛选与排序）、导出 Excel / PDF / CSV（19 §4） |
| `/reports/archive[?template=]` | 已签发的报表（19 §5.4）：按签发原样保存（PDF / Excel / CSV）、核对 |
| `/imports`、`/imports/run?id=<导入>` | 导入（20 §6）：上传文件 → 映射（CSV / Excel 版式调整、字段取自哪一列或常量、保存与载入映射、前 20 行）→ 参数（表单由参数 Schema 生成）→ 预览（全部行执行并回滚：数字、控制合计、全部问题、逐行状态）→ 说明与提交（有任何问题即不能提交；被拒的提交显示其报告） |
| `/imports/runs[?import=]` | 导入记录（20 §5）：结果、行数、重复、问题、说明；问题明细；报告保存为 PDF / Excel / CSV |
| `/audit[?entityType=&entityId=]` | 审计记录（21 §1.4，`audit.read`）：按实体、操作人、时间、流程、字段筛选；展开看每个字段的前后值（敏感值为 `***`，遮蔽字段为其遮蔽形式）；按记录筛选时一并列出其审批。历史页与列表行有入口。"明文显示"页签列出 `sys_reveal_record`（10 §13.1） |
| `/access-review` | 访问审查（10 §13.3，`security.access-review.read`）：选期间（整日，结束为次日 0 时）、签发访问权限报表（`report.issue`，进入报表存档）、期间内的安全变更、职责分离冲突、签核（`security.access-review.sign`，填写意见，需要二次验证）、已签核列表 |
| `/integrity` | 防篡改封存（21 §2.4，`integrity.read`）：最新块（哈希可复制，供系统外留存；密钥不同时提示）、立即校验（`integrity.verify`，即流程 `INTEGRITY_VERIFY`）、校验记录及其问题 |
| `/retention` | 保留与归档（21 §3–§4）：保留期报告（`retention.read`）、法律保全入口（生成的列表与 `LEGAL_HOLD_PLACE` / `RELEASE` 表单）、开放格式导出（`data.export`：选数据视图、时点、是否附报表 PDF，下载 ZIP） |
| `/login/oidc` | 身份提供方的回调（10 §12）：不需要已登录；显示身份提供方或服务端的拒绝 |
| `/account/security` | 安全设置（10 §9）：本人的两步验证状态、绑定（二维码用 `qrcode` 绘制在 canvas 上，另显示密钥）、剩余恢复码数；从页头用户菜单进入 |
| `/processes`、`/processes/:name/:version` | 流程目录与由输入 Schema 生成的表单（嵌套 record → 分组，record 列表 → 可增减的行）；每次打开表单生成一个 `Idempotency-Key`，成功后更换 |

- 布局 `ProLayout`：服务端菜单（`SecMenu`，已按权限过滤、按语言命名）在前，其后是应用扩展的菜单项（第 9 节），再后是两个目录与"报表"（有报表时）；语言切换记在 `localStorage`（仅本机偏好）。
- 实体上的操作（16 §3）：流程目录中 `actsOn` 指向该实体的流程，在列表行与详情中显示为按钮（`when` 只是显示提示）；打开的流程表单中主键已填且只读，
  输入只有主键时确认后直接执行。
- 详情抽屉中的子实体列表（16 §4）：由 `Reference` 与子实体默认视图的列表视图 `filters` 推导，新建时引用字段预填。
- `processOnly` 字段在表单中只读，新建与修改都不发送（16 §5）。
- 没有写权限时，行上的"查看"以只读方式打开详情（显示实体上的操作与子实体列表）。
- 按权限显示操作：新建 / 编辑 / 删除看 `canWrite`，历史看 `temporal && allowTimeTravel`，操作详情与撤销看当前操作人的权限。

## 7. 构建与运行

- 开发：`frontend/` 下 `pnpm install && pnpm dev`（5173，`/api` 代理到 8080）。
- 打包：`./gradlew :app:bootJar` 经 node-gradle（pnpm）构建前端并打入 jar，后端同一端口提供页面与接口（`SpaFallbackFilter`）。
  构建由约定插件 `jabiz.boot-app` 完成（`jabizApp { spa("/", "../../frontend") }`，17 §3.1）；服务端给页面加内容安全策略（17 §3.2），
  端到端测试在任何 CSP 违规时失败。
- 子路径：`VITE_BASE=/admin/`（`src/base.ts` 校验并换算 React Router 的 `basename`，16 §6）；CI 检查以 `/admin/` 构建后的资源路径。
- 检查：`pnpm lint`、`pnpm typecheck`、`pnpm check:api`、`pnpm test`、`pnpm build`；端到端 `pnpm e2e`（见第 8 节）。
- 应用扩展（第 9 节）：`JABIZ_ADMIN_EXTENSION=<目录> pnpm build` 把它编入；`pnpm ext:check` 检查它（类型、lint、测试）。

## 8. 测试

- Vitest：适配层每个模块（`decimal`、`validation`、`kinds`、`listQuery`、`entityForm`、`processForm`、`history`、`problem`、`i18nText`）、
  共享校验用例、历史时间线组件；多语言控件、Markdown 预览（不渲染原始 HTML）、引用下拉、行操作按钮的显示条件（16 §8）。
- Playwright（`e2e/`，对运行中的 jar，`E2E_BASE_URL` 默认 `http://localhost:8080`，管理员 `E2E_ADMIN_USER` / `E2E_ADMIN_PASSWORD`，
  可用 `E2E_CHROMIUM` 指定已安装的 Chromium）：登录与失败、动态菜单与三种语言、只读用户看不到写操作；`Carrier` 列表（筛选、排序、区间）；
  表单新建 / 编辑 / 删除；六种非法输入的前端错误码与直接调用接口的错误码相同；历史时间线、回看、预定、操作详情、撤销；
  列表按时间点读取；另一个时态实体 `Price` 的历史；流程表单。每个测试使用自己的数据（表只增不删）。
- 示范实体 `Carrier`（app，时态，`V7__carrier.sql`）只有声明，前端没有它的专门代码；`CarrierIT` 断言经数据视图接口的写入只有 INSERT。
- 二次验证（阶段 14g-1）：`src/api/stepUp.test.ts`（403 `MFA_REQUIRED` 时询问并以新令牌重发、其他 403 与取消原样返回）、`src/auth/StepUp.test.tsx`、
  `src/auth/useIdleLock.test.tsx`、`src/components/MfaEnrollment.test.tsx`、`src/pages/LoginPage.test.tsx`（含提供方按钮与单点登录后的第二步）、
  `src/pages/OidcCallbackPage.test.tsx`、`src/auth/oidc.test.ts`（阶段 14g-2）；端到端 `e2e/mfa.spec.ts`
  （安全设置中绑定、改价时按要求输入验证码、用恢复码登录；CI 以 `JABIZ_SECURITY_MFA_ADMINISTRATION=false` 运行，共用的管理员没有二次验证）。
- 按权限显示明文、数据期限、访问审查（阶段 14g-3）：`src/meta/columns.test.ts`（遮蔽字段的筛选与排序只对持有权限者开放、"显示"按钮）、
  `src/meta/entityForm.test.ts`（没有权限时只读）、`src/components/MaskedValue.test.tsx`、`src/pages/AccessReviewPage.test.tsx`、`src/pages/AuditPage.test.tsx`（明文显示页签）。
- 语言与区域（第 10 节）：`src/i18n/languages.test.ts`、`src/meta/format.test.ts`、`scripts/app-settings.test.ts`；后端 `LanguageSubsetTest`、`LanguageSubsetIT`。
- 应用扩展（第 9 节）：`src/extension/registry.test.ts`（问题一次报告全部、平台路径、相对路径、菜单按权限过滤与空组、首页）；
  `scripts/extension-lint.test.ts`（深层引用被拒绝）；示范扩展的页面测试（`backend/app/admin-extension/src/*.test.tsx`，经 `pnpm ext:test`）；
  端到端 `e2e/extension.spec.ts`（从菜单进入、读模板、执行流程；无权限用户看不到菜单项，直接打开时服务端返回 403）。

## 9. 应用扩展：应用自己的后台页面【D22】

应用有元数据表达不了的工作流时（例如多行分录的录入网格、对账），在自己的目录里写一个**扩展**，构建通用后台时编入。

```
<应用目录>/admin-extension/            （例：backend/app/admin-extension/、finance-web/）
  tsconfig.json                        {"extends": "<相对路径>/frontend/tsconfig.extension.json", "include": ["src"]}
  src/index.tsx                        export default defineExtension({ routes, menu, messages, home })
  src/…Page.tsx、src/…test.tsx          页面与测试；只 import '@jabiz/admin' 与平台前端已有的第三方包
```

| 项 | 规定 |
|---|---|
| 编入 | `jabizApp { spa("/", "../../frontend", extension = "admin-extension") }`：插件以 `JABIZ_ADMIN_EXTENSION` 构建；`vite.config.ts` 把 `virtual:jabiz-extension` 指向其 `src/index.tsx`（未指定时为空扩展 `src/extension/none.ts`），并以 `resolve.dedupe` 让扩展的第三方引用解析到平台前端的 `node_modules`（一份 React、一份 antd） |
| `routes` | 挂在已登录的外框（`ProLayout`）内；绝对路径、不重复，不能占用 `/`、`/login`、`/data…`、`/processes…`、`/tasks…`；不能是 index 路由（用 `home`） |
| `menu` | 排在服务端菜单之后；`label` 是扩展文案的键；`permission` 只决定是否显示；子项全部不可见的分组不显示 |
| `messages` | 每种界面语言一份，放在 i18next 命名空间 `app`（`useTranslation(EXTENSION_NAMESPACE)`），不覆盖平台文案 |
| `home` | 登录后与未知路径的落点；缺省 `/data` |
| 可用的平台内容 | 只有 `@jabiz/admin`（`frontend/src/lib/index.ts`）：`api` / `unwrap` / `ApiError`、`runQuery`（SQL 模板）、`runProcess`（流程，自动带幂等键）、`useAuth`、元数据 hooks、`EntityFormDrawer` 等通用组件、格式化函数 |
| 检查 | 启动时 `checkedExtension` 一次报告全部问题并停止；`pnpm build` 先对扩展做类型检查；`pnpm ext:typecheck / ext:lint / ext:test / ext:check`（lint 另加规则：拒绝引用 `frontend/` 下的路径与 `virtual:jabiz-extension`） |

- 扩展页面调用的仍是 `/api/**`：权限、数据视图范围与校验都在服务端，页面上的隐藏只是导航。
- 扩展不能自带依赖（没有自己的 `package.json`）；需要新的通用依赖时先加到平台前端。
- 示范：`backend/app/admin-extension/`（"库存概览"：模板 `commerce.stock_availability` 与流程 `STOCK_RECEIVE`，菜单项需 `commerce.stock.read`）。
  操作步骤见 `docs/guide/admin-extension.md`。

## 10. 界面语言与区域【D22 第 7 条】

应用在 `jabizApp` 中声明，前后端取自同一处：

```kotlin
jabizApp {
    languages("en")      // 界面语言：平台语言 zh / ja / en 的子集，缺省全部
    region = "en-US"     // 日期、数字、金额按此区域显示；缺省为平台的中性格式
}
```

| 方面 | 做法 |
|---|---|
| 服务端 | 插件把语言写入 jar 中的 `META-INF/jabiz-app.properties`（`jabiz.i18n.languages`）；消息目录只含这些语言，文案完整性检查只要求这些语言；请求其他语言时以缺省语言回答。启动检查（类别 `I18N`）报告不是平台语言的代码、空列表、不在其中的 `jabiz.i18n.default-locale` |
| 前端 | 以 `VITE_JABIZ_LANGUAGES` / `VITE_JABIZ_REGION` 构建（构建时校验，`scripts/app-settings.ts`）；语言切换只列所选语言，只有一种时不显示；记住的或浏览器的语言不在其中时取第一种（有中文时取中文） |
| 区域 | `src/meta/format.ts`：时间 `formatDateTime`（`en-US`：`01/31/2026, 02:05:09 PM`；无区域：`2026-01-31 14:05:09`）、日期 `formatDate`、金额 `formatAmount(value, {scale, currency?, negative: 'minus' | 'parentheses'})`（精确的十进制文本、千分位、固定小数位；财务报表用括号表示负数）；列表与详情中的金额按区域格式化 |
| 内容语言 | 不变：多语言文本字段仍按平台的三种语言编辑（D20 第 3 条），字段声明自己的必填语言 |

扩展经 `@jabiz/admin` 使用 `formatAmount`、`formatDate`、`formatDateTime`、`enabledLanguages`。

