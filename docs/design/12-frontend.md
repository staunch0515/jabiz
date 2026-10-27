# 12 前端

由元数据驱动的后台前端（ROADMAP 阶段 10）。原则：**业务对象不写前端代码**——新增实体定义与数据视图后，列表页、表单页、历史页
都由元数据生成；前端只是元数据的解释器，不是权限的守门人（隐藏 ≠ 授权，接口照常检查）。约束性细则见【决策 D15】。

## 1. 技术与结构

| 项 | 选择 |
|---|---|
| 工具链 | pnpm、Vite、TypeScript |
| 界面 | React 19；shadcn/ui（Radix + Tailwind CSS v4，组件在 `@jabiz/ui`；第 12 节、决策 D34）。外壳、全部页面与示范扩展都以 `@jabiz/ui` 构建；Ant Design 已在 15d 删除，lint 禁止再引用 |
| 数据 | TanStack Query（缓存键含界面语言）；请求经 `openapi-fetch`，类型由 OpenAPI 生成 |
| 路由、多语言 | React Router；i18next（zh / ja / en，应用可只选其中几种），日期控件的语言随之切换；可按区域（如 `en-US`）显示日期、数字与金额（第 10 节） |
| 测试 | Vitest + Testing Library（适配层、组件）；Playwright（端到端） |

```
frontend/                   pnpm 工作区（pnpm-workspace.yaml：packages/*），一个 lockfile
  openapi/openapi.json      后端 OpenAPI 快照（由后端测试写出并比对，见第 3 节）
  packages/client/          `@jabiz/client`（无界面，第 12 节）：api/schema.d.ts（生成）、client.ts（令牌、语言、401 时刷新一次）、
                            session.ts、problem.ts、files.ts、auth/、lib/calls.ts、i18n/、meta/format.ts、meta/decimal.ts、UserName
  packages/ui/              `@jabiz/ui`（第 12 节）：shadcn 组件、组合组件、theme.css；README.md 记录对上游的修改与升级方法
  src/meta/                 适配层：kinds、listQuery、cells、dataColumns、entityForm、validation、processForm、history（页面与测试直接引用 `@jabiz/client` / `@jabiz/ui`，测试 mock `@jabiz/client`）
  src/components/ pages/    通用页面：目录、列表、表单（右侧的 Sheet）、历史、流程
  src/extension/            应用扩展的约定与加载（第 9 节）
  src/lib/index.ts          `@jabiz/admin`：扩展可用的全部内容（第 9 节）
  scripts/                  扩展的解析与检查（ext.mjs、extension.ts、extension-lint.mjs）；应用 SPA 的版本检查（app-check.mjs）
  e2e/                      Playwright
../spec/validation-cases.json  前后端共享的校验用例（第 5 节）
```

## 2. 元数据接口（前端的全部输入）

| 接口 | 内容 | 权限 |
|---|---|---|
| `GET /api/meta/entities/{name}` | 实体导出（02 §8）+ 按请求语言的 `label`（实体与字段）与 `messages`（本实体前端可能报出的错误码 → 文案模板） | 已认证 |
| `GET /api/meta/datasets` | 调用方**可读**的数据视图：`id`、`entity`、`label`、`isDefault`、`temporal`、`allowScheduled`、`readOnly`、`processOnlyWrites`、`allowTimeTravel`、`softDelete`、`listView`、`maxQueryBatchSize`、`canWrite` | 已认证，按视图读权限过滤 |
| `GET /api/meta/processes` | 调用方**可执行**、且不是 `internal()` / `entryOnly()` / `subProcessOnly()` 的流程：`name`、`version`、`latest`、`deprecated`、`label`、`description`、`input`（输入的 JSON Schema）、`actsOn`（`{entity, input, when?}`，16 §3） | 已认证，按流程权限过滤 |
| `GET /api/meta/imports` | 调用方可运行的导入（20 §6）：字段、版式、参数 Schema、控制合计、能否保存映射 | 已认证，按导入、行流程、文件策略的权限过滤 |
| `GET /api/auth/menus`、`/api/auth/me` | 动态菜单、当前操作人（10 §3） | 已认证 |
| `GET /api/dictionaries/{urn}` | 字典项（按语言） | 已认证 |

- 标签：消息资源键 `entity.<实体>`、`entity.<实体>.<字段>`、`dataset.<视图 id>`、`process.<流程名>`，缺失时回退为名字；
  系统字段（`effectStartTime` 等）由前端自己的文案命名。
- `canWrite` = 具备写权限 ∧ 非只读 ∧ 非 `processOnlyWrites` ∧ 实体可写（有版本字段）。未声明的权限只在 `dev` 下算数（默认拒绝，10 §5）。
- `internal()`（只由系统身份运行，如 `SEC_BOOTSTRAP_ADMIN`）、`entryOnly()`（只经平台自己的入口，如 `SPONSOR_SIGN_IN`、`ADD/UPDATE/DELETE_ENTITY`）、
  `subProcessOnly()`（只作子流程，如 `FILE_ARCHIVE`）的流程不进目录、不作行操作，经流程接口调用即 403 `PROCESS_INTERNAL`（06 §8，D41）；权限照常检查。
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
  `CI` 环境下快照缺失即失败。前端 CI 的 `pnpm check:api` 确认 `packages/client/src/api/schema.d.ts` 与快照一致。
- 以 `Map` 返回的接口（实体导出、历史、操作详情）在 OpenAPI 中是自由对象，前端在 `src/meta/types.ts` 中声明其形状。

## 4. 登录与令牌

- 登录 `POST /api/auth/login`；登出调用 `/api/auth/logout` 并清除本地会话。
- **会话按入口**（D43 第 1、2 条，16c-1）：每个前端有一个登录入口（`configureClient({ entry })`，缺省取构建变量 `VITE_JABIZ_ENTRY`，再缺省 `admin`），
  登录、刷新与 OIDC 的 `start` 都带 `entry`；`/api/auth/me` 的入口与配置的入口不符时清掉会话。同源的几个 SPA 因此各有各的会话。
