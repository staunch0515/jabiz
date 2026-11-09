# F-F4 学者 App 的调整（Q108 §6、§6.1）：实施计划

依据：`docs/quizbuks/first-launch-spec.md` F-F4（以它为准）；`Q108-console-layout.md` §6、§6.1 与原型只作参考。
会话 S3，分支 `1.3/quizbuks-ff4-taker-app`（本计划）；实现分 3 个 PR（§9）。基线 `origin/1.3/quizbuks`（ee69ccb1）：已含平台 17zj（F-F1：`BottomBar`、`PageTabs`、`PageHeader` 图标、`AppShell`）、17zk（F-F2 入口页）、F-F6、F-F7（T12 扫一扫修复）、F-S8 屏蔽、F-S9 主题词、F-S10 短视频、F-S13 推送计数。F-S3（"我的图"）、F-S5（节点证书）、F-C10（AI 帮助）未开始。

## 0. 范围

只动学者 App（`quizbuks-web/app`）的框架与首页之外的页面安排，外加两处只读模板（§4）。

- **首页不变**：`FeedPage` 及其短视频一排、竖屏模式、搜索、筛选、现金与到店奖品的提示（Q62、Q65、Q106、F-S10）一行不改；以视觉对比截图证明（§6）。
- 底部 5 项：首页 / 已答 / **扫一扫（正中、凸起）** / 消息 / 我的；取消右上角的菜单；宽屏（≥ 平台断点 `md`，即 `useIsMobile` 的 768 px）改为左侧一列。
- 每个一级页面的标题前是该区域的图标（与底部菜单同一个图标）；区域内的页面用标题下的页内标签。
- 已答：第一层"待领奖"标签带徽标；扫一扫：同一区域有"出示我的领奖码"；消息：收件箱与对话两个标签；我的：三个可点的数字与"我拥有的""设置"两组。
- 发布详情：说明 / 奖励 / 资料 / 规则四个标签，"开始答题"固定在底部。
- **不做**：首页改版；新的底部菜单项；用户自定义首页；发起方页面（F-F3）；"我的图"本身（F-S3，本项只留位置，§2.4）；AI 帮助本身（F-C10，本项只给入口位置）。

**删除的功能：无。** 右上角菜单的 4 项（扫一扫、我的推荐、外观、退出登录）、底部现有的 5 项（首页、记录、我的学分、收件箱、账户）与账户菜单的全部条目都有新位置（§2 对照表）；**没有一个旧地址失效**（§3）。

## 1. 现状（基线）

- `App.tsx` 的 `Header`：品牌字样；首页另有筛选；导航页有"我的发起方"（`Store`，只对发起方，预算不足带红点）、搜索；右端 `app-menu` 下拉：扫一扫（`scan-open`）、我的推荐（`referrals-open`）、外观（浅色 / 深色 / 跟随系统）、退出登录（`sign-out`）。发布页与结果页有返回。
- `BottomNav`（App 自写）：首页 `/`、记录 `/history`、我的学分 `/wallet`、收件箱 `/inbox`（未读徽标）、账户 `/account`。欢迎页与答题页不显示导航。
- 页面标题前已有圆形的 `TitleBadge`（Q67），各页自己写。
- 路由是 `App.tsx` 中 `SignedIn` 的 if 链加 `routes.ts` 的 `itemPage`（`scanTarget` 依赖它，F-F7 / T12）。

## 2. 新位置：对照表（旧 → 新）

区域与路由归属只在一处：新文件 `app/src/frame/areas.ts`（§5.1）。下表即它的内容；"地址"一栏不变的写"同"。

### 2.1 底部菜单与右上角菜单

| 旧 | 新位置 | 地址 |
|---|---|---|
| 底部"首页" | 底部"首页"（第 1 项） | 同 `/` |
| 底部"记录" | 底部"已答"（第 2 项），区域首个标签"已答" | 同 `/history` |
| 底部"我的学分" | "我的"顶部数字"Kudos"与"我拥有的 → Kudos 明细" | 同 `/wallet`（及其各部分） |
| 底部"收件箱" | 底部"消息"（第 4 项），区域首个标签"收件箱" | 同 `/inbox` |
| 底部"账户" | 底部"我的"（第 5 项） | 同 `/account` |
| 右上角菜单 → 扫一扫 | 底部"扫一扫"（第 3 项，正中、凸起） | 同 `/scan` |
| 右上角菜单 → 我的推荐 | "我的 → 我拥有的 → 推荐" | 同 `/referrals` |
| 右上角菜单 → 外观 | "我的 → 设置 → App 设置 → 外观"（新的一节，同样三个选项） | 同 `/account/preferences` |
| 右上角菜单 → 退出登录 | "我的"页最下方"退出登录"；欢迎页（无导航）的页头另有"退出登录" | — |
| 页头"我的发起方"图标（红点） | **原样保留**（首页不变）；另在"我的"中加一行"我的发起方"（只对发起方，带同一红点） | 同 `/my-sponsor` |
| 页头搜索、首页的筛选、返回 | 原样保留 | 同 |

### 2.2 各页面的区域与标签

