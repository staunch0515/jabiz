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
迁移编号（2026-10-18 统一）：Q7b V12、Q7a V13、Q9e1 V14、Q7c V15、Q8a V16（按此顺序合并；若顺序变化，后合并者顺延）。实际 Q8a 先于 Q7c 合入，Q7c 因此改为 V17，V15 不用。

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

## 实施记录（Q8a，2026-10-18）

分支 `1.2/quizbuks-8a-ai-questions`，自 `origin/1.2/quizbuks-9e2-analytics-pages`（6deb05c：含 Q7b、Q9e1、Q9e2 与本计划）。只改 `.jabiz-app-paths` 中的路径。

### 合并与迁移

- 先以合并提交合入 `origin/1.2/platform`（16t，决策 D50，#177）：Q8a 不使用它（只发文字资料，输入在 60 KiB 内），Q8b 的封面与 PDF 资料会用；合并没有冲突。
- **迁移 V16**（`V16__quizbuks_ai.sql`）：按 2026-10-18 统一的顺序 Q7b V12、Q7a V13、Q9e1 V14、Q7c V15、Q8a V16。本分支上还没有 V13 与 V15（Q7a、Q7c 未合入）：Flyway 只在部署时要求顺序，
  Q7a 与 Q7c 必须在 Q8a 部署之前合入（否则它们成为"已应用版本之前的新迁移"，须 `outOfOrder` 或改号）；若合并顺序变化，后合并者顺延（第 9 节）。
  V16 建 `qb_ai_job`、`qb_ai_usage` 两张普通表，并给时态表 `qb_question_version` 加列 `ai_job_id`（只加列，表仍只追加）。

### 实现（`backend/quizbuks`，包 `content.ai`）

- **提供方与客户端**（D-Q8-1）：外部服务 `anthropic`（`ClaudeService`：`https://api.anthropic.com`、主机 `api.anthropic.com`、密钥 `ANTHROPIC_API_KEY`、连接 10 秒、请求 2 分钟、响应 ≤ 1 MiB、不允许试运行）；
  `ClaudeApi` 只经平台的 `ExternalHttp`：`POST /v1/messages`，`x-api-key`、`anthropic-version: 2023-06-01`；模型与 effort 取自参数；`thinking: {type: adaptive}`；`output_config.effort` 显式；
  `output_config.format` 为 JSON Schema（`AiPrompt.schema()`：题干、选项（文字、是否正确）、分值、依据的资料号；只用 API 接受的关键字，长度与范围由 `AiCandidates` 查）；`max_tokens` 16 000、不流式；
  `fallbacks: "default"` 与 beta 头 `server-side-fallback-2026-07-01`（A-10；Haiku 5.5 没有服务端回退，不发）；`metadata.user_id` 为 `AiUserKeys` 的 HMAC。
  答复：2xx 一律返回（用量先记录，再按 `stop_reason` 判断）：`end_turn` 读文字块中的 JSON；`refusal`（回退后仍拒答）与 `max_tokens` 为永久失败；其他与读不出的 JSON 为可重试的 `QB_AI_BAD_RESPONSE`。
  用量：有 `usage.iterations`（回退）时把每次迭代相加（顶层只含最后一次），缓存的写与读计入输入。非 2xx 由 `ClaudeErrors` 分类：429、408、5xx、529 重试，401/403 `QB_AI_AUTH`，其他 4xx `QB_AI_REQUEST_INVALID`（永久）；
  消息只有状态码与错误类型，不含提供方的消息与请求。用户消息超过 60 KiB 在发送前即永久失败（与请求流程的检查双重保证，从不截断）。
- **隔离**：作业处理器 `AiQuestionsHandler` 只调用接口 `QuestionWriter`（实现 `ClaudeQuestionWriter`）；换提供方只改实现与外部服务。处理器不访问数据库。
- **提示**（`AiPrompt`）：固定的英文系统提示在前（可被提供方缓存；不加 `cache_control`：前缀短于最小可缓存长度，单次请求也很少命中）；用户消息是请求（语言、难度、题数）与 `<quiz_data>` 中的一个 JSON 文档（标题、简介、编号的资料、已有题干），
  JSON 中的 `<` 一律写成 `<`，资料无法关闭或伪造标签；系统提示声明资料只是数据。文章发正文，其他种类只发标题与描述（A-5）。
- **作业** `qb.ai.questions`（`AiQuestionsJob.type`）：每次尝试 5 分钟、3 次尝试（退避 10 秒起、至多 2 分钟）、全实例并发 `quizbuks.ai.max-concurrency`（缺省 4）、每请求人未结束 ≤ 2、同一模版（引用 `quiz:<id>`）只一个；结果只含已按内容规则检查的候选与不合格数。
- **实体**（D-Q8-3）：`QbAiJob`、`QbAiUsage` 为普通表（默认数据视图 `processOnlyWrites`，读权限 `qb.admin.ai.read`；商家只读本人的行的两个数据视图），数据库约束：候选只在 READY 时、READY 必有候选与过期时刻、`(sponsor_id, month)` 唯一、外部作业号唯一。
- **流程**（D-Q8-4，`AiProcesses`）：
  - `QB_AI_QUESTIONS_REQUEST`（`qb.ai.use`、要求已验证、先查封禁）：锁外读入驻与封禁（`SponsorChecks`）、AI 说明的状态（`ConsentChecks.load`，种类 `AI_NOTICE`）、AI 参数（平台参数数据视图：缺少或不合格即 `QB_AI_UNAVAILABLE`，`AiSettings`）、
    经商家视图读模版（不是自己的或已移除即 404）、资料与题目；构造并度量作业输入（用户消息与存储的 JSON 取较大者 ≤ 60 KiB，否则 422 `QB_AI_INPUT_TOO_LARGE`，附各资料的字节数）；须同意而带了当前版本号即记同意（来源 `AI_REQUEST`），否则 `QB_AI_NOTICE_REQUIRED`；
    没有已生效的 AI 说明即 `QB_AI_NOTICE_MISSING`。全部问题一次报告。然后取 `qb.ai:<商家>`，一条语句读未结束的作业、今天的请求与本月的两行用量，`AiLimits` 判断，`StartExternalJob`，插入 `QbAiJob`（QUEUED，记输入字节与预估 Token）。
  - `QB_AI_JOB_COMPLETED` / `QB_AI_JOB_FAILED`（`internal()`，`qb.ai.system`）：取 `qb.ai:<请求人>` → `qb.ai:*`，按外部作业号读作业（不是请求人的或已结束的即什么也不做：重复投递无害）；记每次尝试的用量、尝试次数与回答的模型；
    QUEUED 的完成：候选按内容规则再查（只保留发送过的资料号），有合格的即 READY（候选 JSON、`expiresAt` = 完成 + `qb.ai.candidates.expire-days`），一个都没有即 FAILED `QB_AI_NO_CANDIDATES`；失败：FAILED 与代码、系统的说明；
    已取消的只记结束与用量。用量的商家行与全体行（`*`）各加一个作业（失败的另计 `failedJobs`）。
  - `QB_AI_ACCEPT`（`qb.ai.use`）：`qb.quiz:<模版>` → `qb.ai:<商家>`；作业须是本人的、本模版的（否则 404）、READY 且未过期；每题 `reviewed` 必须为 true（`@AssertTrue`，400）；按商家校对后的内容（题干、选项、分值；依据的资料号取自候选）以 `AiCandidates` 检查，
    候选号不重复且属于本作业，题数上限照旧；题目接在最后（必要时先重新编号，同 `Reorder.next`），记 `aiJobId`；模版 `revision + 1`、`changedSinceVersion`、`aiGenerated = true`；作业 ACCEPTED、清空候选（可只接受一部分）。
  - `QB_AI_DISCARD`（READY → DISCARDED，清空候选）、`QB_AI_CANCEL`（QUEUED → CANCELLED）：取 `qb.ai:<商家>`，别人的作业 404。
  - `QB_AI_EXPIRE`（`internal()`）与逐项任务 `qb.ai-expire`（每日 18:43 UTC，模板 `qb.system.ai-expired`）：每项取 `qb.ai:<商家>`、重读，READY 且已过期的改为 EXPIRED 并清空候选。
