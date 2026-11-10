# F-F3 发起方页面的区域、标签与迁移：实施计划

依据：`docs/quizbuks/first-launch-spec.md` F-F3（以它为准）；`Q108-console-layout.md` §2、§3、§5 只作参考（已归档）。
会话 S3，计划分支 `1.3/quizbuks-ff3-sponsor-areas`（只含本文件）；实现分 4 个 PR（§9），分支 `1.3/quizbuks-ff3a-areas` … `ff3d`。
前提已满足：F-F1（平台 17zj：`AppShell` 的 `collapsible="icon"` / `create` / `bottomBar` / `routeKey`、`ShellNav`、`PageTabs`、`PageHeader` 的 `icon` / `primaryAction` / `tabs`、`GlobalCreate`、`BottomBar`、`Fab`、`ListPage` / `DetailPage` / `FormPage` 与 `useUnsavedChanges`、内容宽度 token `--content-max` / `--content-form-max`）、F-F2（17zk 入口页，发起方页面已接入）、F-F6（17zi）都已到 `1.3/quizbuks`。
F-F5b（不再用那个停用的叫法，S3 自己）须先合入：本项的新文字照 `glossary.md` 写。

## 0. 范围

把 `quizbuks-web/sponsor/src/App.tsx` 的 `groups`（6 组 19 项）改成"首页 + 8 个区域"，区域里的页面用标题下的页内标签（`PageTabs`）切换；
头像菜单、全局"＋ 新建"、手机底部菜单；"待我处理"；发布详情的标签；所有页面套上三种模板；旧地址一张清单统一转向；帮助（三语）与 `operations.md` 的路径；e2e 改写。

- **删除的功能：无。** 每个现有页面都有新位置（§2 的对照表逐项列出），旧地址全部转向。移动而不是删除的：页头的"退出"按钮与外观切换移进头像菜单；商户中心子导航右侧的说明移到概况的卡片上；
  `ProfileLayout` 的子导航换成页内标签。
- **行为上的改变**（PR 描述中逐条列出）：
  1. （F-F3a 实现时取消）旧的 `/messages` 转到"通知与公告"（`/messages/notices`），与原来的缺省一致；`?tab=conversations` 转到"对话"。
  2. 资金区域里"平台费账户"与"账单"对所有发起方显示（没有 Kudos 的发起方在平台费账户中充值到现金，充值记录与收据也列在那里）；现在它们在钱包页内、钱包页只对可付 Kudos 的发起方显示（Q28），没有 Kudos 奖励方式的发起方看不到自己的账单——这是缺口，顺带补上（服务端照旧判断）。"学分钱包"仍只对可付 Kudos 的发起方显示。
  3. 合作权益区域只对持有 `BENEFIT_PROVIDER` 或 `BENEFIT_SPONSOR` 的发起方显示（规格）；现在对所有人显示。直接打开地址照旧可用，能做什么由服务端判断。两种角色都没有的发起方在"发起方中心 → 概况"有一张"合作权益"卡片（`profile/BenefitsEntryCard.tsx`），带到 `/benefits` 的目录与"怎样开通"的说明，原有的入口不减少（总控 2026-11-09）。
  4. Stripe 充值的返回地址与邮件中的链接改为新地址（`CheckoutRequest.returnUrl`、`mail.qb.*` 中的 `{entryUrl.sponsor}wallet`），老邮件与进行中的 Checkout 靠转向。
- **不做**：用户自定义首页、拖动菜单；运营商管理系统与平台管理系统扩展换框架；Quiz 编辑器内部的四个标签（基本信息 / 题目 / 资料 / 语言）改成路由（与 S1 的 F-S6、F-S7 冲突最大，留在页内，§2 注 3）；学者 App（F-F4）。
- **无迁移。** 新增一个只读模板 `qb.sponsor.todo`、一个参数 `qb.sponsor.bottom-bar`（§4）；后端只改 Stripe 返回地址与邮件文字中的路径。

## 1. 现在的菜单（`App.tsx` `groups`，6 组 19 项）

| 组 | 项（路由） |
|---|---|
| 概览 | 概览 `/dashboard` |
| 内容 | Quiz `/quizzes`、发布 `/publications`、树 `/tree`、资源库 `/assets` |
| 奖励 | 现金奖励 `/cash-rewards`、电子奖品 `/digital-prizes`、到店兑换 `/redeem` |
| 赞助权益 | 权益目录 `/benefits`、我的权益 `/benefits/offers`、洽谈 `/benefits/conversations`、采购单 `/benefits/purchases` |
| 资金 | 钱包 `/wallet`（只在可付 Kudos 时）、交易 `/transactions` |
| 设置 | 发起方资料 `/profile`、统计 `/stats`、消息 `/messages`（未读对话的徽标）、账户 `/account`、帮助 `/help` |

页头：显示名、`ThemeToggle`、"退出"（`sign-out`）。路由是 `SignedIn` 里一长串 `if / else if`；旧地址两张表：`profile/paths.ts` 的 `LEGACY_PROFILE_PATHS`（Q79）与 `MOVED_PATHS`（F-C1c）。

## 2. 新的结构与完整对照

区域与标签只在一处定义：`sponsor/src/shell/areas.ts`（区域键、图标、名称的消息键、标签列表、各标签的路由与显示条件）。菜单（`ShellNav`）、页内标签（`PageTabs`）、底部菜单、"更多"、区域根地址的转向都从它生成。

| 区域（中 / 日 / 英） | 图标 | 标签 → 地址 | 显示条件 |
|---|---|---|---|
| 首页 / ホーム / Home | `House` | 仪表盘与待我处理 `/home` | 全部 |
| Quiz / クイズ / Quizzes | `FileQuestion` | 列表 `/quizzes`；编辑 `/quizzes/<id>`（详情，无标签行，面包屑回列表） | 全部 |
| 发布 / 公開 / Publications | `Megaphone` | 列表 `/publications` / 结构图 `/publications/map`；发布详情另有标签（§2.2） | 全部 |
| 奖品与兑换 / 景品と引き換え / Prizes | `Gift` | 到店兑换 `/prizes/redeem` / 现金奖励 `/prizes/cash` / 电子奖品 `/prizes/digital` | 全部 |
| 客户与消息 / お客様とメッセージ / Customers & messages | `MessagesSquare`（徽标：未读对话） | 对话 `/messages/conversations` / 通知与公告 `/messages/notices`（"客户"标签以后再做，§2 注 4） | 全部 |
| 合作权益 / 協賛特典 / Sponsored benefits | `Handshake` | 目录 `/benefits` / 我的权益 `/benefits/offers` / 洽谈 `/benefits/conversations` / 采购单 `/benefits/purchases` | `qb.sponsor.benefit-access` 中任一角色 `open` |
| 资金 / 資金 / Money | `Wallet` | 交易 `/money/transactions` / 学分钱包 `/money/wallet` / 平台费账户 `/money/account` / AI 额度 `/money/ai` / 账单 `/money/bills` | 学分钱包只在可付 Kudos 时（Q28，同现在） |
| 资源库 / リソースライブラリ / Asset library | `Library` | 全部 `/assets` / 素材 `/assets/media` / 文字 `/assets/text` / 奖品 `/assets/prizes` / 商品 `/assets/products` | 全部 |
| 发起方中心 / 主催者センター / Sponsor hub | `Store` | 概况 `/profile` / 基本信息 `/profile/basics` / 公开主页 `/profile/page`（下有统计 `/profile/page/stats`）/ 店员 `/profile/staff` / 邮件服务器 `/profile/mail-server` | 全部 |