| 页面（旧地址） | 区域 | 位置 | 地址 |
|---|---|---|---|
| 首页 `/`、`/?q=`、竖屏模式 `/?shorts=` | 首页 | 不变 | 同 |
| 发布 `/p/<id>` | 首页 | 详情页，内部四个标签（§2.5） | 同；标签为 `?tab=`（`itemPage` 与 `scanTarget` 不变） |
| 记录 `/history` | 已答 | 标签"已答"（原"全部 / 已结束 / 未结束"的切换留在标签内） | 同 |
| — | 已答 | **新**标签"待领奖"（徽标，§4.1） | `/history/prizes` |
| 答题 `/play/<id>` | 已答 | 全屏，无导航（同现在） | 同 |
| 结果 `/result/<id>` | 已答 | 详情页（返回照旧） | 同 |
| 扫一扫 `/scan` | 扫一扫 | 标签"扫码" | 同 |
| — | 扫一扫 | **新**标签"出示领奖码"（§2.3） | `/scan/pass` |
| 扫到的兑奖码 `/redeem/<key>` | 扫一扫 | 详情页 | 同 |
| 柜台扫到的领奖码 `/pass/<key>` | 扫一扫 | 详情页 | 同 |
| 收件箱 `/inbox`、消息 `/inbox/message/<id>`、公告 `/inbox/broadcast/<id>` | 消息 | 标签"收件箱"及其详情 | 同 |
| 对话列表 `/conversations`、对话 `/conversations/<id>` | 消息 | 标签"对话"（收件箱中的"对话"入口卡片由标签取代）及其详情 | 同 |
| 账户菜单 `/account` | 我的 | 区域首页（§2.4） | 同 |
| 资料 `/account/profile` | 我的 | 设置 → 资料 | 同 |
| 关于我 `/account/details` | 我的 | 设置 → 关于我（匹配用的资料；"喜欢的主题"移到"屏蔽与主题"） | 同；旧的 `?section=details` 照旧 |
| 认证 `/account/certificates` | 我的 | 顶部数字"证书"与"我拥有的 → 证书" | 同 |
| 登录与安全 `/account/security` | 我的 | 设置 → 账户与安全 | 同（第三方登录回来、step-up 的设置页仍是它） |
| 通知 `/account/mail` | 我的 | 设置 → 通知（邮件与推送） | 同 |
| App 设置 `/account/preferences` | 我的 | 设置 → App 设置（界面语言、Quiz 的语言、声音、**外观**、安装；"屏蔽"一节移走） | 同 |
| — | 我的 | 设置 → **屏蔽与主题**（F-S8 的屏蔽列表 + F-S9 的喜欢的主题与"只看喜欢的主题"） | **新** `/account/blocks` |
| 我的 ID 与店员 `/account/id` | 我的 | 设置 → **店员柜台**（我的 ID；是店员时"打开领奖台""我的交付"） | 同 |
| 领奖台 `/staff`、我的交付 `/staff/handled` | 我的 | 店员柜台的详情页 | 同 |
| 删除账户 `/account/delete` | 我的 | 页最下方"您的账户"组（只在入口允许或有待处理申请时，同现在） | 同 |
| 我的学分 `/wallet`、`/wallet/balance|rewards|history`、`/wallet/kudos/<方式>`、`/wallet/payout`、`/wallet/bank` | 我的 | 我拥有的 → Kudos 明细及其各页 | 同 |
| 我的推荐 `/referrals`、某次推荐 `/referral/<id>` | 我的 | 我拥有的 → 推荐 | 同 |
| 我的发起方 `/my-sponsor` | 我的 | 见 §2.1 | 同 |
| 欢迎（资料第二步）`/welcome` | — | 无导航（同现在），页头加"退出登录" | 同 |
| 帮助 `/help`、条款 `/legal/<种类>`、关于 `/about` | 我的（入口） | 设置 → 帮助、条款三项、关于；页面本身仍是公开页框架（不登录也能读） | 同 |
| 证书核验 `/verify/<key>`、公开认证页 `/u/<名>`、发起方主页 `/s/<handle>` | — | 公开页（不变） | 同 |
| 登录、注册、找回密码、`/auth/*` 邮件链接页、`/login/oidc`、`/mail/unsubscribe`、`/delete-account` | — | 入口页（17zk，不变） | 同 |

### 2.3 扫一扫与领奖码

- 标签"扫码"：现在的 `ScanPage`，识别逻辑一字不改（`scanTarget`、`targetPath`、`decoder`，T12 的修复保留，`scan.test.tsx` 照旧）。离开标签即停相机（组件卸载，现有逻辑）。
- 学者有**待到店领取**的奖品（`qb.app.prizes` 中 `kind = REDEEM`、`status = DUE`，§4.1）时，扫码标签顶部一个醒目按钮"出示领奖码（n）"：只有 1 个时直接打开该奖品的 `PassButton` 对话框（**一步到领奖码**，验收中的"到店时从底部的扫一扫一步到通行码"）；多于 1 个时转到标签"出示领奖码"。
- 标签"出示领奖码" `/scan/pass`：逐个列出这些奖品（发起方、奖品说明、期限），每个一个 `PassButton`（Q89c 的现有组件：二维码 + 六位数字、有效期 `PASS.lifeMs`、展示中按 `POLL_EVERY` 重读，均为现有常量）；没有时是空状态，链接到"已答 → 待领奖"。
- 相机被拒绝或没有相机时，"出示领奖码"照样可用（它不需要相机）。
- 用语：页面上沿用 App 现有的"领奖码 / 引き換えパス / pass"（Q89c 的界面用语），不另造"通行码"（§8 决定 4）。

