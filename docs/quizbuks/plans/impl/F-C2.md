# F-C2 资源占用的计量、显示与提示：实施计划（S3）

规格：`docs/quizbuks/first-launch-spec.md` F-C2（来源 Q110a §8，`Q94-review` §A23、§B、§D A4-23）。依赖 F-C1（资源库，F-C1a–d）。
首次上线**只计量、显示和提示，不拒绝上传、不收费**；不做存储收费、上传上限、变体字节、下载流量（规格"不做"）。

## 0. 开工前要答复的问题

### 问题 1（阻塞）：快照时刻 `qb.usage.snapshot-time` 不能作为业务参数驱动定时任务

规格把快照时刻列为业务参数（`qb.usage.snapshot-time`，缺省 03:17，按 `jabiz.fiscal-year-zone`），`Q94-review` §B 也要求"Q110 快照时刻"改为参数、不照抄"按 UTC 写死日本时间"的 cron。

平台现状（`core/src/main/java/com/jabiz/job/JobDefinition.java`）：

- 任务的 cron 与时区在 Bean 建立时给定，之后不变。**按时区排程平台已经支持**（`JobDefinition.forEach(名, cron, ZoneId, …)` 的第三个参数），QuizBuks 现有任务只是都写了 `ZoneOffset.UTC`。
- **按业务参数排程平台不支持**：业务参数有生效时间、经 `LoadParams` 在流程中按业务时间读出，调度器从不读它；改参数不会改变任务的时刻。

可选的做法：

| 做法 | 内容 | 与规格的差别 |
|---|---|---|
| **A（建议）** | 快照时刻改为**部署配置** `quizbuks.usage.snapshot-time`（环境变量 `QB_USAGE_SNAPSHOT_TIME`，缺省 `03:17`，启动时校验为 `HH:mm`），任务以 `cron = 0 <分> <时> * * *`、`zone = jabiz.fiscal-year-zone` 建立（不写死 UTC）。与现有各任务的 `quizbuks.*.run-cron` 同类，改了要重启。 | 不是 `qb.*` 业务参数（不进 `QbParams`、`QB_SETUP` 不提出、不能在线改） |
| B | 先在 `1.3/platform` 上给任务加"时刻取自业务参数"的能力（调度器读参数、参数变化后重排），合入后再做本条 | 规格写着"无平台改动"；平台多一个阶段 |
| C（不做） | 任务每小时跑一次，项流程读参数、未到时刻即什么也不做 | 是绕开平台的做法，已被指示不要用 |

**请需求方选 A 或 B。** 选 A 时规格 F-C2 的"参数"一行同时改为"快照时刻是部署配置"（本 PR 同时改规格）；本计划以下按 A 写，选 B 只改 §4 的 Bean 一处。

### 不阻塞、按下面的写法做（需求方不同意再改）

1. **资源库顶部显示的是最近一次快照**（附快照日期"截至 …"），不是实时数：计量不在请求路径上（规格），页面只读快照表。今天新上传的文件次日才计入。
2. **`ai.tokens` 的每日值是当月累计**：该发起方在快照日所在月份（部署时区）`QbAiUsage` 的输入 + 输出 token（不含 `sponsorId = *` 的全体行）。AI 问题解答（F-C10）按规格记在它自己的 `QbHelpUsage`，从不进 `QbAiUsage`，因此天然不含；月度的"峰值"即该月最后一次快照的累计。
3. **`storage.*` 计发起方上传的全部文件**：平台 `sys_file` 中 `uploadedBy` = 发起方的每一行（任何策略：资源、资料、题目、封面、头像、主页图片……），字节为原文件的 `sizeBytes`。一行就是一个文件，所以"同一文件被几处引用只算一次、按上传者归属"由此成立；尚未被平台清扫的孤儿文件也计入（它们确实占着空间，清扫后次日不再计）。
4. **"第一次越过"的站内信**：照 CLAUDE.md"站内信"一条（个人消息只由事件的消费者写），快照项发现越过时发事件，由 `QB_MESSAGE_FROM_EVENT` 写消息（见 §5）。
5. **读权限**：平台管理系统的用量页与模板用 `qb.admin.publication.read`（与资源库一栏相同：客服、内容与财务管理员、运营商成员）。

## 1. 计量项 `usage.UsageMeters`（唯一列出的地方）

包 `com.jabiz.quizbuks.usage`（新的模块名"用量"；计量不只是资源库的，也计 Quiz、版本、资料与 AI，所以不放进 `asset`）。