- 区域根地址（`/prizes`、`/money`、`/messages` 等）转到第一个**可见**标签；打开不可见标签的地址（如没有 Kudos 时的 `/money/wallet`）同样转到该区域第一个可见标签。
- 合作权益、资源库、发起方中心沿用现有前缀，书签不变。区域名的中文照规格；"合作权益"的日文、英文沿用现有菜单的叫法（協賛特典 / Sponsored benefits），三语写进 `glossary.md`。

### 2.1 每个现有页面的去处（旧地址 → 区域 / 标签 → 新地址）

| # | 现在 | 旧地址 | 区域 → 标签 | 新地址 | 转向 |
|---|---|---|---|---|---|
| 1 | 概览（仪表盘、分析图表、屏蔽你的学者、AI 用量卡） | `/dashboard` | 首页 | `/home` | 是 |
| 2 | Quiz 列表 | `/quizzes` | Quiz → 列表 | 不变 | — |
| 3 | Quiz 编辑器、预览 | `/quizzes/<id>`、`/quizzes/<id>/preview` | Quiz → 编辑 | 不变 | — |
| 4 | 发布列表（导入发布设置、F-C4 的"全部提交"） | `/publications` | 发布 → 列表 | 不变 | — |
| 5 | 新建发布、草稿（向导，`?step=`） | `/publications/new`、`/publications/<id>` | 发布 → 表单页 | 不变 | — |
| 6 | 已提交的发布 | `/publications/<id>` | 发布详情 → 概况 | 不变 | — |
| 7 | 单个发布的分析 | `/publications/<id>/analytics` | 发布详情 → 统计 | 不变 | — |
| 8 | 发布预览 | `/publications/<id>/preview` | 发布 | 不变 | — |
| 9 | 树（F-S3 之前是"正在更新"） | `/tree`（`?publication=`） | 发布 → 结构图 | `/publications/map`（带原查询） | 是 |
| 10 | 统计（主页访问与商品点击） | `/stats` | 发起方中心 → 公开主页 → 统计（总控 2026-11-09） | `/profile/page/stats` | 是 |
| 11 | 资源库各标签 | `/assets?tab=all\|media\|text\|prize\|products` | 资源库 → 全部 / 素材 / 文字 / 奖品 / 商品 | `/assets`、`/assets/media`、`/assets/text`、`/assets/prizes`、`/assets/products` | 是（按 `tab`） |
| 12 | 到店兑换 | `/redeem` | 奖品与兑换 → 到店兑换 | `/prizes/redeem` | 是 |
| 13 | 现金奖励 | `/cash-rewards` | 奖品与兑换 → 现金奖励 | `/prizes/cash` | 是 |
| 14 | 电子奖品 | `/digital-prizes` | 奖品与兑换 → 电子奖品 | `/prizes/digital` | 是 |
| 15–18 | 权益目录、我的权益、洽谈（含 `/<id>`）、采购单（含 `/<id>`、`/new?offer=`） | `/benefits/**` | 合作权益 → 各标签 | 不变 | — |
| 19 | 钱包：Kudos 余额、待充值、充值记录与收据、充值对话框 | `/wallet` | 资金 → 学分钱包 | `/money/wallet` | 是 |
| 20 | 钱包：现金账户（入场费结算、兑换） | `/wallet` 页内 | 资金 → 平台费账户（F-M1 在此扩成赠送额度 + 现金） | `/money/account` | — |
| 21 | 钱包：平台的账单（Q87） | `/wallet` 页内 | 资金 → 账单 | `/money/bills` | — |
| 22 | 卡充值的页面（Stripe 返回 `?checkout=`） | `/wallet/topups/<id>` | 资金 → 学分钱包 | `/money/wallet/topups/<id>`（带原查询） | 是（前缀） |
| 23 | 银行转账充值的页面 | `/wallet/bank/<id>` | 资金 → 学分钱包 | `/money/wallet/bank/<id>` | 是（前缀） |
| 24 | AI 用量卡（现在只在仪表盘） | — | 资金 → AI 额度（首页照旧有；本月的用量卡已有内容，所以首次就显示这个标签；F-M9（S2 的 M7）在此加购买） | `/money/ai` | — |
| 25 | 交易（筛选在地址中） | `/transactions?…` | 资金 → 交易 | `/money/transactions?…`（带原查询） | 是 |
| 26 | 发起方资料：概况、基本信息、公开主页、店员、邮件服务器 | `/profile/**` | 发起方中心 → 各标签 | 不变 | — |
| 27 | 消息中心：我发的通知、收到的公告 | `/messages` | 客户与消息 → 通知与公告（页内"我发的 / 收到的"两个页内切换） | `/messages/notices` | 是 |
| 28 | 消息中心：对话 | `/messages?tab=conversations` | 客户与消息 → 对话 | `/messages/conversations` | 是 |
| 29 | 一个对话 | `/messages/conversations/<id>` | 客户与消息 → 对话 | 不变 | — |
| 30 | 账户（地址、密码、二次验证、会话、登录记录、邮件设置、语言、音效、删除账号） | `/account` | 头像菜单 → 账户与安全 | 不变 | — |
| 31 | 帮助中心 | `/help` | 头像菜单 → 帮助 | 不变 | — |
| 32 | 外观切换（页头 `ThemeToggle`）、退出（页头按钮） | — | 头像菜单 → 外观、退出 | — | — |
| 33 | 条款、试答、登录、注册、找回密码、邮件链接页、退订、第三方回调 | `/legal/*`、`/try/<key>`、`/login`、`/signup`、`/password-reset`、`/auth/*`、`/mail/unsubscribe`、`/login/oidc` | 不在框架内（入口页模板 / 公开页） | 不变 | — |
| 34 | Q79 之前的地址 | `/onboarding`、`/page`、`/staff`、`/mail-server` | 发起方中心 | `/profile/basics` 等 | 是（并入清单） |
| 35 | F-C1c 搬走的商品与认证 | `/products`、`/profile/products`、`/certifications` | 资源库 | `/assets/products`、`/assets` | 是（并入清单，目标改为新地址，不连环转） |

注：
1. **"认证"标签不加**：规格的资源库标签列了"认证"，但发起方自己的认证已由 F-S2c2（D6，需求方确认）删除，资源库现在没有这个标签；旧地址 `/certifications` 照旧转到资源库。
2. **统计**：内容是公开主页的访问与商品点击，放在"发起方中心 → 公开主页"下（公开主页的编辑与统计两段，统计有自己的地址 `/profile/page/stats`；总控 2026-11-09 答复问题 3，取代规格中的"发布 → 统计"）。
3. **Quiz 编辑器**内部的 4 个标签留在页内（不是路由）：S1 的 F-S6、F-S7 正在改编辑器；本项只给它的 `PageHeader` 加区域图标与面包屑。
4. **客户**标签（"结构图上的人"）**以后再做**（总控 2026-11-09）：旧的树上的客户随 F-S2c2（D5）去掉，现在没有页面，首次上线不做新的客户列表。菜单、路由、帮助都不指向它（不预留路由，规格 F-F3 同样记为"以后"）。
5. 原来"设置"组没有了：账户与帮助进头像菜单，统计进发起方中心 → 公开主页，消息成为区域，发起方资料成为发起方中心。

### 2.2 发布详情的标签（已提交的发布）