### 2.4 我的

```
[头像首字母] 我的                 ← TitleBadge（与底部"我的"同一区域；头像沿用 Q67）
 名字 · 邮箱
┌─────────┬─────────┬─────────┐
│ Kudos    │ 待领奖品 │ 证书     │  ← 三个可点的数字
│ 1,200    │ 2        │ 3        │
│ 即将开放 │          │          │
└─────────┴─────────┴─────────┘
我拥有的：奖品 · 证书 · （知识结构图，F-S3） · 推荐 · Kudos 明细 · 我的发起方（只对发起方）
（成为发起方 BecomeSponsor，同现在）
设置：资料 · 关于我 · 账户与安全 · 通知 · 屏蔽与主题 · App 设置 · 店员柜台 · 帮助 · 条款（3 项）· 关于
您的账户：删除账户（条件同现在）
[退出登录]
```

- **Kudos** = 钱包的 `available`（`qb.app.wallet`，现有读取，只显示）→ `/wallet`。旁标"即将开放"，**条件取自开关而不是写死**：`qb.app.wallet` 新增一列 `usable`（`qb.transfer.enabled` 或 `qb.withdrawal.bank.enabled` 为 true，§4.2），为 false 时显示。首次上线两者都是 false（F-F10），开关打开后标签自动消失，不必改代码。钱包的余额页同一条件显示同一标签。
- **待领奖品** = `qb.app.prizes`（`open = true`）的条数（`count`）→ `/history/prizes`；与"已答"标签、底部徽标同一个查询（同一 query key，一次请求）。
- **证书** = `readMyCertifications()` 的条数（现有读取）→ `/account/certificates`。
- 三个数字是 `<Link>`，可读名称含数字与说明（如"Kudos 1,200，即将开放"）；读取失败时数字处显示"–"，链接照样可点。
- **知识结构图**：F-S3 交付时在 `areas.ts` 的"我拥有的"清单加一行（规格 F-S3 已写"我的 → 知识结构图"）；本项不放没有内容的入口（功能闭合）。路由预留为 `/account/map`，写进 `areas.ts` 的注释与 CLAUDE.md §5。
- **帮助**：`/help`（现有）；F-C10 交付时 AI 问答放在同一入口（规格 F-C10 "我的 → 帮助"）。F-C10 的"当前页面只取路由白名单中的名字"可直接用 `areas.ts` 的页面名。

### 2.5 发布详情

- 始终在标签之上：封面或视频（Q65 首页播放器接续到这里，`VideoStage` 与 `StartPageExposure` 不动）、名称、Quiz 标题、发起方一行、要点一行（题数、合格线、时限、奖励、节点、参加费、方式、截止）、仅受邀提示、屏蔽提示（F-S8）。
- 标签（`?tab=`，缺省"说明"；切换用 `replace`，返回键回到上一页而不是上一个标签）：

| 标签 | 内容（现有组件，testid 不变） |
|---|---|
| 说明 | 介绍 `detail-intro`、热度 `detail-stats`（原折叠，改为展开） |
| 奖励 | 方式与奖品 `detail-reward` 的内容（原折叠，改为展开）、各档 `detail-tiers`、问卷提示、推荐奖励 `ReferralPanel` |
| 资料 | 素材 `Materials`、商品 `publication-products`、发起方主页 `SponsorVisit`、关于发起方 `detail-sponsor-intro` |
| 规则 | 完整的规则（题数与抽题、合格线、时限、参加费、截止、只能答一次、点亮的节点）、比赛规则链接 `detail-contest-rules`（有奖励时）、举报 `ReportButton`、屏蔽菜单 `BlockMenu` |

- 某个标签没有内容时不显示该标签（如没有素材、商品与发起方介绍时仍有"发起方主页"，所以"资料"总在）。
- "开始答题"栏（`Action`：语言选择、观看提示、参加费、开始按钮及各种不可开始的说明）固定在底部：窄屏在底部菜单之上（`bottom: var(--bottom-bar-height)` + 安全区），宽屏贴底；内容区相应留出高度，最后一行不被遮住。
- `PublicationContent` 在共用目录 `qb/view/`（两个 SPA 各一份、必须相同）：加可选参数 `tabs`（当前标签与渲染链接的函数），不给时照旧竖排。发起方的预览（`sponsor/src/preview/PreviewPage.tsx`）本项不改，仍竖排（§8 决定 9）。

## 3. 旧地址

