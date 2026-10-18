# Q8 AI：生成题目与封面、人工校对、用量（计划，2026-10-18 确认）

依据：`03-plan.md`（Q8：M-25 – M-26、M-10（用量），"等 16e"）、需求原文 4.2、4.3（M-20 "是否 AI 生成"、M-25、M-26 "必须人工校对后才加入题库"）、
`01-requirements-analysis.md` 第 0 节（Q4："调用 OpenAI 接口，模型以后配置；不单独计费，只记录用量"）与 2.2、`02-design.md` 3.2（`QbAiJob`）、5.2（`QB_AI_REQUEST` / `QB_AI_ACCEPT`）、8（外部系统）、
平台决策 D38（外部服务与异步外部作业，16e-1 / 16e-2 已合入）。基于 `origin/1.2/quizbuks-7-plan` @ c4a5cf7。只改 `.jabiz-app-paths` 中的路径；
平台需要的一项（16t）先在 `1.2/platform` 上做（根 CLAUDE.md 第 8 节）。

> **本计划建议把提供方由 OpenAI 改为 Claude（Anthropic API）**（A-1）。这改变 2026-10-10 确认的 Q4，须需求方确认；确认前第 5 – 7 节中的"Claude"读作"所选的提供方"，
> 结构（外部服务、作业、校对、限额）不随提供方改变。

## 1. 目标与范围

| 需求 | 优先级 | 内容 | Q8 的做法 | 子阶段 |
|---|---|---|---|---|
| M-26 AI 生成题目 | P1 | 选择参考资料、题数、难度（易 / 中 / 难）后生成；必须人工校对后才加入题库 | 异步外部作业生成**候选题目**（JSON），商家在编辑器中逐题校对、修改、勾选"已校对"后才由 `QB_AI_ACCEPT` 写入题目；候选 7 天后过期 | Q8a（文字资料）；Q8b 加 PDF 资料 |
| M-25 AI 生成封面 | P1 | 根据标题和简介生成候选图，商家选一张应用 | 生成 3 张候选封面（做法见 A-4），商家选一张，经现有的模版编辑写入 `cover` | Q8b（需平台 16t） |
| M-10 AI 用量（Token） | P1 | 仪表盘显示 AI 用量 | 按商家、按月累计输入与输出 Token 与次数；仪表盘卡片（Q9e 的仪表盘）显示本月用量与剩余额度 | Q8a |
| M-20 是否 AI 生成 | P0 | 模版列表显示 | 接受过 AI 候选（题目或封面）的模版 `aiGenerated = true`（Q3 已留字段，恒为 false）；题目另记来源作业 | Q8a |
| （补充）成本与滥用的控制 | — | 需求未写 | 每商家每日次数、每月 Token 上限、同时进行的作业数、全局月上限与总开关；只对已入驻、已验证、未封禁的商家开放 | Q8a |
| （补充）管理员 | — | — | 后台扩展 `/qb/ai`：按商家与月份的用量、失败率、开关与上限（链到业务参数） | Q8a |

**不做**：AI 计费（Q19：只记用量）；答题人侧的任何 AI；AI 校对商家自己写的题目、AI 辅助审核发布（A-3 的选项 B、C，以后）；抓取链接与视频的内容；
音频转写；图片资料的识别；流式显示生成过程（D38 第 6 条不做）。

## 2. 现状（`origin/1.2/quizbuks-7-plan`）

- **平台 16e 已合入**（D38）：`ExternalService` Bean（基础地址、主机白名单、密钥环境变量、超时、响应上限 ≤ 16 MiB）、`ExternalHttp`（只有平台的 JDK HTTP；
  "官方 SDK 自带的出站不受主机白名单约束，本阶段不开放"）、`ExternalJobType`（处理器、超时 ≤ 1 小时、重试与退避、全实例并发、每请求人未结束的作业数、同一引用只一个）、
  `StartExternalJob`（输入 ≤ 64 KiB、不得含 `@Sensitive`）、完成与失败流程（`internal()`）、用量按尝试记录（`ExternalJobResult.usage(...)`，报表 `jabiz.external_job_usage` 按类型与月份）、
  取消与状态接口、场景步骤 `runExternalJobs` / `completeExternalJob`。处理器**不访问数据库**（ArchUnit `handlersStayOutOfTheDatabase`），只能调用类型声明的服务。