草稿照旧打开向导（表单页，`?step=`），不出现这些标签；草稿的 `/publications/<id>/<标签>` 转回 `/publications/<id>`。

| 标签 | 地址 | 内容（来自现在的 `PublicationView`） |
|---|---|---|
| 概况 | `/publications/<id>` | 状态与日期、入场费收入（Q88b）、`AdminNotice`、退回原因；右栏"状态与下一步"（`DetailPage` 的 `aside`）：状态、`PublicationActions`、预览、去交易与结构图的链接 |
| 题目与版本 | `…/questions` | Quiz 与版本、题数、乱序（F-S7）、`VersionNotice`（换版本） |
| 受众 | `…/audience` | 受众、地区、受众条件（Q47）、`MainlandNotice`（F-S11）、邀请（`InvitationsSection`） |
| 奖励 | `…/rewards` | 档位与奖励方式、推荐（`ReferralsSection`）、电子奖品码池（`DigitalPoolSection`） |
| 费用 | `…/fees` | 共享视频的曝光（F-S13 `ExposureSection`）、共享视频的台账（F-S14 `VideoUseSection`）；F-M6/M7/M8 的配送费、受众规则费、曝光费由各项加在这里 |
| 统计 | `…/analytics` | 现有分析页（地址不变） |
| 分享与二维码 | `…/share` | 分享链接与二维码（现在在 `PublicationActions` 的对话框 `PublicationQrDialog`，对话框照旧可从页头打开） |

`PublicationSummary` 按上表拆成各标签的块（同一组件按块导出），不改读取。
发起方一侧的发布预览（`/publications/<id>/preview`）用同样的标签：内容由共用的 `PublicationContent` 给出，详情与预览各自套框（F-F4 决定 ⑤，在 F-F3c 做）。

## 3. 首页与"待我处理"

首页 = 现在的问候带 + "待我处理"列表 + 现在的 `DashboardCharts`（未通过入驻时只有问候带与待我处理）。每一条显示数目与一句话，整条是去对应页的链接；没有待办时显示一句"现在没有要处理的事"。

| 项 | 数据来源 | 现有 / 新 | 去哪里 |
|---|---|---|---|
| 待确认的领取 | `qb_redemption` 中本人的、等确认的（`qb.sponsor.redemptions` 只给列表） | **新模板列** `claimsToConfirm` | `/prizes/redeem`（筛选到等确认） |
| 待发送 / 有争议的电子奖品 | `qb.sponsor.digital-prizes` 的状态（只给列表） | **新模板列** `digitalToSend`（§11 决定 4） | `/prizes/digital` |
| 未读对话 | `qb.sponsor.conversations-unread`（菜单徽标已在用，至多 99） | 现有，直接用 | `/messages/conversations` |
| 预算不足 | 状态 `FUNDS_SHORT` 的发布 | **新模板列** `fundsShort` | `/publications`（筛选） |
| 账户余额不足 | 等充值的奖励（`qb.sponsor.wallet` 的 `pending`，钱包页已有提示）；逾期未付的账单（`qb_fee_invoice`，未付不能提交发布） | **新模板列** `rewardsWaiting`、`invoicesOverdue`；F-M1 合入后加平台费账户不足 | `/money/wallet`、`/money/bills` |
| 审核中 | 入驻（`useMySponsor` 的 `reviewStatus`）；`PENDING_REVIEW` 的发布；审核中的公开主页；审核中的共享视频 | 入驻现有；其余 **新模板列** `publicationsInReview`、`pageInReview`、`sharedVideosInReview` | 各自的页 |
| 资料缺项 | `profile/checklist.ts`（纯函数，读各节已有的读取） | 现有，抽成 `useChecklist()` 与概况页共用 | `/profile`（概况） |
| 赠送额度即将到期 | F-M2 的 `QbFeeCreditGrant`，天数复用 `qb.fee-credit.expiry-notice-days`（F-M2 计划，缺省 14） | **F-M2 加列** `creditsExpiring` | `/money/account` |
| AI 额度不足 | F-M9 的 AI 额度 | **F-M9 加列** `aiCreditShort`：可用额度不够再出一次题（F-M9 的单次最高费用估算）即提示，不设新数字（总控 2026-11-09） | `/money/ai` |
| 共享视频台账待确认（提供方） | F-S14 `qb_video_use` 中提交给本人的 | **新模板列** `videoUsesToConfirm`（§11 决定 5） | `/assets/media`（我的视频的使用台账） |

- **新模板** `qb.sponsor.todo`（`queries/qb/sponsor/todo.sql`，只读、一行、只给数目，权限同 `qb.sponsor.conversations-unread`（`qb.publication.write`），数据视图一律限本人，同其他 `qb.sponsor.*`）。阈值一律来自已有的规则与参数，模板与页面都不写数字；以后的项（F-M2、F-M9 与其他）各在自己的 PR 中加一列与一行文案。
- 菜单与底部菜单的徽标：消息 = 未读对话（现有）；兑换 = `claimsToConfirm + digitalToSend`。待我处理与徽标的读取沿用 `useUnread` 现有的节奏（进入、回到前台、定时重读、离开即停），不另设数字。
- 测试：`SponsorTodoIT`（每一列各造一例；另一个发起方的数据不计入；没有时全 0），Vitest `TodoList.test.tsx`（每一项的文案与链接、0 不显示、全空的一句）。

## 4. 全局新建、头像菜单、手机底部菜单

- **全局"＋ 新建"**（`GlobalCreate`，`shell/create.ts` 的 `CREATE_ITEMS`）：新建 Quiz（`/quizzes?new=1` 打开现有 `NewQuizDialog`）、新建发布（`/publications/new`）、发通知（`/messages/notices?new=1` 打开 `NoticeEditor`）、导入发布设置（`onSelect`：外框中常驻 `ImportSettingsButton` 的逻辑（抽成 `useImportSettings`），在点击的同一手势里打开文件选择——浏览器不允许跳转后再程序化打开文件框）、从资源库新建（`/assets?new=1` 打开新建资源对话框）。显示条件与各页现有按钮相同。
- **头像菜单**（`shell/AvatarMenu.tsx`，用 `@jabiz/ui` 的 `DropdownMenu`、`Avatar` 组成，不自写组件；平台没有现成的用户菜单，学者 App 在 F-F4 取消右上角菜单，所以只放在发起方页面）：显示名（保留 `sponsor-user`）、账户与安全（`/account`）、外观（亮 / 暗 / 跟随系统，`AppearanceProvider`）、语言（`LanguageSelect`）、帮助（`/help`；F-C10 的 Q109"？"由它加在这里）、退出（保留 `sign-out`）。
- **手机底部菜单**（`BottomBar` + "更多"打开完整菜单）：项只在一处——参数 `qb.sponsor.bottom-bar`（普通参数，不受控，缺省 `home,publications,prizes,messages`，即规格的 首页 / 发布 / 兑换 / 消息）。
  - 规则（`setup` 的参数检查与启动检查）：每项是 `areas.ts` 的区域键（后端一份同样的键名单 `SponsorAreas.KEYS`，与前端由测试对照），不重复，至少 1 项，不多于平台底部菜单的约定（4 项 + "更多"，写在参数说明中）。
  - 读取：新模板 `qb.sponsor.layout`（读 `SysParam`，同 `qb.sponsor.benefit-access` 的写法）；读到之前用前端的缺省 `shell/bottom-bar.default.json`，后端测试 `SponsorLayoutParamsTest` 核对它与 `QbParams` 的缺省一致（同 `ProfilePackagePromptsTest` 读仓库文件的做法），所以缺省时不跳动。区域不可见（如合作权益）时该项不显示。
  - 改这个参数要同时改帮助中的"手机底部"一句（写进 `operations.md`）。