- 由 §2 可见，**旧地址全部照旧可用**：所有页面留在原地址，只是归到新的区域；新增的只有 `/history/prizes`、`/scan/pass`、`/account/blocks`。因此不需要新的转向，邮件、站内信与推送中的链接（`MessageProcesses.link`：`result/…`、`wallet`、`conversations/…`、`referrals`、`referral/…`、`p/…`；邮件 `{entryUrl.app}wallet`、`wallet/payout`；Stripe 回来的 `wallet/payout?onboarding=`）一个都不用改。
- 照 Q79 `LEGACY_PROFILE_PATHS` 的做法仍设**一张清单** `LEGACY_APP_PATHS`（`frame/areas.ts`），收**真正改过的地址**：现有的两处散落转向先收进来——`/delete-account` → `/auth/account-deletion`（Q71，商店登记的地址）、`/account?section=details` → `/account/details`（Q56）；以后任何地址改动都只加在这里。
- 另设 `OLD_ROUTES`：本计划写下时 App 的**全部路由样例**（§2.2 每一行一个代表地址，含带编号的形如 `/p/x`、`/wallet/kudos/PLATFORM`、`/inbox/message/x`）。测试逐条检查：`routeOf(地址)` 得到页面与区域（不是"找不到"）；在 `LEGACY_APP_PATHS` 中的转到登记的新地址。e2e 再逐条打开（§7）。

## 4. 服务端（只读模板，无迁移、无新参数）

### 4.1 新模板 `qb.app.prizes`（待领奖）

- 学者自己的奖品，三种合在一起（`UNION ALL`），最新在前：
  - `REDEEM`：`QbRedemption`（含推荐得来的，`sourceKind`），未领 = `DUE`、`CLAIMED`（与 `qb.app.redemptions` 的 `open` 同一条件）；
  - `CASH`：`QbCashReward`，未了结 = 与 `qb.app.cash-rewards` 的 `open` 同一条件；
  - `DIGITAL`：`QbDigitalPrize`，未了结 = `DUE`、`DELIVERED`、`OVERDUE`、`DISPUTED`（`CONFIRMED`、`VOID` 为已了结；`DELIVERED` 到 `confirmBy` 由现有任务自动确认，不会一直挂着）。
- 参数 `open`（bool，缺省全部）；结果：`kind`、`prizeId`、`attemptId`、`sourceKind`、`publicationId`、`publicationName`、`sponsorCompany`、`amount`、`description`、`status`、`dueAt`、`expiresAt`、`confirmBy`；`list.filters: [kind, status]`、`key: [kind, prizeId]`；`permissions: [qb.me]`；数据视图全是现有的 `taker:` 视图（只看自己的）与 `default:QbPublicationCard`、`default:QbSponsor`。
- 用处（一个来源，四处显示同一个数）：已答的"待领奖"标签与其徽标、底部"已答"的徽标、"我的"的"待领奖品"、扫一扫的"出示领奖码"（`kind = REDEEM`、`status = DUE`）。"已答 → 待领奖"中另有"待领 / 全部"的切换（全部 = `open` 不传），"我拥有的 → 奖品"就是它。
- 每条链接到它的结果页（推荐得来的到推荐页，现有 `rewardPath`）；到店奖品另有 `PassButton`，电子奖品的打开与确认、现金的确认与异议照旧在结果页（不在列表中重做一遍）。
- 首页的两个提示照旧读 `qb.app.redemptions` / `qb.app.cash-rewards`（首页不变）。
- 消息：`query.qb.app.prizes` 与各列，三语（中文、日文、英文）。

### 4.2 `qb.app.wallet` 加一列 `usable`

- `coalesce(bool_or(k = 'qb.transfer.enabled' AND v = 'true') OR bool_or(k = 'qb.withdrawal.bank.enabled' AND v = 'true'), false)`，照 `bank-account.sql` 读 `SysParam` 的写法（该模板已读 `SysParam`）。只用于"即将开放"。消息加列名三语。

## 5. 前端改动

### 5.1 `frame/areas.ts`（新，一处定义）

- `AREAS`：5 个区域，各有 `key`（`home` / `history` / `scan` / `messages` / `me`，即底部菜单的 testid `bottom-bar-<key>`）、消息键、图标（`House` / `History` / `ScanLine` / `MessagesSquare` / `UserRound`）、根地址、`raised`（只有 `scan`）。
- `AREA_TABS`：已答（已答、待领奖）、扫一扫（扫码、出示领奖码）、消息（收件箱、对话）。
- `ME_OWNED`、`ME_SETTINGS`：我的两组的条目（图标、标题键、提示键、地址、显示条件）。
- `routeOf(path, search)`：页面名与区域（取代 `BottomNav` 中手写的归属判断与 `SignedIn` 中的 if 链的归属部分；if 链本身保持，以免与其他会话的改动冲突过大）。
- `LEGACY_APP_PATHS`、`OLD_ROUTES`（§3）。
- **规则**：标题图标与底部菜单图标都从 `AREAS` 取，测试断言两处是同一个对象。

### 5.2 框架（`App.tsx`、新 `frame/AppFrame.tsx`）