- 平台示范：`backend/app` 的 `demo-ai`（`ModerateText` 短调用、`ProductCopyHandler` 异步作业，`POST /v1/generate` 的桩，`JABIZ_DEMO_AI_KEY`）。QuizBuks 的 Stripe 是同样的写法（`StripeApi` 自写表单与解析）。
- **QuizBuks 中已有的 AI 痕迹**：权限 `qb.ai.use`（已授予 `QB_SPONSOR`）、参数 `qb.ai.model`（说明为 "OpenAI model"）、`QbQuiz.aiGenerated`（恒为 false）、
  `backend/quizbuks/CLAUDE.md` 与部署说明中的 `OPENAI_API_KEY`。**没有**实体、流程、外部服务与页面。
- **与 D38 不一致的设计文字**：`02-design.md` 第 8 节写"OpenAI … 官方 Java SDK"，而 16e-1 只开放 JDK HTTP。本计划按 D38 自写客户端（同 `StripeApi`），并改设计文字。
- **资料的大小**：每个模版至多 10 条资料，文章正文每条至多 20 000 字符（`ContentLimits.MATERIAL_BODY_LENGTH`）；中日文约 3 字节 / 字，10 条可达约 600 KB，超过作业输入的 64 KiB。
  PDF 资料是文件（`qb.content.pdf`），处理器不能读文件。生成的封面是二进制图片，作业结果至多 256 KiB 的 JSON，且服务器生成的图片没有进入 `sys_file` 的途径（上传只经 `/api/files?policy=…`）。
- **迁移**：Q7b V12、Q7a V13、Q7c 与 Q9e1 的计划都写 V14；本计划的 Q8a 写作 **V15**，号码按合并顺序取下一个。平台的下一个决策是 **D50**，下一个平台阶段是 **16t**。

## 3. 平台缺口（`1.2/platform`，16t，决策 D50）

| # | 缺口 | 影响 | 处理 |
|---|---|---|---|
| P1 | 作业输入固定上限 64 KiB | 多条长文章不能一次发送 | **16t**：`ExternalJobType.maxInputSize`（缺省 64 KiB，上限 1 MiB；输入仍原样存于只追加的 `sys_external_job`） |
| P2 | 处理器不能读文件 | PDF 资料（M-23 的六类之一）不能作为生成的依据 | **16t**：作业的**文件输入**——`StartExternalJob.of(...).files(ctx -> 文件编号)`：登记时检查每个文件存在、请求人对其策略有读取权限（或是上传者）、大小在类型声明的上限内；执行器在事务之外读出字节交给处理器（`ExternalJobContext.file(编号)`），处理器仍不访问数据库 |
| P3 | 作业结果不能带文件；服务器生成的图片不能进入 `sys_file` | M-25 的候选封面 | **16t**：作业的**文件输出**——`ExternalJobResult.of(值).file(键, 字节, 类型)`，类型声明输出的文件策略、个数与大小上限；执行器按该策略走与上传相同的处理（按内容判定类型、图片重新编码去元数据），以请求人为上传者 `FILE_REGISTER`，完成流程得到 `键 → 文件编号`；未被引用的候选由现有的 `FILE_PURGE_ORPHANS` 清扫 |
| P4 | （观察）`sys_external_job` 只追加，作业输入（资料原文）永久保存 | 商家内容多存一份 | 不做：内容本来就永久保存在模版版本中（`QbQuizVersion` 只写一次）；在 16t 的文档中写明"作业输入不放个人数据" |

