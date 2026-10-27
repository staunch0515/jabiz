# S3：收费（试用、套餐、周期计数、超限拦截）（计划已确认 2026-10-27：方案 A 与 §7 的建议）

总体方案见 `00-overview.md` §3。本阶段只在 `1.3/quizbuks` 上做，预计不需要平台改动；需要时先停下来说明。

## 1. 用 Shopify App Pricing，不自己调 Billing API

Shopify 现在把 **Shopify App Pricing**（原名 Managed Pricing）作为新公开应用的默认收费方式：

- 套餐在 Partner Dashboard 的应用提交表单里设定，不写在代码里；
- 套餐选择页由 Shopify 托管；
- 试用、按比例计费、测试扣款（开发店铺自动是测试）、改价都由 Shopify 处理；
- 文档明确说不要再调 `appSubscriptionCreate`。

所以 QuizBuks 只做三件事：**读**店铺当前的订阅、**数**本周期的完成答题次数、**拦**超限的店面。

| 方案 | 做法 | 取舍 |
|---|---|---|
| **A（建议）** | Shopify App Pricing | 代码最少，审核阻力最小。套餐与价格在 Partner Dashboard 改，不经代码 |
| B | 自己调 Billing API（`appSubscriptionCreate`，确认页、回跳、改套餐） | 套餐写在仓库里，但要自己处理确认、升降级与按比例计费；Shopify 已称之为旧方式 |

## 2. 读订阅

- **数据来源**：Admin GraphQL 的 `currentAppInstallation { activeSubscriptions { id name status test trialDays createdAt currentPeriodEnd } }`。
  - 用 S1 的店铺访问令牌，经外部服务 `shopify` 调用；不需要新的权限范围。
  - 没有 `ACTIVE` 的订阅即视为 **Free**。Shopify 的免费套餐不产生订阅。
- **何时读**：
  - 收到 webhook `app_subscriptions/update` 时。正文只当提示，以 GraphQL 读到的为准。
  - 店主打开内嵌页时（`QBS_PLAN_GET`）。
  - 每日任务 `qbs.plan-sync`（`JobDefinition.forEach`，每家 `ACTIVE` 的店一项），补漏掉的 webhook，也推进周期。
- **存放**：存在 `QbsShopPlan` 中，每店一行。字段有：
  - 套餐代码；
  - Shopify 订阅编号、状态、是否测试；
  - 试用截止（`createdAt + trialDays`）；
  - `currentPeriodEnd`；
  - 上次读取时刻。
- **套餐对应**：Shopify 订阅的名字经配置 `quizbuks.shopify.plans.<代码>.name` 对应到我们的套餐代码。
  - 有不认识的名字时，按 Free 处理，记警告，并在后台提示。
  - 启动检查要求每个套餐代码都配置了名字。
- **降级**：社区确认，降级时订阅对象会立即变化，但 Shopify 照旧按原套餐收费到 `currentPeriodEnd`。
  - 所以降级时，旧套餐的上限保留到存下的 `currentPeriodEnd`。
  - 升级当即生效。
- **读不到时**（令牌失效、`RECONNECT`、Shopify 出错）：沿用上次存下的结果。
  - 从未读到过的店按 Free 处理，Free 照样能用，只是上限低。
  - 不采用"读不到就拦住"：那样店面会因为我们的故障而停掉。
- **待核实**：Shopify 文档把 Partner API 的 `activeSubscription(appId, shopId)` 称为判断订阅的标准方式，`currentAppInstallation` 只是补充。
  - Partner API 要组织级的令牌，是另一个外部服务。
  - 本阶段先用 `currentAppInstallation`，开发店铺联调时一并核实（Q-S3-6）。

## 3. 计量与周期

- **计数单位**：本周期**完成的答题次数**。
  - "完成"指引擎判过分：`QBS_SF_SUBMIT` 第一次交卷成功，包括超时后的自动交卷。
  - 计数与发码在同一事务、同一店铺锁内，加一。
  - 同一答题再交（幂等）不再计。
- **存放**：`QbsUsage`（店铺、周期起点、周期终点、完成次数），每店每周期一行。
- **周期**：
  - 付费套餐：`[currentPeriodEnd − 30 天, currentPeriodEnd)`（Shopify 的套餐每 30 天结算一次）。过了 `currentPeriodEnd` 而还没读到新的，就按 30 天顺推。
  - Free：UTC 的自然月。
