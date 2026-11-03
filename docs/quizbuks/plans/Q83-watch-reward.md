# Q83 完播奖励：看完视频即得少量奖励（计划，2026-11-03 第二稿，待需求方确认 §9）

`05-console-requests.md` 第八项；内容取自 `04-product-direction.md` §5-B（候选 B）、§1 第 26 项（需求方原话"不答题，只要看完视频就可以少量学分。这些学分也是发视频的出"）、§3 场景 S17。
第二稿按需求方 2026-11-03 的原则：**每条路都修出来，开、关、缺省都是受控参数（四眼修改），由管理人员定，代码不决定什么可以、什么不可以**。
依赖的 Q75（播放校验，#411）已完成。**这只是计划**：确认 §9 后开发，一个 PR，工作分支 `1.3/quizbuks-83-watch-reward`，迁移 V64。

## 0. 为什么

- 理念"注意力要有回报：完播低保 + 答题暴击"（04 §2）：商户想让人看完一段视频（新品、店铺介绍），愿意为"看完"付一点；答题者不想答题时也能拿到一点。
- 现在只有"看完才能答"（Q75）：视频是答题的前提，奖励仍取决于答题。完播奖励把视频本身变成发布的全部内容。

## 1. 现状

- Quiz 的种类 `kind`：`QUIZ`（计分）/ `SURVEY`（问卷，Q12：完整回答即"合格"、只有一档奖励，D-Q12-4），建模版时选定、不可变，冻结到版本与卡片（V19 的 `CHECK (kind IN ('QUIZ','SURVEY'))`）。快照以 `survey` 布尔区分。
- Q75：资料可标"看完这个视频即可答题"，卡片冻结 `video`、`watchRequired`、`videoDurationSec`；开始时 App 交播放报告，`WatchRules.satisfied` 判断（看完结尾或看了时长的 90%，`UNKNOWN` 放行），报告记进会话。
  **服务器无法证明报告是真的**（D-Q75-1）：对"看完才能答"这只是挡住诚实的客户端；若奖励只取决于这份报告，伪造一个请求就能领钱。
- 防刷（04 §5-G）尚未做；已有的门：已验证邮箱（全部写入）、一人一次（唯一约束）、名额与预算、限流（开始每人每分钟 60）、每次答题的最高奖励 `qb.reward.max-per-attempt`。

## 2. 决定（草案）