- **参数**（`QbParams`，`QB_SETUP` 补建）：受控的 `qb.ai.enabled`（false）、`qb.ai.model`（种类 code，只允许三个模型，缺省 `claude-opus-5-5`）、`qb.ai.effort`（code：low / medium / high，缺省 medium）、`qb.ai.sponsor.daily-jobs`（20）、
  `qb.ai.sponsor.monthly-tokens`（2 000 000）、`qb.ai.global.monthly-tokens`（50 000 000）；普通的 `qb.ai.candidates.expire-days`（7）与只用于估算的 `qb.ai.price.input-per-mtok-usd`（4）、`output-per-mtok-usd`（20）。
- **权限**：新 `qb.ai.system`（不授予）、`qb.admin.ai.read`（内容管理员，超级管理员由此继承）；`qb.ai.use` 照旧（商家）。
- **限流**：`QB_AI_QUESTIONS_REQUEST` 每人每分钟 5 次、`QB_AI_ACCEPT` 30 次。
- **模板**：`qb.sponsor.ai-jobs`、`qb.sponsor.ai-usage`、`qb.admin.ai-usage`、`qb.admin.ai-jobs`、`qb.admin.ai-settings`、`qb.system.ai-expired`。
- **法律文本**：新种类 `AI_NOTICE`（字典、`QB_SETUP` 的 `[TEST PLACEHOLDER]` 草稿、两个 SPA 的 `/legal/ai`、后台规约类页）；不在 `SPONSOR_KINDS` 中，登录后的同意门不问它。
- **本地桩** `tools/quizbuks/claude-stub/ClaudeStub.java`（JDK 单文件，端口 8094，`dev` 已指向它）：按请求的题数与语言生成题目，`[refuse]` 拒答、`[busy]` 529；只监听本机、从不访问网络。
  IT 用 `StubHttpServer`（`AiItSupport`），记录每个请求以断言发送的内容。
- **部署**：`ANTHROPIC_API_KEY` 缺少即启动失败（平台 EXTERNAL 检查）：本机 compose 与 `local-demo.sh` 填占位，CI 的 package / e2e 设一次性的值，`platformCheck` 同 Stripe 的签名密钥；没有密钥的部署可设占位，AI 保持关闭。

### 页面

- 商家后台 `sponsor/src/ai/`：编辑器题目页上方的 AI 面板（本月剩余 Token 与今日次数）、生成对话框（见第 5 节）、进行中的状态与取消、候选校对、失败与过期的说明；仪表盘的 AI 用量卡（Q9e2 的卡片位）。
- 后台扩展 `/qb/ai`（菜单"AI 生成"，`qb.admin.ai.read`）：开关与上限的现行值，开关以 `CONTROL_CHANGE_PROPOSE` 提出（填理由，另一位管理员发布）；按商家与月份的用量、失败率与估算；最近的请求（可只看失败）。

### 页面上的文字（请需求方确认）

- 面板与对话框：
  - 英：*AI questions* / *Let the AI write candidate questions from this quiz's materials. Nothing is added until you review it.* / *Generate with AI* /
    *Sent to the AI provider: this quiz's title, introduction, the chosen materials and, if ticked, its questions. Never your account details or anything about quiz takers.* /
    *Check every question and its answers against your materials. Change anything you like, then tick "I have reviewed this question". Only reviewed questions are added.*
  - 中：*AI 出题* / *让 AI 根据本模版的资料生成候选题目。未经你校对，不会加入题库。* / *AI 生成题目* /
    *发送给 AI 提供方的内容：本模版的标题、简介、所选资料，以及（勾选时）已有题目的题干。不会发送你的账户信息或任何答题人的数据。* / *请对照资料逐题检查题干和答案，可任意修改，然后勾选"我已校对此题"。只有勾选的题目会加入题库。*
  - 日：*AI による問題作成* / *このクイズの資料から AI に候補問題を作成させます。あなたが確認するまで追加されません。* / *AI で作成* /
    *AI 提供者に送信される内容：このクイズのタイトル、紹介文、選んだ資料、および（チェック時）既存の問題文。あなたのアカウント情報や回答者のデータは送信しません。* / *問題と答えを資料と照らして 1 問ずつ確認してください。…*
  - 失败的通用说明：*AI 暂时不可用，请稍后再试或手动编辑。*（另有拒答、过长、无合格候选、过期的说明）
- **AI 说明的占位**（`QB_SETUP` 建立的草稿，**不是法律文本**，须由需求方或律师替换后发布）：标题 `[TEST PLACEHOLDER] AI 利用に関する説明` / `[TEST PLACEHOLDER] AI notice` / `[TEST PLACEHOLDER] AI 使用说明`，
  正文为通用的占位说明与一节"第1条 AI 提供者に送信される情報 / 1. What is sent to the AI provider / 第一条 发送给 AI 提供方的内容"，内容为"仮の文章です / Placeholder text / 占位文本"。
  正式文本至少应说明：发送给 Anthropic 的内容（标题、简介、所选资料、已有题干）与不发送的内容；提供方的数据使用与保留（是否用于训练、保留期、处理地域，按 Anthropic 的商业条款，须法务确认）；生成的题目须商家校对、商家对内容负责。

### 与计划的偏离（共 9 项；第 1、3、4 项经评审修订，见下"评审修订"）

1. **请求超时 2 分钟**（计划写 5 分钟）：平台 `ExternalService` 的请求超时上限是 2 分钟（`MAX_REQUEST_TIMEOUT`）。effort `medium` 的非流式回答在其内；超过即该次尝试失败并重试，作业每次尝试的时限仍是 5 分钟。若真实测验中经常超时，可降低题数上限或改用流式（平台的 `ExternalHttp` 目前只有整体读取）。（评审后：超时的尝试按预估计入，`max_tokens` 随题数，effort `high` 至多 10 题。）
2. **"3 次重试"按计划 D-Q8-4 理解为共 3 次尝试**（首次 + 2 次重试）。
3. **取消不经平台的 `EXTERNAL_JOB_CANCEL`**：它是 `entryOnly()`，不能作子流程；且被取消的作业不调用完成或失败流程，用量就不会进入 `QbAiUsage`（反复"请求 → 运行中取消"可绕开月限额）。所以 `QB_AI_CANCEL` 只把本地状态改为 CANCELLED：
   提供方照常完成，结果丢弃、用量照记，取消的作业在结束前仍占"未结束"的名额与预估。代价是排队中的作业被取消后仍会调用一次提供方。平台若提供"取消后仍回调用量"或子流程取消，可改为真正的取消（见"未完成"）。（评审后：运维以平台的 `EXTERNAL_JOB_CANCEL` 停止的作业由 `QB_AI_JOB_STOPPED` 关闭并按完整预估计入。）（平台 16v-1 之后改为真正的取消：见文末"平台 16v-1 之后"。）
