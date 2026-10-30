# Q22 奖励的三种支付方式：平台代付、商家付现金、商家处兑换

需求方 2026-10-24：奖励（Kudos）有三种支付方式——(1) 平台支付（现状），(2) 商家直接付现金，(3) 商家处线下兑换（答题人扫商家的码领奖）。
1) 管理员按商家设定允许哪几种（一到三种）；2) 商家按发布选一种，线下兑换要给出等值 Kudos 与说明；3) 现金另行记录：商家标记已付、答题人须确认，双方都确认即完成；
答题人一段时间内不确认也不争议 → 禁止开始新的答题；4) 兑换：合格后答题人扫商家的码领奖，扫码即完成；5) 答题人首页可按三种方式筛选；6) 其余相关事项由实现方补齐。

确认（2026-10-24，"全部同意"）：现金金额就是 Kudos 的数字（JPY）；平台不收费、不入账；缺省期限：答题人确认 7 天、商家付款 14 天、兑换 30 天（非受控参数，管理员改）；
兑换缺省用商家后台**轮换的码**，另可打印固定码；设定允许方式的是单个管理员（不走四眼）。

分两个 PR：**Q22a**（本文）= 方式的模型、管理员设定、发布的方式、首页筛选、现金的全套流程、兑换的登记；**Q22b** = 线下兑换的领取（扫码）、商家的兑换屏幕。

## 1. 做法（Q22a）