- **为什么要新决策 D50**：P3 是根 CLAUDE.md "上传只经 `/api/files?policy=…`"（D18）之外的第二个入口，须写明它同样按策略判定类型、重新编码、记上传者；P1、P2 改变 D38 实现说明中的固定上限与"处理器只调用服务"的范围。
- 分支 `1.2/phase-16t-external-job-files`，3–4 天：P1 0.25；P2 1–1.25；P3 1.25–1.5；示范（`backend/app`：商品文案作业读商品的 PDF 说明书、商品图片的变体作业）、
  runtime IT（权限、大小、重新编码、孤儿清扫、失败与取消不留文件、迟到的尝试丢弃其文件）、文档 06 / 11 / 14 与 09（D50）0.75–1。
- **Q8a 不依赖 16t**（只发送文字资料，输入在 64 KiB 内，超出即请商家少选资料，A-5）；Q8b（封面、PDF、更长的输入）依赖 16t 合入并合并到 `1.2/quizbuks`。

## 4. 拆分、分支与顺序

| 子阶段 | 分支 | 内容 | 迁移 | 预估 | 依赖 |
|---|---|---|---|---|---|
| 平台 16t | `1.2/phase-16t-external-job-files` | P1 – P3，D50 | 平台无业务表；`sys_external_job` 加文件输入 / 输出的两张只追加表 | 3–4 天 | 16e（已合入） |
| **Q8a** 生成题目与用量 | `1.2/quizbuks-8a-ai-questions` | 外部服务 `anthropic`、`ClaudeApi`、作业 `qb.ai.questions`、候选与校对、接受、限额与用量、同意、编辑器的 AI 面板、仪表盘卡片、后台扩展 `/qb/ai` | V15 | 3.5–4 天 | Q9e2（仪表盘卡片；也可先放在模版页） 、Q7b（同意的版本，A-11）|
| **Q8b** 封面与 PDF 资料 | `1.2/quizbuks-8b-ai-cover` | 作业 `qb.ai.cover`（A-4）、封面候选与选择、PDF 资料作为文件输入、输入上限提到 256 KiB | 可能无（`QbAiJob.kind` 已有 `COVER`） | 2–2.5 天 | 16t |

合计应用 5.5–6.5 天（`03-plan.md` 原估 3–4 天：原估未含限额与同意、候选的逐题校对、后台扩展页与 e2e），平台 3–4 天。
建议顺序：Q9e → Q8a（与 16t 并行）→ Q8b。

## 5. Q8a 生成题目、校对与用量

### D-Q8-1 提供方与客户端（A-1、A-2、A-9）

- 外部服务 `anthropic`（`ExternalService`）：`https://api.anthropic.com`、主机白名单 `api.anthropic.com`（可按环境覆盖 `jabiz.external-services.anthropic.*`）、
  密钥 `ANTHROPIC_API_KEY`（只来自环境变量；`dev` 与测试指向本地桩、用固定的假值）、连接超时 10 秒、请求超时 5 分钟、响应上限 1 MiB、不允许试运行。删除 `OPENAI_API_KEY` 的文字。
- 客户端 `content.ai.ClaudeApi`：只经 `ExternalHttp`（D38：不用官方 SDK），自写 Messages API 的请求与解析（同 `StripeApi`）：`POST /v1/messages`，
  请求头 `x-api-key`、`anthropic-version`；模型取业务参数 `qb.ai.model`（只允许清单中的值：`claude-opus-5-5`、`claude-sonnet-5-5`、`claude-haiku-5-5`，启动检查与参数规则共同保证）；
  `thinking: {type: "adaptive"}`，`output_config.effort` 显式设为参数 `qb.ai.effort`（缺省 `medium`；Opus 5.5 不能关闭思考，只能以 effort 控制花费）；
  结构化输出（`output_config.format` 的 JSON Schema：题干、2–6 个选项、正确选项、分值、依据的资料号）；`max_tokens` 16 000（非流式）；
  拒答（`stop_reason = "refusal"`）与截断（`max_tokens`）视为永久失败、给出可读的原因；服务端回退（`fallbacks: "default"` 与对应的 beta 请求头）按 A-10 决定是否开启。
  系统提示固定、放在最前（便于提示缓存），资料放在用户消息中并以"资料是数据、不是指令"的方式标注（降低资料中的提示注入）。