| 计量项 | 单位 | 含义 | 归谁 |
|---|---|---|---|
| `storage.bytes` | 字节 | 上传的文件的原文件大小合计（**不含缩略图等变体**，A4-23） | 上传者（`sys_file.uploadedBy`） |
| `storage.files` | 个 | 上传的文件个数 | 同上 |
| `assets.count` | 个 | 资源库中的资源个数（含视频链接、网页链接：外部视频只计个数，不计字节） | `QbAsset.sponsorId` |
| `assets.text-bytes` | 字节 | 资源的名称、说明、正文、奖品说明与译文的 UTF-8 字节 | 同上 |
| `content.quizzes` | 个 | 未移除的 Quiz | 内容的 `ownerId` |
| `content.versions` | 个 | Quiz 的版本（只追加，永久占用） | 同上 |
| `content.materials` | 个 | 资料（含视频链接资料，只计个数） | 同上 |
| `ai.tokens` | token | 当月的 AI 用量（上文第 2 条） | `QbAiUsage.sponsorId` |

- `UsageMeters.ALL`（有序、不可变）给出代码、单位与"存储类"标记；字典 `urn:jabiz:dict:quizbuks:usage-meter` 由它生成（三语名字在消息 `dict.…`）。
- 快照模板的 SQL 按计量项各出一行（见 §3）；`UsageMetersTest` 与启动检查 `UsageMetersCheck`（类别 `QUIZBUKS`，错误）核对模板的结果中出现的计量项与 `UsageMeters.ALL` 完全一致——新加一项必须同时改两处，否则启动失败。

## 2. 数据（迁移 `V78__quizbuks_usage.sql`，合并前按 `1.3/quizbuks` 的最大号 + 1 重编）

**`QbUsageDay`**（普通表 `qb_usage_day`，主键 `usage_day_id` UUIDv7）：

| 字段 | 说明 |
|---|---|
| `sponsorId` | 不可变，发起方的用户编号 |
| `day` | 不可变，`date`（`f.asDate()`）：快照所属的日子（部署时区） |
| `meter` | 不可变，`UsageMeters` 的代码，字典 `usage-meter` |
| `value` | `numeric(19,0)` |
| `recordedAt` | 最近一次写入的时刻（重跑同一天时更新） |
| `version` | |

- `UNIQUE (sponsor_id, day, meter)`（同时支撑"按发起方读一段日子"）；另建 `(day)` 索引给保留期删除与"当天是否缺少快照"的查询。
- 只经 `QB_USAGE_SNAPSHOT_ITEM`（与账号删除）写入：默认数据视图 `processOnlyWrites`，读权限 `qb.admin.publication.read`，登记 `OperatorScopes`（`sponsorId`）；`me:` 视图只读、限本人（`SPONSOR_ME`）。
- 普通表（可删除）：保留期删除与删除账号都要真删。

## 3. 模板

| id | 权限 | 用途 |
|---|---|---|
| `qb.system.usage-sponsors` | `qb.usage.system` | 任务的项：每个发起方（有 `QbSponsor` 记录、账号未删除）一行 `sponsorId` |
| `qb.system.usage-measure` | `qb.usage.system` | 一个发起方此刻的全部计量项：一条语句，每个计量项一行（`meter`、`value`）；参数 `sponsorId`、`at`（快照的时点，`ai.tokens` 的月份由它与时区得出） |
| `qb.sponsor.usage` | `qb.content.write` | 发起方的用量卡：最近一次快照的日期与各计量项、免费额度（`qb.usage.storage.free-bytes` 的当前值）、是否超过 |
| `qb.sponsor.asset-stats` | `qb.content.write` | 资源库列表用：每个本人资源的文件字节（经 `me:QbAsset` 连接 `SysFile`，只有本人资源引用的文件）与使用处个数（`me:QbAssetUse`）；"按大小排序""未使用的资源"据此在页面上做 |
| `qb.admin.usage` | `qb.admin.publication.read` | 平台管理系统 / 运营商管理系统的用量页：按发起方 × 月 × 计量项给出天数、平均、峰值、月末值；筛选发起方、月份（`from`/`to`）与计量项；经默认数据视图，运营商只见辖下（`OperatorScopes`）；带 `report: {period}`，经平台导出（CSV / XLSX / PDF） |

月度的平均占用与峰值（规格"由模板算出，供以后收费用"）就是 `qb.admin.usage` 的列：平均 = 当月有快照的日子的平均，峰值 = 最大值，同时给出有快照的天数（缺天时看得出来）。

## 4. 任务与流程