| 部分 | 内容 |
|---|---|
| 方式 | `publishing.RewardMethod`：`PLATFORM` / `SPONSOR_CASH` / `OFFLINE_REDEEM`，字典 `urn:jabiz:dict:quizbuks:reward-method`。商家缺省只有 `PLATFORM` |
| 商家允许的方式 | 时态实体 `QbSponsorRewardMethods`（`userId` 唯一，`methods` 为按固定顺序的逗号分隔文本，`note`、`setBy`、`setAt`）。只经流程 `QB_SPONSOR_REWARD_METHODS_SET`（权限 `qb.admin.reward-methods`，1–3 种，锁商家账户锁）写入；没有记录 = 只有 `PLATFORM`。商家经限本人的视图 `sponsor:QbSponsorRewardMethods` 读自己的 |
| 发布 | `QbPublication` 与 `QbPublicationCard` 新字段 `rewardMethod`（必填）、`redeemDescription`（兑换说明，至多 500 字，只在 `OFFLINE_REDEEM` 时写）。保存、复制与提交都核对商家此刻允许的方式（422 `QB_REWARD_METHOD_NOT_ALLOWED`）；兑换缺说明在就绪检查中报 `FIELD_REQUIRED`；审核请求的事实与内容带方式（改方式即重审） |
| 结算 | `AttemptFinishing`：方式不是 `PLATFORM` 时**不取钱包锁、不读钱包、不过账**（`AttemptRules` 不再要求商家可支付；首页与详情的"商家可支付"条件只对 `PLATFORM`）；计数照常记获奖人数与 `spent`（等值）。`QbAttempt.rewardStatus` 多 `CASH` / `REDEEM`。现金：插入 `QbCashReward`（`DUE`，`payBy = now + qb.reward.cash.pay-days`），事件 `qb.reward.cash-due`；兑换：插入 `QbRedemption`（`DUE`，`expiresAt = max(now + qb.reward.redeem-days, 发布结束)`，说明取自卡片），事件 `qb.reward.redeem-due`。两者都有人认得的码 `C-<会话编号末 8 位>` |
| 现金记录 | 时态实体 `QbCashReward`（`reward` 包）：状态 `DUE → MARKED_PAID → CONFIRMED`；`DISPUTED`（答题人任何未结状态都可提出，须附说明）；`OVERDUE`（标记已付后过了 `confirmBy` 仍未确认）；`VOID`。流程：商家 `QB_CASH_REWARD_MARK_PAID`（`confirmBy = now + qb.reward.cash.confirm-days`，附注至多 200 字）；答题人 `QB_CASH_REWARD_CONFIRM`（`MARKED_PAID` / `OVERDUE` → `CONFIRMED`）、`QB_CASH_REWARD_DISPUTE`；管理员 `QB_CASH_REWARD_RESOLVE`（权限 `qb.cash.resolve`：`PAID` → `CONFIRMED`，`UNPAID` → 回到 `DUE` 重新付，`VOID`）。争议建待办 `qb.cash-reward.dispute`（权限 `qb.cash.resolve`）；到付款期限未付建待办 `qb.cash-reward.pay`（商家） |
| 定时任务 | `qb.cash-reward-overdue`（每小时）：`MARKED_PAID` 过了 `confirmBy` → `OVERDUE`；`qb.cash-reward-remind`（每日 01:20 UTC）：`DUE` 过了 `payBy` 且未提醒 → 记 `remindedAt`、商家待办与消息。都是 `JobDefinition.forEach` 的 `internal()` 流程 |
| 禁止新答题 | `QB_ATTEMPT_START` 在封禁检查之后 `CashRewardChecks.refuseOverdue`：本人有 `OVERDUE` 的现金记录即 422 `QB_CASH_CONFIRM_DUE`（确认或争议后解除） |
| 消息 | 站内信（Q7c）：答题人收到现金待付 / 商家已付请确认 / 已逾期 / 裁定结果 / 兑换待领；商家收到答题人已确认、提出争议、裁定结果（商家后台的收件箱现只显示公告，商家的这些消息在平台通用后台可见，Q22b 一并补商家收件箱） |
| 模板 | `qb.app.feed` 多参数 `method` 与列 `rewardMethod`；`qb.app.publication`、`qb.public.publication` 多 `rewardMethod`、`redeemDescription`；`qb.app.history` 带现金 / 兑换的状态；新 `qb.app.cash-rewards`（本人未结的）、`qb.sponsor.cash-rewards`、`qb.admin.cash-rewards`；`qb.admin.sponsors` 多 `rewardMethods`；`qb.sponsor.publications` 多 `rewardMethod` |
| 参数 | `qb.reward.cash.confirm-days`（7）、`qb.reward.cash.pay-days`（14）、`qb.reward.redeem-days`（30）：普通业务参数（不受控），`QB_SETUP` 建立 |
| 迁移 | `V24__quizbuks_reward_methods.sql`：两列加到发布版本与卡片（卡片按 `(reward_method, status)` 索引），`qb_attempt` 的奖励状态检查放宽，新时态表 `qb_sponsor_reward_methods_version`、`qb_cash_reward_version`、`qb_redemption_version` |
| 商家后台 | 向导第一步在有多于一种可选时显示方式的单选（`publication-method`），兑换时要求说明（`publication-redeem`）；概要与列表显示方式；新页 `/cash-rewards`（待付现金：搜索、按状态筛选、"标记已付"对话框） |
| App | 首页顶部分段控件按方式筛选（记在本机 `qb.feed.method`），卡片带方式徽章；有未结现金时首页提示并链到结果页；详情页显示方式与兑换说明；结果页：现金 → 码、状态、"我收到了" / "有问题"（附说明）；兑换 → 说明、码、有效期（领取在 Q22b）；历史带状态 |
| 后台扩展 | 商家审核页多"允许的支付方式"面板（勾选、附注、保存）；新页 `/qb/cash-rewards`（全部现金记录、争议的裁定对话框）；菜单项 |

`src/qb/` 的 `play.ts`、`ids.ts` 两处相同。

## 1b. 做法（Q22b：到店领取）

确认（2026-10-24，"开始"）：码存表（可撤销、一码一次只对一位商家）；固定码的领取须商家确认，轮换码即时完成；领取是终局（商家在场，不设争议）；管理员只能作废未领取的奖品。