4. ~~**全体月上限在锁外读**~~（评审后撤销，评审 M2）：请求在 `qb.ai:<商家>` 之后也取 `qb.ai:*`，全体上限计入所有商家未结束作业的预估（`qb.system.ai-open`，部分索引 `qb_ai_job_open_idx`），与计划一致。
5. **AI 参数为受控参数**：计划写"普通参数，超级管理员可改"，但本应用不把 `platform.param.write` 授予任何角色（应用 `CLAUDE.md` 第 4 节"受控参数"），超级管理员实际改不了普通参数；开关与限额关系到花费，所以与金额参数一样四眼。
   后台 `/qb/ai` 的开关因此是"提出变更"。Q8a 之前装过的库中 `qb.ai.model` 是旧的文本参数 `gpt-4o-mini`（`QB_SETUP` 不改已有的）：AI 不可用，须以受控变更改为清单中的值（`deploy/quizbuks/README.md`）。
6. **模型清单的保证**：参数的种类（code 与允许值）只对新建的参数生效；每次请求都按 `ClaudeService.MODELS` 再查，所以没有另写启动检查。
7. **审核页的"AI 生成"标在模版上**：版本快照只有模版级的 `aiGenerated`（审核页已显示"Made with AI"）；题目的 `aiJobId` 不进快照（否则改变快照格式与哈希），只在平台通用后台的题目数据与 `/qb/ai` 中可见。
8. **没有场景回放** `scenarios/qb/ai.yml`：IT 已覆盖全部流程（任务说明的测试清单没有场景），留作后续。
9. **商家后台加了开发依赖 `vitest-axe` 0.1.0**（与平台前端同版本，`app:check` 通过）：页面测试在 jsdom 中以亮、暗两种外观跑 axe（对比度在 e2e 的真实浏览器中查）。

### 自查（对抗性）

- **成本滥用**：只给入驻通过、已验证、未封禁的商家；入口限流（每分钟 5 次）；锁内的每日次数、每月 Token（含未结束作业的预估，一次最多输入 / 3 + 1 500 + 16 000）与同时未结束 ≤ 2（平台另以每请求人 ≤ 2、每模版 1 个兜底）；取消的作业在结束前仍占名额、结束后用量照记（偏离 3），
  所以"请求 → 取消"不能绕开限额；模型、effort 与 `max_tokens` 由服务端决定，商家只能选题数（1–20）；输入 ≤ 60 KiB。（评审后：预估覆盖 3 次尝试，没有回答的尝试按预估计入，全体上限计入正在运行的作业，见"评审修订"。）多个商家账号仍受入驻审核与全体上限约束。建议在 Anthropic Console 为工作区设月支出上限（`operations.md` §1.3）。
- **提示注入只影响商家自己**：发送的只有该商家的模版内容，没有任何其他数据可被诱导泄露；资料以转义后的 JSON 数据发送；输出按内容规则检查、只以纯文本显示（输入框与文本框，不渲染 Markdown 或 HTML，`ai.test.tsx` 断言）、必须逐题校对才入库、入库后仍经发布审核；
  候选的资料号只保留发送过的。输出不能触发任何动作（没有工具、没有链接被打开）。
- **发送给提供方的数据**：标题、简介、所选资料（文章正文；其他种类的标题与描述）、已有题干（可关闭）、题数、难度、语言与 `metadata.user_id`（HMAC，`AiQuestionsIT` 断言请求中没有商家编号、公司、名字与邮箱样式的文字）；不发送答题人的任何数据。
  商家可能在资料中写入个人信息：由 AI 说明告知（A-11，须法务确认）。作业输入永久存于平台只追加的 `sys_external_job`（P4）；持有 `external-job.read` 的财务管理员能读到这些作业的输入（与内容管理员能读全部模版相比风险小，记为已知）。
- **密钥不进日志**：`ANTHROPIC_API_KEY` 只经 `ctx.secret` 放进 `x-api-key` 头（平台从不打印头的值，`ExternalHttp.Request.toString`）；`ClaudeApi`、`AiUserKeys` 的 `toString` 遮蔽；失败消息只有状态码与错误类型（`ClaudeApiTest` 断言不含提供方的消息）；
  `failureMessage` 只存处理器或平台的说明；测试与 `dev` 的密钥是固定的假值；HMAC 的密钥由完整性密钥派生、不另存。

### 对已有测试的影响

- `QB_SETUP` 多提出 6 个受控参数（AI 参数排在已有参数之后、审批规则之前），所以各场景的提案号顺延（`p29`–`p34`）并多发布 6 个；新的主键使发布号改变，选项的打乱（按答题人与发布号的 HMAC）随之改变，
  `analytics`、`payout`、`play`、`topup` 四个场景的所选选项按新的正确答案改写（得分与奖励的预期不变），场景快照随之更新。
- `setup.yml`：`qb.ai.model` 不再是普通参数（改为提出），新建的普通参数多了过期天数与两个价格；法律草稿多了 `AI_NOTICE`。`SetupRemovalsIT` 改以普通参数 `qb.ai.candidates.expire-days` 演示"改过的参数不被重建"。
- `ProcessAccessIT` 加入三个 `internal()` 流程；公开模板目录快照（`public-queries.json`）中法律种类多了 `AI_NOTICE`。
- `AttemptStatementBudgetIT` 未改动并通过（答题路径不受影响）。

### 测试结果（2026-10-18，本机 PostgreSQL 16）

- `./gradlew :quizbuks:check :quizbuks:bootJar`：通过（635 个测试、0 失败，含 `platformCheck` 与覆盖率门禁）；其中 `AiQuestionsIT` 11、`AiSwitchedOffIT` 1，单元测试 `ClaudeApiTest`、`AiCandidatesTest`、`AiLimitsTest`、`AiPromptTest`。
- 商家后台：`pnpm lint && pnpm typecheck && pnpm test`（159 个测试，含 `ai.test.tsx` 亮暗两种外观的 axe）；答题 SPA：133 个测试；`pnpm app:check` 两个 SPA 均通过；后台扩展 `pnpm ext:check`（74 个测试）通过。
- e2e（打包的 jar、本地 Claude 桩与 Stripe 桩、Mailpit）：全部 52 个通过（14.5 分钟；`ai.spec.ts` 亮暗两种外观：对话框、AI 说明、校对、加入、列表的"AI generated"、仪表盘用量卡、后台 `/qb/ai`，各处跑 axe）。端口、数据库与进程都是本任务自己的（应用 18180、Claude 桩 18194、Stripe 桩 18193、Mailpit 18125/11125，库 `jabiz_e2e_q8a`）。

### 评审修订（2026-10-18，独立评审：设计与 Messages API 的用法无误，成本控制有缺口）

先合并 `origin/1.2/quizbuks-9e2-analytics-pages`（f5572b2：Q9e2 评审修复与 `PlayAdmissionIT` 的竞态修复），再逐项先写测试后修复。

