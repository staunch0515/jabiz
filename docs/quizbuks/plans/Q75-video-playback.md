# Q75 视频播放的校验："看完这个视频即可答题"真正要求看完（计划，2026-11-01；需求方 2026-11-01 确认并完成）

`04-product-direction.md` §5 的候选 A；需求方 2026-11-01 定下顺序 A → D → E，先出 A 的计划。编号 Q75 是建议（下一个空号），确认时可改。
需求方 2026-11-01 确认了 §9 的全部决定（缺省项），并定下**老数据不用管**：不回填已上线卡片的 `watch_required`（它们不要求看完），已有版本的"看完即可"视频没有时长也不受影响。一个 PR，工作分支 `1.3/quizbuks-75-video-playback`。实施记录见 §10。

## 0. 为什么

- Q19 把"看完这个视频即可答题"做成了**显示方式**，已知风险写着"平台不检查答题人是否看完视频"。Q63、Q65 之后视频在首页卡片与开始页上播放，但点"开始答题"仍与看没看无关。
- 需求方的理念（04 §2）：注意力有回报、按"有效认知"而不是曝光计价。要让商户的钱花在"看过的人"身上，先得知道谁看过、看了多久。
- 后面的候选都要它：完播奖励（B）没有它无从结算；时间点插题（C）、视频指标（J）、防刷加强（G）都读它记下的数据。

## 1. 现状

| 部分 | 现在 |
|---|---|
| 资料 | `QbMaterial.watchToAnswer`（Q19）：只有 YouTube / Vimeo 的视频链接可勾，每个模版一个；快照只在为真时写 `"watchToAnswer": true` |
| 卡片 | `QbPublicationCard.video`：上线时（`PublicationEditing.cardVideo`）与版本切换时（`PublicationVersions`）冻结播放器地址——先取"看完即可"的视频，否则取资料中第一个能播的视频（Q63）。卡片**不记**这个视频是不是"看完即可"的，也不记时长 |
| App | 整个 App 只有一个播放器 `video/stage.tsx`（Q65）：iframe 带 `enablejsapi=1&origin=…`（YouTube）或 Vimeo 的播放器，已经以 `postMessage` 发命令（`unMute`、`setMuted`、`play`）并监听回报（YouTube 的 `infoDelivery.muted`、Vimeo 的 `volumechange`）。首页居中的卡片静音自动播放，点卡片带声音接到开始页；开始页的"开始答题"随时可点 |
| 开始流程 | `QB_ATTEMPT_START`（`play.AttemptProcesses`）：输入 `PublicationRef(publicationId, language)`；锁外一条语句读本人会话、卡片、发布、匹配条件与轮次；开始锁内一条语句重读，再插入会话、更新开始计数。语句数由 `AttemptStatementBudgetIT` 断言确切值（开始共 11 / 13、锁内 3） |
| 商户后台 | 资料对话框的复选框"看完这个视频即可答题"（`content/MaterialsPanel.tsx`）；导出格式 `quizbuks.quiz` 版本 1 带可选字段 `watchToAnswer` |
| 测试 | `VideoEmbedsTest`、`WatchVideoIT`、`QuizSnapshotsTest`、e2e `watch-video.spec.ts`（播放器以桩页面回答，不访问 YouTube） |

## 2. 决定（草案，待确认）