- **访问令牌只在内存中**；**刷新令牌的键按入口分开**（`jabiz.session.<入口>.refresh`），存在哪里由入口的 `session.persistence` 决定
  （客户端启动时读 `GET /api/auth/entries/{name}` 的 `sessionPersistence`，没有该项即 `tab`；`admin` 不读，固定 `tab`）：
  `tab` 在 `sessionStorage`（刷新页面保持登录，关闭标签页即结束）；`device` 在 `localStorage`（关闭后再打开仍登录）。
  `admin` 入口、以及没有 Web Locks 的浏览器一律按 `tab`；启动时在 `localStorage` 中找到的令牌先照用，读到设置后再按设置搬到对应的存储。
  后台第一次启动时把旧键 `jabiz.refreshToken` 迁移为 `jabiz.session.admin.refresh`（一次）。
  收到 401 时用刷新令牌刷新一次（同一标签页的并发请求共享同一次刷新：刷新令牌只能用一次），成功后重发原请求；刷新被服务端拒绝时清除会话回到登录页，网络失败时保留会话以便重试。
  `device` 下多个标签页以 Web Locks（锁名 `jabiz.refresh.<入口>`）轮流刷新，取得锁之后才读存储中的令牌，所以别的标签页刚换新的令牌不会被再用一次（否则服务端视为重用而吊销整个令牌族）；
  `device` 的令牌另写一份到 IndexedDB（库 `jabiz-session`），刷新时在锁内从那里读、写完（事务提交）才释放锁（16c-2）：各标签页读 IndexedDB 的结果一致，
  而一个标签页看到的 `localStorage` 是浏览器稍后才从其他标签页同步过来的副本（Chromium 如此），刚轮到它时仍可能读到旧令牌；`localStorage` 中的副本只供同步的判断（有没有会话）。
  会话结束时 IndexedDB 中留下空值（墓碑），不会再退回到旧的 `localStorage` 副本；写入 IndexedDB 失败时删除该记录，读取退回 `localStorage`；
  没有 IndexedDB 的浏览器只用 `localStorage`；本人改密码随请求送出的刷新令牌也在锁内这样读；
  登出与在两种存储之间搬移令牌也在锁内；`tab` 的令牌是本标签页自己的，不取锁；浏览器拒绝锁（沙箱、不透明源）时不加锁执行。
  刷新限时 15 秒；只有服务端拒绝（400 / 401 / 403 / 422）才清除会话，5xx、429 与网络失败保留会话。清除会话时两种存储中该入口的键都删除。
  `device` 下一个标签页登出时其他标签页随之登出。`refreshSession({ force: true })` 不搭已在进行的刷新、另取新令牌（验证邮箱后令牌的 `email_verified` 才为真）。
  外观与界面语言是用户偏好，同源的各 SPA 有意共用。已知限制："复制标签页"会复制 `sessionStorage`，两个标签页之后各自刷新会触发重用吊销（`tab` 下原有问题）。
- 当前操作人与权限来自 `GET /api/auth/me`，不在前端解码令牌；`useAuth()` 另给出 `entry`、`email`、`emailVerified`。
- 二次验证（10 §9，D28）：登录响应为 `MFA_REQUIRED` 时，登录页转入输入验证码（或恢复码）的一步（`/api/auth/challenge/verify`）；
  为 `MFA_ENROLLMENT_REQUIRED` 时先绑定（二维码与密钥、确认码、只显示一次的恢复码），然后重新登录。挑战令牌只在页面状态中，不存储。
- 按操作要求二次验证（10 §10）：任何请求收到 403 且违规为 `MFA_REQUIRED` 时，API 客户端请外框弹出输入框（`StepUpProvider`，并发请求共用一次），
  `POST /api/auth/step-up` 成功后以新的访问令牌重发原请求；取消则把原 403 交给调用方。未绑定时提示先到"安全设置"绑定。
- 单点登录（10 §12）：登录页列出 `/api/auth/oidc/providers?entry=<入口>` 的提供方按钮（只有该入口有回调的，16c-2）；点击后以本前端的入口取得授权地址并整页跳转（登录后要回到的页面与绑定值记在
  `sessionStorage` 的 `jabiz.session.<入口>.oidcReturnTo` / `.oidcBinder`，只接受本站的路径，缺省由调用方给出：`takeReturnPath(fallback)`）；身份提供方回到 `/login/oidc`，该页把 state 与授权码交给服务端一次，结果与密码登录相同（需要第二步时回到登录页的验证码或绑定步骤）。
- 闲置锁定（10 §11）：外框按键盘、指针、滚动与触摸活动计时（`idleTimeoutSeconds` 来自 `/api/auth/me`），到时登出并回到登录页，提示原因、填好用户名（`sessionStorage`，只是便利）。
- 数据期限（10 §13.2）：`/api/auth/me` 的 `dataFrom` / `dataTo` 不为空时，页头显示期限标签（列表与报表只含该期间，由服务端过滤）。
- 所有请求带 `Accept-Language`（界面语言），服务端据此返回错误文案、标签与字典。

## 5. 适配层