- **H1 超时的尝试从不计费**：非流式、2 分钟的请求超时，而提供方可能已为整个回答计费。
  - 没有得到回答的尝试（平台的 `ExternalCallException`：超时、断线、回答过大；平台拒发与没建立的连接 `ConnectException` 除外）在失败前**按预估记入用量**：完整的输入（系统提示与用户消息，按 3 字节 1 Token）与所要的全部输出（`ClaudeApi.unanswered`），商家与全体都计。
  - 单个作业的最坏情况降低：`max_tokens` = 4 000 + 600 × 题数、至多 16 000（`ClaudeApi.maxTokens`）；effort `high` 时一次至多 10 题（`QB_AI_COUNT_TOO_HIGH`，商家用量模板给出 `maxCount`，对话框照它）。
  - 请求超时可配（`quizbuks.ai.request-timeout`，`QB_AI_REQUEST_TIMEOUT`，缺省也是上限 2 分钟；IT 用 2 秒）。
  - 测试：`AiCostControlIT.attemptsWithoutAnAnswerAreChargedTheirEstimateAndHitTheLimit`（桩在客户端放弃之后才回答，3 次尝试都按预估计入，下一请求即 `QB_AI_MONTHLY_LIMIT`）、`AiPromptTest`、`ClaudeApiTest`。
- **M2 全体上限不计他人正在运行的作业**：请求在 `qb.ai:<商家>` 之后取 `qb.ai:*`，以一条语句（模板 `qb.system.ai-open`，部分索引 `qb_ai_job_open_idx … WHERE finished_at IS NULL`）读所有未结束作业的预估，加进全体检查。锁序不变（商家 → 全体，完成与失败相同）。测试：`theGlobalLimitCountsOtherSponsorsRunningJobs`、`AiLimitsTest`。
- **M3 关闭开关不停已排队或重试中的作业**：**没有照评审建议让处理器在执行时重读 `qb.ai.enabled`**：平台决策 D38 规定外部作业处理器不访问数据库（ArchUnit `handlersStayOutOfTheDatabase`，应用不能放宽），平台也没有给处理器看业务参数的接口。改为：
  - 本实例的配置开关 `quizbuks.ai.halted`（`QB_AI_HALTED`，需重启）：为 true 时每次尝试不调用提供方即永久失败（`QB_AI_HALTED`），排队与重试中的也一样（`AiPromptTest.aHaltedServerSendsNothingAndEndsTheJob`）；
  - 运维以平台的 `EXTERNAL_JOB_CANCEL`（`external-job.cancel`）停止已排队的作业：平台对被取消的作业不调用完成或失败流程，所以新增 `QB_AI_JOB_STOPPED`（`internal()`，订阅平台事件 `jabiz.external-job.finished`，只处理本类型的 `CANCELLED`）关闭本地作业（`QB_AI_STOPPED`）并**按完整预估**计入（没人知道它用了多少）；测试 `switchingOffStopsNewRequestsAndAnOperatorStopsTheQueuedOnes`（关闭后新请求 `QB_AI_UNAVAILABLE`、取消后提供方没有被调用、用量为预估）；
  - `operations.md` §7.8 写明：最快的停止是在 Anthropic Console 吊销密钥（之后每次尝试 401 即永久失败），受控开关需要两位管理员且只拦新请求。
  - **平台后续**（需在 `1.2/platform` 上做）：让执行器按作业类型暂停或在每次尝试前问一个同步的闸门（如 `ExternalJobType.pausedWhen(参数)`），或让内部流程能取消外部作业并照常回调用量；之后处理器才能"执行时看开关"。
- **M4 预估覆盖全部尝试**：`AiLimits.estimate(输入字节, 题数)` = 3 × 每次尝试（完整输入 + `maxTokens`），作业记录的 `estimatedTokens` 即此；未结束作业的预留因此不会被它们自己的重试越过。`AiLimitsTest.theEstimateErrsHighAndCoversEveryAttempt`。
- **L5**：`QbAiJob.candidates` 标 `auditMasked`（审计只记该列变了，`theSponsorsOwnChecksHold` 断言审计中没有候选的文字）。文档与界面不再说"清除"：过期、丢弃、接受后候选从 `QbAiJob` 移除、不能再加入题库，但**提供方的原始回答仍在平台只追加的作业结果中**（`sys_external_job`，P4）。不存进作业结果在平台上做不到（结果就是完成流程收到候选的途径）。
- **L6**：商家的两个数据视图只供模板限定范围，本身不可读（读权限改为不授予任何角色的 `qb.ai.system`；直接查询 403，模板照常），商家看不到 `servedModel`、`model`、`estimatedTokens`、`inputBytes`。
- **L7**：拒答而 `stop_details.recommended_model` 存在（回退因限流或过载没能运行）时可重试；没有它的拒答照旧永久失败（`aRefusalWhoseFallbackWasBusyIsTriedAgainAndAuthIsNot`）。
- **L8**：
  - 美元估算按使用 Token 的模型计价（`AiCost`：Opus 5.5 4 / 20、Sonnet 5.5 2 / 10、Haiku 5.5 0.10 / 0.50、Opus 5 与 Opus 4.8 5 / 25 美元每百万 Token；不认识的模型按 `qb.ai.price.*`）。回退模型所用的部分单独记录（`fallbackInputTokens` / `fallbackOutputTokens`，来自 `usage.iterations` 的 `fallback_message`），失败的作业不知道回退到哪个模型时按 Opus 5 / 4.8 的价。作业与用量行记 `costUsd`，`qb.admin.ai-usage` 只读它。
  - `qb.sponsor.ai-usage` 的时区改为请求时记在作业上的 `jabiz.fiscal-year-zone`（`QbAiJob.zone`，与 `AiLimits` 相同），商家第一次请求之前才退回分析任务记下的时区。
  - 缺省分值不一致（记下，不改）：AI 候选没有分值时为 10（A-12），手动新建题目的缺省是 1（`QuestionsPanel`）。
  - `application.yml` 的注释（占位密钥由本机 compose 与 `local-demo.sh` 填，开关是受控变更）与 `QbRoles.SUPER` 的 Javadoc（`qb.ai.model` 已是受控参数）已改正；偏离项数写明（共 9 项）。
- **L9 测试**：`AiCostControlIT`（8 个）补上封禁与未验证的商家、接受到本人的另一个模版（404）、超过 `MAX_QUESTIONS`、过时的 `baseRevision`（422 `QB_QUIZ_CHANGED`）、接受排队中的作业（`QB_AI_JOB_NOT_READY`）、商家 B 取消 A 的作业（404）、401 归为 `QB_AI_AUTH`（一次尝试）、超时、排队中关闭开关；`AiQuestionsIT` 的并发测试断言被拒的一个是 `QB_AI_DAILY_LIMIT`。场景 `scenarios/qb/ai.yml` 仍未写（IT 覆盖全部流程）。

**剩余风险**（应记入平台决策 D38 的实施说明；`docs/design/` 是平台目录，应用分支不能改，须在 `1.2/platform` 上补记）：

1. 非流式请求最长 2 分钟（`ExternalService.MAX_REQUEST_TIMEOUT`）：慢的回答被放弃、按预估计费，重试可能再次被放弃。每个作业的花费仍以 3 × 预估为上限、计入限额，但可能为没人读到的回答付费。**平台后续：`ExternalHttp` 的流式读取**（边读边记用量，超时只取消剩余部分）。
2. 处理器读不到业务参数，关闭开关不停已排队与重试中的作业（见 M3 的平台后续）。
3. 提供方不按幂等键去重：同一尝试的重发可能被计费两次；按预估计入使限额保守，但实际账单可能高于记录（Console 的工作区月支出上限是最后一道防线）。

