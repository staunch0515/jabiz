# F-C2 资源占用的计量、显示与提示：实施计划（S3）

规格：`docs/quizbuks/first-launch-spec.md` F-C2（来源 Q110a §8，`Q94-review` §A23、§B、§D A4-23）。依赖 F-C1（资源库，F-C1a–d）。
首次上线**只计量、显示和提示，不拒绝上传、不收费**；不做存储收费、上传上限、变体字节、下载流量（规格"不做"）。

## 0. 需求方的决定（2026-11-07）

### 快照时刻是部署配置（做法 A）

规格原把快照时刻列为业务参数 `qb.usage.snapshot-time`。平台的任务（`core/src/main/java/com/jabiz/job/JobDefinition.java`）的 cron 与时区在 Bean 建立时给定：**按时区排程平台已经支持**（`forEach` 的 `ZoneId`），**按业务参数排程不支持**（调度器从不读业务参数）。
需求方 2026-11-07 选定做法 A：快照时刻是**部署配置** `quizbuks.usage.snapshot-time`（环境变量 `QB_USAGE_SNAPSHOT_TIME`，缺省 `03:17`，启动时校验为 `HH:mm`），任务以 `cron = 0 <分> <时> * * *`、`zone = jabiz.fiscal-year-zone` 建立（不写死 UTC）；改了要重启。规格 F-C2 的"参数"一行随本 PR 改写。
没有采用的：B（先在平台上加"时刻取自业务参数"）、C（每小时跑、项流程读参数决定做不做：绕开平台）。

### 已确认的四个选择（需求方 2026-11-07）

1. **资源库顶部显示的是最近一次快照**（附快照日期"截至 …"），不是实时数：计量不在请求路径上，页面只读快照表。今天新上传的文件次日才计入。
2. **`ai.tokens` 的每日值是当月累计**：该发起方在快照日所在月份（部署时区）`QbAiUsage` 的输入 + 输出 token（不含 `sponsorId = *` 的全体行）。AI 问题解答（F-C10）按规格记在它自己的 `QbHelpUsage`，从不进 `QbAiUsage`，因此天然不含；月度的"峰值"即该月最后一次快照的累计。
3. **`storage.*` 计发起方上传的全部文件**：平台 `sys_file` 中 `uploadedBy` = 发起方的每一行（任何策略），字节为原文件的 `sizeBytes`。一行就是一个文件，所以"同一文件被几处引用只算一次、按上传者归属"由此成立；尚未被平台清扫的孤儿文件也计入。
4. **读权限**：平台管理系统的用量页与模板用 `qb.admin.publication.read`（与资源库一栏相同：客服、内容与财务管理员、运营商成员）。

另：第一次越过免费额度的站内信照 CLAUDE.md"站内信"一条（个人消息只由事件的消费者写），快照项发现越过时发事件，由 `QB_MESSAGE_FROM_EVENT` 写消息（见 §5）。
`sys_file (uploaded_by)` 的索引由平台阶段 17zq 补上（不等它，见 §11）。

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
| `qb.admin.usage` | `qb.admin.publication.read` | 平台管理系统 / 运营商管理系统的用量页：按发起方 × 月 × 计量项给出天数、平均、峰值、月末值；筛选发起方、月份（`from`/`to`）与计量项；经默认数据视图，运营商只见辖下（`OperatorScopes`）；经平台的导出接口导出全部行（`POST /api/queries/qb.admin.usage/export`，页面给 CSV 与 Excel）。不声明 `report`（不进财务的报表页、不签发） |

月度的平均占用与峰值（规格"由模板算出，供以后收费用"）就是 `qb.admin.usage` 的列：平均 = 当月有快照的日子的平均，峰值 = 最大值，同时给出有快照的天数（缺天时看得出来）。

## 4. 任务与流程

- **任务 `qb.usage-snapshot`**：`JobDefinition.forEach`，每个发起方一项（`qb.system.usage-sponsors`，`maxItems` = `MAX_MAX_ITEMS`），cron 由部署配置 `quizbuks.usage.snapshot-time` 得出、时区为 `jabiz.fiscal-year-zone`（§0）。快照的日子 = 计划时刻在部署时区的日期（补跑以 `JobRunner.run(job, 计划时刻)` 指定哪一天）。
- **项流程 `QB_USAGE_SNAPSHOT_ITEM`**：`internal()`，权限 `qb.usage.system`（不授予任何角色）。**不取业务锁**，只读不写别人的表：
  1. `LoadParams` 读 `qb.usage.storage.free-bytes`、`qb.usage.retention-days`（以计划时刻读）；
  2. 一条 `RunTemplate`（`qb.system.usage-measure`）读出全部计量项；一条 `QueryEntities.all` 读该发起方当天已有的行、以及前一次快照（当天以前最近一天）的 `storage.bytes`；
  3. 计算（纯 Java `UsageRules`）：当天已有的行更新数值与 `recordedAt`，没有的插入（"当天已有的行即覆盖"）；超过保留期（`day < 快照日 − retention-days`）的本人行删除；
  4. 越过免费额度（前一次 ≤ 额度或没有前一次，今天 > 额度）时 `PublishEvent` `qb.usage.free-exceeded`（发起方、日子）。