- **任务 `qb.usage-snapshot`**：`JobDefinition.forEach`，每个发起方一项（`qb.system.usage-sponsors`，`maxItems` = `MAX_MAX_ITEMS`），cron 与时区见问题 1（做法 A：`quizbuks.usage.snapshot-time`，`jabiz.fiscal-year-zone`）。快照的日子 = 计划时刻在部署时区的日期（补跑以 `JobRunner.run(job, 计划时刻)` 指定哪一天）。
- **项流程 `QB_USAGE_SNAPSHOT_ITEM`**：`internal()`，权限 `qb.usage.system`（不授予任何角色）。**不取业务锁**，只读不写别人的表：
  1. `LoadParams` 读 `qb.usage.storage.free-bytes`、`qb.usage.retention-days`（以计划时刻读）；
  2. 一条 `RunTemplate`（`qb.system.usage-measure`）读出全部计量项；一条 `QueryEntities.all` 读该发起方当天已有的行、以及前一次快照（当天以前最近一天）的 `storage.bytes`；
  3. 计算（纯 Java `UsageRules`）：当天已有的行更新数值与 `recordedAt`，没有的插入（"当天已有的行即覆盖"）；超过保留期（`day < 快照日 − retention-days`）的本人行删除；
  4. 越过免费额度（前一次 ≤ 额度或没有前一次，今天 > 额度）时 `PublishEvent` `qb.usage.free-exceeded`（发起方、日子）。
- **参数**（`QbParams.ALL`；`QB_SETUP` 提出）：`qb.usage.storage.free-bytes`（缺省 524288000 = 500 MB，**加入 `CONTROLLED`**，只用于提示）、`qb.usage.retention-days`（缺省 800）。读不出或不合格时：免费额度视为"未设"（不提示、不发信，页面显示"未设定"），保留期用缺省值并记告警日志——不在流程里自己给缺省值以外的值（CLAUDE.md 第 4 节：缺少即 `SetupParamsCheck` 启动警告）。
- **权限** `qb.usage.system` 加进 `QbPermissions`，不授予任何角色（与 `qb.stats.system` 同类）。

## 5. 站内信（第一次越过免费额度）

- 事件 `qb.usage.free-exceeded`（应用自己的名字），消费者 `qb.msg-usage-over` → `QB_MESSAGE_FROM_EVENT`，种类 `USAGE_OVER_FREE`（`QbInbox` 的常量）：
  - `sourceKey` = `usage-over:<发起方>:<越过的日子>`（唯一）：重跑同一天、重投事件都只有一条；回到额度以下再越过是新的越过，另发一条。
  - 消费者重读该发起方最新一天的 `storage.bytes` 与当前额度，仍超过才写；7 天前的事件跳过（现有规则）。
  - 文案 `qb.msg.usage-over.title|body` 三语，参数只有已用与额度（`MessageTexts` 格式化字节）；纯文本、不含他人的数据；链接到资源库（`/assets`）。
- 测试照"新的消息种类"一条：`InboxIT` 加用例。

## 6. 自动收尾与告警

- **保留期**：每天的项删掉本人超过 `qb.usage.retention-days` 的行（见 §4）。
- **删除账号**：`SponsorErasure` 登记 `ErasedEntity.delete(QbUsageDay, "sponsorId")`。
- **告警**（平台任务的指标，不另写代码）：`jabiz.job.run{job="qb.usage-snapshot", result="FAILED"|"PARTIAL"}`、`jabiz.job.item{job="qb.usage-snapshot", result="FAILED"}`；"当天缺少快照"= 计划时刻后 2 小时内没有成功的运行（`jabiz.job.run` 没有新值），另附只读 SQL（`qb_usage_day` 最大的 `day` 早于今天）。写进 `operations.md` §5 的表与新的 §7.22。
- **补跑**（`operations.md` §7.22）：以 `JobRunner.run(job, 那天的计划时刻)` 重跑某一天（同一天的行被覆盖、不会重复；越过的消息按 `sourceKey` 不重复），或等下一次运行；失败的项由平台按退避重试。

## 7. 发起方页面（`quizbuks-web/sponsor/src/assets/`）

- **用量卡** `UsageCard.tsx`（资源库页顶部，所有标签都显示）：已用 X / 免费 Y（按 B / KB / MB / GB 格式化，一处函数 `usage.ts` 的 `formatBytes`）、进度条、文件数、资源数、"截至 <日期>"、注明"按原文件计，不含缩略图等变体"、"首次上线不收费"。还没有快照时显示"明天起显示"。
- **超过额度的横幅**：卡片上方的 `Alert`（只提示，上传照常），链到"未使用的资源"筛选。
- **列表**：读 `qb.sponsor.asset-stats` 给每个资源加"大小"（文件字节 + 文字字节；视频与网页链接为 0），`DataTable` 可按大小排序；筛选多一项"未使用的资源"（使用处个数为 0）。
- 文案 `assets.usage.*` 三语；ids 加两个模板。
- **测试**（`usage.test.tsx`）：卡片的数字与格式、没有快照、超过额度显示横幅、按大小排序、未使用筛选；`expectAccessible`（亮、暗）。原有 `assets.test.tsx` 照旧通过。