- 主按钮：各页的主按钮（新建发布、新建 Quiz、充值、发通知、新建资源）改为 `PageHeader` 的 `primaryAction`：宽屏紧跟标题，手机变成底部菜单之上的浮动按钮。

## 5. 旧地址：一张清单

- `sponsor/src/shell/legacy-paths.json`（取代 `LEGACY_PROFILE_PATHS` 与 `MOVED_PATHS`，两者删去）：每条 `{ from, to, match: "exact" | "prefix", tab?, keepSearch?, example }`。
  `prefix` 用于 `/wallet/topups/<id>` 这类带编号的地址；`tab` 匹配旧的 `?tab=`；`keepSearch` 带上原查询（Stripe 的 `?checkout=`、交易的筛选、树的 `?publication=`）。
- `shell/legacy.ts` 的纯函数 `legacyTarget(path, search): string | null`，在路由表之前调用，`<Redirect replace>`。
- 单元测试 `legacy.test.ts`（表驱动，逐条）：每条的 `example` 转到期望的地址；目标是一个现存的路由（`routeOf(target)` 不是"不存在"）；目标不再是清单中的旧地址（不连环转）；`from` 不与现存路由重叠（除 `tab` 限定的 `/assets`）。
- e2e `sponsor-areas.spec.ts` 读同一个 JSON，逐条打开 `/sponsor<example>`，断言最终地址与页面的标题（`page-title`）。`pwa.spec.ts` 的"客户端路由回落到本页"照旧（`/sponsor/onboarding`、`/sponsor/wallet` 仍返回 200）。
- 发起方页面不是 PWA（`spa("/sponsor", …)` 没有 `pwa`），没有起始页要转；规格中"PWA 的起始页"一条对它不适用。
- 后端：`CheckoutRequest.returnUrl` 改为 `money/wallet/topups/<id>?checkout=`（`CheckoutRequestTest`、`TopUpIT`、`TopUpOwnerIT` 随之改）；邮件 `mail.qb.topup.paid` / `topup.granted` / 预算不足（`{entryUrl.sponsor}wallet` → `money/wallet`）、`mail.qb.fee.invoice-issued` / `invoice-overdue`（→ `money/bills`），三语；`publications`、`assets`、`account` 的链接不变。
  F-C8（邮件文字的定制）之前改：覆盖过的文字里的旧链接靠转向（Q107 的"缺省已更新"标记会提示）。

## 6. 页面与模板

- **区域页头**：`shell/AreaPageHeader.tsx` = `PageHeader` + 区域图标 + 该区域的 `PageTabs`（标签是路由链接，`aria-current`）；标题是这个标签页自己的名字（读屏读出的是页面，区域名在菜单、标签行的名称与图标上；F-F3a 实现时定），页面传标题、说明、`primaryAction`、`actions`。
  现有各页把自己的 `<PageHeader title=…>` 换成 `<AreaPageHeader tab=… />`：每页一处小改，页面文件不搬家（降低与其他会话的冲突）。详情页（Quiz 编辑器、发布详情、对话、充值页、采购单）标题是对象的名字，带面包屑回区域、标题前同样有区域图标。
- `AppShell`：`collapsible="icon"`（收起状态由平台记在本机）、`create`、`bottomBar`、`routeKey={path}`（路由改变时焦点到标题）；内容区套 `PageContent`（宽度 token）。
- **路由表**：`SignedIn` 的 `if / else if` 链移到 `shell/routes.tsx`（`routeOf(path, search)` 纯函数 + 表），以后的功能加一行；`App.tsx` 只剩外框。
- **三种模板**：
  - 列表页（`ListPage`）：Quiz 与发布（保留 Q34b 画廊卡片 `ListView`）、到店兑换、现金奖励、电子奖品、交易、资源库各标签、权益目录 / 我的权益 / 洽谈 / 采购单、通知、店员；工具栏的搜索与状态小标签沿用各页现有的筛选，不新增筛选。
  - 详情页（`DetailPage`）：发布详情（§2.2）、对话、充值页、采购单。
  - 表单页（`FormPage` + `useUnsavedChanges`）：基本信息、公开主页、邮件服务器、账户中的表单块不动（平台积木）；发布向导与 Quiz 编辑器有自己的保存规则（逐步保存、`baseRevision`），只套宽度与页头，不加底部保存条。
- **发起方中心 → 概况**：完成度与每节一张卡片（完成或待填写、还差什么），`profile.navHint.*` 的说明移到卡片上；页头 `actions` 一个"导入与导出"菜单：资料包导出 / 导入（Q100，现在的 `ProfileTransferCard`）、发起方包（Q103，F-C4 的 `SponsorPackageCard`）、从官网生成（Q104，F-C7 合入时加）；对话框照旧，只是入口从卡片移到页头。"你的运营商"卡片留在概况。
- 文案：区域名、标签名、待我处理、头像菜单、"更多"之外的新文字在 `messages.ts`（`sponsor`）三语；`profile.nav.*` 改为标签名，`nav.groups.*` 删去（不再用）。用词照 `glossary.md`，`terms.test.mjs` 检查。

## 7. 进行中的其他项放在哪里

| 项（会话） | 状态 | 位置 | 怎么接 |
|---|---|---|---|
| F-C4 发起方包（S3，`1.3/quizbuks-fc4-sponsor-package`，未合入） | 概况加 `SponsorPackageCard`、发布列表加 `SubmitAllDraftsButton` | 发起方中心 → 概况页头"导入与导出"；发布 → 列表的 `actions` | 建议 F-C4 先合入；F-F3d 把卡片移到页头 |
| F-S14 视频台账（S3，已合入 #571） | 发布页 `VideoUseSection`、资源库 `VideoUsesProvided` | 发布详情 → 费用；资源库 → 素材（不变） | F-F3c 移块 |
| F-S13 曝光计数（已合入） | 发布页 `ExposureSection` | 发布详情 → 费用 | F-F3c |
| F-C3 共享视频的资源库一侧（S3，#558 已合并） | 资源库页 | 资源库 → 素材 | 已在基线中，随 F-F3a 一起挂到路由标签 |
| F-S3 知识结构图界面 / F-S4 发起方答题点亮（S2，未开始） | 树页"正在更新" | 发布 → 结构图（`/publications/map`，"答题点亮"即 F-S4 的"挑战"也在这里，规格未决 3）；子页由 F-S3/F-S4 在 `/publications/map/…` 下自定 | 直接按新框架写 |
| F-M1 平台费账户（S2，进行中）、F-M2 赠送、F-M13 账单收据 | 钱包页的块 | 资金 → 平台费账户 / 账单；待我处理加列 | F-M 计划 §3 已约定"F-F3 先搬，或我改后 S3 一起搬"：F-F3a 只把 `WalletPage` 拆成三个组件文件（`KudosWallet`、`CashAccount`、`Invoices` 已是独立函数），S2 在各自的组件里改 |
| F-M4–M6 提交时的资金检查、配送费、受众规则费（S2） | 向导与 `settings.ts` | 向导不动；费用显示在发布详情 → 费用 | F-F3 不改向导 |
| F-M9 AI 额度（S2） | — | 资金 → AI 额度（F-F3 先放现有用量卡） | F-M9 加购买与待我处理列 |
| F-C10 AI 问题解答（Q109a，S3） | — | 头像菜单 → 帮助 | 由 F-C10 加 |
| F-C7 从官网生成（Q104，S3） | — | 概况页头"导入与导出" | 由 F-C7 加 |
| F-F9 链接名提示（已合入） | 公开主页编辑 | 发起方中心 → 公开主页（地址不变） | 无需改 |
| F-S6 / F-S7 题库（S1） | Quiz 编辑器 | Quiz → 编辑（只改页头） | 一处小改 |