| # | 决定 |
|---|---|
| D-Q75-1 | **"看完"由 App 从播放器的接口得知，服务器只按规则核对与记录**：YouTube 的 `infoDelivery`（`currentTime`、`playerState`、`duration`）与 Vimeo 的 `timeupdate` / `ended` / `play` 事件，都经已有的 `postMessage` 通道（不加载两家的脚本，内容安全策略不变）。服务器不能验证客户端的报告是真的；它能保证的是**诚实的客户端不看完不能开始**，并把报告记下来供 G、J 使用。这是与需求方讲明的边界（04 §7.4） |
| D-Q75-2 | **"看过的时间"怎么算**（`video/playback.ts`，纯函数）：播放中（状态为播放）每次时间回报与上一次之差计入"已看秒数"，但每次计入不超过墙上时钟流逝的 **2 倍**（允许 2 倍速，**快进跳过的不算**）；暂停、缓冲不计；播放器报告播完（YouTube 状态 0、Vimeo `ended`）记 `ended`。记录按发布保存在本标签页的 `sessionStorage`（换页不丢，关标签页即清，与开始的输出同一处） |
| D-Q75-3 | **哪里的播放算**：开始页上的播放都算（有声无声都算：静音是观看者的选择）；首页卡片上的静音自动播放**不算**，点卡片开声音并进入开始页之后的才算（Q65 的同一个播放器接着播，不从头）。理由：首页的自动播放是被动的，Q63 的目的是"先看视频学习"，学习发生在开始页 |
| D-Q75-4 | **合格的条件**：卡片要求看完（`watchRequired`）时，开始须满足 `ended`，或已看秒数 ≥ 时长 × **0.9**（片尾常被跳过）。卡片没有时长时只认 `ended`。不满足即 422 `QB_ATTEMPT_WATCH_REQUIRED`（带还差的秒数），**继续进行中的会话不查**（已经开始过） |
| D-Q75-5 | **时长来自商户**：勾"看完即可答题"的资料必须填时长 `durationSec`（1–7 200 秒）；对话框里的预览播放器读到时长就预填（YouTube `infoDelivery.duration`、Vimeo `getDuration`），读不到时商户手填。不信任答题者的客户端报告的时长，只记下来对照。**保存版本**时"看完即可"的视频没有时长即拒绝（`QB_MATERIAL_DURATION_REQUIRED`，同"版本只收完整的"）；已有版本不受影响（它们的卡片没有时长，按 D-Q75-4 只认播完） |
| D-Q75-6 | **取不到播放器的事件时放行并标记**：播放器开始播放后 8 秒内没有任何时间回报（浏览器扩展、隐私模式、Vimeo 的私密设置都可能），App 提示"无法确认播放进度"，仍可开始，报告 `playback.status = UNKNOWN`；服务器放行、会话记 `playbackStatus = UNKNOWN`。拒绝会误伤真实用户；`UNKNOWN` 的比例在 J 中给商户看，在 G 中作可疑信号。**待需求方选择**：另一个选项是拒绝（"请换浏览器"） |
| D-Q75-7 | **答题路径一条语句也不加**：卡片多两个冻结的列，开始锁外已读到；报告写进会话的插入（同一条语句）。`AttemptStatementBudgetIT` 的数字不变。不读浏览记录、不做"已看秒数不能多于打开页面以来的时间"之类的核对（首页的播放早于打开开始页，会误判） |
| D-Q75-8 | **只在开始时报告**：提交时不再报告（题目页上没有视频）。B（完播奖励）以后在自己的流程中报告 |
| D-Q75-9 | **记录的用途**：会话上的 `watchedSec`、`videoEnded`、`playbackStatus` 给支持人员（`qb.admin.attempts`）、以后给商户分析（J）；答题者自己看不到（没有意义）。这三列不是个人敏感信息，隐私政策不需改；嵌入播放器的说明照 Q19 |
| D-Q75-10 | **商户预览与审核不受约束**：商家后台的预览与后台扩展的版本预览只显示时长，不要求看完 |

## 3. 后端（`backend/quizbuks`）

### 3.1 迁移 V59 `quizbuks_video_playback`（Q73c 用了 V58）

```sql
ALTER TABLE qb_material_version ADD COLUMN duration_sec numeric(5, 0) CHECK (duration_sec IS NULL OR duration_sec BETWEEN 1 AND 7200);
ALTER TABLE qb_publication_card ADD COLUMN watch_required boolean NOT NULL DEFAULT false;
ALTER TABLE qb_publication_card ADD COLUMN video_duration_sec numeric(5, 0);
ALTER TABLE qb_attempt ADD COLUMN watched_sec numeric(6, 0);
ALTER TABLE qb_attempt ADD COLUMN video_ended boolean;
ALTER TABLE qb_attempt ADD COLUMN playback_status varchar(10) CHECK (playback_status IN ('REPORTED', 'UNKNOWN'));
```

三张都是普通表（可以加列、给缺省）。已上线的卡片：`watch_required` 由迁移按版本快照回填为真（`video` 来自 `watchToAnswer` 的资料的），
时长留空（D-Q75-5）。不加索引。