**验证**（本机 PostgreSQL 16，自己的库与端口）：`./gradlew :quizbuks:check :quizbuks:bootJar` 通过（663 个测试、0 失败，含 `platformCheck`；`AttemptStatementBudgetIT` 未改动并通过）；商家后台 163 个测试、答题 SPA 133 个，两个 SPA 的 lint、typecheck 与 `app:check` 通过，后台扩展 `ext:check` 74 个；e2e 全部 52 个通过（15.3 分钟，打包的 jar、本地 Claude 与 Stripe 桩、Mailpit，库 `jabiz_e2e_q8a`）。

## 实施记录（Q8b，2026-10-18）

分支 `1.2/quizbuks-8b-ai-covers-pdf`，自 `origin/1.2/quizbuks-8a-ai-questions`（1bd12d7：含 Q8a、平台 16t 与 16u）。只改 `.jabiz-app-paths` 中的路径；平台没有需要补的能力（16t 的文件输入、文件输出与按类型的输入上限已足够）。

### 迁移

- **V18**（`V18__quizbuks_ai_covers.sql`）：原为 V17。Q7c 的站内信迁移在 Q8a 的 V16 之后合入，不能再用 V15（Flyway 不开 `outOfOrder` 时拒绝落在已执行的 V16 之后的版本），改号为 V17（`V17__quizbuks_inbox.sql`），Q8b 因此顺延为 V18（2026-10-18，合并 `1.2/quizbuks-7c-inbox` 时以 `git mv` 改号；已合入的 V16 等不动，改动会改变其校验和）。现在的顺序：Q7b V12、Q7a V13、Q9e1 V14、Q8a V16、Q7c V17、Q8b V18（没有 V15）。
  给普通表 `qb_ai_job` 加三列 `cover_1` – `cover_3`（`uuid`，封面作业的候选图）与约束"三列只在封面作业 READY 时非空"。计划第 4 节写"可能无迁移"：候选图必须被某个字段引用，否则平台的清扫（`jabiz.files.orphan-after`，24 小时）会在 7 天的选择期内删掉它们（偏离 1）。

### 实现（`backend/quizbuks`，包 `content.ai`）

- **封面**（M-25，A-4 做法 A）：
  - `CoverDesigns`：固定图库（8 种配色、4 种版式、7 种图形）与设计的检查（名称在图库中、文字去首尾空白后 1–40 字、无控制 / 格式 / 私用字符、服务器画得出）；`CoverRenderer`（Java2D，无头）画成 1200 × 630 的 PNG：配色的渐变、强调色的图形、粗体文字按宽度换行至多 3 行（拉丁按词、中日文按字），字号从 92 降到 36；
    字体为平台自带的 Noto Sans Bold、部署为 PDF 配置的 `jabiz.reports.pdf.fonts`（镜像中的 IPA Gothic 与 Droid Sans Fallback）与 JVM 的无衬线字体，逐字取第一种有该字的字体；字体在建 Bean 时读一次（处理器运行时不读文件）。
  - 作业 `qb.ai.cover`（`AiCoverJob`）：与题目同样的超时、3 次尝试、并发设置、每请求人未结束 ≤ 2、每模版 1 个；文件输出 `qb.content.image`、至多 3 个、每个 ≤ 4 MiB（合计取平台缺省 12 MiB）。处理器 `AiCoverHandler`：停机开关、**标题画不出即 `QB_AI_COVER_FONT`（不调用提供方、不计费）**，再经 `CoverDesigner`（实现 `ClaudeCoverDesigner`）请 Claude 设计、检查、画图，以 `cover-1..n` 返回文件；
    平台按策略登记（按内容判定类型、重新编码、以商家为上传者）。提示 `AiCoverPrompt`：固定的系统提示列出图库，用户消息是语言与 `<quiz_data>` 中的标题与简介（`<` 转义），JSON Schema 以枚举列出图库的名字；`max_tokens` 8 000。
  - 调用 Claude 的共同部分移到 `ClaudeCalls`（用量即时记录、没有回答的尝试按预估计入、拒答 / 截断 / 其他结束原因的处理，与 Q8a 相同），题目与封面都经它；`ClaudeApi.Request` 加了 JSON Schema 与文档两项（原 6 参构造保留）。
  - 流程：`QB_AI_COVER_REQUEST`（`qb.ai.use`，与题目请求同一套步骤：入驻、封禁、AI 说明、参数、经商家视图读模版，`qb.ai:<商家>` → `qb.ai:*` 内查限额；**每模版每种类**同时一个：封面与题目可同时进行，`QB_AI_QUIZ_BUSY` 只看同种类）；
    完成流程 `QB_AI_JOB_COMPLETED` 按作业种类分支：画出的设计与平台给出的文件编号写入 `candidates`（`{schema: 1, covers: [{no, palette, layout, motif, text, fileId}]}`）与 `cover1..3`，READY 与过期时刻；一个都没有即 FAILED `QB_AI_NO_CANDIDATES`。
    `QB_AI_COVER_ACCEPT`（`qb.ai.use`，限流每人每分钟 30 次）：`qb.quiz:<模版>` → `qb.ai:<商家>`；作业须是本人的、本模版的（否则 404）、封面种类（否则 422 `QB_AI_WRONG_KIND`，题目的接受同样拒绝封面作业）、READY 且未过期；带 `baseRevision`；所选文件经 `QuizEditing.requireOwnFile` 与 **`QuizEditing.touch`（现有的模版编辑）** 写入 `cover` 与 `aiGenerated = true`；作业 ACCEPTED、清空候选与三列。
    丢弃与过期同样清空三列；平台停止（`QB_AI_JOB_STOPPED`）也处理封面类型。用量、美元估算、商家与全体的月行、每日次数都与题目共用。
- **PDF 资料**（A-5）：
  - 作业 `qb.ai.questions` 改为 `maxInputSize(256 KiB)`（`MAX_JOB_INPUT_BYTES`）并声明文件输入（至多 10 个、每个 ≤ `quizbuks.ai.pdf.max-bytes`、策略 `qb.content.pdf`）；用户消息上限 `ClaudeApi.MAX_INPUT_BYTES` 由 60 KiB 提到 250 KiB。
  - 请求流程读所选 PDF 资料的文件（平台文件数据视图），每个 ≤ `quizbuks.ai.pdf.max-bytes`（缺省 10 MiB，上限 20 MiB，`QB_AI_PDF_MAX_BYTES`）、合计 ≤ 20 MiB（base64 后仍在提供方 32 MB 的请求上限内），否则 422 `QB_AI_PDF_TOO_LARGE`（列出各 PDF 的大小）；以 `StartExternalJob…files` 交给作业（平台检查商家能读、记入 `sys_external_job_file`）。资料在输入中只记文件编号与"document"名，不放内容。
  - 处理器（`ClaudeQuestionWriter`）经上下文读文件，**不解析**（评审 H1/H2 之后，见下"评审后的修改"）：以提供方免费的 `count_tokens` 数完全相同的请求，超过请求按 `quizbuks.ai.pdf.max-pages`（缺省 100，上限 600，`QB_AI_PDF_MAX_PAGES`）预留的输入即 `QB_AI_PDF_TOO_LONG`，提供方拒绝文档即 `QB_AI_PDF_UNREADABLE`，都在付费调用之前、不计费；
    PDF 以文档块（base64，标题 `Material <no>`）放在请求文字之前，资料的 JSON 带 `"document": "Material <no>"`，系统提示说明文档同样只是数据。
  - **预估**：请求时还不知道页数，带 PDF 的请求按页数上限预留：`AiLimits.estimate(输入字节, max_tokens, 页数)` = 3 × (文字输入 + 页数 × 5 000 + 所要的输出)，每页 5 000 Token（`ClaudeApi.TOKENS_PER_PDF_PAGE`：提供方说明每页文字 1 500–3 000 Token，另加页面图像约 1 600）；没有回答的尝试按提供方数出的输入计入（`ClaudeApi.unanswered`）。
  - `AiCost`：最小的模型一次调用超过 10 万输入 Token 时整个调用按长提示的价格（0.50 / 2.50 美元）估算，按每次调用分别记（评审 L2）。