| 部分 | 内容 |
|---|---|
| 商家的码 | 普通表 `qb_redeem_token`（实体 `QbRedeemToken`：`sponsorId`、`tokenValue`（敏感，数据视图不返回；名字含 token，平台按名遮蔽）、`kind` ROTATING / PRINTED、`issuedAt`、`expiresAt`、`revokedAt`）。只经 `QB_REDEEM_CODE_ISSUE`（商家，`qb.publication.write`）写入：ROTATING 有效 2 分钟（`QbRewards.ROTATING_TOKEN_LIFE`），每次签发顺带删除本商家已失效的轮换码；PRINTED 长期有效，再次请求返回同一个，`renew: true` 作废旧码并签发新码。随机数经平台的阻塞步骤 `RandomBytesStep`（192 位，URL 安全 Base64），只在该流程的输出 `redeemKey` 中出现一次 |
| 码的内容 | App 链接 `<站点>/redeem/<redeemKey>`：任何手机相机扫码即打开；未登录先登录再回到该页（`next`）。~~App 中不写扫码代码~~（Q52 起 App 另有"扫一扫"，`plans/Q52-scan.md`：系统相机打开的是浏览器，主屏幕上的 App 与商店 App 在那里没有登录） |
| 核对 | `QB_REDEEM_TOKEN_CHECK`（答题人，`qb.me`，输入 `scannedKey`）：码此刻有效（未失效、未作废，否则 422 `QB_REDEEM_TOKEN_INVALID`）→ 商家公司名、码的种类与本人在该商家未领取的奖品（`DUE` / `CLAIMED`，超过 `confirmBy` 的申请按 `DUE` 显示） |
| 领取 | `QB_REDEMPTION_CLAIM`（答题人，输入 `scannedKey` + `redemptionId`）：奖品锁内经答题人视图读（他人 404），码须有效且属于奖品的商家（422 `QB_REDEEM_TOKEN_SPONSOR`），状态须为 `DUE`（含超时的申请）。轮换码 → `REDEEMED`（`redeemedBy` = 商家，事件 `qb.redemption.redeemed`）；固定码 → `CLAIMED`（`confirmBy = now + 10 分钟`，事件 `qb.redemption.claimed` → 商家收件箱）。2026-10-29 起，轮换码也接受 `CLAIMED`：固定码的申请还在等商家确认时，扫屏幕上的轮换码即领取（人在柜台前，不必等确认或十分钟），原申请随之结束 |
| 商家的回答 | `QB_REDEMPTION_CONFIRM`（`CLAIMED` 未超时 → `REDEEMED`，`redeemedBy` = 操作人；超时即 422 `QB_REDEMPTION_CLAIM_LAPSED`）、`QB_REDEMPTION_DECLINE`（→ `DUE`，事件 `qb.redemption.declined` 告知答题人）。都经商家视图读（他人 404） |
| 作废与过期 | `QB_REDEMPTION_VOID`（管理员，权限 `qb.redemption.void`，`DUE` / `CLAIMED` → `VOID`，附注）；任务 `qb.redemption-expire`（每日 02:10 UTC，`DUE` 过了 `expiresAt` → `EXPIRED`）与 `qb.redemption-lapse`（每十分钟，`CLAIMED` 过了 `confirmBy` → `DUE`），都是 `forEach` 的 `internal()` 流程 |
| 状态 | `RedemptionStatus`：DUE → CLAIMED / REDEEMED / EXPIRED / VOID；CLAIMED → REDEEMED / DUE / VOID。`QbRedemption` 新字段 `claimKind`、`claimedAt`、`confirmBy`、`redeemedBy`、`voidedBy`、`voidNote`（迁移 `V25`） |
| 消息 | 站内信种类 `REDEEM_CLAIMED`（商家，动作 `REDEEM` → 领奖页）、`REDEEM_REDEEMED` / `REDEEM_DECLINED` / `REDEEM_EXPIRED` / `REDEEM_VOID`（答题人 → 结果页）。**商家后台的收件箱**（`qb.sponsor.inbox`）现在同时列出商家自己的消息（现金与奖品）与平台公告（Q22a 遗留项） |
| 模板 | `qb.sponsor.redemptions`（搜索、状态、`since`）、`qb.app.redemptions`（`open`）、`qb.admin.redemptions`、`qb.system.redemptions-expired`、`qb.system.redemption-claims-lapsed` |
| 商家后台 | 页 `/redeem`（导航"奖品领取"）：轮换码（每分钟重新签发、显示链接与有效期）、固定码（按需显示，保存 PNG / SVG / A4 海报，更换）、待确认的申请（每 5 秒轮询，确认 / 拒绝）、今天已领取、全部奖品的表（搜索、状态） |
| App | 页 `/redeem/<key>`：商家名、可领奖品、"领取"按钮（轮换码时申请中的奖品也有按钮）；轮换码即显示"已领取"，固定码显示"等待商家确认"并轮询结果；码失效的说明。未登录时扫的码记在本机十分钟（`redeemReturn.ts`）：登录在另一个标签页结束（邮件链接、Google / Facebook / LINE）或先经欢迎步骤时，登录后仍回到该码的页面（2026-10-29，答题者在 Safari 登录后落在首页）。结果页显示奖品码、扫码提示、`CLAIMED` 状态；首页"奖品等你领取"提示；历史带 `CLAIMED` |
| 后台扩展 | 页 `/qb/redemptions`：全部奖品、状态筛选、搜索、作废对话框（权限 `qb.redemption.void`）；菜单"奖品" |