## 8. 规则、帮助与运维的修改

- **`backend/quizbuks/CLAUDE.md` 第 5 节**：把"商户中心（Q79）"一条换成"发起方页面的区域（F-F3）"：
  区域与标签只在 `sponsor/src/shell/areas.ts`（首页 + 8 个区域），页面是区域里的标签、页头用 `AreaPageHeader`；**以后加功能只加标签（或头像菜单中的一项、概况页头的一项），不加菜单项，也不加底部菜单项**（取代 Q79"新的商户设置页加为一个节"）；
  底部菜单只经参数 `qb.sponsor.bottom-bar`；全局新建只在 `shell/create.ts`；路由只在 `shell/routes.tsx`；旧地址只在 `shell/legacy-paths.json`（单元测试与 e2e 逐条检查），新改地址时加一条；邮件与 Stripe 的链接用新地址；"待我处理"的新项加为 `qb.sponsor.todo` 的一列，阈值复用功能的参数。
  发起方中心照旧：概况的完成度清单是纯函数 `profile/checklist.ts`。同时改"支付页"一条中的地址（`/money/wallet/topups/`）。F-F4 的同类规则（学者 App）由 F-F4 加在同一节。
- **帮助底稿（三语，`docs/quizbuks/help/`，内容管理员照改 FAQ）**：
  - 新增 `sponsor-areas.md`："菜单在哪里"——首页与 8 个区域各一句、手机底部的 4 项与"更多"、全局新建、头像菜单、旧名称到新位置的对照（概览 → 首页，钱包 → 资金 → 学分钱包，发起方资料 → 发起方中心，统计 → 发布 → 统计，消息 → 客户与消息）。
  - 改路径：`asset-library.md`（"内容 → 资源库" → "资源库"；顺带改正"商品""认证"两个标签一句：认证已去掉）、`storage-usage.md`、`shared-videos.md`（资源库 → 素材）、`video-uses.md` 与 `exposure.md`（发布页的一节 → 发布详情的"费用"标签）、`your-operator.md` 与 `profile-package.md`（"发起方资料 → 概况" → "发起方中心 → 概况"）、`knowledge-map.md` 的发起方部分（发布 → 结构图，随 F-S3 一起核对）、`blocks.md`（首页，不变，核对）。
  - 截图：仓库中没有发起方页面的截图（商店截图只有学者 App）；FAQ 中若有截图由内容管理员换。
- **`operations.md`**：新增一节"发起方页面的地址"（区域与地址的对照、旧地址清单的位置、参数 `qb.sponsor.bottom-bar` 的含义与改它时要同时改帮助）；逐条改引用发起方页面位置的处理步骤（现在的第 260 行钱包与充值 → 资金 → 学分钱包、第 432 行仪表盘的更新时刻 → 首页、第 501 行"邮件服务器"页 → 发起方中心 → 邮件服务器、第 615 行资源库「认证」标签、第 872、903 行发布页的曝光与台账 → 发布详情 → 费用），实现时以 `grep -n "发起方页面" operations.md` 全部核对。
- `glossary.md`：加区域名的三语一行（§2 表）。

## 9. 拆成 4 个 PR（每个合入后产品完整）

| PR | 内容 | 粗估 |
|---|---|---|
| **F-F3a 区域、地址与外框** | `shell/areas.ts`、`routes.tsx`、`legacy-paths.json` + `legacy.ts`（取代两张旧表）、`AreaPageHeader`；`AppShell` 换成新外框（收起成图标、全局新建、头像菜单、底部菜单 + 参数 `qb.sponsor.bottom-bar` 与模板 `qb.sponsor.layout`）；各页换区域页头（不搬文件）；资源库与消息改成路由标签；奖品、资金的新地址；`WalletPage` 拆成学分钱包 / 平台费账户 / 账单三个标签、AI 额度标签放用量卡；合作权益按 `benefit-access` 显示；Stripe 返回地址与邮件链接；`CLAUDE.md` 第 5 节；帮助 `sponsor-areas.md` 与各底稿的路径；`operations.md`；`glossary.md`；e2e 导航改写 + `sponsor-areas.spec.ts`（旧地址逐条、底部菜单、新建、头像菜单） | 2.5 天 |
| **F-F3b 首页与待我处理** | 模板 `qb.sponsor.todo` + `SponsorTodoIT`；首页的待我处理、`useChecklist` 抽出；兑换的徽标；帮助中"首页"一句 | 1 天 |
| **F-F3c 发布详情的标签** | §2.2 的 7 个标签与 `DetailPage` 右栏；`PublicationSummary` 拆块；发起方的发布预览用同样的标签（共用 `PublicationContent`，F-F4 决定 ⑤）；`video-uses.md`、`exposure.md` 改为"费用"标签；e2e 中打开发布详情的断言 | 1 天 |
| **F-F3d 模板、概况与验收** | 列表页 / 表单页模板套到其余页面、主按钮改 `primaryAction`、内容宽度；概况的完成度卡片与页头"导入与导出"（接 F-C4）；a11y：每个区域亮暗两种外观各一条 axe；360 px 每个区域无横向滚动；宽屏主按钮紧贴标题的断言 | 1–1.5 天 |

合计 5.5–6 天。顺序：F-F5b 合入 → F-F3a →（b、c、d 可并行，各自合入最新的 a）。a 是冲突最集中的一个，开工前在进度表与给总控的消息中预告，合入后通知 S1、S2 新的路由表与区域页头的写法。

## 10. 测试

- **Vitest（`sponsor`）**：`areas.test.ts`（键唯一；每个标签的路由都由 `routeOf` 认出；每个区域有图标与三语名称；底部菜单缺省的每项是区域键）；`legacy.test.ts`（§5）；`routes.test.ts`（表驱动：每个现有地址 → 页面，`/publications/map`、`/publications/stats` 不被当作发布编号，发布编号是 UUIDv7、不会与保留字相同）；`AvatarMenu.test.tsx`（各项、键盘、`expectAccessible`）；`TodoList.test.tsx`；`AreaPageHeader.test.tsx`（当前标签 `aria-current`、隐藏的标签不出现）；`messages.test.ts` 照旧检查三语键。`qb/` 不改（两个 SPA 的 `diff -r` 不受影响）。
- **后端**：`SponsorTodoIT`；`SponsorLayoutParamsTest`（参数检查：未知键、重复、超出约定即报错；缺省与 `bottom-bar.default.json` 一致；`SponsorAreas.KEYS` 与 `areas.ts` 一致）；`CheckoutRequestTest`、`TopUpIT`、`TopUpOwnerIT`（新返回地址）；setup 场景快照（新参数）；`platformCheck`（两个新模板）。
- **e2e（`quizbuks-web/e2e/tests`）**：`support.ts` 加 `openArea(page, 区域, 标签?)`（`menu-<区域>`、`page-tab-<标签>`，手机宽度下经底部菜单与"更多"）与 `signOutSponsor(page)`（头像菜单），各 spec 改用它们，不再按菜单文字点链接。
  - 改导航：`a11y`、`assets`、`content`、`digital-prize`、`journal`、`mail-server`、`reward-methods`、`sponsor`、`survey`、`topup`、`transactions`、`transfer`、`video-file`、`watch-video`、`analytics`（退出）。
  - 改地址或断言：`cash-account`、`platform-fees`、`entry-fee`、`topup`（`/sponsor/wallet` 与充值页的地址）、`transactions`（`/sponsor/money/transactions?publication=`）、`conversations`、`inbox`、`shared-video`（消息与资源库的标签 `page-tab-*`）、`video-use`（发布详情的"费用"标签，F-F3c）、`operators-home`（概况，不变，核对）。
  - 不变：`pwa`（回落路由照旧）、`biz`、`legal`、`auth` 的入口页部分。
  - 新：`sponsor-areas.spec.ts`（旧地址逐条；底部菜单 4 项 + 更多；全局新建各项；头像菜单；没有权益角色时看不到合作权益）；`a11y.spec.ts` 每个区域（首页与 8 个区域、发布详情）亮暗各一次 `expectAccessible`，并在 360 px 下逐个区域断言 `scrollWidth ≤ clientWidth`、底部菜单与浮动按钮不重叠；宽屏 1920 px 时主按钮与标题的距离不超过一个按钮宽（F-F1 的验收在发起方页面上复核）。