### 3.2 内容（`content`）

- `QbContent.MATERIAL_ENTITY` 加 `durationSec`（`asNumeric(5, 0)`，范围规则 `Rules.RANGE` 1–7 200，`ContentLimits.MAX_VIDEO_SECONDS = 7200`）。
- `MaterialProcesses`：输入加 `Integer durationSec`（只对 `VIDEO_LINK` 且非语言版本接受，否则 `QB_MATERIAL_FIELD_NOT_ALLOWED`）；`watchProblems` 不变。
- `QuizProcesses`（保存版本）：有 `watchToAnswer` 的资料没有 `durationSec` 即 `QB_MATERIAL_DURATION_REQUIRED`（`ContentCodes`，三语）。
- `QuizSnapshot.Material` 加 `Integer durationSec`；`QuizSnapshots` **只在 `watchToAnswer` 为真且有时长时**写 `"durationSec"`（其他快照逐字不变，哈希不变，同 Q19 的做法；`schema` 不变）。
- 复制模版、导出与导入（`content.transfer.QuizFile*`：格式版本 1 的可选字段 `durationSec`，缺省空）、内容包工具（Q36，同一格式）带上它。
- 翻译与语言版本（Q73）：时长只在基准资料上，语言版本照基准（`QuizLanguages.bases`）。

### 3.3 发布（`publishing`）

- `QbPublications` 卡片实体加 `watchRequired`（`asBool`，必填）与 `videoDurationSec`（`asNumeric(5, 0)`）。
- `PublicationEditing.cardVideo` 改为返回一个小记录 `CardVideo(player, watchRequired, durationSec)`：先取"看完即可"的视频（`watchRequired = true`，时长为其 `durationSec`），否则第一个能播的（`false`，无时长）；上线（`write`）与版本切换（`PublicationVersions`）都写三列。
- 商家预览（`sponsor/src/preview/model.ts`）按同一规则。

### 3.4 答题（`play`）

- `AttemptProcesses.PublicationRef` 加可选组件 `Playback playback`（record：`String status`（`REPORTED` / `UNKNOWN`）、`Integer watchedSec`（0–99 999）、`Boolean ended`、`Integer reportedDurationSec`）；都可选，旧客户端不带即视为没有报告。
- 纯 Java 规则 `play.WatchRules`：`required(card)`、`satisfied(card, playback)`、`shortfall(card, playback)`（还差的秒数）、`values(playback)`（写进会话的三列）。
- 开始：在"Continue an attempt in progress"之后、锁之前加一个计算步骤"Refuse an unwatched video"（只对新开始，`starting(ctx)`）：卡片 `watchRequired` 且报告不满足 D-Q75-4 → `ctx.reject(Violation("playback", PlayCodes.WATCH_REQUIRED, …, Map.of("shortfallSec", n)))`；`UNKNOWN` 放行（D-Q75-6）。不读任何新的行。
- 锁内 `values` 加 `watchedSec`、`videoEnded`、`playbackStatus`（卡片没有视频时三者为空）。同一条插入语句，预算不变。
- `QbPlay.ATTEMPT_ENTITY` 加三个字段（`processOnly`）；`StartOutput` 不变。

### 3.5 模板

- `qb.app.feed`、`qb.app.publication`：加卡片的 `watchRequired`、`videoDurationSec`（App 据此禁用按钮、显示进度）；`qb.public.publication`（分享页）同样加（未登录也能看到要求）。
- `qb.admin.attempts`（支持人员）加 `watchedSec`、`videoEnded`、`playbackStatus`。
- 商户分析的模板不改（J 另做）。

### 3.6 文案

- `QB_ATTEMPT_WATCH_REQUIRED`、`QB_MATERIAL_DURATION_REQUIRED` 三语；实体字段 `entity.QbMaterial.durationSec`、`entity.QbPublicationCard.watchRequired` / `videoDurationSec`、`entity.QbAttempt.watchedSec` / `videoEnded` / `playbackStatus`。

## 4. 前端

### 4.1 App（`quizbuks-web/app`）