没有新参数（2 分钟、10 分钟是常量）。`src/qb/` 的 `play.ts`、`ids.ts` 两处相同。

## 2. 测试

- IT `RewardMethodsIT`：方式的允许 / 拒绝 / 复制；现金 欠付 → 已付 → 确认（模板、消息、他人 404）；逾期任务禁止开始，争议、裁定与提醒；兑换记录。
- 单元：`RewardMethodTest`、`AttemptRulesTest`（现金与兑换不要求钱包）、`PublicationReadinessTest`（兑换须有说明）。`SetupIT` 与场景 `setup.yml` 的参数。
- 商家 Vitest `publishing.test.tsx`：只有平台时不显示选择并发送 `PLATFORM`；三种时选兑换、填说明、概要显示；现金页列出并标记已付。
- App Vitest `Play.test.tsx`：筛选参数与本机记忆、徽章；首页的未结现金提示；详情页的方式；结果页的现金（码、确认、争议）与兑换；历史。
- 后台扩展 `pages.test.tsx`（面板只在有权限时可保存）、`reward.test.tsx`（现金页、裁定、按状态筛选、axe）。
- 场景：`setup.yml` 的参数；`QB_SETUP` 多建三个参数使之后生成的编号后移，选项的打乱随发布编号改变，`play` / `payout` / `topup` / `analytics` 场景中写死的所选位置按新顺序改（与 Q18b 相同），快照随之更新。
- IT `RedemptionIT`（Q22b）：轮换码签发 / 核对 / 即时领取、码失效与清理、他人 404、模板与数据视图不返回令牌；固定码的申请、商家收件箱与标记已读、确认 / 拒绝、超时与任务、更换固定码、他商家的码；过期任务、作废与消息。
- Vitest：商家 `publishing.test.tsx`（领奖页：轮换码链接、固定码、申请的确认、全部奖品）与 `notices.test.tsx`（收件箱中的消息：纯文本、动作链接、按 MESSAGE 标记已读）；App `Play.test.tsx`（领取页：即时领取、固定码等待、码失效；首页提示；结果页 `CLAIMED`）；后台扩展 `reward.test.tsx`（奖品页与作废，axe）。
- 场景（Q22b）：`QB_SETUP` 多建一条角色权限（`qb.redemption.void`）使之后生成的编号后移、选项打乱改变，`play` / `payout` / `topup` / `analytics` 的所选位置按新顺序改（同 Q18b、Q22a），快照随之更新。
- e2e `reward-methods.spec.ts`（Q22b）：商家后台领奖页取得轮换码链接 → 未登录的答题人打开链接、登录后回到该页 → 领取 → 结果页 → 商家页的"今天已领取"；新页面过 axe。
- e2e `reward-methods.spec.ts`：管理员允许现金 → 商家发布现金方式 → 答题人合格 → 商家后台标记已付 → App 首页提示 → 结果页确认 → 历史；新页面过 axe。

## 3. 风险

Q22b：
- 固定码可在任何地方扫：申请须商家在十分钟内确认，否则回到待领取；商家不在屏幕前时答题人要重扫轮换码。
- 轮换码是链接中的秘密：两分钟有效、只在签发流程的输出中出现、存储时按名遮蔽、数据视图不返回；管理员后台也看不到码的值。
- 手机相机扫码打开的是本站链接；~~App 不请求相机权限~~（Q52：只在答题人点"扫一扫"时请求，画面只在本机识别）。


- 现金与兑换绕过账本：平台不知道钱有没有真付；争议只能靠管理员裁定与商家信誉。管理员应只对信得过的商家开放（设定时写附注）。
- 商家一直不付：到期提醒商家，但不自动惩罚商家（Q22b 或后续按逾期数限制商家发布）。
- 逾期禁止新答题只在答题人确认或争议后解除；争议中的答题人可以继续答题。
- 兑换在 Q22a 只登记，没有领取的入口（Q22b）；此前发布兑换方式的商家需知道这一点（管理员开放时注意）。
- 商家后台的收件箱不显示发给商家的现金消息（现只有公告），Q22b 补。