- 窄屏：平台 `BottomBar`（17zj）5 项，`renderLink` 用 App 的 `Link`；消息与已答的徽标以 `aria-label` 写进可读名称（如"消息，3 条未读"，沿用 `inbox.navUnread` 的写法），徽标数字本身不重复读出。外层 `md:hidden`。
- 宽屏：左侧一列 `frame/SideRail.tsx`（App 自己的小组件：同一个 `AREAS`、`aria-current`、徽标、键盘可达；宽度用平台侧栏 token `--sidebar-width`，不写新数字），`hidden md:flex`；内容列照旧 `max-w-md` 居中于剩余宽度（首页的宽度因此不变）。
- **不用 `AppShell`**（§8 决定 1）：它的页头带侧栏开关（窄屏会多出一个菜单按钮、改变首页页头），并在跨断点时换一棵组件树（手机横屏、平板转屏会重新挂载页面与播放器）。同一棵树以 CSS 在两种宽度间切换。
- `Header`：去掉 `app-menu` 下拉及其 import；欢迎页加"退出登录"（`sign-out`，testid 沿用）。其余（品牌、返回、筛选、我的发起方、搜索、测试系统横条）不动。
- 主内容 `pb-24` 改为按平台变量留出底部菜单高度（窄屏 `--bottom-bar-height` + 安全区；宽屏不留）；首页的 `pt-0` 与宽度不变。
- 路由切换时焦点移到新页面的标题（`data-slot="page-title"`，与平台 `AppShell` 的 `routeKey` 同一做法，App 自己实现几行）；首页没有可见标题，焦点不动（不改首页）。

### 5.3 页头与标签（新 `frame/AreaHeader.tsx`）

- `AreaHeader`：`TitleBadge`（Q67 的圆形图标，即原型"我的"页的样式）+ `h1`（`data-slot="page-title"`、`tabIndex=-1`）+ 可选的一行说明 + 平台 `PageTabs`（标签是链接、`aria-current`、方向键切换、窄屏横向滚动）。
- 用在：已答两标签、扫一扫两标签、消息两标签（收件箱、对话列表）、我的、我的各子页（资料、关于我、认证、账户与安全、通知、屏蔽与主题、App 设置、店员柜台、删除账户）、Kudos 明细各页、推荐、我的发起方、领奖台与我的交付。各页原来手写的 `TitleBadge + h1` 改为它；子页的"返回我的"链接保留。
- 不用平台 `PageHeader` 的 `icon`（小号灰色图标）：会改变 App 已有的 Q67 样式（§8 决定 2）。
- 详情页（发布、结果、对话、消息详情、兑奖、领奖码）不加区域图标（它们不是一级页面），保留各自现有的标题。

### 5.4 各页

- `pages/HistoryPage.tsx`：外加标签；新 `pages/PrizesPage.tsx`（待领奖，读 `qb.app.prizes`，"待领 / 全部"切换，每页条数沿用 `HISTORY_PAGE`、"更多"）。
- `scan/ScanPage.tsx`：外加标签与"出示领奖码（n）"按钮；新 `pass/MyPassesPage.tsx`。
- `inbox/InboxPage.tsx`、`conversation/ConversationPages.tsx`：外加标签；收件箱中的 `ConversationsLink` 卡片删去（由标签取代，未读数显示在标签上）。消息的底部徽标 = 收件箱未读 + 对话未读：对话未读的读取放进 `useUnread` 的同一节奏（进入、回到前台、`UNREAD_INTERVAL`），不新增轮询。
- `pages/AccountPage.tsx`：菜单改为 §2.4 的"我的"；`ACCOUNT_PARTS` 加 `blocks`；`DetailsPart` 去掉 `LikedTopics`、`PreferencesPart` 去掉 `BlockList`，两者合成 `BlocksPart`；`PreferencesPart` 加"外观"一节（平台 `APPEARANCES` 与 `useAppearance`，三选一，testid `appearance-<值>` 沿用）；`id` 的标题改为"店员柜台"。
- `wallet/WalletPage.tsx`：余额旁"即将开放"（§2.4 同一条件）。
- `pages/PublicationPage.tsx` 与 `qb/view/PublicationContent.tsx`（两份同步）：§2.5。
- 消息键：三语一起加（`nav.*`、`frame.*`、`me.*`、`prizes.*`、`pass.mine.*`），用词照 `docs/quizbuks/glossary.md`（学者、发起方、Kudos），不出现"后台"；用词测试 `terms.test.mjs` 与 `messages.test.ts` 覆盖。

### 5.5 名称（三语）

| 位置 | 中文 | 日本語 | English |
|---|---|---|---|
| 底部 | 首页 / 已答 / 扫一扫 / 消息 / 我的 | ホーム / 回答済み / スキャン / メッセージ / マイページ | Home / Answered / Scan / Messages / Me |
| 已答的标签 | 已答 / 待领奖 | 回答済み / 受け取り待ち | Answered / To collect |
| 扫一扫的标签 | 扫码 / 出示领奖码 | スキャン / 引き換えパスを表示 | Scan / Show my pass |
| 消息的标签 | 收件箱 / 对话 | 受信箱 / 会話 | Inbox / Conversations |
| 我的的数字 | Kudos / 待领奖品 / 证书；"即将开放" | クドス / 受け取り待ちの景品 / 証明書；「近日公開」 | Kudos / Prizes to collect / Certificates; "Coming soon" |
| 我的的两组 | 我拥有的 / 设置 | 持っているもの / 設定 | What I have / Settings |
| 新条目 | 屏蔽与主题 / 店员柜台 / 退出登录 | ブロックとトピック / スタッフカウンター / ログアウト | Blocks and topics / Staff counter / Sign out |

（"证书"与现有"我的认证"的用词按 F-S5 定稿时对齐；运营商在任何新文字中出现时日文为"運営パートナー"。）