| 语义类型 | 输入控件 | 展示 | 列表筛选 |
|---|---|---|---|
| `text` | 文本框（`multiline` → 多行） | 原样 | 允许 `LIKE` → 包含；否则等于 |
| `monetary` / `numeric` | 数字框（文本模式，不取整，金额带币种后缀） | 按币种与小数位 / 精确小数 | 区间（`between` / `gte` / `lte`） |
| `temporal` | 日期时间（按应用区域的格式键入，ISO `YYYY-MM-DD HH:mm:ss` 也可；或从日历选择；无效的键入不提交） | 本地时间 | 时间区间 |
| `date` | 日期（按所选的那一天发送 `YYYY-MM-DD`，不经时区换算） | 日期（区域格式） | 日期区间（含两端） |
| `code` | 下拉（字典的启用项按顺序，否则固定值） | 字典标签 | 等于 |
| `bool` | 开关（新建时默认"否"） | 是 / 否 | 等于 |
| `version` | 整数 | 原样 | 区间 |
| `semanticIdentity` | 文本框 | 原样 | 等于 |
| `reference` | 可搜索下拉（目标实体声明了 `display` 且默认视图可读时，`lookup`；否则文本框） | 标签（`labels`，按页批量；取不到时主键） | 等于 |
| `custom`：`jabiz.file` | 上传按钮（`accept` 与大小提示来自导出的策略）+ 预览；移除即清空 | 图片缩略图（策略中最窄的合适变体；图片比它窄时服务端返回原件，14 §5）/ 下载按钮 | 不提供 |
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
| `/tasks` | 我的待办（18 §5.3）：`Card` 列表，审批待办就地批准或驳回（`ApprovalPanel`：意见框加两个按钮，驳回必须填意见），其他待办链接到其页面；没有待办时为 `PageState` 的空状态；页头显示开放待办数 |
| `/reports`、`/reports/run?id=<模板>` | 报表（19 §3.3）：目录中声明了 `report` 的模板；参数表单、生效 / 记录时点、结果表格（分页、白名单内筛选与排序）、导出 Excel / PDF / CSV（19 §4） |
| `/reports/archive[?template=]` | 已签发的报表（19 §5.4）：按签发原样保存（PDF / Excel / CSV）、核对 |
| `/documents[?layout=&subject=]` | 已签发的单据（22 §6）：`DataTable`，下载存档的原件、核对（存档字节与数据）、发送（`Dialog`，地址用 `TagsInput`，单据中的地址预填，移除的可以一键加回）；展开行（`DataTable` 的展开按钮）列出发送记录（结果的 `Badge`，失败原因在 `Tooltip` 中）；有 `document.archive.read` 时在菜单中 |
| `/imports`、`/imports/run?id=<导入>` | 导入（20 §6）：上传文件 → 映射（CSV / Excel 版式调整、字段取自哪一列或常量、保存与载入映射、前 20 行）→ 参数（表单由参数 Schema 生成）→ 预览（全部行执行并回滚：数字、控制合计、全部问题、逐行状态）→ 说明与提交（有任何问题即不能提交；预览之后改了版式、映射或参数须重新预览，提交只发送预览过的内容；被拒的提交显示其报告） |
| `/imports/runs[?import=]` | 导入记录（20 §5）：结果、行数、重复、问题、说明；问题明细；报告保存为 PDF / Excel / CSV |
| `/audit[?entityType=&entityId=]` | 审计记录（21 §1.4，`audit.read`）：`Tabs` 两个页签，各是 `FilterBar` 加远程分页的 `DataTable`（每页缺省 50）；按实体、操作人、时间、流程、字段筛选，地址中的 `entityType` / `entityId` 等是筛选的初值；展开行看每个字段的前后值（敏感值为 `***`，遮蔽字段为其遮蔽形式）；按记录筛选时一并列出其审批。历史页与列表行有入口。"明文显示"页签列出 `sys_reveal_record`（10 §13.1） |
| `/access-review` | 访问审查（10 §13.3，`security.access-review.read`）：选期间（首日与末日两个 `DatePicker`，整日，结束为次日 0 时）、签发访问权限报表（`report.issue`，先经 `ConfirmDialog`，进入报表存档）、期间内的安全变更、职责分离冲突、签核（`security.access-review.sign`，在 `Dialog` 中填写意见（必填），二次验证由 step-up 处理）、已签核列表（三个表都是 `DataTable`） |
| `/security/sessions` | 会话（10 §15.9，`security.user.session.read`）：按用户名搜索用户（`SecUser` 默认视图的查询，需要 `security.user.read`），列出其在全部入口的有效会话（设备、入口、地址、最近使用、登录与到期时间）；持有 `security.user.session.revoke` 时可结束单个或全部会话，须填写原因，二次验证由 step-up 处理。用户列表上另有生成的行操作"结束会话"（`SEC_USER_SESSIONS_REVOKE`） |
| `/integrity` | 防篡改封存（21 §2.4，`integrity.read`）：最新块（`DescriptionList`，哈希带复制按钮，供系统外留存；密钥不同时提示）、立即校验（`integrity.verify`，即流程 `INTEGRITY_VERIFY`，之后刷新记录并打开其详情）、校验记录（`DataTable`）及其问题（详情在 `Sheet` 中，标题含 `#` 编号） |
| `/retention` | 保留与归档（21 §3–§4）：保留期报告（`retention.read`）、法律保全入口（生成的列表与 `LEGAL_HOLD_PLACE` / `RELEASE` 表单）、开放格式导出（`data.export`，react-hook-form：以 `Combobox` 逐个加入数据视图（至少一个）、时点 `DateTimePicker`、是否附报表 PDF（`Checkbox`，及签发日的起止），下载 ZIP） |
| `/login/oidc` | 身份提供方的回调（10 §12）：不需要已登录；显示身份提供方或服务端的拒绝 |
| `/auth/verify-email?entry=&token=` | 验证邮件的链接（10 §15.7）：不需要已登录；点击后才先问 `check`、再提交（可预占的账号要当前密码或新密码），成功后"继续"到入口的 `app-path`；链接无效时可输入邮箱重发（对任何邮箱同样回答），其他失败可重试 |
| `/auth/join?entry=&token=` | "你已有账号"邮件的链接（10 §15.7）：点击"继续"才提交（邮件扫描器打开链接不改变任何东西）；账号的邮箱从未验证时要求设置新密码 |
| `/auth/password-reset[?entry=&token=]` | 找回密码（10 §15.7）：无令牌时输入邮箱申请（对任何邮箱同样回答）；有令牌时设置新密码，完成后到入口的 `app-path` 登录。登录页有"忘记密码"链接。三个 `/auth/*` 页读出令牌后即从地址栏去掉它，打开期间不发送 referrer |
| `/account/security` | 安全设置：由各节组成，节的顺序由页面中的数组 `ACCOUNT_SECTIONS` 声明（每节一个 `<section aria-labelledby>` 加 `Card`，测试 id `account-section-<名>`）：两步验证（`@jabiz/ui` 的 `MfaSettings`，10 §9：状态、剩余恢复码数、未绑定时绑定，二维码用 `qrcode` 绘制在 canvas 上，另显示密钥；示范门户的账户页也用它）、改密码（`ChangePasswordForm`，10 §15.8）、本人当前入口的会话（`SessionList`，10 §15.9）、关联的外部账号（`LinkedIdentities`，10 §12，16f-1；只在后台入口提供了身份提供方时显示——与登录页同一个 `/api/auth/oidc/providers` 查询——节可声明 `shown`；关联完成后 OIDC 回调页经路由状态把结果带回本页）、邮件（`MailPreferences`，18 §5.6，16s：后台入口的可退订通知与邮件语言）；新的一节只需往数组里加一项；从页头用户菜单进入 |
| `/processes`、`/processes/:name/:version` | 流程目录与由输入 Schema 生成的表单（嵌套 record → 分组，record 列表 → 可增减的行）；每次打开表单生成一个 `Idempotency-Key`，成功后更换 |