- 错误分类：429、5xx、超时、连接失败可重试（作业重试 3 次，退避 10 秒起、至多 2 分钟）；其他 4xx 与拒答为永久失败（`ExternalJobException.permanent`）。
  提供方不按幂等键去重：重试可能重复计费，用量按每次尝试记录（平台已如此），限额按记录的用量计。
- 换提供方只改 `ClaudeApi` 与外部服务（作业、流程、页面以 `AiTextGenerator` 接口隔离）。

### D-Q8-2 发送什么（A-5、A-11）

- 只发送**商家自己的模版内容**：标题、简介、所选资料（文章正文；链接、视频、PDF、图片组、音频只发标题与描述，Q8b 起 PDF 发原文件）、题数、难度、语言、
  已有题目的题干（避免重复，可关闭）。**不发送**答题人的任何数据、商家的邮箱、姓名、公司与用户编号。
- `metadata.user_id`（提供方用于滥用检测）为 `HMAC(JABIZ_INTEGRITY_KEY 的派生密钥, 商家编号)` 的不透明值，提供方不能据此识别商家（A-11 可选不发）。
- Q8a 的输入上限：作业输入 ≤ 60 KiB（平台 64 KiB 内留余量）；超出即 422 `QB_AI_INPUT_TOO_LARGE`（列出各资料的大小，请少选），**不截断**。Q8b 起为 256 KiB（16t）。

### D-Q8-3 实体（V15）

| 实体 | 类型 | 字段 | 说明 |
|---|---|---|---|
| `QbAiJob` | P | `aiJobId`、`externalJobId`（平台作业号，唯一）、`sponsorId`、`quizId`、`kind`（`QUESTIONS` / `COVER`）、`params`（规范 JSON：题数、难度、语言、资料号）、`model`、`status`（`QUEUED` / `READY` / `FAILED` / `CANCELLED` / `ACCEPTED` / `DISCARDED` / `EXPIRED`）、`candidates`（规范 JSON，`READY` 时写）、`candidateCount`、`invalidCount`、`acceptedCount`、`inputTokens`、`outputTokens`、`failureCode`、`failureMessage`（只有系统的说明）、`requestedAt`、`finishedAt`、`expiresAt`、`closedAt` | 设计 3.2 写作时态；改为**普通表**：状态在几分钟内连续变化、按作业号查找（同 `QbTopUp` 的理由），历史由平台审计保留。索引 `(sponsor_id, requested_at DESC)`、`(quiz_id, requested_at DESC)` |
| `QbAiUsage` | P | `sponsorId`（全局一行为 `*`）、`month`（部署时区的年月）、`jobs`、`inputTokens`、`outputTokens`、`failedJobs` | 唯一 `(sponsor_id, month)`；只在 `qb.ai:<商家>` 锁内（全局行在 `qb.ai:*` 锁内）由完成与失败流程累加 |
| `QbQuestion` 加 `aiJobId`（可空） | T（列） | — | 来自哪个 AI 作业；审核页（Q9a）据此标"AI 生成"。时态表加列（`ALTER TABLE … ADD COLUMN`，不违反只追加） |

### D-Q8-4 流程