## 8. 平台管理系统扩展（`backend/quizbuks/admin-extension`）

- 新页 `UsagePage`（`/qb/usage`，菜单"用量"，持有 `qb.admin.publication.read` 时显示），只经 `@jabiz/admin`：
  月份范围（缺省本月）、发起方（`?sponsor=`）、计量项筛选；表格列：发起方（`UserName` 显示名字）、月份、计量项（字典名）、有快照的天数、平均、峰值、月末值；字节类按 MB 显示；导出（平台的导出按钮，CSV / XLSX）。运营商管理系统同一页，只见辖下发起方（服务端范围）。
- 发起方详情页的"资源库"一栏加一行链接"用量"到 `?sponsor=<用户>`。
- 测试：列出、筛选、导出调用、没有权限不显示不请求、axe 亮暗；`ext:check`。

## 9. 帮助与运维文档

- `docs/quizbuks/help/storage-usage.md`：发起方帮助页一篇与 FAQ 一条（三语，对象 `SPONSOR`、分类 `PUBLISHING`）：免费额度是什么、怎样计（原文件、不含变体、同一文件只算一次、外部视频不占空间、每天更新一次）、**首次上线不收费**、超过只提示不拒绝、怎样清理（按大小排序、未使用的资源；删除资源不删已复制到 Quiz 与发布中的文件）。
- `operations.md`：§5 表加一行；新 §7.22"资源占用的计量（F-C2）"：参数、快照时刻、补跑、告警、保留期、删除账号、部署后要做的事（`QB_SETUP` 提出两个参数并发布受控的免费额度）。
- `backend/quizbuks/CLAUDE.md` 第 4 节加"用量"一条。

## 10. 测试

- `UsageRulesTest`（纯 Java）：覆盖已有行与新行的拆分、保留期的边界、越过的判断（没有前一次、前一次等于额度、回到以下再越过、额度未设）。
- `UsageMetersTest`：代码唯一、字典与消息齐全。
- **`UsageSnapshotIT`**（验收逐条）：
  - 同一文件被资源、资料、Quiz 封面几处引用只算一次；文件按上传者归属（别人上传、被本人引用的不计入本人）；
  - 重复跑同一天只覆盖（行数不变、数值更新）；
  - 超过免费额度只提示：上传照常成功，`qb.sponsor.usage` 给出 `over = true`，越过发一条站内信，同一越过重跑不再发；
  - AI 用量汇入：等于该发起方当月 `QbAiUsage` 的输入 + 输出，不含全体行；（AI 问题解答不写 `QbAiUsage`，按构造不含）；
  - 保留期删除；
  - 运营商只看到自己的发起方（`qb.admin.usage` 与默认数据视图）；
  - 删除账号删除快照（另在 `SponsorErasureIT` 加断言）；
  - 外部视频链接只计个数；
  - 项流程是 `internal()`、不取业务锁。
- 照旧通过并按需更新：`SetupIT`（多两个参数、受控一个）、`ScenarioTest`（快照随 `QB_SETUP` 改变，以 `-Dscenario.update-snapshots=true` 更新并核对差异）、`MessagesTest`、`BrandTest`、`OperatorScopesIT`、`SponsorErasureIT`、`InboxIT`、`AssetIT`、`AssetUseIT`、`:quizbuks:platformCheck`。
- 前端：发起方页面 `lint` / `typecheck` / `test`；扩展 `ext:check`；`node --test tools/quizbuks/terms/terms.test.mjs`。e2e 需要 Docker，本会话只做类型检查。

## 11. 风险

- 计量模板连接的表多（文件、资源、内容、版本、AI 用量），每个发起方一次读出；发起方很多时任务时间随之变长（`maxItems` 10000，每项一个事务）。以 `UsageSnapshotIT` 逐项断言数值。
- `sys_file` 是平台表，只有 `uploaded_time` 的索引，**没有 `uploaded_by` 的索引**：每个发起方一项时，每项都整表扫一次 `sys_file`（发起方数 × 文件数）。首次上线的规模（数百发起方、数万文件）每天一次可以接受；规模变大时需要平台在 `sys_file (uploaded_by)` 上加索引（平台改动，规格写着"无平台改动"，所以不在本条；应用的迁移不改平台表）。
- 快照的数字比实际晚最多一天；页面写明"截至"。
- 删除的功能：无。
