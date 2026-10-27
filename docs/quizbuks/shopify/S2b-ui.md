# S2b：商户后台的活动页与店面组件（计划已确认 2026-10-27，"默认建议"）

依据：`S2-storefront.md` §5（已确认的 S2 计划）。后端是 S2a（PR #274），S2b 只做界面，原则上不改后端。线 1.3，工作分支 `1.3/quizbuks-S2b-ui`，从 S2a 合并后的 `1.3/quizbuks` 拉出。

## 1. 内嵌后台（`quizbuks-web/shopify`，现有 SPA）

| # | 内容 |
|---|---|
| B-1 | 页面分两个标签：**概况**（S1 现有：店铺、连接、成员）和**答题活动**（新）。用 `@jabiz/ui` 的 Tabs，不引入路由（只有两页；S4 做 Polaris 风格时再按 App Bridge 的导航菜单改） |
| B-2 | **活动列表**（`QBS_CAMPAIGN_LIST`）：进行中的活动在最上面，带状态与折扣，下面是草稿与已结束的。顶部是码池的三个数：可发、今日已发 / 每日上限、近 7 天已发。<br>未连接 Shopify 时提示先连接（发布会被服务端以 `QBS_NOT_CONNECTED` 拒绝） |
| B-3 | **活动编辑**（`QBS_CAMPAIGN_SAVE`，只限草稿）：<br>- 名称；<br>- 视频链接（YouTube / Vimeo，可空）；<br>- 时限（秒，30–7200）；<br>- 折扣（1–90 %）；<br>- 题目：1–20 题，每题 2–6 个选项，单选一个正确答案；增删、上下移动；<br>- 及格题数（1–题数）。<br>用 react-hook-form，前端校验与服务端规则一致，服务端的违规按字段显示。并发修改用 `baseRevision`（409 `QBS_CAMPAIGN_CHANGED` 时提示重新载入） |
| B-4 | **发布、暂停、恢复、结束**（`QBS_CAMPAIGN_PUBLISH` / `MOVE`），每个操作都先确认。结束时提示"已结束不能再开，要改内容请复制为新草稿"。<br>"复制为新草稿"在前端完成：读出内容，再以新草稿保存。后端不加流程 |
| B-5 | **预览**：编辑页下方按店面组件的样子显示题目，不调用店面接口 |
| B-6 | 三种语言的文字（`messages.ts`，键齐全的测试沿用 S1 的写法） |

## 2. 店面组件（Theme App Extension）

| # | 内容 |
|---|---|
| E-1 | **源码**：独立的小工程 `quizbuks-web/storefront/`，TypeScript，Vite 库模式打包成一个 `quiz.js`。<br>**不用 React、不用 `@jabiz/ui`**：组件跑在商户的店面上，与主题共存，体积预算是压缩后 ≤ 15 KB。这是根 `CLAUDE.md`"界面组件只用 `@jabiz/ui`"的例外，只限这一个店面组件（§4 Q-S2b-1）。<br>原生 Web Component（`<quizbuks-quiz>`）加 Shadow DOM，样式不外泄也不被主题污染；字体、文字颜色继承主题（`inherit` / `currentColor`），只加最少的中性样式 |
| E-2 | **扩展**：放在 `tools/quizbuks/shopify/extensions/quizbuks-quiz/`，与 `shopify.app.toml` 同级，即 Shopify CLI 的工程结构。包括：<br>- `shopify.extension.toml`；<br>- `blocks/quiz.liquid`：App block，只用于商品页模板，不设任何设置项；输出 `<quizbuks-quiz>` 和店面语言 `request.locale.iso_code`；<br>- `assets/quiz.js`：构建产物；<br>- `locales/`：英、中、日 |
| E-3 | **流程**：<br>1. 载入后以 GET `/apps/quizbuks/QBS_SF_CAMPAIGN` 读活动。没有活动或今天的码已发完就不显示。<br>2. 显示按钮"答题赢 X% 折扣"，点开对话框（`<dialog>`）。<br>3. 视频是点击后才加载的占位：不预载 YouTube / Vimeo 的播放器和缩略图，首屏不受影响，也不在顾客点之前向第三方发请求。<br>4. "开始答题"（`QBS_SF_START`）：一题一页，显示倒计时。<br>5. 交卷（`QBS_SF_SUBMIT`）：<br>- 通过：显示码、"复制"、"去使用"（`/discount/<码>?redirect=<当前页>`）；<br>- 未通过：显示得分；<br>- 当天发完、没码了：照实说明 |
| E-4 | **访客键**：首次载入时生成随机键（`crypto.randomUUID()`），存 `localStorage`，查询参数和正文里都带上。领到的码按发布编号也存 `localStorage`，再打开直接显示。<br>已答过时服务端回 `QB_ATTEMPT_ALREADY_TAKEN`（带答题编号），组件用这个编号再交一次，取回已领的码（S2a 的"同一答题再交，答同一个码"） |
| E-5 | **语言**：组件自带英、中、日文字，按 Liquid 给出的店面语言选，其他语言用英文。服务端的错误码映射成组件自己的文字，不显示服务端原文 |
| E-6 | **无障碍**：对话框聚焦管理与 Esc 关闭，选项是 radio 组，倒计时以 `aria-live` 低频播报，颜色对比不依赖主题（只用文字色加边框） |

## 3. 测试与 CI

- 内嵌后台：Vitest。覆盖列表、编辑（校验与服务端违规）、发布、暂停、结束、复制，以及未连接时的提示；`expectAccessible`；`pnpm app:check`。
- 店面组件：Vitest + jsdom，fetch 用桩。覆盖：
  - 没有活动；
  - 开始、答题、交卷；
  - 通过得码、未通过、当天发完；
  - 已答过取回码；
  - 访客键与码的持久化；
  - 三语键齐全；
  - 无障碍检查：经 `@jabiz/ui/testing` 的 `expectAccessible`，只作开发依赖。