- 布局（`@jabiz/ui` 的侧栏外壳，15a）：服务端菜单（`SecMenu`，已按权限过滤、按语言命名）在前，其后是应用扩展的菜单项（第 9 节），再后是两个目录与"报表"（有报表时）；语言切换记在 `localStorage`（仅本机偏好）。
- 全部页面以 `@jabiz/ui` 构建（15b–15c），用 `AdminPage`（页头、唯一的 `<h1>`、面包屑：数据 › 实体（› 历史）、流程 › 流程名、报表 › 报表名、导入 › 导入名），
  随外观变化（15d 起不再有固定为亮色的区域，路由也不再带标记）。
  列表（`DatasetListPage`）：`DataTable` 远程分页（每页 10 / 20 / 50 / 100，缺省 20）、排序（列表视图的默认排序；只有白名单内、且遮蔽字段只对持有权限者可排序；
  每列先升序、再降序、再取消）与 `FilterBar`（白名单字段，按语义类型给文本、选项、数值 / 时间 / 日期区间；不含敏感字段，遮蔽字段只给持有权限者），
  新的查询或排序回到第 1 页；请求由 TanStack Query 发出，翻页时保留上一页；操作列固定在右侧。列与筛选由 `meta/dataColumns.tsx` 生成，单元格（`meta/cells.ts`
  的 `renderCell`：引用显示名、多语言回退、文件缩略图、遮蔽值"显示"）只有这一份。
  报表（`ReportPage`）：参数由 `SchemaForm` 生成，模板接受请求时点时两个 `DateTimePicker` 放在表单的 `children` 中；结果与列表页同样是 `DataTable`（每页缺省 50）
  加 `FilterBar`，列与筛选同样由 `dataColumns` / `filterFields` 生成、请求同样由 `buildFilters` / `buildSorts` 按模板的白名单组成（金额右对齐、负数加括号）；
  导出是 `DropdownMenu`，签发先经 `ConfirmDialog`。导入向导（`ImportPage`）：`Steps` 标出当前步骤；上传是 `FileUpload` 的拖放区；版式用 `Combobox` / `DecimalInput` / `Switch`；
  每个字段的来源列是一个以"字段名 的来源列"命名的 `Combobox`；参数是隐藏了自身按钮的 `SchemaForm`（`id` + `hideSubmit`，由"预览"按钮或确认后的提交来提交）；
  预览与提交的报告用 `DescriptionList`（多列）、`SegmentedControl` 与 `DataTable`；提交先经 `ConfirmDialog`。导入记录的详情在 `Sheet` 中。
  表单（`EntityFormDrawer`）是右侧的模态 `Sheet`，值由 react-hook-form 保存（`toFormValue` 给出文本：时间为存储的 ISO 时刻、日期为 `YYYY-MM-DD`），
  校验仍只走 `meta/validation.ts`；字段的问题以 `aria-describedby` 关联到输入框。子实体在父实体的 `Sheet` 之上再开一个 `Sheet`。流程表单由 `SchemaForm`（react-hook-form）生成：
  前端只检查必填与 `pattern`（纯函数 `checkInputs`），数字输入以 JSON 数字发送，必填的布尔值默认"否"；历史页的时间点（`asOf` / `knownAt`）写在地址中。
- 实体上的操作（16 §3）：流程目录中 `actsOn` 指向该实体的流程，在列表行与详情中显示为按钮（`when` 只是显示提示）；打开的流程表单中主键已填且只读，
  输入只有主键时确认后直接执行。
- 详情（表单 `Sheet`）中的子实体列表（16 §4，每页 5 条）：由 `Reference` 与子实体默认视图的列表视图 `filters` 推导，新建时引用字段预填。
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
- 应用自有的 SPA 与 PWA（第 12 节，17 §3.1、§4.1）：应用目录中的 pnpm 工程，用预设 `@jabiz/client/vite`；`jabizApp { spa(…, entry, pwa) }` 构建并打包，
  `pnpm app:check <目录>` 检查版本（Gradle 的 `spaCheck<名>`）。示范门户 `backend/app/portal/`。

## 8. 测试

- Vitest：适配层每个模块（`decimal`、`validation`、`kinds`、`listQuery`、`dataColumns`、`entityForm`、`processForm`、`history`、`problem`、`i18nText`）、
  共享校验用例、历史时间线组件；多语言控件、Markdown 预览（不渲染原始 HTML）、引用下拉、行操作按钮的显示条件（16 §8）。
- Playwright（`e2e/`，对运行中的 jar，`E2E_BASE_URL` 默认 `http://localhost:8080`，管理员 `E2E_ADMIN_USER` / `E2E_ADMIN_PASSWORD`，
  可用 `E2E_CHROMIUM` 指定已安装的 Chromium）：登录与失败、动态菜单与三种语言、只读用户看不到写操作；`Carrier` 列表（筛选、排序、区间）；
  表单新建 / 编辑 / 删除；六种非法输入的前端错误码与直接调用接口的错误码相同；历史时间线、回看、预定、操作详情、撤销；
  列表按时间点读取；另一个时态实体 `Price` 的历史；流程表单。每个测试使用自己的数据（表只增不删）。
- 示范实体 `Carrier`（app，时态，`V7__carrier.sql`）只有声明，前端没有它的专门代码；`CarrierIT` 断言经数据视图接口的写入只有 INSERT。
- 二次验证（阶段 14g-1）：`packages/client/src/api/stepUp.test.ts`（403 `MFA_REQUIRED` 时询问并以新令牌重发、其他 403 与取消原样返回）、`src/auth/StepUp.test.tsx`、
  `packages/client/src/auth/useIdleLock.test.tsx`、`packages/ui/src/auth/MfaEnrollment.test.tsx`、`src/pages/LoginPage.test.tsx`（含提供方按钮与单点登录后的第二步）、
  `src/pages/OidcCallbackPage.test.tsx`、`packages/client/src/auth/oidc.test.ts`（阶段 14g-2）；端到端 `e2e/mfa.spec.ts`
  （安全设置中绑定、改价时按要求输入验证码、用恢复码登录；CI 以 `JABIZ_SECURITY_MFA_ADMINISTRATION=false` 运行，共用的管理员没有二次验证；并以 `JABIZ_ADMIN_ROLES=*`、`JABIZ_PORTAL_SELF_REGISTRATION=false` 运行，测试以自己的角色登录后台）。
- 按权限显示明文、数据期限、访问审查（阶段 14g-3）：`src/meta/masked.test.ts`、`src/meta/dataColumns.test.ts`（遮蔽字段的筛选与排序只对持有权限者开放、"显示"按钮）、
  `src/meta/entityForm.test.ts`（没有权限时只读）、`src/components/MaskedValue.test.tsx`、`src/pages/AccessReviewPage.test.tsx`、`src/pages/AuditPage.test.tsx`（明文显示页签）。