## 4. 独立评审的修正

Q22b：

| 级别 | 问题 | 处理 |
|---|---|---|
| 中 | 签发流程把商家全部码读 200 条且无顺序：轮换码多了会漏掉在用的固定码（更换时旧码不作废）、失效码不再清理 | 分两条有条件的查询（在用的固定码；已失效的轮换码），并给 `QB_REDEEM_CODE_ISSUE` / `TOKEN_CHECK` / `REDEMPTION_CLAIM` 加限流 |
| 中 | 领取与确认不看 `expiresAt`，过期奖品在每日任务之前仍可领；申请中（`CLAIMED`）过期的从不过期 | `effectiveStatus` 先判过期（核对不列出、领取与确认 422）；过期任务与状态机覆盖 `CLAIMED` |
| 中 | 签发输出 `redeemKey` 不遮蔽：带幂等键的调用会把它存进 `op_process_result` | 商家后台签发时不带幂等键（平台只为带键的调用保存输出）；轮换码两分钟、固定码的申请仍须商家确认，残余风险可接受 |
| 中 | App 领取后用"未领取列表"找会话编号，已领取的不在其中，结果链接不显示 | 核对输出的每件奖品带 `attemptId` |
| 低 | App 把申请中的奖品也显示"领取"按钮（服务端拒绝） | 申请中的显示"等待商家交付"，不给按钮 |
| 低 | 商家后台待确认列表含已超时的申请（确认即 422） | 按 `confirmBy` 过滤（以读取时刻） |
| 低 | 两个任务的扫描没有索引 | `V25` 加部分索引 `(confirm_by) WHERE status='CLAIMED'`、`(expires_at) WHERE status IN (DUE, CLAIMED)` |
| 低 | 结果页用 `setInterval` 轮询，后台标签页也轮询 | 改为结果查询自己的 `refetchInterval` |
| 低 | "今天已领取"在客户端过滤 50 条 | 服务端按状态筛选 |

安全评审（Q22b）：无高级问题；中级即上表第一条。令牌明文存储（字段敏感、数据视图与审计不返回、输入按名遮蔽）接受；链接中的秘密进入浏览器历史与代理日志（两分钟有效 / 固定码本就印在海报上）接受；`next` 重定向经平台 `sitePath` 校验，无开放重定向。


| 级别 | 问题 | 处理 |
|---|---|---|
| 中 | 现金消息按"种类:记录"去重，裁定"未付"后再次标记已付 / 争议 / 逾期不再有消息 | 这些种类的消息键带事件时刻；同一事件的重复投递仍只一条 |
| 中 | 裁定"未付"后付款期限与提醒不重置，已提醒过的商家不再被提醒 | 裁定"未付"时 `payBy = 裁定时刻 + 付款天数`、`remindedAt` 清空 |
| 中 | `QB_SPONSOR_REWARD_METHODS_SET` 的 `actsOn` 指向 `QbSponsor`（主键是入驻编号，不是用户） | 改为 `SecurityEntities.USER`（同封禁流程） |
| 中 | 审核内容加入方式后，部署前已在审核中的发布的内容哈希改变、批准失效 | 只有不是 `PLATFORM` 时才把方式与说明放进审核内容 |
| 中 | 天数参数缺失当作 0 天、非整数抛错导致提交 500 | `QbParams.days`：缺失取声明的缺省，取整、0–3650 天 |
| 低 | 裁定消息把状态码（`DUE`）写进正文 | 消息 `qb.cash-reward.status.<状态>` 按语言填入 |
| 低 | 结果页对平台支付的会话也查现金、兑换与商家 | 只按会话的 `rewardStatus` 查 |
| 低 | 站内信对现金种类也查会话（永远查不到） | 只对平台奖励的种类查会话 |
| 低 | 未用的常量 `DUE_EVENT` | 删除 |
| 低 | 商家只被允许现金时，新草稿缺省仍是 `PLATFORM`，第一次保存被拒 | 新草稿取第一个允许的方式 |

安全评审（无高、中级问题）：现金流程先取记录锁再按限本人的视图读（他人只能短暂争用锁，与发布流程一致，接受）；一条说明即可以争议解除逾期禁止（设计如此：争议交管理员裁定）；
管理员撤回方式后已批准的发布照常上架（本计划 §3 已列）；`days()` 的取整与上限已修。