## 6. 首页像素一致：视觉对比

- 新 e2e `home-frame.spec.ts`，在**首个 PR（不改界面）**中提交，基线截图在 CI 上（Ubuntu 24.04 固定镜像，P8）以 `--update-snapshots` 生成后提交（`home-frame.spec.ts-snapshots/`），之后的 PR 必须在不更新基线的情况下通过。
- 数据：测试自建一个发起方与 3 个固定名称的发布（无视频、固定封面占位、固定文字），以唯一的搜索词 `/?q=<词>` 只显示它们（共享的 e2e 数据库中其他测试的发布不会混入），另一张不带搜索的只截首页结构区域（短视频一排的位置、提示区）并遮住卡片。
- 截取：`main` 元素（首页内容）在 360、390、1280 px，亮暗两种外观；页头在 360、390 px，遮住右端按钮区（菜单按钮去掉后其余按钮右移一格，属预期），另断言右端按钮的集合恰为"筛选、我的发起方（如有）、搜索"。底部菜单与宽屏左列不在比较范围（规格要求改的部分）。
- `animations: 'disabled'`，遮住会变的部分（统计数字、相对时间、视频位置）；`maxDiffPixels: 0`（像素一致即 0，测试常量，写在文件中并说明）。
- 补充一条结构断言：首页 `main` 内的 testid 序列与基线相同（DOM 层面，排查截图差异时用）。

## 7. 测试

### 单元（Vitest，`app/src`）

- `frame/areas.test.ts`：5 个区域、`scan` 在第 3 位且只有它 `raised`；`OLD_ROUTES` 每条都有页面与区域；`LEGACY_APP_PATHS` 每条转到登记的地址；新增的三个地址有区域；每个一级页面标题的图标与底部菜单同一个；`itemPage` / `scanTarget` 的现有测试不改。
- `App.test.tsx`、`inbox.test.tsx`、`staff.test.tsx`、`scan.test.tsx`、`Play.test.tsx`、`Legal.test.tsx`：改掉 `app-menu`、`nav-*` 的用法；新增：没有 `app-menu`；欢迎页能退出登录；底部徽标的可读名称；宽屏有左列且无底部菜单（以 `matchMedia` 模拟）。
- 新 `frame/me.test.tsx`："即将开放"随 `usable`；三个数字的链接与可读名称；读取失败显示"–"。
- 新 `pages/prizes.test.tsx`、`pass/my-passes.test.tsx`：三种奖品的显示与链接；只有 1 个到店奖品时按钮直接打开领奖码；没有时空状态。
- `PublicationContent`：不给 `tabs` 时的输出不变（快照或现有测试照旧过）；给 `tabs` 时四个标签与内容归属、无内容的标签隐藏。
- 每个新页面 `expectAccessible()`（`@jabiz/ui/testing`）。

### 服务端（IT，真实 PostgreSQL）

- `qb.app.prizes`：三种奖品各种状态的归属（未领 / 已了结），只看自己的，推荐得来的到店与现金奖品在内，`count` 正确；模板经平台启动检查（`platformCheck`）。
- `qb.app.wallet.usable`：两个开关的四种组合。

### e2e（`quizbuks-web/e2e/tests/`）

| spec | 改动 |
|---|---|
| `a11y.spec.ts` | 退出登录改在欢迎页页头与"我的"底部；`nav-account` → `bottom-bar-me`；`nav-inbox` → `bottom-bar-messages`；新增每个区域（已答两标签、扫一扫两标签、消息两标签、我的、屏蔽与主题、App 设置）亮暗、360 与 1280 px 的 axe |
| `reward-methods.spec.ts` | 扫一扫从底部进入（`bottom-bar-scan`，360 px 居中、无横向滚动）；新增"出示领奖码"从扫一扫一步打开；店员经"我的 → 店员柜台"；`nav-history` → `bottom-bar-history`，待领奖标签与徽标 |
| `digital-prize.spec.ts`、`play.spec.ts`、`store/screenshots.spec.ts` | `nav-history` → `bottom-bar-history`；电子奖品出现在待领奖、确认后消失 |
| `payout.spec.ts`、`legal.spec.ts` | `nav-wallet` → 我的的 Kudos 数字；`nav-home` → `bottom-bar-home`；"即将开放"在开关关闭时显示 |
| `inbox.spec.ts` | 徽标与可读名称改在 `bottom-bar-messages` |
| `conversations.spec.ts` | `inbox-conversations` 卡片 → 消息的"对话"标签（未读数在标签上） |
| `blocks.spec.ts`、`topics.spec.ts`、`exposure.spec.ts` | `/account/preferences` 的屏蔽、`/account/details` 的主题 → `/account/blocks` |
| `install.spec.ts` | 安装入口仍在 `/account/preferences`（不变，只核对页头与标题） |
| `sponsor-in-app.spec.ts` | 页头图标不变；另核对"我的 → 我的发起方" |
| 新 `taker-frame.spec.ts` | 360 px：底部 5 项都可点、扫一扫居中（与视口中线相差不超过 1 px）、凸起；每个区域打开无横向滚动；1280 px：左列 5 项、无底部菜单；`OLD_ROUTES` 逐条打开都落到对应页面（带编号的用测试自己造的数据）；`LEGACY_APP_PATHS` 逐条转向；发布详情四个标签与固定的"开始答题"（不遮住最后一行） |
| 新 `home-frame.spec.ts` | §6 |

