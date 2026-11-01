# Q71 答题者账号的删除与个人信息抹除（2026-11-01）

需求方（2026-11-01）："继续，做答题者账号删除的抹除"。这是 Q49a（`Q49-mobile-apps.md`）中上架必需的一项：
Apple 规则 5.1.1(v) 要求能注册账号的 App 在 App 内提供删除；Google Play 的账号删除政策另要求一个不装 App 也能申请删除的网址。
商户的删除见 Q70；平台的删除见决策 D62（10 §17、21 §2.5）。

## 1. 已确认的决定（需求方 2026-11-01）

| # | 决定 |
|---|---|
| D-Q71-1 | **钱包余额留在钱包**：删除时不记账，余额照旧留在已删除的账户上；申请前提示"不予支付"（Q70 的余额提示对所有账户都有效） |
| D-Q71-2 | **未结事项只提示**（同 D-Q70-3）：进行中的提现照常处理；未领取的现金奖励与兑换券放弃；都照常删除 |
| D-Q71-3 | **先做平台 17zb**：邀请与店员两张表中，答题者的行由另一个字段指向他（认领人、店员本人），而商户的删除已按发出人、商户声明了它们；平台改为同一实体可按不同的用户字段声明几次（D62 第 8 条） |

## 2. 抹除什么（`identity.TakerErasure`，`PersonalDataEraser`）

Q70 的声明对所有账户都有效（资料、设置、收件箱、通知与 Q70 的余额提示）；下表是答题者另有的。

| 实体 | 处理 | 覆盖的字段 |
|---|---|---|
| `QbProfileAttribute`（"关于我"） | 删除 | —（值在审计中本来就遮蔽） |
| `QbBankAccount` | 删除 | accountNumber、accountHolder |
| `QbPayoutOnboarding`（Stripe 的开户链接） | 删除 | url |
| `QbInvitation`（按认领人 `userId`，他的地址） | 删除 | email |
| `QbSponsorStaff`（按店员本人 `userId`） | 删除 | — |
| `QbTransfer`（提现，保留 10 年） | 保留 | accountNumber、accountHolder（可空的列由清除置空；迁移 V52 放宽检查约束 `qb_transfer_method`，银行提现的这两列可以为空） |
| `QbCashReward`（应付给他的现金奖励） | 保留 | markedPaidNote、disputeNote、resolveNote |
| `QbRedemption`（兑换券） | 保留 | voidNote |

不动的（只有用户编号，按 D62 第 4 条保留；商户的统计与账目依赖它们）：作答与答案、浏览、开始记录、公告的已读、数字奖品、同意记录、认证记录、封禁、钱包与账本，
以及 **Stripe 的收款账户 `QbPayoutAccount`**（与计划不同：只有 Stripe 的账户编号与状态，没有个人信息；删除它会让 Stripe 之后发来的 `account.updated` 变成"未知账户"的待办，
对账也找不到收款人）。Stripe 处的账户由 Stripe 按其政策保存（隐私政策第10条写明）。

## 3. 申请前的提示（`notices`，消息 `erasure.notice.<代码>`，三语）

| 代码 | 条件 |
|---|---|
| `qb.wallet-balance`（Q70） | 钱包余额大于 0：删除后既不能提现，也不会退还（文案改为对答题者也适用） |
| `qb.withdrawal-in-progress` | 有 `REVIEW`、`SENDING`、`CHECK` 的提现：请等到账后再让账户删除。Stripe 的照常处理；银行提现的账户信息在清除时置空，届时尚未支付的不能再支付，Kudos 留在钱包（冷静期 14 天通常足够） |
| `qb.rewards-unclaimed` | 有未结的现金奖励（`DUE`、`MARKED_PAID`、`DISPUTED`、`OVERDUE`）或未使用的兑换券（`DUE`、`CLAIMED`）：删除后放弃 |

## 4. 申请的入口

- App 入口 `app` 的 `account-deletion: ${QB_APP_ACCOUNT_DELETION:false}`：缺省关闭，生产先设维护连接再开启（`DEPLOY.md` §1a、`operations.md` §1.1）。**提交两家商店审核前必须开启。**
- App 账户页：入口允许（或已有待执行的申请）时，菜单最下方出现"删除账户"（`/account/delete`），页面写明抹除什么、保留什么，下面是平台积木 `AccountDeletion`（身份确认、提示、冷静期、撤回）。
- 不装 App：`https://quizbuks.com/taker/delete-account` 转到平台的申请页 `/auth/account-deletion`（填邮箱，凭寄到已验证地址的链接确认）。这是 Google Play 表单的"删除账号的网址"（`store/play-data-safety.md` §4）。
- 隐私政策（三语）第8条、第10条补"删除账号"一节：入口、14 天的冷静期、抹除什么、保留什么、钱包余额、第三方处的副本。

## 5. 平台的配合

- 17zb（`staunch0515/jabiz#392`）：同一实体按几个用户字段声明（邀请、店员）；抹除删除的普通表行，其审计在清除时照样遮蔽（测试本项时发现，Q70 的商户删除也受益）。
- 应用的检查约束与清除：清除把可空的列置空，应用的检查约束若要求它们非空，整个清除回滚。声明要覆盖的字段时核对表上的约束（V52 即为此）。

## 6. 测试

- `TakerErasureTest`：提示的条件。
- `TakerErasureIT`（维护连接）：
  - "关于我"、银行账户、认领的邀请、店员关系删除；
  - 提现保留，持有人与账号置空；
  - 她的账号、持有人名与邮箱在任何表、版本与审计记录中都找不到；
  - 另一位答题者的不受影响。
- App `App.test.tsx`：删除只在入口允许时出现在菜单中；`/delete-account` 转到申请页。

## 7. 不在本项内

- 发给答题者邮箱但他没有认领的邀请（`userId` 为空）：按用户字段找不到，邮箱留在商户的邀请名单中（商户输入的地址）。需要时以平台按邮箱查找的声明另做。
- 隐私政策写明了 App 与商户后台的删除入口：须先在生产开启 `QB_APP_ACCOUNT_DELETION` / `QB_SPONSOR_ACCOUNT_DELETION` 再发布新版政策（`legal/README.md`）。

- Q49a 的其余部分：有奖活动的规则页、发布页的举报入口。
- 平台层面的 Stripe 账户关闭（Connect 账户的删除）：需要时另议。
