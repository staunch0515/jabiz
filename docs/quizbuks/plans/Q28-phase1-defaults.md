# Q28 阶段一（推广期）的缺省（计划，2026-10-25 确认）

需求方（2026-10-25，"全部同意"）：阶段一（推广期）线下奖品免费、平台不经手资金（招商网站 Q31 的承诺）。三件事：商家与发布的自动审核（已有的受控参数，只需发布）、
没有单独设定的商家缺省可用的奖励方式（推广期只有线下奖品，替代原来固定的"只有 Kudos"）、每次答题的最高奖励（景品表示法的上限）。

## 1. 已确认的决定

| # | 决定 |
|---|---|
| D-Q28-1 | **缺省方式是受控参数**，不是部署设置：`qb.reward.default-methods`（文本，逗号分隔的方式名；声明值 `PLATFORM`，即 Q22 以来的行为，使已有的测试与安装不变；推广期发布 `OFFLINE_REDEEM`）。改变谁可以用什么方式是业务控制，走四眼；而且商家后台要知道它（SQL 模板读 `SysParam`，部署设置读不到）。计划里写的"部署设置"由此改为参数 |
| D-Q28-2 | 读法在 `RewardMethod.defaultMethods`：没有 `QbSponsorRewardMethods` 记录的商家按参数在操作时点的值；不是方式的名字忽略（写错只会更少，不会更多）；全都不是即什么都不能用，直到管理员单独设定；空白与未发布（设置之前）同声明值 `PLATFORM`。三个流程（保存、复制、提交）与设定一起以 `LoadParams.optional` 读 |
| D-Q28-3 | **最高奖励** `qb.reward.max-per-attempt`（Kudos，声明值 `0` = 不限）：就绪检查对高于它的每个奖励档报 `QB_REWARD_ABOVE_MAX`（字段 `tiers[i].reward`，参数 `tier`、`reward`、`max`），保存时作为问题返回、提交时 422。不分方式：奖品与现金的数字就是其 Kudos 等值 |
| D-Q28-4 | 商家后台经模板 `qb.sponsor.reward-methods`（`qb.publication.write`）读当前允许的方式（自己的设定，否则参数；`own` 标出来源）与最高奖励；向导据此给出选择并显示上限；不含 `PLATFORM` 时**不显示钱包与充值页**（菜单项与路由都去掉；只是隐藏，允许什么由服务端决定）。交易页保留（历史） |
| D-Q28-5 | 后台扩展的"商家审核"页经模板 `qb.admin.reward-defaults`（`qb.admin.publication.read`）读参数，没有单独设定的商家显示"当前允许（平台缺省）"；勾选保存时以缺省为起点 |
| D-Q28-6 | 阶段一的设置写成 `operations.md` §3 末的清单：缺省方式、最高奖励、两个自动审核开关、转账与 AI 的开关，以及开始收费前要改回的项 |

## 2. 改动

| 部分 | 改动 |
|---|---|
| 参数 | `QbParams.REWARD_DEFAULT_METHODS`、`REWARD_MAX_PER_ATTEMPT`（列入 `CONTROLLED`，`QB_SETUP` 提出）；辅助 `QbParams.text` / `kudos`（缺失取声明值） |
| 流程 | `PublicationEditing.loadSponsorMethods` 加读参数；`allowedMethods` 没有记录时取 `RewardMethod.defaultMethods`；`maxRewardPerAttempt`；`PublicationReadiness.check(…, maxRewardPerAttempt)`；`PublishingCodes.REWARD_ABOVE_MAX` 与三语消息 |
| 模板 | `qb.sponsor.reward-methods`、`qb.admin.reward-defaults` |
| 商家后台 | `readRewardSettings`（模板）、`REWARD_SETTINGS_QUERY`；向导显示上限；`App` 按方式隐藏钱包 |
| 后台扩展 | `readRewardDefaults`；商家审核页的缺省显示与文案 |
| 测试 | `RewardMethodTest`、`PublicationReadinessTest`（上限）；`RewardMethodsIT.theDefaultMethodsAndTheHighestRewardComeFromTheParameters`（参数、模板、保存的问题、提交 422、单独设定优先）；商家后台 `publishing.test`（上限提示）、`App.test`（隐藏钱包）；后台扩展 `pages.test` |
| 文档 | 本文、`03-plan.md`、`operations.md`（§3 第 6 步与阶段一清单）、`backend/quizbuks/CLAUDE.md` |

## 3. 以后

- 开始收费时的套餐与方式的对应（Q31 价格页的"计划中"）。
- 按交易额自动算景品上限（需要购买金额，平台目前没有）。