### 构建

`pnpm lint`、`typecheck`、`test`（两个 SPA）、`diff -r` 两份 `qb/`、`app:check`、`./gradlew check`（含 `platformCheck`）、`:quizbuks:test --tests '*ScenarioTest'`（模板变化不影响场景快照，若影响则说明后更新）。

## 8. 决定（需转达总控 / 需求方，可改）

1. **宽屏左列用 App 自己的小组件，窄屏用平台 `BottomBar`，不用 `AppShell`**：`AppShell` 的页头总有侧栏开关（窄屏多一个菜单按钮，与"取消右上角菜单"和首页不变相悖），且按 `useIsMobile` 换整棵树（转屏重挂页面与播放器）。代价：左列是 App 的几十行代码而非平台组件；若以后平台给 `AppShell` 加"无开关、纯 CSS 切换"的形态，再换过去（列入"以后"，不阻塞）。
2. **标题图标用 App 现有的 `TitleBadge`（Q67）**，不用平台 `PageHeader` 的小灰图标：保持 App 现有观感，也正是原型"我的"页的样式；与底部菜单同一图标由 `areas.ts` 保证。
3. **地址全部不变**，区域归属由路由表决定；新加的只有三个地址。理由：邮件、站内信、推送、Stripe 回调、商店登记的地址都不必改，旧邮件不靠转向；与其他会话在服务端链接上的冲突为零。`LEGACY_APP_PATHS` 仍是唯一清单，先收现有的两处转向。
4. **用语沿用"领奖码 / 引き換えパス / pass"**（Q89c 已上线的界面用语），规格中的"通行码"是计划名。
5. **"待领奖"用一个新模板 `qb.app.prizes`** 把到店、现金、电子三种合起来：一个来源给标签、两处徽标、"我的"数字与扫一扫，数字处处相同；含推荐得来的奖品（`qb.app.history` 不含）。首页的提示仍读原来两个模板（首页不变）。
6. **底部"已答"也带待领奖徽标**（不只标签上）：规格的目的是"到店前一眼看到"；首页的提示是另一处，不冲突。
7. **"即将开放"取自开关**（`qb.app.wallet.usable`），不写死，F-F10 打开开关后自动消失。
8. **"设置"多两项**：规格列的是"资料、账户与安全、通知、屏蔽与主题、店员柜台、帮助与条款"；现有的"关于我"与"App 设置"（语言、声音、外观、安装）不能删（删除的功能：无），作为同组的两行保留；外观从右上角菜单移入"App 设置"。
9. **发起方的预览暂不改为标签**：`PublicationContent` 的标签是可选参数，预览照旧竖排，避免与 F-F3 同时改发起方页面；F-F3 若要预览与 App 一致，只需传入参数。
10. **对话未读并入消息徽标**，读取随 `useUnread` 的节奏，不新增轮询。
11. **页头的"我的发起方"图标保留**（首页不变），"我的"中另加一行。

## 9. PR 拆分

| PR | 内容 | 粗估 |
|---|---|---|
| F-F4a | `frame/areas.ts`（区域、路由归属、`OLD_ROUTES`、`LEGACY_APP_PATHS` 收拢现有两处转向）与单元测试；`home-frame.spec.ts` 与 CI 生成的基线截图。**界面无变化** | 0.5–1 天 |
| F-F4b | 框架（底部 5 项、宽屏左列、去掉右上角菜单、`AreaHeader` 与标签）、我的、屏蔽与主题、消息标签、已答的待领奖、扫一扫的出示领奖码；`qb.app.prizes`、`qb.app.wallet.usable`；e2e、帮助、CLAUDE.md §5 | 2.5–3 天 |
| F-F4c | 发布详情的四个标签与固定的"开始答题"（`qb/view/PublicationContent.tsx` 两份同步） | 1 天 |

F-F4a 先合，使基线截图出自未改动的界面；F-F4b、F-F4c 不得更新基线。

## 10. CLAUDE.md 第 5 节（F-F4b 中写入，与 F-F3 的同一条合写）

> - **学者 App 的框架**（F-F4，`app/src/frame/`）：底部 5 项（首页 / 已答 / 扫一扫（正中、凸起）/ 消息 / 我的），宽屏为左侧一列；区域、标签、"我的"的条目与每个页面属于哪个区域只在 `frame/areas.ts`，标题图标与底部菜单图标都从它取。**新功能加为某个区域里的标签或"我的"中的一项，不加底部菜单项，也不恢复右上角菜单。** 地址改动只经 `LEGACY_APP_PATHS`（测试逐条检查），`OLD_ROUTES` 只增不删。首页（`FeedPage`）的改动须同时更新视觉对比基线并说明理由（`home-frame.spec.ts`）。"我的图"的路由预留 `/account/map`（F-S3）。

同时删去第 5 节中"右上角菜单"的描述（Q62 的说明）与账户菜单的旧描述。

## 11. 帮助与文档