- **ArchUnit**：`ArchitectureTest` 加入平台的 `handlersReadFilesThroughTheirContext`（处理器与它经应用自己的类用到的类都不依赖 `com.jabiz.runtime.file`）。
- **本地桩**：`tools/quizbuks/claude-stub` 对"Number of covers: 3"回答 3 个图库内的设计，接受文档块（不读），并回答 `count_tokens`（评审后：文字每 4 字符 1 Token、每个文档 1 500，用量报同一个数）；IT 的桩（`AiItSupport`）同样回答封面并能取出请求的文字块与文档块。

### 页面

- 商家后台 `sponsor/src/ai/AiCoverPanel.tsx`：编辑器"模版"页基本信息下的"AI 封面"面板：对话框选封面文字的语言（缺省为界面语言）、说明发送的内容（标题与简介）、第一次使用时的 AI 说明（与题目对话框共用 `AiNoticeSection`）；进行中每 5 秒查询、至多 5 分钟；3 张候选图（`Thumbnail`）以单选选择、"使用此封面"、全部丢弃；失败与过期的说明（`QB_AI_COVER_FONT`、无可画的设计）。
  选定后 `editing.edited` 记下新的 revision，`HeadingForm` 随读到的 `cover` 更新（其他未保存的字段保留）。作业模板按种类读（`qb.sponsor.ai-jobs` 新参数 `kind`），题目面板只读题目作业。
- 生成题目的对话框：有文件的 PDF 资料注明"发送 PDF 文件本身（至多 10 MB；全部 PDF 合计至多 100 页）"，不计入文字大小；文字上限的提示改为 250 KB；失败说明加 PDF 的两种。
- 后台扩展 `/qb/ai`：最近的请求在状态之上注明种类（题目 / 封面；不另加一列：表格变宽后其滚动区域不能以键盘到达，axe `scrollable-region-focusable`）；`qb.admin.ai-jobs` 给出 `kind` 并可按它筛选。
- 页面上的文字（请需求方确认）：英 *AI cover* / *Let the AI design three covers from this quiz's title and introduction. Choose one, or none.* / *The chosen cover replaces the quiz's cover. The others are deleted later.*；
  中 *AI 封面* / *让 AI 根据本模版的标题和简介设计三个封面。可以选一个，也可以都不用。*；日 *AI カバー* / *このクイズのタイトルと紹介文から AI に 3 つのカバーをデザインさせます。…*。AI 说明的正式文本应补上"PDF 资料的原文件会发送给 AI 提供方"。

### 与计划的偏离（共 6 项）

1. **有迁移 V18**（原为 V17；计划写"可能无"）：见上"迁移"。
2. **每模版每种类同时一个作业**（计划 Q8a 写"同一模版同时只一个"）：封面与题目互不相关、可同时进行；平台的"同一引用只一个"本来就按作业类型。每商家同时未结束 ≤ 2 不变（两种合计）。
3. **不数页、以提供方的计数为准，请求按上限预留**：计划写"每个至多 10 MB 与 100 页，多于即请少选"。流程不读文件（平台的约束），所以请求时只能查大小；服务器也不解析 PDF（评审 H1/H2），作业在付费调用前以 `count_tokens` 数输入，超过按页数上限的预留即作业失败（不发送、不计费），页面显示"请少选或选较短的 PDF"。100 页按"一次请求的全部 PDF 合计"计（按每个 100 页时 10 个 PDF 可达 1 000 页，超过提供方的 600 页，预留也会大到不可用）。
   **代价**：带 PDF 的请求在结束前预留约 150 万 Token（缺省 100 页），缺省的每商家每月 200 万 Token 只容一个进行中的 PDF 请求，本月已用超过约 50 万后再不能带 PDF 请求（直到月末）。见"待确认"。
4. **合计 20 MiB 的 PDF 上限**（计划未写）：提供方的请求上限是 32 MB，base64 使 PDF 大约三分之一。
5. **封面文字的语言由商家选**（计划只写"根据标题和简介"）：缺省为界面语言，同题目。
6. **封面候选与题目候选同一过期天数**（`qb.ai.candidates.expire-days`），过期任务同一个；没有为封面另设参数。

### 评审后的修改（独立评审，2026-10-18）

- **H1、H2 决定：服务器不解析 PDF，删去 PDFBox**。本地数页有两个问题：PDFBox 的 `Loader.loadPDF` 会把商家构造的对象流（压缩的对象嵌套展开）全部读进内存，一个小文件即可使 JVM 内存耗尽（处理器在请求路径之外，但与整个实例共用堆）；页树的 `/Pages /Count` 可以谎报，数出的页数不可信，预留因此可被绕过。
  所以改为：处理器在付费调用之前，以完全相同的请求（同一外部服务 `anthropic` 与请求头；去掉计数不接受的 `max_tokens`、`metadata` 与 `fallbacks`）调用提供方免费的 `POST /v1/messages/count_tokens`（`ClaudeCalls.counted`、`ClaudeApi.countTokens`），以它的数为准：
  - 超过请求为一次尝试预留的输入（`AiLimits.reservedInput` = 文字的估计 + 页数上限 × 5 000，记在作业输入与参数 `reservedInputTokens` 中）即永久失败 `QB_AI_PDF_TOO_LONG`，不发送、不计费；因此计入的输入从不超过预留；
  - 提供方以 400 拒绝文档（消息说是 PDF 或文档）即 `QB_AI_PDF_UNREADABLE`，永久；计数的 400 不计费，付费调用的 400 同样不计费；
  - 计数本身断线、超时或 5xx 照常重试，不计费；
  - 付费调用没有回答时按这个数计入（不再按估计的页数）；
  - 只有带 PDF 的请求先计数（文字的估计本来就是上界）。
  字节上限不变（每个 10 MiB、合计 20 MiB）；`AiPdfs` 只剩上限；依赖 `org.apache.pdfbox` 删除，ArchUnit `quizbuksParsesNoPdf` 防止再引入。本地桩（dev、e2e）与 IT 的桩都实现 `count_tokens`（IT 的桩按真实的 `/Type /Page` 对象数，谎报 `/Count` 的文件因此仍被拒绝）。
  `ClaudeCalls.record` 在某次尝试的用量超过预留时记告警日志（只有作业种类，D16）。