| 流程 | 权限 | 说明 |
|---|---|---|
| `QB_AI_QUESTIONS_REQUEST` | `qb.ai.use`，要求已验证 | 锁外经商家视图读模版（不是自己的或已移除即 404）与所选资料；检查入驻已通过、未封禁、已同意 AI 说明（A-11）、总开关 `qb.ai.enabled`；取 `qb.ai:<商家>`，锁内检查限额（A-7：未结束的作业、今日次数、本月 Token + 未结束作业的预估）；`StartExternalJob`（引用 = 模版，同一模版同时只一个）；插入 `QbAiJob`。输出作业号 |
| `QB_AI_JOB_COMPLETED` / `QB_AI_JOB_FAILED` | 系统（`internal()`，`qb.ai.system` 不授予任何角色） | 取 `qb.ai:<商家>` → `qb.ai:*`：累加用量（商家与全局）；完成：逐个候选按内容规则校验（与 `QuizCompleteness`、`ContentLimits` 相同：题干与选项长度、2–6 个选项、至少 1 个正确、分值 1–1 000），不合格的丢弃并计数；`READY` 与 `expiresAt`；失败：`FAILED` 与原因 |
| `QB_AI_ACCEPT` | `qb.ai.use`，要求已验证 | 取 `qb.quiz:<模版>` → `qb.ai:<商家>`；作业须为本人、`READY`、未过期；输入为商家**校对后**的题目（可改写任何字段）与每题 `reviewed = true`（缺即 400）；按现有的题目流程规则写入（序号接在最后、题数上限）、`aiJobId`；`QbQuiz.aiGenerated = true`、`changedSinceVersion = true`；作业 `ACCEPTED`（可只接受一部分）。不改任何已保存的版本 |
| `QB_AI_DISCARD` / `QB_AI_CANCEL` | `qb.ai.use` | 丢弃候选（清空 `candidates`）；取消排队或执行中的作业（经平台 `EXTERNAL_JOB_CANCEL`，执行中的在完成后丢弃，用量照记） |
| 任务 `qb.ai-expire` | 系统 | 每日：`READY` 且过期的作业改为 `EXPIRED` 并清空 `candidates`（逐项任务，每项取 `qb.ai:<商家>`） |

- **锁序**：`qb.quiz:<模版>` → `qb.ai:<商家>` → `qb.ai:*`；请求与完成只取后两者；与发布、开始、钱包的锁不相交（没有流程同时持有它们）。答题路径不受影响，`AttemptStatementBudgetIT` 不变。
- 作业类型 `qb.ai.questions`：超时 5 分钟、3 次尝试、全实例并发 `quizbuks.ai.max-concurrency`（缺省 4）、每请求人未结束 ≤ 2、同一模版只一个。
- 限流（`jabiz.process.rate-limits`）：请求每人每分钟 5 次、接受每人每分钟 30 次。

### D-Q8-5 页面

- **商家后台的编辑器**（`content/QuizEditorPage.tsx` 的题目面板旁）："AI 生成题目"对话框：选资料（显示各自大小与是否会发送）、题数（1–20）、难度、语言（缺省为界面语言，可改）、
  本月剩余额度；第一次使用时显示 AI 说明与同意（A-11）；提交后显示进行中（每 5 秒查询本人的作业模板，至多 5 分钟；离开页面即停）。
- **候选校对面板**：每个候选一张可编辑的卡片（题干、选项、正确选项、分值、依据的资料号），"我已校对"复选框；只有勾选的才能"加入题库"；丢弃全部；
  失败与过期的说明（"AI 暂时不可用，请稍后再试或手动编辑"）。候选只以纯文本显示（不渲染 Markdown）。
- **模版列表**：`aiGenerated` 的"AI"徽标（M-20）；审核页（后台扩展 Q9a）对 `aiJobId` 非空的题目标"AI 生成"。
- **仪表盘**（Q9e2 的卡片位）：本月 Token（输入 / 输出）、次数、剩余额度。
- **后台扩展** `/qb/ai`（`qb.admin.ai.read`，超级管理员与内容管理员）：按月、按商家的用量与失败数，按价格参数估算的美元金额（只作参考），开关与上限的当前值（修改链到平台业务参数）。
- 只用 `@jabiz/ui`；颜色只用主题 token；亮暗两种外观过 axe。

### D-Q8-6 模板

`qb.sponsor.ai-jobs`（本人某模版的作业与候选，`qb.ai.use`）、`qb.sponsor.ai-usage`（本月用量与额度，经 `now:SysParam` 读参数，`qb.ai.use`）、
`qb.admin.ai-usage`（`qb.admin.ai.read`）、`qb.system.ai-expired`（任务选取）。

### Q8a 的参数与权限