- 语言与区域（第 10 节）：`packages/client/src/i18n/languages.test.ts`、`packages/client/src/meta/format.test.ts`、`scripts/app-settings.test.ts`；后端 `LanguageSubsetTest`、`LanguageSubsetIT`。
- 应用扩展（第 9 节）：`src/extension/registry.test.ts`（问题一次报告全部、平台路径、相对路径、菜单按权限过滤与空组、首页）；
  `scripts/extension-lint.test.ts`（深层引用、antd、`@jabiz/ui` / `@jabiz/client` 被拒绝，`@jabiz/admin/testing` 只在测试中；平台前端拒绝 antd，e2e 拒绝 `.ant-*` 选择器）；
  `src/lib/testing.test.ts`（`@jabiz/admin/testing` 即 `@jabiz/ui/testing` 的辅助函数，生产源码不以任何形式引用它）；`scripts/bundle-check.test.ts`（生产包中测试代码的标记）；示范扩展的页面测试（`backend/app/admin-extension/src/*.test.tsx`，含亮暗两种外观的 axe，经 `pnpm ext:test`）；
  端到端 `e2e/extension.spec.ts`（从菜单进入、读模板、执行流程；无权限用户看不到菜单项，直接打开时服务端返回 403）。

## 9. 应用扩展：应用自己的后台页面【D22】

应用有元数据表达不了的工作流时（例如多行分录的录入网格、对账），在自己的目录里写一个**扩展**，构建通用后台时编入。

```
<应用目录>/admin-extension/            （例：backend/app/admin-extension/、finance-web/）
  tsconfig.json                        {"extends": "<相对路径>/frontend/tsconfig.extension.json", "include": ["src"]}
  src/index.tsx                        export default defineExtension({ routes, menu, messages, home })
  src/…Page.tsx、src/…test.tsx          页面与测试；只 import '@jabiz/admin'（测试另可 '@jabiz/admin/testing'）与平台前端已有的第三方包
```

| 项 | 规定 |
|---|---|
| 编入 | `jabizApp { spa("/", "../../frontend", extension = "admin-extension") }`：插件以 `JABIZ_ADMIN_EXTENSION` 构建；`vite.config.ts` 把 `virtual:jabiz-extension` 指向其 `src/index.tsx`（未指定时为空扩展 `src/extension/none.ts`），并以 `resolve.dedupe` 让扩展的第三方引用解析到平台前端的 `node_modules`（一份 React、一份 `@jabiz/ui`） |
| `routes` | 挂在已登录的外框（`@jabiz/ui` 的 `AppShell`）内，随外观变化；绝对路径、不重复，不能占用 `/`、`/login`、`/data…`、`/processes…`、`/tasks…`、`/access-review…`、`/security…`、`/auth…`、`/mail…` 等平台路径（`PLATFORM_PATHS`）；不能是 index 路由（用 `home`） |
| `menu` | 排在服务端菜单之后；`label` 是扩展文案的键；`permission` 只决定是否显示；子项全部不可见的分组不显示 |
| `messages` | 每种界面语言一份，放在 i18next 命名空间 `app`（`useTranslation(EXTENSION_NAMESPACE)`），不覆盖平台文案 |
| `home` | 登录后（没有要返回的页面时）、根路径与未知路径的落点；缺省 `/data` |
| 可用的平台内容 | 只有 `@jabiz/admin`（`frontend/src/lib/index.ts`）：`api` / `unwrap` / `ApiError`、`runQuery`（SQL 模板）、`runProcess`（流程，自动带幂等键）、`useAuth`、元数据 hooks、`EntityFormDrawer` 等通用组件、`UserName`（用户编号显示为名字，10 §14）、格式化函数；测试另有 `@jabiz/admin/testing`（`frontend/src/lib/testing.ts`，16w-1）：`expectAccessible(root?)` 与 `WCAG_TAGS`，即 `@jabiz/ui/testing` 的，亮暗两种外观过 axe（§11）。它同属 D22 的契约，只用于测试：带入 Vitest 与 vitest-axe，`@jabiz/admin` 与平台前端的生产源码都不引用它（`src/lib/testing.test.ts`），生产包里没有 |
| 检查 | 启动时 `checkedExtension` 一次报告全部问题并停止；`pnpm build` 先对扩展做类型检查；`pnpm ext:typecheck / ext:lint / ext:test / ext:check`（lint 另加规则：拒绝引用 `frontend/` 下的路径、`virtual:jabiz-extension` 与 antd / `@ant-design/*`（平台前端自己的 lint 用同一条，15d）；16w-1 起（W-7）拒绝 `@jabiz/ui`、`@jabiz/ui/*`、`@jabiz/client`、`@jabiz/client/*`（提示经 `@jabiz/admin`）与 `@jabiz/ui/testing`（提示 `@jabiz/admin/testing`），`@jabiz/admin/testing` 只许在测试（`*.test.*`、`*.spec.*` 与 `test/` 目录下的辅助文件）中——只拒绝 D22 本来就不允许的；规则对 `import()`、`require()`、`export … from` 与 `.js` / `.jsx` / `.mjs` / `.cjs` 文件同样适用。`ext:check` 另有 `bundle` 步骤：带扩展构建到临时目录，读回 `assets/*.js`，有 Vitest、`@vitest/*`、vitest-axe 或 axe-core 即失败；通用后台的每次构建由 Vite 插件 `noTestCodeInBundle`（`scripts/bundle-check.mjs`）同样检查）。菜单项的 `icon` 是 lucide 图标组件（`icon: Boxes`），菜单类型是平台的 `ShellMenuItem` |

- 扩展页面调用的仍是 `/api/**`：权限、数据视图范围与校验都在服务端，页面上的隐藏只是导航。
- 扩展不能自带依赖（没有自己的 `package.json`）；需要新的通用依赖时先加到平台前端。平台前端的依赖（含只在 `exports` 中声明类型的 `react-router`）
  由 `tsconfig.extension.json` 的 `paths` 解析，扩展的 `tsconfig.json` 只需 `extends` 与 `include`。`@jabiz/admin/testing` 也在其中（另有 `@jabiz/ui/testing`，
  只为它的传递引用）；`vite.config.ts` 的别名把 `@jabiz/admin/testing` 排在 `@jabiz/admin` 之前，Vitest 已由 `resolve.dedupe` 保证一份。