- `video/playback.ts`（新，纯函数 + 存储）：`PlaybackRecord { watchedSec, ended, durationSec, lastTime, lastAt, status }`；`reduce(record, event, now)` 按 D-Q75-2；`load(publicationId)` / `save(...)`（`sessionStorage` 的 `qb:playback:<发布>`）；`toInput(record)` → 流程输入的 `playback`；`satisfied(record, card)` 与服务器的 `WatchRules` 同一规则（用例相同，`playback.test.ts` 与 `WatchRulesTest` 对照）。
- `video/stage.tsx`：监听扩展为 YouTube 的 `infoDelivery`（`currentTime`、`playerState`、`duration`）与 `onStateChange`，Vimeo 加 `addEventListener` `timeupdate`、`play`、`pause`、`ended`，并在就绪时 `getDuration`；只在舞台处于**开始页的槽位**或声音已开（D-Q75-3）时把事件交给 `playback.reduce`，记到当前发布的记录。8 秒没有时间回报即 `status = UNKNOWN`（D-Q75-6）。
- `pages/PublicationPage.tsx` 的 `Action`：卡片 `watchRequired` 时读本发布的记录——未满足：按钮不可用，旁边一行"看完视频后可开始（已看 m:ss / 共 m:ss）"随播放更新（`aria-live="polite"`，节流到每秒一次）；满足：按钮可用；`UNKNOWN`：按钮可用并提示"无法确认播放进度"。`useStartAttempt` 把 `toInput(record)` 放进 `QB_ATTEMPT_START` 的输入；服务器回 `QB_ATTEMPT_WATCH_REQUIRED`（客户端判断与服务器不一致时）即显示它的文案并保持在开始页。
- 首页卡片（`FeedCardView`）：`watchRequired` 的卡片在播放标记旁加小字"看完即可答题"（现有文案 `feed.watchToAnswer` 若已有则复用）。
- `qb/play.ts`：`FeedCard`、`PublicationView` 加 `watchRequired`、`videoDurationSec`；`Material` 加 `durationSec`；开始的输入类型加 `playback`。`sponsor/src/qb/` 同步（CI `diff -r`）。
- 退出登录清除 `sessionStorage` 的记录（与开始的输出同一处）。

### 4.2 商户后台（`quizbuks-web/sponsor`）

- `content/MaterialsPanel.tsx`：勾"看完这个视频即可答题"时出现"视频时长"（分:秒输入，保存为秒）；对话框内嵌该视频的播放器预览（不自动播放），读到 `duration` 即预填并注明"来自播放器"，商户可改；未填即前端先拦，服务端 `QB_MATERIAL_DURATION_REQUIRED` 兜底。资料列表在"显示在首页"旁显示时长。
- `content/api.ts`、`content/transfer.ts`：字段 `durationSec`。
- 版本页与预览（`preview/model.ts`）显示时长。

### 4.3 后台扩展（`backend/quizbuks/admin-extension`）

- `snapshot.ts` 加 `durationSec`；版本预览与审核页的资料行显示时长；答题记录页（Q14）加三列（`qb.admin.attempts`）。

## 5. 测试

- **单元**：`WatchRulesTest`（要求与否、`ended`、0.9 的边界、没有时长只认播完、`UNKNOWN` 放行、还差的秒数）；`QuizSnapshotsTest`（只有看完即可的视频写 `durationSec`，其他快照逐字与哈希不变）；`QuizFileFormatTest`（导出导入带时长、旧文件没有也能导入）。
- **集成**：
  - `WatchVideoIT` 扩展：勾选须有时长、非视频资料不接受时长、语言版本不接受；保存版本时没有时长即拒绝；上线后卡片 `watchRequired = true` 且带时长、普通视频的卡片 `false`；版本切换（Q51）后卡片随新版本；没有报告或看得不够即 422 `QB_ATTEMPT_WATCH_REQUIRED`（带 `shortfallSec`），`ended` 或 ≥ 0.9 即开始，`UNKNOWN` 开始并记状态；继续进行中的会话不查；会话的三列按报告写入、普通发布为空。
  - `AttemptStatementBudgetIT`：数字**不变**（开始带报告再跑一次确认）。
  - `AnswerSecrecyIT`、`SponsorInAppIT` 等不变。
  - 迁移的回填：Flyway 在测试 schema 上从 V1 跑到最新，V59 之前没有卡片，回填的 SQL 由一个专门的用例覆盖——先按旧形态（不带新列）插入一张"视频来自看完即可资料"的卡片与一张普通视频的卡片，再执行迁移中的同一段回填语句，核对前者 `watch_required = true`、后者 `false`。