- **参数**（`QbParams.ALL`；`QB_SETUP` 提出；项流程以 `LoadParams.optional` 读，受控的免费额度发布之前照常记快照、只是不提示）：`qb.usage.storage.free-bytes`（缺省 524288000 = 500 MB，**加入 `CONTROLLED`**，只用于提示）、`qb.usage.retention-days`（缺省 800）。读不出或不合格时：免费额度视为"未设"（不提示、不发信，页面只显示已用），保留期用声明的缺省值——不在流程里自己给缺省值以外的值（CLAUDE.md 第 4 节：缺少即 `SetupParamsCheck` 启动警告）。
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
- **补跑**（`operations.md` §7.22）：快照记的是运行那一刻的占用，只能补跑**当天**（过去的一天补不出当时的数字；月度统计按有快照的天数平均）。测试与开发中以 `JobRunner.run(job, 当天的另一时刻)` 重跑（同一计划时刻只会重放）；生产环境没有手工运行任务的接口，按 §7.22 把 `QB_USAGE_SNAPSHOT_TIME` 临时设到几分钟以后并重启。同一天的行被覆盖、不会重复；越过的消息按 `sourceKey` 不重复。失败的项由平台在之后的运行中重试（记的是那一天）。

## 7. 发起方页面（`quizbuks-web/sponsor/src/assets/`）

- **用量卡** `UsageCard.tsx`（资源库页顶部，所有标签都显示）：已用 X / 免费 Y（按 B / KB / MB / GB 格式化，一处函数 `usage.ts` 的 `formatBytes`）、进度条、文件数、资源数、"截至 <日期>"、注明"按原文件计，不含缩略图等变体"、"首次上线不收费"。还没有快照时显示"明天起显示"。
- **超过额度的横幅**：卡片上方的 `Alert`（只提示，上传照常），链到"未使用的资源"筛选。
- **列表**：读 `qb.sponsor.asset-stats` 给每个资源加"大小"（文件字节 + 文字字节；视频与网页链接为 0），`DataTable` 可按大小排序；筛选多一项"未使用的资源"（使用处个数为 0）。
- 文案 `assets.usage.*` 三语；ids 加两个模板。
- **测试**（`assets.test.tsx` 新的一组，与资源库共用同一个模拟服务器）：卡片的数字与格式、没有快照、超过额度显示横幅并转到未使用的资源、按大小排序、未使用筛选；`expectAccessible`（亮、暗）。原有用例照旧通过。站内信的"打开资源库"链接在 `notices.test.tsx`。

## 8. 平台管理系统扩展（`backend/quizbuks/admin-extension`）

- 新页 `UsagePage`（`/qb/usage`，菜单"用量"，持有 `qb.admin.publication.read` 时显示），只经 `@jabiz/admin`：
  月份范围（缺省本月）、发起方（`?sponsor=`）、计量项筛选；表格列：发起方（公司名，没有时以 `UserName` 显示名字）、月份、计量项（字典名）、有快照的天数、平均、峰值、月末值；字节类按 MB 显示；导出（平台的导出按钮，CSV / XLSX）。运营商管理系统同一页，只见辖下发起方（服务端范围）。
- 发起方详情页的"资源库"一栏加一行链接"用量"到 `?sponsor=<用户>`。
- 测试（`usage.test.tsx`）：列出（字节按 MB）、改月份与计量项、起止颠倒时提示且不能导出、导出调用、一个发起方与回到全部、空状态、发起方详情的"用量"链接、axe 亮暗；`ext:check`。菜单项按 `qb.admin.publication.read` 显示（运营商的成员也持有，所以两个管理系统共用一项）。

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
- `sys_file` 是平台表，只有 `uploaded_time` 的索引，**没有 `uploaded_by` 的索引**：每个发起方一项时，每项都整表扫一次 `sys_file`（发起方数 × 文件数）。首次上线的规模（数百发起方、数万文件）每天一次可以接受；规模变大时需要 `sys_file (uploaded_by)` 的索引：由平台阶段 17zq 补上（不在本条；应用的迁移不改平台表）。
- 快照的数字比实际晚最多一天；页面写明"截至"。
- 删除的功能：无。