- 示范：`backend/app/admin-extension/`（"库存概览"：模板 `commerce.stock_availability` 与流程 `STOCK_RECEIVE`，菜单项需 `commerce.stock.read`；以 `react-router` 的 `Link` 链到入库记录的生成页面；
  15c-2 起全用 `@jabiz/ui`：`PageHeader`、入库表单为 react-hook-form 加 `Input` / `DecimalInput`（整数）、提示用 `notify`、表格为 `DataTable`）。
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
| 区域 | `@jabiz/client` 的 `meta/format.ts`：时间 `formatDateTime`（`en-US`：`01/31/2026, 02:05:09 PM`；无区域：`2026-01-31 14:05:09`）、日期 `formatDate`、金额 `formatAmount(value, {scale, currency?, negative: 'minus' | 'parentheses'})`（精确的十进制文本、千分位、固定小数位；财务报表用括号表示负数）；列表与详情中的金额按区域格式化 |
| 内容语言 | 不变：多语言文本字段仍按平台的三种语言编辑（D20 第 3 条），字段声明自己的必填语言 |
| 邮件语言 | 界面语言是浏览器的，邮件语言是账号的（`SecUser.locale`，18 §5.6），两者分开保存（D49 第 3 条）。切换界面语言**不**静默修改邮件语言：后台顶栏的语言切换在 `changeLanguage` 之后调用 `@jabiz/ui` 的 `offerMailLanguage(lang)`——已登录且邮件用的是另一种语言时，以带按钮的提示询问"邮件也改用该语言吗？"，用户点按钮才 `setMailLanguage`。应用 SPA 的语言切换照此显式调用；判断本身是 `@jabiz/client` 的 `mailLanguageOffer(lang)`（只读，不修改） |

扩展经 `@jabiz/admin` 使用 `formatAmount`、`formatDate`、`formatDateTime`、`enabledLanguages`。

## 11. 无障碍（WCAG 2.2 AA，阶段 14m）

通用后台以 WCAG 2.2 A / AA 为目标。自动检查找得到的部分由 e2e 守住，审计由使用方组织（自动检查约能发现审计问题的三分之一）。

| 方面 | 做法 |
|---|---|
| 自动检查 | `e2e/a11y.spec.ts`：`@axe-core/playwright` 以 `wcag2a`、`wcag2aa`、`wcag21a`、`wcag21aa`、`wcag22aa` 规则检查登录页（含必填提示与身份提供方的拒绝）、数据目录、列表（含删除确认与时间点的日历）、新建表单（含字典的选项、生效时间的日历）、引用的选择（展开的选项）、历史（含日历、操作详情、撤销对话框）、流程目录、流程表单（含必填的选择及其展开的选项）、待办、报表目录、报表（含导出菜单与签发确认）、报表存档、导入目录、导入向导（含映射步骤与展开的来源列选项）、导入记录（含详情 `Sheet`）、待办、审计、单据（含展开的发送记录与发送对话框）、访问审查（含签发确认与签核对话框）、封存（含校验详情的 `Sheet`）、保留、安全设置、会话；每页先等到其内容出现（不是加载或错误状态）再检查；任何违规即失败（列出规则与元素），不设排除项 |
| 对比度 | `@jabiz/ui` 的 `theme.css` 的语义 token：文字 token 在其所用的背景上 ≥ 4.5:1，输入框边框与焦点环 ≥ 3:1，亮、暗两种外观都如此（主色 `#0958d9` / `#69a7ff`、错误色 `#cf1322` / `#ff7875`、成功色 `#237804` / `#95de64`、警告色 `#874d00` / `#ffc53d`）；`theme.test.ts` 逐对检查。暗色控件的背景是半透明的输入色（输入框、文本框、选择框、标签输入为 `dark:bg-input/20`，选择框悬停 `/30`）：占位文字（暗色 `--muted-foreground` `#a8a8b0`，16v-1 由 `#a1a1aa` 调亮）在它混合到 background、card、popover 之上的颜色上，静止与悬停都 ≥ 4.5:1，`theme.test.ts` 按组件源码中的透明度计算（原来的 `#a1a1aa` 与 `/30`、`/50` 不通过） |
| 名称与属性 | 有展开行的表格（审计、单据）用 `DataTable` 的 `renderExpanded`：展开按钮带"展开行 / 收起行"的名称与 `aria-expanded`；选择用 `Combobox`（`role="combobox"`，以 `<Label htmlFor>` 或 `aria-label` 命名，`aria-required` / `aria-invalid` 在触发按钮上）。e2e 按角色、名称或 `data-*` 找元素（`e2e/support.ts` 的 `dialog`、`dataRows`、`option`、`confirm`），按钮名称写精确的文字 |
| 扩展与应用 | 扩展的页面用 `@jabiz/ui`（经 `@jabiz/admin`），主题 token、外观与全局的 preflight 自动适用；扩展的测试经 `@jabiz/admin/testing` 用同一个 `expectAccessible`（16w-1，§9；不另做 Playwright 的版本）。检查的辅助函数由 `@jabiz/ui` 导出（16v-1）：组件与页面测试用 `@jabiz/ui/testing` 的 `expectAccessible(root?)`（Vitest，亮暗两种外观，jsdom 不算样式所以不查颜色对比度），e2e 用 `@jabiz/ui/testing/playwright` 的 `expectAccessible(page, 名称, { expect })`（含颜色对比度，等动画结束后检查；`expect` 是调用方自己的，辅助函数不加载 `@playwright/test`）、`accessibilityViolations(page)`、`animationsSettled(page)` 与 `setAppearance(page, 外观)`；规则集（`WCAG_TAGS`）与上表相同，平台自己的测试也用它们；`@jabiz/client/vite` 的预设在 Vitest 中让 `vitest`、`vitest-axe` 都是 SPA 自己的副本 |

新增或修改页面时：在 `a11y.spec.ts` 中加上该页面；颜色用主题 token，不写死色值。

## 12. 前端包、外观与迁移【D34，阶段 15】

- **两个包**（源码形式，`exports` 指向 `src/index.ts`，不发布）：`@jabiz/client`（登录与令牌刷新、闲置锁定、step-up、API 客户端、
  `runQuery` / `runProcess`、i18n 初始化（各部分以 `addMessages(命名空间, …)` 加入文案）、金额 / 日期 / 数字格式化（`setAmountUnit(币种, 标签)` 让应用的账本币种显示为标签，如 JPY 显示为 "Kudos"；无币种的数字与其他币种不变）、`UserName`）；
  `@jabiz/ui`（shadcn 组件与组合组件 `DataTable`、`ConfirmDialog`、`notify`、`DatePicker` / `DateTimePicker`、`DecimalInput` / `MoneyInput`、
  `ThemeToggle`、`PageHeader`、`AppShell` / `ShellNav`（16zi 起 `ShellNav` 另可按 `groups` 分节：每节可有标题（带标题的节是有名称的 `group`），节与节之间一条分割线，空节不显示），15b-1 起另有 `Combobox`、`FilterBar`、`FileUpload`、`Timeline`、`DescriptionList`、`TagsInput`、
  `CopyButton`、`PageState`、`Spinner`，日期控件可键入并可限定范围；15c-1 起另有 `Steps`、`SegmentedControl`（shadcn 的 toggle-group 之上的单选组）、
  `FileUpload` 的拖放区（`variant="dropzone"`）、`PageState` 的空状态（`kind` 即 `data-state`）、`DescriptionList` 的多列与复制按钮；16n 起另有内容显示的 `MarkdownView`（`react-markdown`，`skipHtml`、不启用 `rehype-raw`：原始 HTML 一律不渲染，D20；样式为 `markdown.css`）与 `FilePreview`（以会话取文件内容（`@jabiz/client` 的 `fetchFileContent` / `fetchFileDownload`）：图片为缩略图、点击在 `Dialog` 中放大，其余为下载按钮，`blob:` 卸载时释放；`field` 只需 `image` 与 `variants`），以及其下的 `useFileUrl` / `downloadFile`，原在后台 `src/components`，`@jabiz/admin` 照旧以同名、同属性再导出；表单状态 react-hook-form 由该包再导出；文案命名空间 `ui`）。`@jabiz/admin` 在两者之上导出（第 9 节）。平台前端、两个包与扩展的 lint 都不允许 antd 与 `@ant-design/*`
  （同一条规则 `NO_ANT_DESIGN`）。shadcn 组件只在 `packages/ui` 中生成或修改。