- **场景回放**：`content` 场景的"看完即可"资料加时长，快照随之（只多一个字段）；其他场景不变。
- **前端**：`video/playback.test.ts`（与 `WatchRulesTest` 相同的用例；2 倍速的上限、快进不计、暂停不计、`UNKNOWN` 的 8 秒）；`stage.test.tsx`（两家播放器的消息解析、只在开始页或开声音后计入）；`PublicationPage` 的测试（禁用与进度、满足后可用、`UNKNOWN` 的提示、服务器的 422 文案；axe）；`play.test.ts`（输入带 `playback`）；商户后台 `content.test.tsx`（时长字段的出现、预填、校验）、`transfer.test.tsx`。
- **e2e** `watch-video.spec.ts` 扩展：桩播放器按 YouTube 的协议回答 `listening` 并每秒 `postMessage` 一次 `infoDelivery`（`currentTime` 递增、最后状态 0）；开始页的按钮先不可用、显示进度，播完后可用并进入第一题；另一个用例以 API（`request`）不带报告开始即 422。商户侧：勾选后填时长、预填来自桩的 `duration`。

## 6. 文档

- `02-design.md` §3.3（会话的三列）、§9（安全与反作弊：加"看完的核对只约束诚实的客户端"）；`03-plan.md` 加 Q75 一行；`04-product-direction.md` §5-A 指向本计划；
  `backend/quizbuks/CLAUDE.md` 的"答题"一节加一句（报告只在开始时、语句数不变、规则只在 `WatchRules`）。
- Q19 计划 §3 的风险"平台不检查答题人是否看完视频"标注"Q75 起核对客户端的报告"。

## 7. 估计

| 部分 | 天 |
|---|---|
| 后端：迁移、资料与快照、卡片、开始流程、模板、文案、IT | 1.5 |
| App：`playback.ts`、舞台的监听、开始页、首页标记、测试 | 1 |
| 商户后台与后台扩展：时长字段与预填、导出导入、预览、测试 | 0.5 |
| e2e、场景、文档 | 0.5 |
| **合计** | **3–3.5** |

## 8. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 高 | **客户端可以伪造报告**：脚本直接调用 `QB_ATTEMPT_START` 带 `ended: true` | 讲明边界（D-Q75-1）；G 以最短作答时间、频率与 `UNKNOWN` 比例标记可疑；B 的完播奖励开始前须先有 G |
| 中 | YouTube 的接口在某些浏览器扩展（广告拦截、脚本拦截）或隐私模式下不回报；Vimeo 设了域名限制或私密的视频不回报 | D-Q75-6 放行并标记；e2e 覆盖 `UNKNOWN` 路径；商户后台的预览读不到时长时提示"请手填" |
| 中 | iOS Safari 把视频接管为系统全屏：事件照常到达，但观看者回来时页面状态要正确 | 开始页的进度在全屏期间照常累计；真机在 TestFlight 上确认 |
| 中 | 标签页切到后台：YouTube 继续播（有声）或暂停（静音自动播放） | 以播放器的状态为准：播着就算，暂停不算；不另判断可见性 |
| 低 | 已上线的发布：卡片 `watchRequired` 回填为真但没有时长，老的 App（未刷新的 PWA）不带报告 → 422 | 文案清楚（"请刷新后看完视频再开始"）；Service Worker 更新经用户同意（平台），上线后几天内自然过渡；上线前在 staging 用旧版 App 试一次 |
| 低 | 2 倍速上限对 2 倍速以上的观看者是惩罚 | 需求方可改上限（`playback.ts` 与 `WatchRules` 各一处常量，测试用例相同） |
| 低 | 商户填错时长（过短或过长） | 预填来自播放器；过长只是让人多看；过短的下限由 0.9 与播完兜底 |

## 9. 需要需求方确认的