- 参数（`QbParams`，`QB_SETUP` 补建；都不是账本金额，普通参数，超级管理员可改）：`qb.ai.enabled`（缺省 false：部署时有密钥再开）、`qb.ai.model`（`claude-opus-5-5`）、`qb.ai.effort`（`medium`）、
  `qb.ai.sponsor.daily-jobs`（20）、`qb.ai.sponsor.monthly-tokens`（2 000 000）、`qb.ai.global.monthly-tokens`（50 000 000）、`qb.ai.candidates.expire-days`（7）、
  `qb.ai.price.input-per-mtok-usd` / `output-per-mtok-usd`（只用于后台的估算）。
- 新权限：`qb.ai.system`（不授予）、`qb.admin.ai.read`（`QB_ADMIN_SUPER`、`QB_ADMIN_CONTENT`）；`qb.ai.use` 照旧。

### Q8a 的测试

- **单元**：`ClaudeRequestTest`（请求体：模型清单、effort、结构化输出的 Schema、资料以数据标注、`metadata.user_id` 不可逆）、`ClaudeResponseTest`（正常、拒答、截断、畸形 JSON、用量解析）、
  `CandidateRulesTest`（与内容规则同一组用例）、`AiLimitsTest`（每日、每月、未结束作业的预估、全局上限）。
- **IT** `AiQuestionsIT`（本地桩代替提供方，`runExternalJobs`）：请求 → 作业 → 候选；限额的每一项（含两个请求并发时不超出：锁内计数）；总开关；未入驻、封禁、未验证、未同意 → 拒绝；
  别人的模版 404；输入过大 → 422 且不截断；429 后重试成功、400 永久失败、拒答的说明；取消；接受时校对标记缺失 → 400、改写后的内容才是写入的内容、写入后 `aiGenerated`、`aiJobId`；
  过期任务；用量的商家行与全局行；权限矩阵（答题人、管理员 403）；`QB_AI_QUESTIONS_REQUEST` 的 `input_summary` 不含资料原文。
- **ArchUnit**：`ClaudeApi` 只经 `ExternalHttp`（平台共用规则已覆盖，另加"处理器不访问数据库"）。
- **场景** `scenarios/qb/ai.yml`：请求 → `completeExternalJob`（带用量）→ 接受两题 → 保存版本。
- **Vitest**：对话框（资料大小、额度、同意）、轮询与停止、候选卡片的编辑与"已校对"、只接受勾选的、失败与过期的文案、徽标。
- **e2e** `ai.spec.ts`（提供方由 e2e 的本地桩回答，同 Stripe）：生成 → 校对一题 → 加入题库 → 列表的 AI 徽标；`a11y.spec.ts` 加对话框与候选面板（亮、暗）。

## 6. Q8b 封面与 PDF 资料（16t 之后）

- 作业 `qb.ai.cover`（A-4 的做法 A）：处理器请 Claude 给出 3 个封面设计（配色取自固定的几组、版式与图形取自固定的图库编号、标题文字 ≤ 40 字），
  在处理器中以 Java2D 渲染为 1200 × 630 的 PNG（字体用部署已配置的中日文字体，同 `jabiz.reports.pdf.fonts`），以 16t 的文件输出交给平台按 `qb.content.image` 策略登记。
  选 B 时改为调用图片模型的第二个外部服务（另一把密钥与一家数据处理方）。
- `QB_AI_COVER_ACCEPT`：取模版锁，经现有模版编辑把所选文件写入 `cover`，`aiGenerated = true`；其余候选文件不被引用，由孤儿清扫删除。
- PDF 资料：经 16t 的文件输入发送原文件（每个至多 `quizbuks.ai.pdf.max-bytes`（10 MB）与 100 页，多于即请少选）；输入上限提到 256 KiB。
- 页面：模版基本信息中的"AI 生成封面"（3 张候选、选一张）；生成题目对话框中 PDF 资料可选。
- 测试：`AiCoverIT`（渲染的图片经策略重新编码、只有本人能选、未选的被清扫、作业失败不留文件）、PDF 输入的权限与大小、Vitest、e2e。

## 7. 风险