- 体积门槛：构建后压缩 ≤ 15 KB，否则测试失败。
- CI：`quizbuks.yml` 的 web 作业加上 storefront 工程（install、lint、typecheck、test、build）。
- 构建产物 `assets/quiz.js` 不提交。部署脚本 `tools/quizbuks/shopify/deploy-extension.sh` 先构建，再 `shopify app deploy`，由需求方以自己的 Partner 账号执行。
- 不写 Playwright 端到端：内嵌页离不开 Shopify 的 App Bridge，店面组件离不开真实店面的 App Proxy。改为开发店铺上的手工联调清单（§5），写进 PR。

## 4. 待需求方确认

| # | 问题 | 建议 |
|---|---|---|
| Q-S2b-1 | 店面组件不用 React 与 `@jabiz/ui`（根 `CLAUDE.md` 规则的例外，只限店面组件，写进 `quizbuks-web/storefront/` 的说明与 S2 文档） | 同意：体积预算与在商户主题中的隔离 |
| Q-S2b-2 | 是否要求"看完视频才能答题" | 不要求：只推荐观看（引擎的 `watchToAnswer` 只是标记）；强制观看要跟踪播放器事件，且两家播放器各不相同 |
| Q-S2b-3 | 后台导航：两个标签，还是 App Bridge 的侧栏导航菜单 | 现在用标签；Polaris 风格与侧栏导航放 S4 |
| Q-S2b-4 | 店面组件的外观：继承主题字体与文字色、中性边框，不提供自定义 | 同意（"零可选项"；自定义 CSS 在"不做"清单里） |
| Q-S2b-5 | `quiz.js` 不提交，部署前构建 | 同意；若希望仓库里随时有可部署的扩展，就改为提交，并由 CI 检查与源码一致 |

## 5. 开发店铺联调清单（S2a + S2b，需求方准备好店铺后一起做）

1. `usageLimit: 1` 的含义：同一批的两个码分别下单，都能用（Q-S2-4）。
2. 折扣的 GraphQL 写法：建批、完成、删除在店铺后台的折扣列表中可见、正确。
3. App Proxy：GET 与 POST（带 JSON 正文）都被转发并签名；已登录顾客带 `logged_in_customer_id`。
4. 商品页加上 App block：显示、答题、领码、用码结账。
5. 卸载后再装：活动暂停，恢复后补码。
6. 三个合规 webhook 用 Shopify CLI 触发。

估计：约 2 天（后台 1 天，店面组件 1 天）。

## 6. 实施说明（与计划不同或计划未写的）

| # | 内容 |
|---|---|
| I-1 | 构建产物 `quiz.js` 是**经典脚本**（Vite 库模式 `iife`），不是 ES 模块：App block 的 `javascript` 以延迟的普通脚本载入它。压缩后约 6 KB（门槛 15 KB，`scripts/size.mjs`） |
| I-2 | 服务端的错误答复只带规则码，不带参数，所以"已答过"时取不到答题编号。组件在开始答题时把答题编号存进浏览器；`QB_ATTEMPT_ALREADY_TAKEN` 时用它再交，取回已领的码。浏览器里没有存（换了设备、清除了数据）时，说"已经答过"。 |
| I-3 | 后台的"今日已发 / 每日上限"只显示今日已发：列表的答复不带上限（参数未建立时按 100）。上限在后台显示放到 S3（套餐与用量页） |
| I-4 | 扩展的 `shopify.extension.toml` 按 Shopify 主题应用扩展的写法；本环境访问不到 Shopify 的文档，联调清单（§5）加一项：`shopify app deploy` 接受扩展、App block 出现在商品页模板的"应用"块中 |
| I-5 | 应用的规则（`backend/quizbuks/CLAUDE.md`）加了店面组件的例外，以及 Shopify 模块经渠道合约调用引擎 |
| I-6 | 浏览器里存的答题编号与码按发布**和顾客**分开（`quizbuks:code:<发布>:<c顾客号 / anon>`，Liquid 传 `customer="{{ customer.id }}"`）：共用电脑上的下一个人看不到上一个人的码 |
| I-7 | 店面组件不跑 `pnpm app:check`：它只用 `@jabiz/ui/testing`（axe），不用 React 与 `@jabiz/ui` 的组件，`app:check` 会要求它装上全部共用依赖。改为 `pnpm versions`（`scripts/versions.mjs`，用 `app:check` 自己读锁文件的函数）检查 vite 与测试工具的版本与平台一致，CI 中执行。若平台的 `app:check` 将来区分"只用测试辅助"的项目，再改回 |
| I-8 | 后台切换到"概况"标签时活动页保持挂载（隐藏），正在编辑的草稿不丢；活动列表在第一次打开活动标签时才读 |

### 已知问题（需要改后端，留给后续阶段）

| # | 问题 | 影响与打算 |
|---|---|---|
| K-1 | 店面的计时器在后台标签页里会被浏览器节流，到时自动交卷可能晚几秒到几十秒 | 服务端按截止时间判定（过时即 `QB_ATTEMPT_EXPIRED`），不会多给时间；只是访客回到标签页时才看到"已超时"。要在截止时也算分，需服务端在到期时以已存的答案结算，并在答题中逐题保存答案（引擎的渠道合约要加"保存答案"） |
| K-2 | 换设备（或清除浏览器数据）后取不回已领的码 | I-2 的限制。登录的顾客可由服务端按顾客查已发的码；需要新的店面流程（如 `QBS_SF_MY_CODE`），放到后续阶段 |