- **M1**：封面处理器在调用提供方之前检查所请求语言的样例文字（`AiCoverHandler.SAMPLES`：en、ja、zh）与标题都画得出，否则 `QB_AI_COVER_FONT`（只有拉丁字体的服务器不再为中日文封面付费）。
- **M2**：堆的需要写进 `operations.md` §7.8 与部署 README（约 PDF 合计的 5–6 倍 / 运行中的题目作业，"512 MiB + 120 MiB × 并发"）；PDF 只做一次 Base64（计数与调用共用），请求体直接写成字节，不同时持有 JSON 字符串与字节。
- **L1**：平台停止的 PDF 作业按预留（含页数上限）计入输入。**L2**：长提示价格按每次调用计（只有最小的模型有两档；较大的两个模型没有长提示加价）。**L3**：`completeCover` 再查版式与图形。**L4**：§7.8 写明提供方并发是两种作业之和（4 + 4）。**L5**：§7.8 写明完成迟于 `orphan-after` 时候选图可能已被清扫。
- 细节：`QB_AI_HALTED` 的文案不再只说题目；两个 Claude 调用方的超时取自同一个 Bean（`ClaudeCalls.Settings`）；`AiCoversTest` 用固定时刻；封面 Schema 的数量与长度写在说明中（结构化输出拒绝 `maxItems`、`maxLength`）；点击封面图即选中；两个接受流程另要 `qb.content.write`（现在只有 `QB_SPONSOR` 持有 `qb.ai.use`，它也有内容写权限，加上是为了以后的角色）。
- 新测试：`AiPdfIT` 改为 7 个（计数后以文件原样发送、计数的请求与发送的相同、无 PDF 不计数；谎报 `/Count` 的 PDF 按计数拒绝、没有回答按计数计入；提供方拒绝文档（计数与付费调用两处）、其他 400 不变；计数失败重试不计费；过大 422；停止的 PDF 作业按预留计入与计价；同一商家同时请求封面与题目时共用每日次数）；`AiPdfsTest` 不再用 PDFBox；`AiCoversTest` 加只有拉丁字体时 ja、zh 不调用。
- 验证（评审修改之后，2026-10-18，同上的库与端口）：`./gradlew :quizbuks:check :quizbuks:bootJar` 通过（713 个测试、0 失败；`platformCheck` 0 错误）；商家后台 lint、typecheck、171 个 Vitest，`app:check`，`ext:check`（74 个）；e2e 全部 54 个一次通过（`ai-cover.spec.ts` 改为先点图片选中）。

### 复审后的修改（2026-10-18）

- **M-a**：提供方的回退（`fallbacks: "default"`）在拒答后会在另一个模型上把输入再算一次，带 PDF 的调用因此可能超过计数与预留，§7.8 原来"计入的输入从不超过预留"不准确。决定：**带文档的请求不带 `fallbacks`**，每次付费的 PDF 调用正好是计过数的请求；带 PDF 的请求被拒答时由已有的"推荐模型"重试在下一次尝试中处理（再计一次数）。只有文字的请求照常带回退、按估计，可能超过预留（日志告警）。§7.8 改为准确的说法。
- **M-b**：原来凡 400 提到 document 都算 `QB_AI_PDF_UNREADABLE`，会把我们自己的错误（字段校验）藏成"商家的文件坏了"。改为：带文档的请求，400 说请求过长（"prompt is too long"、"maximum of N PDF pages"、"too many pages"）即 `QB_AI_PDF_TOO_LONG`；只有说 PDF 本身无效、有密码、加密、损坏、读不了的才是 `QB_AI_PDF_UNREADABLE`；以字段路径开头的与其他 400 一律 `QB_AI_REQUEST_INVALID`。
  API 文档写明了限制（每次请求 600 页、1M 上下文时；32 MB）但没有给出这些错误的原文，所以按常见说法宽松匹配，上线前以真实的 API 对照一次（上线清单）。新增启动检查 `AiPdfLimitsCheck`：页数上限 × 5 000 加上最大文字输入与最大输出须放得进三个模型的上下文窗口（`ClaudeService.CONTEXT_WINDOW_TOKENS` = 100 万，约 179 页），且不超过提供方的 600 页。
- **L-a**：平台停止的作业，若每次尝试的预留输入超过 10 万 Token 且模型有长提示价格，按长提示价格计（`AiProcesses.stoppedUsage`）。**L-b**：`Usage.asLongPrompt` 不再把回退模型的部分也算进长提示部分，`AiCost.of` 另把长提示部分限制在请求模型自己的部分之内，每个 Token 只计价一次。
- **L-d**：§7.8 写明计数的 429 / 5xx 会用掉 3 次尝试中的一次（不计费）。**L-e**：封面与题目同时请求的测试重复 5 轮、每轮新商家，并断言至少一轮两个调用确实同时在途。**L-f**：IT 的桩与本地桩对带文档的付费调用报告与计数相同的输入用量（本地桩去掉计数不接受的字段再估算）。**L-g**：上线清单加一项：开启 PDF 之前以真实密钥手动调用一次 `count_tokens`。
- 细节：封面图改为单选按钮的 `<label htmlFor>`（不是 `div` 上的 `onClick`）；`QB_AI_NOTICE_MISSING` 的文案改为通用的"AI 生成"。
- 验证（复审修改之后）：`./gradlew :quizbuks:check :quizbuks:bootJar` 通过（717 个测试、0 失败；`platformCheck` 0 错误）；商家后台 lint、typecheck、171 个 Vitest，`app:check`，`ext:check`（74 个）；e2e 全部 54 个一次通过。（之后合并 Q7c 时改为 V18，见"迁移"。）
- 验证（合并 Q7c、迁移改为 V18 之后，2026-10-19）：`./gradlew :quizbuks:check :quizbuks:bootJar` 通过（807 个测试、0 失败，场景与快照无需改动；`platformCheck` 0 错误）；商家后台 177 个、App 148 个 Vitest，两个 SPA 的 lint、typecheck 与 `app:check`，`ext:check`（82 个）；e2e 全部 59 个一次通过。

### 平台 16v-1 之后（2026-10-19，分支 `1.2/quizbuks-sync-platform-16v1`）

平台 16v-1（PR #186，决策 D52 第 2 条）给了上面"偏离 3"与 M3"平台后续"所要的两项：作业类型的取消结局 `onCancelled`（含全部尝试的用量与"是否有尝试运行过"）与流程中的取消步骤 `CancelExternalJob`。改为：
- `qb.ai.questions` 与 `qb.ai.cover` 声明 `.onCancelled(QB_AI_JOB_CANCELLED)`（`internal()`，输入 `ExternalJobCancellation`）：在 `qb.ai:<商家>` → `qb.ai:*` 内关闭本地作业（`finishedAt`、用量、尝试次数；仍是 QUEUED 的即运维停止，记 `CANCELLED` / `QB_AI_STOPPED` 与 `closedAt`），
  按平台记下的**实际用量**计入作业与月用量（`AiProcesses.usage`：输入、输出、回退与长提示的部分照 `ClaudeCalls.record` 记下的，美元按 `AiCost`，回答的模型未知，同失败）；`attemptRan == false` 的不计费、不计入用量行。