- **外观**：`theme.css` 的语义 token（亮色在 `:root`，暗色在 `.dark`，挂在 `<html>`），对比度由 `theme.test.ts` 按 WCAG 2.2 AA 检查；
  亮 / 暗 / 跟随系统存于 `localStorage`（`jabiz.appearance`，不可用时跟随系统）。组件只用工具类与 token。
- **preflight 与外框**：`theme.css` 把 Tailwind 的 preflight 全局引入层 `base`（层顺序 `theme, base, components, utilities`，`src/index.css`），
  `body` 的背景与文字取 token，原生控件与滚动条随 `.dark` 变暗；应用自有 SPA 经同一 `theme.css` 也是全局 preflight。后台外壳的内容区是 `text-sm`（14px，
  与 antd 时期相同；登录、邮件链接页、门户与应用 SPA 仍是浏览器缺省的 16px）。渲染出的 Markdown（`.markdown-view`）的标题、列表、链接、代码与引用
  由 `@jabiz/ui` 的 `markdown.css`（层 `components`，只用 token）给出样式，不用 `@tailwindcss/typography`。侧栏收起时整体移出屏幕并设为 `inert`
  （不收成图标条：服务端菜单没有图标、分组在图标条中无法展开）；窄屏上侧栏是抽屉，点链接后关闭。
- **迁移的历史**（15a–15d）：迁移期间 antd 页面与新组件共存，preflight 只作用于 `.jabiz-ui`（`UI_SCOPE`）之内，antd 的样式在 CSS 层 `antd` 中，
  antd 页面所在的内容区固定为亮色。15d 删除 antd、ProComponents、icons 与 React 19 补丁，preflight 改为全局，去掉层 `antd`、亮色固定与路由标记；
  `UI_SCOPE` 仍导出（D22 的约定），只是一个没有样式的类名，不再需要。
- **登录与账户的积木**（D43，16c-1）：`@jabiz/ui` 的 `src/auth/`，文案在命名空间 `auth`（三语，应用可用 `addMessages('auth', …)` 覆盖个别键），
  亮、暗两种外观都过 axe。`AuthFrame`（品牌与标题由属性给出，带语言与外观切换）、`SignInForm`（密码、二次验证码、
  绑定三步，身份提供方按钮，"忘记密码 / 注册"链接；403 `EMAIL_NOT_VERIFIED` 提示并可重发验证邮件，`SIGN_IN_REFUSED` 只给通用文案）、
  `SignUpForm` + `SignUpSent`（邮箱、两次密码、显示名、邮件语言，应用的附加字段经 `extra` 插槽；202 后一律提示查收邮件并可重发）、
  `MailLinkPage`（按路径末段分派到 `VerifyEmailPanel`、`JoinPanel`、`PasswordResetConfirm`，无令牌的 `password-reset` 为 `PasswordResetRequest`）、
  `MfaCodeStep`、`MfaEnrollment`、`MfaSettings`（本人的两步验证：状态与剩余恢复码数，未绑定时绑定；默认调用 `/api/auth/mfa*`，可经属性替换；`refreshKey` 改变即重读；15c-2，后台安全设置与示范门户共用）、`StepUpProvider`、
  `OidcButtons`（按提供方的 `kind` 画标志，Google 为品牌指南的四色"G"（D44），16f-1）、`OidcCallback`（登录与本人关联共用：按
  `startIdentityLink` 记下的用途完成关联后调用 `onLinked`；403 `OIDC_ACCOUNT_EXISTS` 时说明用原方式登录后关联）、`LinkedIdentities`（本人的外部账号：列表、关联（需要时先问当前密码）、
  确认后解除；10 §12，16f-1）、`ChangePasswordForm`（`POST /api/auth/account/password`，16m）、
  `SessionList`（本人当前入口的有效会话：设备、地址、最近使用与登录时间，本机标记；确认后结束单个会话（本机即退出登录）或"退出其他全部设备"，
  提示按 `effectiveWithinSeconds` 给出生效时间；16g，10 §15.9；另导出 `SessionDeviceIcon`、`sessionDeviceName`、`effectiveMinutes` 供应用自绘列表）、
  `MailPreferences`（本人的邮件：当前入口的每个可退订通知一个开关（`Switch` 加 `Label`，名称为服务端给出的显示名），其后"您关闭的其他邮件"（其他入口、用户关闭过的，可重新开启），邮件语言的选择（应用语言多于一种时，含"默认"一项）；每次改动即时保存并以状态提示说明，失败时保持原值；读取失败时有"重试"；界面语言变化时重读显示名；别处设置的邮件语言经 `onMailLanguageChange` 立即显示；默认调用下面的函数，可经属性替换；18 §5.6，16s，D49）、
  `offerMailLanguage(lang)`（切换界面语言后询问是否把邮件也改为该语言，见 §10；提示一直显示到回答，说明以后在哪里修改；`notify` 为此增加可选的 `action` 与 `cancel` 按钮）、
  `MailUnsubscribe`（退订链接的落地页：读出令牌，只 POST 一次 `/api/auth/mail/unsubscribe`，状态 `working` / `done` / `invalid` / `failed`（`data-testid="unsubscribe-state"` 的 `data-state`）；`brand`、`appearance`、`search` 同其他落地页；通用后台的 `/mail/unsubscribe` 与示范门户的 `/portal/mail/unsubscribe` 都是它；18 §5.6，16v-1，D52 第 3 条）、
  `VerifyEmailBanner`、`OfflineBanner`。积木**不依赖路由**：导航经属性 `onNavigate(path)` / `hrefOf(path)` 与回调（`onSignedIn(path)` 等）交出，
  没有时为普通链接与整页跳转；邮件链接的入口与令牌从 `window.location` 读（有路由时传 `search`）。后台的登录页、OIDC 回调页、三个落地页与 step-up
  是这些积木外的 React Router 适配（`src/auth/routerNavigation.ts`），测试 id 与行为不变，后台不显示外观切换。