- **新**帮助底稿 `docs/quizbuks/help/taker-app.md`（三语，FAQ `audience = TAKER`，由内容管理员在平台管理系统的"FAQ"中发布）：底部五项各做什么；到店怎样出示领奖码（扫一扫 → 出示领奖码）；待领奖在哪里；在哪里退出登录、切换外观；旧书签照常可用。
- 改路径描述（三语）：`help/blocks.md`（"账户 → App 设置 → 屏蔽" → "我的 → 设置 → 屏蔽与主题"）、`help/topics.md`（"账户 → 关于我" → "我的 → 设置 → 屏蔽与主题"）；`operations.md`（§7.30 的屏蔽、通行密钥一节的"账户 → 登录与安全"）；`troubleshooting.md`（"账户 → 通行密钥"）；`launch-checklist.md`（安装入口）；`store/appstore-review.md` 与隐私政策三语草稿（"Account → Delete account" → "Me → Delete account"，法律文本在开发阶段，直接改，Q91 §0-10）。
- **安装到主屏幕的提示与截图**：安装入口仍在"App 设置"，提示文字的路径改为"我的 → 设置 → App 设置"；商店截图由 `e2e/store/screenshots.spec.ts` 生成（不提交），随底部菜单改动重跑 workflow `quizbuks-store.yml` 并请需求方确认；帮助中心若有安装截图，由内容管理员按新界面替换（截图出自同一 workflow）。
- 进度表 `first-launch-progress.md` 的 F-F4 一行（S3）。

## 12. 运营闭合

- 用户路径：任何页面都能经底部菜单（或宽屏左列）到达 5 个区域；到店：扫一扫 → 出示领奖码（只有一个奖品时一步）；店员：我的 → 设置 → 店员柜台 → 打开领奖台；退出登录在"我的"底部，欢迎页另有。旧书签、旧邮件链接全部照旧。
- 管理：不涉及权限；"我的发起方"、删除账户、店员柜台中的领奖台等照旧按服务端的数据显示。
- 核对：e2e 覆盖底部 5 项、360 px、宽屏左列、旧地址清单；视觉对比守住首页。审计与统计：无。

## 13. 风险

- **合并冲突**（其他会话同时改学者 App）。最可能冲突的文件：
  - `quizbuks-web/app/src/App.tsx`（F-S3 的"我的图"路由、F-C10 的帮助、F-S6 的搜索、F-S4）——本项只改框架部分，路由 if 链保留原样以减小冲突面；
  - `pages/AccountPage.tsx`（F-S3、F-S5 证书、F-C10、F-M15 注销）；
  - `app/src/messages.ts`（几乎所有会话；新键集中放在新的 `frame/messages.ts`，`messages.ts` 只改少数旧键）；
  - `qb/view/PublicationContent.tsx`（两个 SPA 的共用文件，F-S3 节点、F-S6、F-F3 预览）；
  - `pages/PublicationPage.tsx`、`pages/HistoryPage.tsx`、`scan/ScanPage.tsx`、`inbox/InboxPage.tsx`、`conversation/ConversationPages.tsx`、`wallet/WalletPage.tsx`（F-M 系列）；
  - e2e：`a11y.spec.ts`、`reward-methods.spec.ts`、`blocks.spec.ts`、`topics.spec.ts`；
  - `backend/quizbuks/CLAUDE.md` 第 5 节（F-F3 同节）、`docs/quizbuks/operations.md`、`help/blocks.md`、`help/topics.md`。
  做法：F-F4a 尽早合入（区域表先占位，其他会话的新页面直接登记在 `areas.ts`）；F-F4b 开工前与 S2（F-S3）对一下"我的图"的路由与条目。
- **视觉对比的稳定性**：字体与渲染随机器不同——基线只在 CI 的固定镜像上生成；数据随共享库变化——以唯一搜索词隔离、遮住会变的部分。若仍偶发差异，先查 DOM 结构断言再看截图，不放宽 `maxDiffPixels`。
- **视频与曝光**：`VideoStage` 按占位元素的位置叠放播放器，宽屏左列使内容列右移；F-S10、F-S13 的 `shorts.spec.ts`、`watch-video.spec.ts`、`exposure.spec.ts` 须在宽屏也通过。发布页标签切换不能卸载顶部的视频（视频在标签之上）。
- **固定的"开始答题"**与底部菜单、iOS 安全区、键盘弹出时的遮挡：360 px 与 iPhone 尺寸的 e2e 断言最后一行可见。
- **相机**：扫一扫成为底部一项后被点开得更多；相机只在"扫码"标签上启动（现有逻辑），"出示领奖码"标签不启动相机。

## 14. 未决（请总控 / 需求方回答）

1. 规格的"通行码"与 App 现有的"领奖码 / 引き換えパス"：是否统一为后者（本计划如此）？
2. 底部"已答"是否也带待领奖徽标（本计划带，决定 6），还是只在标签上？
3. 日文底部"我的"用"マイページ"还是"マイ"（5 项在 360 px 上的宽度都可以）？
4. "证书"数字数的是现有的"我的认证"（Q77），F-S5 的节点证书合入后是否合并计数？
5. 发起方的预览是否在 F-F3 中改为与 App 相同的四个标签（决定 9）？
6. "设置"中保留"关于我"与"App 设置"两行（决定 8）是否可以，还是"关于我"并入"资料"一页？