- 删去 `QB_AI_JOB_STOPPED` 与订阅 `qb.ai-job-stopped`（`jabiz.external-job.finished`）与 `AiProcesses.stoppedUsage`：两者并存会对运维停止的作业计两次。
- `QB_AI_CANCEL` 在本地改为 CANCELLED 之后，按作业种类调用 `QB_AI_CANCEL_QUESTIONS_JOB` / `QB_AI_CANCEL_COVER_JOB`（`subProcessOnly()`，`qb.ai.use`），其中是平台步骤 `CancelExternalJob.of(类型, 作业号).ifOpen()`：
  步骤只认一种声明的类型（没有按条件执行），所以一种类型一个子流程；缺省的请求人检查保留（商家即请求人），QuizBuks 自己的本人检查（别人的作业 404）在前；本地作业的 `externalJobId` 即平台作业号（唯一约束 `uk_qb_ai_job_external`）。
  平台作业恰在此前结束时 `.ifOpen()` 不报错，由完成或失败流程照旧计入（本地已是 CANCELLED，不改状态）。
- **计费前后对比**：此前商家取消只改本地状态，排队中的作业照样调用一次提供方，完成或失败时按实际用量计入；运维停止按**完整预估**（3 次尝试的全部输入与输出）计入。之后排队中被取消的不调用提供方、不计费；运行中被取消的在本次尝试后结束，按实际用量计入；两种取消同一流程、只计一次。
- 测试：新增 `AiCancelIT`（商家取消排队的题目作业：平台作业由商家取消、提供方没有被调用、不计费、可再请求；封面作业经封面的子流程取消；运行中取消：回答丢弃、按尝试记下的用量计入一次、再运行与再投递不变、再取消 422；运维取消运行中的作业经同一流程计入）；
  改 `AiCostControlIT.switchingOffStopsNewRequestsAndAnOperatorStopsTheQueuedOnes`（运维停止排队的作业不计费）、`AiPdfIT.anOperatorsStopOfAQueuedPdfJobIsChargedNothing`（不计数、不计费、预留释放）、`AiQuestionsIT.discardAndCancelEndTheJob`（提供方不被调用、用量不变）；
  `AiPdfsTest.aCancelledJobIsPricedFromWhatItsAttemptsRecorded` 代替 `stoppedUsage` 的单元测试；`ProcessAccessIT` 与 `MessagesTest` 加新流程。
- 剩余：实例在尝试中途停机而没记下用量时，那次尝试计为零（失败的作业同样如此）。
- 同一同步分支的其他采用：退订页按入口（`jabiz.mail.unsubscribe-paths.sponsor` / `.app`，两个 SPA 挂载 `MailUnsubscribe`）、`@jabiz/ui/testing(/playwright)` 代替复制的 axe 辅助函数（后台扩展的测试暂留副本：平台的 `tsconfig.extension.json` 没有映射 `@jabiz/ui/testing`）、导出存档的 `GeneratedFile` 保留 10 年。
  合并平台后场景回放的设置编号没有变化，快照不需更新。
- 验证（2026-10-19，本机 PostgreSQL 16，自己的库与端口）：`./gradlew :quizbuks:check :quizbuks:bootJar` 通过（811 个测试、0 失败，场景 10 个；`platformCheck` 0 错误）；
  商家后台 lint、typecheck、178 个 Vitest；App lint、typecheck、149 个 Vitest；两个 SPA `app:check` 通过；后台扩展 `ext:check`（82 个测试）；e2e 全部 59 个通过（应用 18480，Claude 桩 18494、Stripe 桩 18493、Mailpit 18425/18426）。

### 待确认（需求方）

- **PDF 的预留与月上限**（偏离 3）：建议上线前以真实 PDF 测出每页实际 Token（`launch-checklist.md`），再选其一：A 提高 `qb.ai.sponsor.monthly-tokens`（例如 500 万）；B 降低 `QB_AI_PDF_MAX_PAGES`（例如 30 页，预留约 45 万）；C 两者都调。缺省值暂按计划（100 页、200 万）。
- **封面的风格**：图库的配色、版式与图形（`CoverDesigns`）请需求方过目；要插画或照片风格须改选 A-4 的 B（另一家图片模型与密钥）。

### 测试

- 单元：`AiCoversTest`（图库与设计的检查、提示与 Schema、每种配色 × 版式都画成 1200 × 630 的 PNG 且同一设计同一张图、断词、处理器把保留的设计画成作业文件、停机与画不出的标题不调用）、`AiPdfsTest`（评审后：上限、文档块在文字之前且是原样字节、计数的请求与发送的相同、没有回答时按计数计入、带 PDF 的预估与预留、提供方拒绝文档的分类、最小模型长提示按调用计价）。
- IT（真实 PostgreSQL、本地桩）：`AiCoverIT`（5 个：画出的图以商家为上传者按图片策略登记、1200 × 630 的 PNG、平台记下作业产生的文件；发送的只有标题与简介；模板按种类；选定经模版编辑写入、`aiGenerated`、只能选一次；未选的图由清扫删除、选定的保留；别人与别的模版 404、种类不符 422、过时的 revision、候选号越界 400；丢弃与过期清空引用、过期后的图被清扫；失败的作业不存文件、图库外与过长的设计被丢弃、只剩一个也可用；画不出的标题不调用不计费；封面与题目共用限额、AI 说明、权限）、
  `AiPdfIT`（2 个：PDF 作为文件输入登记并以文档块原样发送、预估按页数上限；过大 422 且不登记作业、页数过多 / 有密码 / 读不出的作业不发送不计费）；`AiQuestionsIT` 的输入上限用例改为 5 篇长文章（约 300 KB），2 篇（120 KB）现在可以发送。
- Vitest：`ai.test.tsx` 加封面面板（对话框与 AI 说明、按种类读、选定为模版编辑、纯文本、失败 / 过期 / 丢弃、读候选）、PDF 资料的说明与大小、亮暗两种外观的 axe；后台扩展的种类。候选图的 `alt` 为"封面 n：文字"（`Thumbnail` 加可选的 `alt`，列表中的封面仍是装饰）。
- e2e：`ai-cover.spec.ts`（亮、暗：AI 说明、生成封面、选第二张、基本信息显示新封面、列表 `aiGenerated`；再以文章与 PDF 生成题目，PDF 注明以文件发送；各处 axe）。

### 验证（2026-10-18，本机 PostgreSQL 16，自己的库与端口）

- `./gradlew :quizbuks:test`：704 个测试、0 失败（Q8a 之后为 663；新增 `AiCoverIT` 5、`AiPdfIT` 2、`AiCoversTest` 6、`AiPdfsTest` 4 等）；`./gradlew :quizbuks:check :quizbuks:bootJar` 通过（含 `platformCheck`：0 错误，警告与 Q8a 相同；`AttemptStatementBudgetIT` 未改动并通过）。
- 商家后台：`pnpm lint && pnpm typecheck && pnpm test`（171 个测试）；`pnpm app:check` 两个 SPA 均通过（答题 SPA 未改动）；后台扩展 `pnpm ext:check`（74 个测试）。
- e2e（打包的 jar、本地 Claude 与 Stripe 桩、Mailpit，库 `jabiz_e2e_q8b`，应用 18280、Claude 桩 18294、Stripe 桩 18293、Mailpit 18225/18226）：全部 54 个中 52 个第一次即通过；`ai.spec.ts` 的两个因后台 AI 页加了一列后 axe 报滚动区域不可聚焦而失败，改为在状态之上注明种类后，`ai.spec.ts`、`ai-cover.spec.ts` 与 `a11y.spec.ts`（22 个）重跑全部通过。