- 测试只增数据，可重复运行（同现在）。

## 11. 未决与我做的决定（可改）

决定：
1. **手机底部 4 项**：首页 / 发布 / 兑换 / 消息（规格第 4 节第 19 条，由 Claude 先定、可否决）。做成参数 `qb.sponsor.bottom-bar`：是一个列表，按"具体数字与名单不写死"的约定放进参数；页面先用同样的缺省、由测试保证一致，所以缺省时不跳动。不列入受控参数（不涉及钱与审核）。
2. **区域里的地址**：内容不变的区域（Quiz、合作权益、资源库、发起方中心、单个发布与预览、账户）保留原前缀，只有换了区域的页面改地址，旧地址尽量少。资源库与消息的 `?tab=` 改为路径，与其他区域的标签一致（平台 `PageTabs` 的标签是路由）。
3. **资金区域多一个"学分钱包"标签**：规格的标签是 交易 / 平台费账户 / AI 额度 / 账单，没有给现在的 Kudos 钱包（奖励的预算与充值）留位置；F-M1 计划中学分钱包也是单独的。按"只加标签"的规则加上，不删功能。
4. **待我处理多两项**：待发送 / 有争议的电子奖品、提供方待确认的共享视频台账——都是"要我去做"的事，数据已有；规格列的 7 项全部保留。
5. **合作权益的显示**按 `qb.sponsor.benefit-access` 的 `open`（F-S2c 以来角色由点亮门槛节点决定）；以前买过、现在角色关闭的发起方仍可从地址打开自己的记录。
6. **头像菜单在应用中组成**（`@jabiz/ui` 的下拉菜单与头像），不加平台组件：只有发起方页面需要。
7. **Quiz 编辑器内部标签、发布向导不动**，避开 S1、S2 正在改的文件。

总控的答复（2026-11-09，计划已批准，拆分 a → b / c / d）：
1. **"客户"标签**：首次上线不显示，不做新的客户列表；记为"以后"（本计划 §2 注 4 与规格 F-F3），菜单、路由、帮助都不指向它。
2. **"AI 额度不足"的界线**：可用额度不够再出一次题即提示，不设新数字（F-M9 加列时照此）。
3. **"统计"**：放"发起方中心 → 公开主页"下（`/profile/page/stats`，旧 `/stats` 转向）。
4. **区域名的日文、英文**：照 §2 表，写进 `glossary.md`，并在 PR 中列出，需求方以后可改。
5. 规格未决 3 的落点照本计划。
6. §0 的三条行为改变都同意（尤其是发起方能看到自己的账单）。

## 12. 风险

- **与其他会话的合并冲突**（最大的风险）。最容易冲突的文件：
  `sponsor/src/App.tsx`（所有新页面都在这里加路由与菜单）、`sponsor/src/messages.ts` 与各功能的 `messages.ts`、`publishing/PublicationPage.tsx`（S2 的 F-M5–M8、S3 的后续）、`publishing/PublicationListPage.tsx`（F-C4）、`profile/OverviewPage.tsx` 与 `profile/paths.ts`（F-C4、F-C7、F-C11b、F-M2 的入驻赠送）、`wallet/WalletPage.tsx`（S2 的 F-M1/M2/M7/M9/M13）、`assets/AssetsPage.tsx`（F-C6；F-C3 #558 已合并）、`pages/DashboardPage.tsx` 与 `analytics/DashboardCharts.tsx`、`notices/MessagesPage.tsx`、`tree/TreePage.tsx`（F-S3）、`profile/ProfileLayout.tsx`、e2e 的 `support.ts` 与各 spec。
  对策：页面文件不搬家，只换页头一处；路由与区域集中到 `shell/` 的新文件，以后的功能只加一行；`WalletPage` 按 F-M 计划的约定先拆成独立组件；F-F3a 尽快合入并通知；b、c、d 小而短。
- **e2e 大面积改动**：先加 `openArea` 等辅助函数并一次改完；本机跑 `tools/quizbuks/e2e.sh`，跑不了的在 PR 中写明，CI 上连跑确认。
- **Stripe 返回地址**：进行中的 Checkout（有效期内）返回旧地址，靠前缀转向带上 `?checkout=`；`sponsor/src/wallet/stripePage.ts` 的检查与轮询不变；e2e `topup`、`cash-account` 覆盖。
- **发布编号与新的保留路径**：`/publications/map`、`/publications/stats` 与 `/publications/<id>` 共用前缀；编号是 UUIDv7，`routeOf` 先认保留字，测试覆盖。
- **文件选择的手势**：全局新建的"导入发布设置"必须在点击的同一手势里打开文件框（§4），已在设计中处理，e2e 覆盖。
- **帮助与 FAQ 不同步**：底稿改了，数据库中的 FAQ 要内容管理员照改；PR 描述与进度备注中写明。

## 13. F-F3a 的实现记录（2026-11-09）

- 分支 `1.3/quizbuks-ff3-sponsor-areas`。新目录 `sponsor/src/shell/`：`areas.ts`、`paths.ts`、`routes.tsx`（`pageOf`）、`legacy-paths.json` + `legacy.ts`、`AreaPageHeader.tsx`、`SponsorShell.tsx`、`AvatarMenu.tsx`、`layout.ts` + `bottom-bar.default.json`、`messages.ts`、`shell.test.tsx`。
- 后端：参数 `qb.sponsor.bottom-bar`（普通，`QbParams` 最后）、模板 `qb.sponsor.layout`、`SponsorLayoutParamsTest`；`CheckoutRequest.returnUrl` 改为 `money/wallet/topups/`；邮件链接 `money/wallet`、`money/bills`（三语）。无迁移。
- 页面文件不搬家：各标签页把 `PageHeader` 换成 `AreaPageHeader`；资源库与消息的 `?tab=` 改为路径标签；`WalletPage` 拆出 `FeeAccountPage`（现金账户，带"充值"，缺省选现金）、`BillsPage`（账单）、`AiCreditPage`（本月的 AI 用量）。S2 的 F-M1（#578）合入后，`FeeAccount` 放进 `FeeAccountPage`、`Invoices` 已在 `BillsPage`。
- 合作权益的入口：没有权益角色时概况上的卡片（`BenefitsEntryCard`，Vitest 两种情形在 `benefits/benefits.test.tsx`）。
- 全局新建的"导入发布设置"在外框中常驻一个不带按钮的 `ImportSettingsButton`（`opener`），点击的同一手势里打开文件框。
- 未做（照拆分留给 b、c、d）：首页的"待我处理"与 `qb.sponsor.todo`（F-F3b）；发布详情的标签与共用的 `PublicationContent`（F-F3c）；列表 / 表单模板、主按钮改 `primaryAction`、概况的卡片与页头"导入与导出"（F-F3d）。

