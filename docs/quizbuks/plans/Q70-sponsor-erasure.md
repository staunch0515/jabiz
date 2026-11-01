# Q70 商户账号的删除与个人信息抹除（2026-10-31）

需求方（2026-10-31）："继续，做商户账号删除的抹除"。平台的账号删除（决策 D62，10 §17、21 §2.5）早已就绪，QuizBuks 之前没有开启，也没有声明抹除什么。

## 1. 已确认的决定（需求方 2026-10-31）

| # | 决定 |
|---|---|
| D-Q70-1 | **只做商户账号**：商家后台开启账号删除；答题者（App）以后单独做 |
| D-Q70-2 | **商户的 Quiz、发布与公告下线并保留**：进行中的发布结束、从答题者的页面消失；记录保留（答题者的作答、奖励与财务对账指向它们）；商户的个人信息在清除时覆盖 |
| D-Q70-3 | **未结事项只提示**（平台 D62 的做法）：进行中的发布、钱包余额、欠答题者的现金奖励在申请前逐项提示，照常删除；账本与付款记录保留 |

## 2. 抹除什么（`identity.SponsorErasure`，`PersonalDataEraser`）

`ACCOUNT_ERASE` 按下表的顺序处理该用户的行；`ERASURE_PURGE`（维护连接）覆盖列出的字段在各版本、只追加行与审计记录中的值。

| 实体 | 处理 | 覆盖的字段 |
|---|---|---|
| `QbSponsorMailServer`（含密封的密码） | 删除 | host、username、fromAddress、fromName、lastError |
| `QbRedeemToken`、`QbSponsorStaff`、`QbQuizProduct` | 删除 | — |
| `QbSponsorProduct` | 删除 | name、description、image、shopUrl |
| `QbSponsorPage` | 删除 | handle、headline、cover、website |
| `QbAiJob` | 删除 | candidates、failureMessage |
| `QbInvitation`（商户输入的他人地址） | 删除 | email |
| `QbProfile`、`QbUserSettings`、`QbInboxState`、`QbMessage`、`QbMailNotice` | 删除 | displayName、city；title、body |
| `QbSponsor`（入驻资料） | 删除（墓碑） | contactName、company、intro、avatar |
| `QbTopUp`（保留 10 年） | 保留 | payerName |

不动的：Quiz、发布、公告（D-Q70-2）；钱包、账本、收据、奖励、同意记录（保留期或只记一次）。
认证（Q69）的记录与官方发布者的标记也不动：其中只有用户编号（按 D62 第 4 条保留）与运营商写的理由，是"申请成为商户"所凭的记录；官方发布者的发布照常随账号结束，不再有新的认证轮次。
主页浏览与商品点击的计数随账号删除（平台 17z-6：计数器声明 `ownerIsUser()`，`ACCOUNT_ERASE` 删除所有人是该用户的计数）。

## 3. 删除之后（`QB_SPONSOR_ERASED`）

平台在删除的事务中发出 `jabiz.account.erased`；QuizBuks 订阅它（`qb.account-erased`），以内部流程 `QB_SPONSOR_ERASED`：
先取账户锁（同封禁），读出该商户的发布，对进行中的（排期、进行中、暂停、资金不足）逐个以子流程 `QB_PUBLICATION_ERASED_END` 结束
（`endReason = ADMIN`、`adminReason = QB_ACCOUNT_DELETED`，与管理员结束相同：不再接受新作答，进行中的作答照常完成），并撤回其公告（`QB_BROADCAST_BAN_WITHDRAW`）。

## 4. 申请前的提示（`notices`，消息 `erasure.notice.<代码>`，三语）

| 代码 | 条件 |
|---|---|
| `qb.publications-end` | 有进行中的发布（数量） |
| `qb.wallet-balance` | 钱包余额大于 0（金额；删除时不退还） |
| `qb.cash-rewards-open` | 欠答题者、未结清的现金奖励（数量；删除后仍须结清） |

## 5. 开启与部署

- 商家入口 `account-deletion: ${QB_SPONSOR_ACCOUNT_DELETION:false}`：缺省关闭。开启前须设维护连接（`JABIZ_ERASURE_DATASOURCE_*`，`deploy/quizbuks/DEPLOY.md` §1a）。
- 商家后台账户页：入口允许删除（或有待执行的申请）时显示"删除账户"区块（平台积木 `AccountDeletion`，含提示的确认、冷静期、撤回）；邮件链接经平台的 `MailLinkPage`。

## 6. 测试

- `SponsorErasureTest`：提示的条件与参数（进行中的发布只算商户的、余额、未结清的现金奖励；没有时不提示）。
- `SponsorErasureIT`：删除后主页与商品没有了，主页的计数删除（别的商户的保留）；发布保留、以"账号已删除"结束，别的商户的照常；入驻资料成为墓碑、各版本的姓名与公司被覆盖；资料的显示名被覆盖。
- 商家后台 `App.test.tsx`：入口不允许时不显示删除区块，允许时显示。

## 7. 已知

- 管理员也可以为答题者安排删除（`SEC_USER_DELETION_SCHEDULE`）：本声明只覆盖商户与账户共用的数据，答题者的作答、提现账户等留给以后（D-Q70-1）。
- 审核中的发布（待审）不结束：审核人会看到商户已删除。