- **成本**：Claude Opus 5.5 为每百万 Token 输入 4 美元、输出 20 美元；一次 10 题、约 2.5 万输入与 5 千输出（含思考）约 0.2 美元。缺省每商家每月 200 万 Token（约 65 次、约 13 美元），
  全局 5 000 万 Token（约 330 美元）为上限；Sonnet 5.5 约为一半，Haiku 5.5 约为 1/40（质量须以样本比较，A-2）。用量按尝试记录，重试与拒答也计入。
- **滥用**：只给已入驻通过、已验证、未封禁的商家；锁内计数的限额；输入大小有上限；每请求人未结束的作业 ≤ 2；入口限流；总开关。商家可能用 AI 生成不当内容——
  候选不会自动入库，入库后仍要经过发布审核（P-09，免审核开关打开时例外，见 Q10b 的下架规则）。
- **提示注入**：资料是商家自己的，注入只影响他自己的候选；输出按内容规则校验、以纯文本显示、必须人工校对，不会直接到答题人。
- **发送给提供方的数据**：只有商家自己的 Quiz 内容（D-Q8-2），不含答题人的数据；但商家可能在资料中写入个人信息。商家条款与 AI 说明须告知"内容会发送给第三方 AI 提供方处理"，
  提供方的数据使用与保留（是否用于训练、保留期、处理地域）按其商业条款，须法务确认（A-11）；作业输入在平台的 `sys_external_job` 中永久保存（P4）。
- **质量与正确性**：AI 可能给出错误的正确选项——这正是 M-26 要求人工校对的原因；校对面板显示"依据的资料号"便于核对。
- **提供方故障**：重试用尽后作业失败，手动编辑不受影响；长时间故障由管理员关闭开关；不自动切换到另一家提供方（A-10）。
- **设计变更**：提供方由 OpenAI 改为 Claude、客户端由 SDK 改为 JDK HTTP（D38），须同步 `01-requirements-analysis.md` 第 0 节、`02-design.md` 3.2 / 5.2 / 8、`backend/quizbuks/CLAUDE.md` 与部署说明。

## 8. 文档

`02-design.md` 3.2（`QbAiJob` 改普通表、`QbAiUsage`）、5.2（流程）、6（模板）、7.2（页面）、8（外部系统：Claude、`ANTHROPIC_API_KEY`、JDK HTTP）、11；
`01-requirements-analysis.md` 第 0 节（Q4 的变更与日期）；`backend/quizbuks/CLAUDE.md` 加"AI"一条（候选从不自动入库、只发送商家内容、限额在 `qb.ai:<商家>` 锁内、锁序、密钥）；
`deploy/quizbuks/README.md` 与 `operations.md`（密钥、开关、上限、故障处理）；`launch-checklist.md`（AI 说明已发布、密钥与开关）；`03-plan.md` 状态。

## 9. 已确认（2026-10-18）

需求方接受全部建议（A-1 – A-14）：A-1 改用 Claude（取代 Q4 的 OpenAI），A-2 缺省 `claude-opus-5-5`、上线前以 20 份真实测验比较 Opus 与 Sonnet 后由需求方决定，A-13 做平台 16t（D50）。
迁移编号（2026-10-18 统一）：Q7b V12、Q7a V13、Q9e1 V14、Q7c V15、Q8a V16（按此顺序合并；若顺序变化，后合并者顺延）。

以下为原问题与建议（即结论）：