| # | 决定 |
|---|---|
| D-Q83-1 | **新的种类 `WATCH`**（与 `QUIZ`、`SURVEY` 并列，字典加值，迁移放宽三处 `CHECK`）。建模版时选定、不可变。内容：**没有题目**，恰好一条视频资料（YouTube / Vimeo，Q19 的两种播放器），必须标"看完"并填时长 `durationSec`（Q75 的规则）；可有封面、标题、简介，不能有其他资料（文章、PDF 只会被跳过）。版本的完整性检查（`QuizCompleteness`）按种类：`WATCH` 不要题目、要这条视频。翻译（Q73）照常只译文字；视频不按语言替换（第一步）。 |
| D-Q83-2 | **奖励与名额照旧**：一档奖励、及格线 = 满分 = 0 分（开始后满足 D-Q83-3 即"合格"），每人一次，预算、名额、审核、暂停、到期、统计、推荐、前置条件都照旧。`QB_TIER_NO_REWARD`（至少一档 > 0）照用。**单次奖励另有上限**：受控参数 `qb.watch.max-reward`（缺省 10 Kudos，与 `qb.reward.max-per-attempt` 取较小者），提交发布时检查（`QB_WATCH_REWARD_TOO_HIGH`）。 |
| D-Q83-3 | **合格的条件以服务端的时间为准**：`WATCH` 的"开始"就是按下播放（`QB_ATTEMPT_START`，不要求先看完），"领取"是 `QB_ATTEMPT_SUBMIT`（空答卷）。提交时须同时满足：①服务端记下的开始到提交**至少过了时长的 90%**（`WatchRules.needed`，按卡片冻结的时长；伪造请求也得真等这么久）；②App 的播放报告满足 `WatchRules.satisfied`；读不到播放器（`UNKNOWN`）时能否领取是受控参数 `qb.watch.accept-unknown`（缺省 false：不能领，页面说明原因）。服务端计时也是受控参数 `qb.watch.server-timing`（缺省开）。不满足即 422 `QB_WATCH_NOT_FINISHED`（带还差的秒数），会话照常进行、可再领。时限 = 时长 × 2 + 5 分钟（超时即按"未看完"结束，不发奖）。 |
| D-Q83-4 | **防刷的账号门槛**：受控参数 `qb.watch.min-account-days`（缺省 7，0 即不限）：注册（资料建立）不满 N 天的账号开始 `WATCH` 即 422 `QB_WATCH_ACCOUNT_TOO_NEW`；首页与详情照样显示，按钮说明何时可领。已验证邮箱、一人一次照旧。§5-G 的其余防刷（同地址多账号、可疑列表）不在本阶段。 |
| D-Q83-5 | **总开关**：受控参数 `qb.watch.enabled`（缺省 false）。关着时商户不能建 `WATCH` 模版、不能提交 `WATCH` 发布（`QB_WATCH_UNAVAILABLE`），已上架的照常运行到结束。代码合入、部署后由管理人员决定何时打开。 |
| D-Q83-6 | **奖励方式**：四种都做出来（平台 Kudos、现金、到店领取、电子奖品），`WATCH` 允许哪几种是受控参数 `qb.watch.reward-methods`（缺省四种都允许），并照旧受商户允许的方式约束。 |
| D-Q83-7 | **答题路径的语句数**：`WATCH` 的开始不多读（账号天数取自开始在锁外已读的本人资料 `createdAt`）；提交的两条判断用已在锁内重读的会话与卡片，不增加语句。普通发布的预算不变（`AttemptStatementBudgetIT` 加一个 `WATCH` 的提交用例）。 |
| D-Q83-8 | **商户可见的统计**：完播人数即完成人数（已有）；分析页的题目统计对 `WATCH` 不显示。会话的播放报告照 Q75 记录，后台扩展的会话页可见（给以后的防刷）。 |

## 3. 后端（`backend/quizbuks`）

### 3.1 迁移 V64 `quizbuks_watch`

- `qb_quiz`、`qb_quiz_version`、`qb_publication_card` 的 `kind` 检查约束加 `WATCH`。没有新表、没有新列（时长、视频已在卡片上，Q75）。

### 3.2 改动

- `content`：`QbContent.KIND_WATCH` 与字典；`QuizCompleteness`（`WATCH`：0 题、恰好一条标"看完"且有时长的视频，`QB_WATCH_VIDEO_REQUIRED` / `QB_WATCH_NO_QUESTIONS`）；`QuestionProcesses` 拒绝给 `WATCH` 加题；`QuizSnapshot` 以 `kind` 取代只看 `survey`（`watch` 为真时快照多一个 `"kind":"WATCH"`，旧快照逐字不变、哈希不变）；导入导出（`QuizFileFormat`）认这个种类；AI 出题对 `WATCH` 不可用。
- `publishing`：`PublicationReadiness.watch`（一档、及格线 0、奖励上限、`pickCount` 不可用、开关）；卡片照旧冻结；`PublicationApprovals` 的内容含种类（已有）。
- `play`：`AttemptRules` / `Scoring`：`WATCH` 的开始不查 Q75 的"先看完"、查账号天数；提交查 D-Q83-3 的两条（`WatchRules.claimable(durationSec, startedAt, now, playback)`，纯 Java）；时限按 D-Q83-3；超时结束为不合格。`SubmitInput` 加可选的 `playback`（与开始同形）。
- `setup`：`QbParams` 加六个受控参数（`qb.watch.enabled`、`qb.watch.max-reward`、`qb.watch.min-account-days`、`qb.watch.accept-unknown`、`qb.watch.server-timing`、`qb.watch.reward-methods`），进 `ALL` 与 `CONTROLLED`。
- 模板：`qb.app.feed` / `qb.app.publication` 给出 `kind`（已有）与 `watchClaimableAt`（详情：账号满 N 天的时刻）；`qb.sponsor.*` 照旧；分析的题目模板对 `WATCH` 返回空。
- 文案三语：种类名、错误码、参数名。