- **拦截**：在**开始**时拦。
  - 本周期已完成次数 ≥ 上限时，`QBS_SF_START` 返回 422 `QBS_PLAN_LIMIT`。
  - `QBS_SF_CAMPAIGN` 返回 `live=false`，组件不显示。
  - 已开始的答题照常可交、照常计数，所以最多超出"同时在答的人数"，这一点接受。
  - 活动本身不改状态：下个周期或升级后自动恢复。
- **上限存放**：上限是受控业务参数 `qbs.plan.<代码>.monthly-attempts`，列入 `ControlledParams`（四眼修改）。
  - 参数未建立时用代码里的缺省值（同 S2a 的每日发码上限）。
- **AI 出题按套餐限次**：Shopify 侧还没有 AI 出题，本阶段不做。

## 4. 界面

- **内嵌后台**（`quizbuks-web/shopify`）新标签"套餐与用量"，显示：
  - 当前套餐、是否试用中与试用截止、本周期起止；
  - 已完成 / 上限的进度条；
  - "选择套餐"按钮：经 App Bridge 在顶层打开 Shopify 托管的套餐页 `https://admin.shopify.com/store/<店铺>/charges/<应用 handle>/pricing_plans`。`handle` 由配置 `quizbuks.shopify.app-handle` 给出。
- **提示**：概况页在用到 80% 和 100% 时显示提示，100% 时说明店面已停止显示答题。
- **店面组件**：Free 套餐在结果页底部显示一行 "Powered by QuizBuks"（链接到落地页），由 `QBS_SF_CAMPAIGN` 的 `badge` 字段决定。

## 5. 生命周期

- **卸载**：Shopify 会自己取消订阅。`app/uninstalled` 删除 `QbsShopPlan`（即回到 Free，见 I-9），用量保留。
- **重装**：下次打开内嵌页时重新读订阅。
- **`shop/redact`**：删本店的 `QbsShopPlan` 与 `QbsUsage`。
- **`shopify.app.toml`**：加 webhook 订阅 `app_subscriptions/update`。

## 6. 拆分、迁移与测试

- **S3a（后端与店面拦截）**：
  - 迁移 V35，新建 `qbs_shop_plan`、`qbs_usage`；
  - `ShopifyApi.activeSubscriptions`；
  - 子流程 `QBS_PLAN_SYNC`（`subProcessOnly`，店铺锁内读与写）；
  - webhook 消费者 `QBS_SHOPIFY_APP_SUBSCRIPTIONS_UPDATE`（`internal()`）；
  - 任务 `qbs.plan-sync`；
  - `QBS_PLAN_GET`（店铺成员）；
  - 计数与拦截；
  - 卸载与 redact 的清理；
  - 启动检查。
- **S3b（界面）**：套餐页、概况提示、店面的 badge。
- **测试**：
  - 集成测试（GraphQL 用本地桩）覆盖：
    - 读订阅：付费、试用、测试扣款、没有订阅、不认识的名字；
    - 降级保留到期末；
    - webhook；
    - 读不到时沿用；
    - 周期顺推；
    - 计数只计一次；
    - 到上限即拒绝开始、已开始的照常交卷；
    - 卸载与 redact。
  - 场景回放一条：试用中答题、到上限、升级后恢复。
  - 前端用 Vitest。

## 7. 待需求方确认

| # | 问题 | 建议 |
|---|---|---|
| Q-S3-1 | 套餐名字、月价、每月完成答题上限 | 按原设想，去掉 GMV 抽成：**Free** 50 次、**Starter** $29 500 次、**Growth** $99 3,000 次、**Pro** $299 15,000 次。以后在 Partner Dashboard 与受控参数里改，不经代码 |
| Q-S3-2 | 试用天数，以及试用期按哪个上限 | 14 天，按所选套餐的上限（"装上就能用完整功能"） |
| Q-S3-3 | 计数单位 | 完成（判过分）的答题，不是开始的。开始了没交的不算 |
| Q-S3-4 | 超限时店面怎么办 | 组件不显示（与没有活动时相同），不显示"已停用"之类的字样。后台提示店主 |
| Q-S3-5 | Free 套餐的 "Powered by QuizBuks" | 显示在结果页底部，不显示在入口按钮上 |
| Q-S3-6 | 判断订阅用 Admin 的 `currentAppInstallation` 还是 Partner API 的 `activeSubscription` | 先用前者（店铺令牌已有）。联调时核实；需要后者时再加外部服务与组织令牌 |
| Q-S3-7 | 年付 | 不做（总体方案 §3）；App Pricing 支持，以后只需在 Dashboard 加套餐，并处理年度周期 |

## 8. 需要需求方准备（联调时）

- 在 Partner Dashboard 为应用设 App Pricing 套餐，名字与 Q-S3-1 一致；
- 应用的 handle；
- 在开发店铺上走一遍：选套餐、试用、升级、降级、卸载。