## 14. 进度与下一步（2026-11-09 18:10 UTC，按总控降速暂停）

- **状态**：F-F3a 已推送（`952619fb`，含没有权益角色的发起方从发起方中心概况进入"合作权益"的入口与 `profile-links.spec` 的新菜单）；草稿 PR 已开，尚未合入最新 `1.3/quizbuks`。
- **下一步 1：合入最新 `1.3/quizbuks`**（#578 F-M1、#582–#584 之后有 9 个冲突）：`backend/quizbuks/CLAUDE.md`、`QbParams.java`（基线的新参数在前、`qb.sponsor.bottom-bar` 在后）、`SetupIT.java`、`setup.yml` 与 `setup.snapshot.json`（解决后以 `--tests '*ScenarioTest' -Dscenario.update-snapshots=true` 重新生成）、`operations.md`（#583 给小节号去重并加了检查：本项的小节按基线最大号 + 1 重编）、`PublicationListPage.tsx`、`WalletPage.tsx` 与 `invoices.test.tsx`（#578 的 `<FeeAccount/>` 放进 `FeeAccountPage`，`Invoices` 已在 `BillsPage`，见 §8 与 S2 的约定）。
- **下一步 2**：合入后跑整个 `:quizbuks:test :quizbuks:platformCheck`、发起方与 App 的 lint / typecheck / Vitest、`src/qb` 一致、用词测试、e2e typecheck；e2e 由 CI（ready 后）或 S4 跑：`sponsor-areas`、`a11y`、`analytics`、`assets`、`auth`、`cash-account`、`content`、`conversations`、`digital-prize`、`entry-fee`、`inbox`、`journal`、`mail-server`、`platform-fees`、`reward-methods`、`shared-video`、`sponsor`、`survey`、`topup`、`transactions`、`transfer`、`video-file`、`watch-video`、`profile-links`。
- **之后**：F-F3b（首页与 `qb.sponsor.todo`）、F-F3c（发布详情标签与发起方预览共用标签）、F-F3d（页面模板、概况卡片、每个区域的 axe 与 360 px）。
- **2026-11-10（F-F3a 的整轮）**：a11y 在 360 px 下发现横向滚动（资金 → 交易 224 px，奖品与兑换也有），属于 F-F3d 的"内容宽度"与"360 px 每个区域无横向滚动"（§9）：F-F3a 只断言每个区域在 360 px 能打开并通过 axe，`scrollWidth` 的断言随 F-F3d 加回，并先修这两处。

## 15. F-F3b 的实现记录（2026-11-10）

- 分支 `1.3/quizbuks-ff3b-sponsor-home`（F-F3a #587 之后）。无迁移。
- 后端：模板 `qb.sponsor.todo`（`queries/qb/sponsor/todo.sql`，一行十列，权限 `qb.publication.write`，各数据视图限本人；参数 `at` 是客户端此刻，只用于赠送额度的到期窗口）、`SponsorTodoIT`（每一列各一例；另一个发起方不计入；没有时全 0；到期天数改参数后随之变）。
- 各列的含义：`claimsToConfirm` = 本人的、状态 `CLAIMED` 的领取（不含权益供应方经手的，同 `qb.sponsor.redemptions`）；`digitalToSend` = 电子奖品 `DUE`、`OVERDUE`（发起方欠着）与 `DISPUTED`（学者提出异议）；`fundsShort` = 状态 `FUNDS_SHORT` 的发布；`rewardsWaiting` = 学分钱包的 `pendingCount`（钱包页同一个数）；`invoicesOverdue` = 状态 `OVERDUE` 的平台费账单；`publicationsInReview` = `PENDING_REVIEW` 的发布；`pageInReview` = 公开主页 `PENDING`；`sharedVideosInReview` = 本人共享的视频 `PENDING`；`videoUsesToConfirm` = 提交给本人（提供方）、状态 `SUBMITTED` 的台账；`creditsExpiring` = 还有余额、未过期、在 `qb.fee-credit.expiry-notice-days`（F-M2 的提醒用的同一个参数，缺省 14）天内到期的赠送额度批次。模板里没有新数字；参数缺失或格式不对时 `creditsExpiring` 为 0。
- 前端：`sponsor/src/home/`（`api.ts`、`todo.ts` 纯函数与去处表、`useTodo.ts`、`TodoList.tsx`、`messages.ts` 三语、`home.test.tsx`）；`profile/useChecklist.ts` 从概况页抽出（概况页与首页共用，`ready` 表示各部分都已读到）；首页 = 问候带 + 待我处理 + 图表（未通过入驻时没有图表，待我处理只有"申请审核中"与资料缺项）；奖品与兑换的菜单与底部徽标 = `claimsToConfirm + digitalToSend`（App 的 `badges`）；读取节奏同未读对话的徽标（进入、回到前台、5 分钟、后台不读，两处共用一个查询）；读取失败时不显示"都处理完了"。发布列表读 `?status=`（待我处理的"预算不足""审核中"带过去）。
- 决定：电子奖品一列含 `OVERDUE` 与 `DISPUTED`（计划写"待发送 / 有争议"，一列合计）；资料缺项数的是必需的未完成项（`optional: false`），选填项不进待办；入驻被驳回不单列（问候带已显示原因与按钮）。
- 平台费账户不够支付（F-M1）：列 `feeInvoicesUncovered` = 状态 `OPEN` / `PARTIAL` / `OVERDUE` 的账单中，未付额（`total − paidAmount`）大于平台费账户可用额的张数；可用额与 `feeAccountAvailable`（账单页）同一个算法：赠送额度钱包 `FEE_CREDIT` 的余额（不小于 0）加现金钱包 `CASH` 的余额减冻结额（不小于 0）。去 `/money/bills`。`SponsorTodoIT` 覆盖够与不够（含冻结的现金）。
- **AI 额度不足（F-M9）** 未做：F-M9 尚未合入。F-M9 加列 `aiCreditShort`（可用额度不够再出一次题即提示，不设新数字，总控 2026-11-09 ②）时：在 `todo.sql` 加一列并在 `TodoColumn` / `TODO_COLUMNS` / `TODO_ORDER` / `TODO_PATH`（`/money/ai`）与三语 `home.todo.items.aiCreditShort` 各加一处，`SponsorTodoIT` 加一例。

## 16. F-F3c 的实现记录（2026-11-10）