| 编号 | 问题 | 选项 | 建议 |
|---|---|---|---|
| A-1 | AI 提供方（改变 Q4 "OpenAI"） | A Claude（Anthropic API）；B OpenAI（原决定）；C 两家都接、按参数切换（+1 天，两套测试） | **A**；客户端以接口隔离，以后换提供方只改一处 |
| A-2 | 模型 | `claude-opus-5-5`（4 / 20 美元每百万 Token，一次 10 题约 0.2 美元）；`claude-sonnet-5-5`（2 / 10，约 0.1 美元）；`claude-haiku-5-5`（0.10 / 0.50，约 0.005 美元）；模型是参数 `qb.ai.model`，只允许这三个 | **缺省 `claude-opus-5-5`、effort `medium`**；上线前以 20 个真实模版比较 Opus 与 Sonnet 的候选质量，需求方据此决定是否改为 Sonnet 以减半成本 |
| A-3 | AI 做什么 | A 只做需求中的 M-26 题目与 M-25 封面；B 再加"检查我的题目"（指出歧义、可能错误的正确选项，只给建议，+1 天）；C 再加发布审核的辅助（给管理员的风险提示，+1.5 天） | **A**；B、C 以后另立阶段 |
| A-4 | 封面怎样生成（Claude 不生成图片） | A Claude 设计 3 个封面（配色、版式、图形取自固定的图库、标题文字），由服务器渲染为 PNG：一家提供方、风格统一、没有写实图片的版权与肖像问题；B 另接图片模型（如 OpenAI 的图片接口）：插画式封面，多一把密钥、一家数据处理方与费用；C 不做 M-25 | **A**；若需求方要插画或照片风格再选 B |
| A-5 | 哪些资料发送给 AI | 文章正文全文；链接、视频、图片组、音频只发标题与描述（不抓取网页、不转写）；PDF 在 Q8b 发原文件（≤ 10 MB、≤ 100 页）；超出输入上限即请商家少选，不截断 | **如左** |
| A-6 | 人工校对怎样体现 | 候选从不自动入库；每题须改好并勾选"已校对"才能加入；候选 7 天过期并清除；接受后模版与题目标为 AI 生成，审核页可见 | **如左** |
| A-7 | 每个商家的成本上限 | 每日 20 次、每月 200 万 Token、同时未结束 ≤ 2、全局每月 5 000 万 Token、总开关；都是参数，超级管理员可改；达到上限即 422 并在页面显示何时恢复 | **如左**（数值请需求方按预算调整） |
| A-8 | 是否向商家收费 | A 不收费，只记用量（Q19 的缺省）；B 计入平台抽成或另收服务费（需要分录与发票，另立阶段） | **A** |
| A-9 | 密钥与环境 | `ANTHROPIC_API_KEY` 只来自环境变量；`dev`、测试与 e2e 用本地桩；缺省 `qb.ai.enabled = false`，部署有密钥后由管理员打开；删除 `OPENAI_API_KEY` | **如左** |
| A-10 | 提供方故障与拒答 | 重试 3 次（429、5xx、超时），之后失败、页面提示稍后再试或手动编辑；不切换到另一家提供方；拒答的回退：A 开启平台的服务端回退（`fallbacks: "default"`，由提供方按拒答类别改用其他模型，计费按实际使用的模型）；B 不开启，拒答即失败 | **A**（拒答时商家仍能得到结果；页面不显示用了哪个模型，后台显示） |
| A-11 | 发送的数据与同意 | 只发送商家自己的模版内容，不发送答题人的任何数据与商家的身份信息（`metadata.user_id` 为不可逆的 HMAC，或不发）；第一次使用时显示"AI 说明"并记录同意：A 用 Q7b 的法律文本版本（新种类"AI 利用说明"，文本由需求方或律师提供），B 只记一个复选框的时间；提供方的数据使用与保留由法务确认 | **A**（依赖 Q7b 合入）；`metadata.user_id` 发不可逆值 |
| A-12 | 生成的语言、题数与题型 | 语言缺省为商家界面语言（可选英 / 中 / 日）；每次 1–20 题；难度易 / 中 / 难；单选与多选都可（与评分规则一致）；2–6 个选项；分值缺省 10（可改） | **如左** |
| A-13 | 平台缺口 16t 的范围与决策 | A 16t 做文件输入、文件输出与按类型的输入上限，新决策 D50（服务器生成的文件按策略进入 `sys_file`）；B 只做输入上限，不做封面与 PDF（M-25 推迟）| **A** |
| A-14 | 子阶段与顺序 | Q9e → Q8a（与平台 16t 并行）→ Q8b | **如左** |