## 4. 前端

- **商户后台**：建模版时可选"看完即得"（开关关着时不显示）；编辑器对 `WATCH` 只显示基本信息与一条视频（时长必填，"看完"固定勾选）；发布向导一档奖励、没有及格线与抽题，说明单次上限；列表与预览标种类。
- **App**：首页卡片标"看完即得 · n Kudos"；详情页即播放器：按播放 = 开始（`QB_ATTEMPT_START`），播放中显示进度，满足条件后出现"领取"（`QB_ATTEMPT_SUBMIT` 带播放报告），不满足时显示服务端给的还差秒数；读不到播放器（`UNKNOWN`）时说明不能领取的原因；账号太新时说明何时可领。结果页只显示领到的奖励（没有题目）。历史标种类。
- **后台扩展**：版本预览与发布审核显示种类与视频；会话页显示播放报告（已有字段）。

## 5. 测试

- core：`WatchRulesTest` 加 `claimable`（时间不足、`UNKNOWN`、报告不足、刚好够）；`QuizCompletenessTest`、`PublicationReadinessTest` 的 `WATCH` 用例。
- IT：`WatchRewardIT`（开关关着时不能建与提交；账号太新不能开始；按下播放即开始、时间未到领不了、报告 `UNKNOWN` 领不了、满足后领到并入账；超时不合格；一人一次；单次上限；四种奖励方式之一）；`AttemptStatementBudgetIT` 加 `WATCH` 提交；场景回放加一个 `watch.yml`；`SetupIT` 参数。
- 前端：商户后台的建模版与向导、App 的卡片与播放领取页，axe。
- e2e：`watch.spec.ts`（商户建 `WATCH` 并发布 → 答题者看完领取；播放器用桩的时长）。

## 6. 文档

- `02-design.md`（种类、§7.1 / §7.2）、`backend/quizbuks/CLAUDE.md` 第 4 节"完播奖励"一条、`operations.md`（三个受控参数）、`05-console-requests.md` 第八项与 `03-plan.md` 的 Q83 行；本文件 §10 实施记录。
- **产品文本**：条款（完播奖励的条件：看完、账号满 N 天、读不到播放器时按开关）与有奖活动规则的补充由需求方给出。

## 7. 估计

| 部分 | 天 |
|---|---|
| 迁移、种类、完整性、快照、发布检查、参数 | 1 |
| 开始与领取的规则、时限、统计、模板 | 1 |
| 商户后台（建模版、编辑器、向导） | 0.75 |
| App（卡片、播放领取页、结果） | 0.75 |
| 后台扩展、测试、e2e、文档 | 0.5 |
| **合计** | **4** |

## 8. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 刷量：服务器不能证明看了 | 服务端计时（D-Q83-3：伪造也得等满时长）、`UNKNOWN` 缺省不收、账号天数、一人一次、单次上限、限流；§5-G 另做 |
| 中 | 单价很低，多数人攒不到提现门槛 | 卡片与结果页说明 Kudos 累积用途；不改门槛 |
| 低 | 播放器读不到（扩展、私密窗口）的人领不到 | 页面说明原因与解决办法（换浏览器、关扩展） |
| 低 | 快照多一个字段 | 只在 `WATCH` 时写，旧快照与哈希逐字不变 |

## 9. 需要需求方确认的

1. D-Q83-5：代码合入、部署，总开关 `qb.watch.enabled` 缺省关，何时打开由管理人员定。
2. D-Q83-3：领取以服务端计时（开始到领取 ≥ 时长的 90%）与播放报告为准；计时与"读不到播放器能否领"都是开关，缺省为计时开、读不到不能领。
3. D-Q83-4：账号满 N 天才能领，缺省 7 天（0 即不限）。
4. D-Q83-2：单次奖励上限缺省 10 Kudos。
5. D-Q83-6：四种奖励方式都做出来，允许哪几种由开关定，缺省四种都允许。
6. 第一步只支持一条视频、不按语言换视频；条款与有奖活动规则的补充文本由需求方给出。
7. 编号 Q83，一个 PR，约 4 天，迁移编号以合入时为准。