- 分支 `1.3/quizbuks-ff3c-publication-tabs`（F-F3a #587、F-F3b #605 之后）。无后端改动、无迁移；删除的功能：无（每个块与操作仍可达）。
- **标签 → 地址 → 块**（`publishing/PublicationPage.tsx`，`publishing/PublicationTabs.tsx` 用平台 `PageTabs`；页面用平台 `DetailPage`，右栏 = 状态、说明一句、预览与 `PublicationActions`、去统计 / 交易 / 结构图的链接，每个标签都在）：
  概况 `/publications/<id>`：预算不足提示、`AdminNotice`、名称 / 封面 / 期间 / 入场费、入场费收入（Q88b）、日期；
  题目与版本 `…/questions`：版本、时限、合格线、抽题（含按难度）、乱序（F-S7）、满分 + `VersionNotice`；
  受众 `…/audience`：`MainlandNotice`（含换版本中的）、受众、地区、受众条件（Q47）、`InvitationsSection`；
  奖励 `…/rewards`：预算、人数上限、奖励方式与奖品、推荐、最高奖励与支出、档位表、`ReferralsSection`、`DigitalPoolSection`；
  费用 `…/fees`：一句说明、`ExposureSection`（F-S13）、`VideoUseSection`（F-S14）；F-M6 / M7 / M8 的配送费、受众规则费、曝光费由各项加在这里；
  统计 `…/analytics`（地址不变，`PublicationAnalyticsPage` 的页头加同一排标签）；
  分享与二维码 `…/share`：`PublicationQrPanel`（从 `PublicationQrDialog` 抽出，对话框仍从页头打开）。
- 草稿照旧是向导；草稿的 `…/<标签>` 用 `Redirect` 转回 `/publications/<id>`。旧地址没有变化，`legacy-paths.json` 不动。`publicationTabOf` 只认五个页内标签（`analytics`、`preview` 照旧各有自己的路由）。
- `PublicationSummary` 的各项按 `PART_OF` 分到四块（`SummaryBlock part=overview|quiz|audience|rewards`，奖励块带档位表）；向导的复核仍用合成的 `PublicationSummary`（同一份项、原来的顺序，读取不变）。
- **共用的 `qb/view/PublicationContent.tsx`**（两个 SPA 逐字相同）：可选参数 `tabs: ContentTabs`（`current`、`path`、`renderLink`、可选的 `extra` 供 F-F4c 加商品 / 比赛规则 / 举报 / 屏蔽）；四个标签说明 / 奖励 / 资料 / 规则（`CONTENT_TABS`），封面、名称、要点与仅受邀提示始终在标签之上；草稿的预览没有"奖励"，没有素材、发起方介绍与 `extra` 时没有"资料"。**不给 `tabs` 时输出不变**（竖排，折叠块照旧；App 首页基线不受影响）。发起方预览的标签是按钮（预览没有各标签的地址），F-F4c 用链接 `?tab=`。规则标签 = 要点的完整句（题数 / 抽题、合格线、时限、参加费、截止、节点），比赛规则链接与举报等只有 App 的内容由 F-F4c 经 `extra` 放入。
- 测试：`publishing.test.tsx`（七个标签的地址与 `aria-current`、各标签的块、分享标签、草稿的标签地址转回、预览的四个标签；原来依赖竖排详情页的用例改到对应标签），`qb/view/PublicationContent.test.tsx`（两份相同：有 / 无 `tabs`、隐藏无内容的标签、`extra`、axe）。e2e（只改了类型检查通过，未运行）：`invitation`（`/audience`）、`video-use`（`/fees`）、`content-bank`（`/questions`）、`digital-prize`（`/rewards`）、`a11y` 的发起方发布测试（每个标签各一次，亮暗各循环）。
- 帮助：`help/sponsor-areas.md`（提交后的发布的七个标签）、`help/exposure.md`、`help/video-uses.md`（"费用"标签）；发起方手册初稿三语各一行。

## 17. F-F3d 的实现记录（2026-11-10）

- 分支 `1.3/quizbuks-ff3d-templates`（F-F3a #587、F-F3b #605、F-F3c #608 之后）。无后端改动、无迁移；删除的功能：无（每个按钮、对话框与测试 id 仍在）。
- **主按钮**：页头的 `primaryAction`（宽屏紧挨标题，手机是浮动按钮）：Quiz 列表（`new-quiz`）、发布列表（`new-publication`）、资源库（`asset-add`）、权益的"我的权益"（`benefit-offer-add`）、学分钱包（`topup-open`）、平台费账户（`fee-account-topup`）。平台的主按钮测试 id 固定为 `page-primary-action`，所以由 `shell/mainAction.tsx` 用 `renderLink` 给出带页面自己测试 id 的元素（桌面与手机的浮动按钮同一个 id）；次要的按钮（导入 Quiz、导入发布设置、全部提交、资源库的"收集"、交易导出）仍在页头的 `actions`。页面宽度早已由外框的 `PageContent`（`--content-max`）给出，页面文件不搬家。
- **概况的"导入与导出"**：页头一个菜单 `profile-transfer`，两组：发起方资料包（导出 `profile-export` / 导入 `profile-import` / 给 AI 工具的提示词 `profile-prompt`）、发起方包（导出 `sponsor-package-export` / 导入 `sponsor-package-import`）。`ProfileTransferCard` 与 `SponsorPackageCard` 去掉卡片，成为只带对话框与文件框的 `ProfileTransfer`、`SponsorPackage`（经 `controls` 交出动作），由 `profile/TransferMenu.tsx` 的 `TransferTools` 挂一次；文件框（`profile-import-file`、`sponsor-package-file`）与所有对话框的测试 id 不变。上次中断的发起方包导入的提示（`OpenImportNotice`，`sponsor-package-open` / `-abandon`）留在概况页上。完成度卡片（状态、清单、每节的说明与"去设置"）沿用现有的，"从官网生成"（F-C7）未合入，不加占位。列表页 / 表单页模板的其余部分：各页的筛选与保存规则不同，只套页头与宽度，不加底部保存条、不改筛选（同 §6 对发布向导与 Quiz 编辑器的做法）。
- **360 px 的横向滚动**（用 Chrome 在 360 px 下逐页量 `scrollWidth`，页面接口用假数据，含日文 / 中文；Vitest 量不了布局）：
  - 资金 → 交易（224 px）：平台 `FilterBar` 的范围行（`div.flex.items-center`，不换行）里两个日期框合计 584 px。`TransactionsPage` 给 `FilterBar` 加 `className="[&_[role=group]>div]:flex-wrap"`，两个日期框在手机上换行。平台组件不改。
  - 奖品与兑换（到店兑换 / 现金奖励 / 电子奖品）：带假数据（长名称、备注、争议说明），卡片与表格两种视图在 360 px 都没有横向滚动，没有复现；`RedeemPage` 的二维码（固定 320 px）比手机上的卡片内容宽 26 px（居中，被内边距吸收），顺手加 `max-w-full`（图片 `h-auto`）。
  - 其余区域（首页、Quiz、发布与结构图、消息、合作权益四个标签、资金五个标签、资源库五个标签、发起方中心各节、账户、帮助）在 360 px 都是 0。
- e2e（未运行）：`a11y.spec.ts` 加回 360 px 每个区域的 `scrollWidth - clientWidth <= 0`，加 1920 px 下三个主按钮（`new-quiz`、`new-publication`、`asset-add`）与标题的距离不超过一个按钮宽（`expectBesideTitle`），概况的"导入与导出"菜单一条 axe；`sponsor-package.spec.ts` 先打开菜单。每个区域亮 / 暗各一条 axe 早已在 a11y 中。
- 测试：`shell/mainAction.test.tsx`（测试 id、点击、禁用）；`profile/transfer.test.tsx`、`SponsorPackage.test.tsx` 改为经菜单与提示。帮助：`help/profile-package.md`、`help/sponsor-package.md` 的位置。