1. D-Q75-3：首页卡片的静音自动播放不算看过，只算开始页上的（与开声音后的）。
2. D-Q75-4：0.9 的比例；没有时长只认播完。
3. D-Q75-5：时长由商户填（预览预填），保存版本时必填。
4. D-Q75-6：取不到事件时放行并标记（缺省）还是拒绝。
5. 编号 Q75。

## 10. 实施记录（2026-11-01，PR #411）

- **决定**：§9 的四项按缺省确认；老数据不回填（§3.1 的回填语句与 §5 的回填用例随之取消：V58 只加列，`qb_publication_card.watch_required` 缺省 `false`）。
- **后端**：迁移 V58（`qb_material_version.duration_sec`，`qb_publication_card.watch_required` / `video_duration_sec`，`qb_attempt.watched_sec` / `video_ended` / `playback_status`）；
  `MaterialInput.durationSec`（只给基准语言的视频，1–7 200，`QB_MATERIAL_DURATION_RANGE`）、`QuizSnapshot.Material.durationSec`（快照只在"看完即可"的视频上写，其余逐字不变）、
  `QuizCompleteness` 的 `QB_MATERIAL_DURATION_REQUIRED`；复制、导入导出（`quizbuks.quiz` 可选字段 `durationSec`）与语言版本随之；
  `PublicationEditing.cardVideo` 改为返回 `CardVideo(player, watchRequired, durationSec)`，上线与版本切换都写三列（版本切换此前只取"看完即可"的视频，现在与上线同一规则：没有时也取第一个能播的）；
  `play.WatchRules`（规则、`Playback` 输入、会话的三列）、`AttemptProcesses` 的步骤 "Refuse an unwatched video"（锁外、只对新开始、只对 App 的输入；渠道的开始不受影响）；
  模板 `qb.app.feed` / `qb.app.publication` / `qb.public.publication` 多 `watchRequired`、`videoDurationSec`，`qb.admin.attempts` 多三列；`qb.app.publication` 的语言版本资料带上基准的 `durationSec`。
- **与计划的差异**：（1）违规的 `params` 不在接口的应答中（平台只返回 `field`、`ruleCode`、`message`），还差的秒数在文案 `QB_ATTEMPT_WATCH_REQUIRED` 中给出；（2）商家后台要以播放器预览读时长，`/sponsor` 的内容安全策略因此同 App 多 `frame-src` 两个播放器（`QuizbuksAppIT` 随之）；（3）`qb_material` 是时态表（`qb_material_version`）。
- **App**：`video/playback.ts`（纯函数的计数与规则、按发布的记录、`sessionStorage`，退出登录时随会话清除）；`video/stage.tsx` 监听两家播放器的时间、状态、结束与时长，只在开始页或开声音后计入（D-Q75-3），8 秒没有报告即 `UNKNOWN`；
  开始页的按钮在要求看完时禁用并显示进度（`detail-watch`），开始带 `playback`；"减少动态效果"（没有舞台）时以 `UNKNOWN` 开始；首页卡片标"看完即可答题"。
- **商家后台**：资料对话框的"视频时长（分:秒）"（`content/duration.ts` 解析 m:ss / h:mm:ss / 秒）与 `VideoDurationProbe`（勾选后内嵌预览，读到时长即预填，商户键入后不再覆盖；填错即先拦）；资料列表显示时长；导出带时长；预览的卡片带 `watchRequired` / `videoDurationSec`。
- **后台扩展**：`snapshot.ts` 的资料带 `watchToAnswer` 与 `durationSec`（只是类型与读取）。
- **测试**：`WatchRulesTest`、`QuizSnapshotsTest`、`QuizCompletenessTest`；`WatchVideoIT`（时长的规则、版本须有时长、卡片与公开模板的两列、开始的拒绝 / 放行 / 继续 / `UNKNOWN`、会话的三列、普通视频不要求）；`AttemptStatementBudgetIT` 不变；
  App `video/playback.test.ts`、`Play.test.tsx`（进度与禁用、快进不计、首页静音播放不计、播完、`UNKNOWN`、普通发布不带报告）；商户后台 `content.test.tsx`、`duration.test.ts`、`preview/model.test.ts`；e2e `watch-video.spec.ts`（桩播放器按 YouTube 的协议回报）。