## 9. S3a 的实施（与计划不同或计划未写的）

| # | 内容 |
|---|---|
| I-1 | 周期是 **30 天**而不是"1 个月"：Shopify 的月套餐按 30 天结算（§3 已改）。付费套餐没有给出周期截止（如试用中）时按自然月 |
| I-2 | `app_subscriptions/update` 的正文只有订阅（`admin_graphql_api_id`、名字、状态），**没有店铺地址**，所以不能像其他主题那样以签名的正文核对请求头。消费者 `QBS_SHOPIFY_APP_SUBSCRIPTIONS_UPDATE` 取请求头 `X-Shopify-Shop-Domain` 所指的店铺，只是向 Shopify 重新读它的订阅：请求头被改也只会让另一家店被重读一次，不会写入任何正文中的内容。入站提供方 `shopify` 的主题加上它，`shopify.app.toml` 加订阅 |
| I-3 | 不另设 `QBS_PLAN_SYNC` 子流程：读取与保存是三处共用的步骤（`PlanProcesses.readSteps` / `readAndKeep`），由每日任务的 `QBS_PLAN_SYNC`（`internal()`）、webhook 消费者与 `QBS_PLAN_GET` 各自执行。`QBS_PLAN_GET` 在一小时内不重复读，除非页面要求（`refresh`，从套餐页回来时） |
| I-4 | 计数在 `QBS_SF_SUBMIT` 中引擎判分之后，取新锁 `qbs.shop-usage:<店铺>`（锁序：引擎的发布锁 → 用量锁 → 发码锁）。只有引擎这次真的判了分才计：再要已发的码（`AGAIN`）、再交已结束的答题（引擎拒绝）都不计 |
| I-5 | 套餐的上限是受控参数 `qbs.plan.<free / starter / growth / pro>.monthly-attempts`（同每日发码上限：列入 `ControlledParams`，不由 `QB_SETUP` 建立，未建立时按 50 / 500 / 3,000 / 15,000）。Shopify 的套餐名与我们的套餐经配置对应（`QB_SHOPIFY_PLAN_STARTER` 等），应用的 handle 为 `QB_SHOPIFY_APP_HANDLE`（`deploy/quizbuks/README.md`） |
| I-6 | 后台读不到时（Shopify 出错）沿用存下的套餐；Shopify 拒绝令牌时同其他调用：删除令牌、店铺转为"需要重新连接"，套餐仍沿用 |
| I-7 | 店面的 `QBS_SF_CAMPAIGN` 多了 `badge`（免费套餐为真），S3b 的组件据此显示 "Powered by QuizBuks" |
| I-8 | 场景回放不另加：场景执行器不能以会话令牌登录、也不能替 Shopify 应答，建不了店铺、活动与订阅（同 S1、S2）。"试用中答题、到上限、升级后恢复"由 `ShopifyStorefrontIT.thePlansLimitStopsNewAttemptsUntilTheShopMovesUp` 覆盖 |
| I-9 | **卸载删除套餐行**（计划写"清回 Free"）：没有行即免费，重装后第一次打开内嵌页就重读，不受一小时的间隔限制。用量保留 |
| I-10 | 开始答题的拦截放在引擎的开始**之后**：引擎的开始也用于继续已开始的答题（`resumed`），继续的照常放行；新的答题到上限时整个流程拒绝，引擎的新答题与新顾客行都不保留 |
| I-11 | 保留的高套餐带上它被计数的周期（`keptFrom` 到 `keptUntil`），用量仍记在同一行；付费周期总是取包含当前时刻的 30 天（Shopify 给出的截止在 30 天以后时向前对齐，不会变成更长的周期） |
| I-12 | 套餐名与 handle 的配置错误由 `PlatformCheck`（`ShopifyPlans`，类别 `QUIZBUKS`）在启动与 `platformCheck` 中一次报告 |

**测试**：
- `ShopifyPlanIT`（9 例）：付费套餐与一小时内不重读、重装后立即重读、无订阅与不认识的名字、webhook 触发重读与请求头不是店铺、降级保留到期末、读不到时沿用与令牌被拒、每日任务、卸载回到免费与 `shop/redact` 删除、已卸载店铺的成员被拒。
- `ShopifyStorefrontIT` 加 1 例：计数只计一次、到上限不显示也不能开始（被拒的开始不留下答题）、已开始的照常继续与交卷并计数、升级后恢复且不显示 badge。
- `QbsBillingTest`：周期（含对齐与顺推）、保留的套餐与其周期、上限、套餐名对应与配置检查。