- **`@jabiz/client` 的无界面函数**（D43，16c-1）：`configureClient({ entry })`、`entryInfo()`、`register()`、`requestPasswordReset()`、
  `confirmPasswordReset()`、`resendVerification()`、`changePassword()`、`listSessions()`、`revokeSession(id, current)`（结束本机会话时清空本地令牌）、`revokeOtherSessions()`、`startIdentityLink(provider, {currentPassword?}, returnTo)`、`finishIdentityLink()`、`listIdentities()`、`unlinkIdentity(id)`（16f-1）、`getMailPreferences()` / `setMailPreference(template, subscribed)`、`getMailLanguage()` / `setMailLanguage(locale | null)`、`onMailLanguageChange(listener)`、`mailLanguageOffer(lang)`（16s，D49）、`readMailLink()` / `useMailLink()`（读出入口与令牌，以 `replaceState` 去掉令牌，
  页面打开期间 `<meta name="referrer" content="no-referrer">`）、`isProblem(error, code)`、`useOnline()`；`createApiClient<Paths>()` 以应用自己快照生成的类型
  调用应用的接口，与 `api` 共用令牌、刷新与 step-up（流程与模板仍经 `runProcess` / `runQuery`）。
- **应用自有 SPA**（D34 第 5 条，D43）：以 `link:` 引用两个包，样式表 `@import '@jabiz/ui/theme.css'`（它 `@source` 了该包的源码）；
  `pnpm app:check <目录>` 比对 `react`、`react-dom`、`tailwindcss`、`@tanstack/react-query`、`i18next`、`react-i18next`、`vite`（以及使用两个包时其运行时依赖，
  逐项读自包的 `package.json`，16n 起含 `react-markdown`）的版本与平台前端一致，不一致即失败；16v-1 起 SPA 有 `@jabiz/ui` 的可选 peer `vitest`、`vitest-axe`、`@playwright/test`、`@axe-core/playwright`（测试辅助函数 `@jabiz/ui/testing`、`@jabiz/ui/testing/playwright` 所用）之一时，也须是平台的版本。应用 SPA 不引用 `@jabiz/admin`，后台与 SPA 共用的显示积木都在 `@jabiz/ui`。写法见指南 `docs/guide/app-spa.md`。
- **构建预设 `@jabiz/client/vite`**（16c-2，只在 Node 中运行，纯 JavaScript `vite.mjs` 加类型 `vite.d.mts`，任何 Node 版本都能从应用的 `vite.config.ts` 加载）：
  `jabizSpa({ pwa })` 取并校验构建变量（`VITE_BASE`、`VITE_JABIZ_ENTRY`、`VITE_JABIZ_PWA`、`VITE_JABIZ_SPA_PREFIXES`），设 `base`，以 `resolve.dedupe`
  统一 React、i18next、TanStack Query 的副本（Vitest 中另把平台前端的依赖交给 Vite、取其 ES 模块版本并指向 SPA 自己的副本：否则经 `link:` 的包会带来第二个 React）；
  `pwa = ON` 时生成 `manifest.webmanifest`（`id`、`start_url`、`scope` 为前缀，`display: standalone`；图标必须是 `public/` 中尺寸相符的 PNG，且有 192×192、512×512
  与 maskable 的，否则构建失败）、在页面中链接它，并以 Vite 自己的打包器（Vite 8 不再带 esbuild）把 `packages/client/src/pwa/sw.ts` 编译为经典脚本 `sw.js`，
  注入预缓存清单、缓存名与要放过的前缀；`REMOVE` 只生成注销自己的 `sw.js`。路由判断是纯函数 `swRoute`（`src/pwa/route.ts`），Service Worker 的逻辑在
  `src/pwa/worker.ts`，都有单元测试。
- **PWA 的注册与更新**：`registerServiceWorker()`（`@jabiz/client`，按构建的 `VITE_JABIZ_PWA`；以前缀为作用域、`updateViaCache: 'none'`）；新版本安装后等待，
  `useServiceWorkerUpdate()` 给出应用它的函数，`@jabiz/ui` 的 `PwaUpdatePrompt` 据此提示"有新版本"与"刷新"按钮：用户同意后才 `SKIP_WAITING`，
  控制者切换后重新加载，从不自动重新加载；首次安装不提示。`REMOVE` 时页面一侧也注销本 SPA 的注册并删除自己的缓存。
- **安装到主屏幕**（16zj）：浏览器不允许网页自己把图标放到主屏幕，只能由用户添加。`@jabiz/client` 的 `useInstallPrompt()` 给出这个浏览器的安装方式
  （纯函数 `installSituation`）：`prompt`（Chrome、Edge、Samsung 的 `beforeinstallprompt`：`registerServiceWorker` 一开始就以 `captureInstallPrompt()`
  接住并 `preventDefault`，不显示浏览器自己的横条；`install()` 弹出浏览器的确认框，只能用一次）、`ios`（iPhone、iPad 的 Safari 及 iOS 上的其他浏览器：
  只能从"分享"菜单手动添加；iPadOS 自称 Mac，以触屏区分）、`menu`（Android 的 Firefox：浏览器菜单）、`in-app`（LINE、微信、Instagram、Facebook 等 App 内置浏览器：
  不能安装，请在浏览器中打开）、`installed`（`display-mode: standalone` 或 Safari 的 `navigator.standalone`）、`none`（电脑，或浏览器没有提供）。
  `@jabiz/ui` 的 `PwaInstallBanner`（`appName`）据此显示不打断操作的提示与 iOS 的分步指引 `PwaInstallGuide`（"分享"按钮在新版 Safari 中可能在"…"里；
  主屏幕上的 App 与 Safari 的存储分开，须再登录一次）；用户关掉后 30 天内不再出现、关掉 3 次后不再出现（`snoozeDays`、`maxDismissals`，记在本浏览器的
  `localStorage`，按 SPA 前缀分开，存储不可用时下次照常出现）；`dismissible={false}` 用于作为安装入口的页面（账户页）：没有关闭按钮，已安装时说明已安装。
  何时显示由应用决定（不要放在首次访问或登录页：多半被直接关掉）。
