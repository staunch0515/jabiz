# S4：上架收尾（计划已确认 2026-10-27，"默认建议"）

依据：`00-overview.md` §4（S4：隐私政策页、性能预算、Polaris 风格、支持邮箱）与 §5 中尚未回答的上架要求，加上前几个阶段推到 S4 的事项：
- S1：API key 在构建时写入；
- S2b Q-S2b-3：导航与 Polaris 风格；
- S3b Q-S3b-2："Powered by QuizBuks" 的链接。

线 1.3，应用分支 `1.3/quizbuks`。若需要平台改动（§4），先在 `1.3/platform` 上做，再同步。

S4 的目标：**代码与页面达到 Shopify 应用商店的提交条件**。提交本身（Partner Dashboard 的商店资料、截图、演示视频、审核往来）由需求方在联调之后进行，本阶段准备好其中的文字草稿。

## 1. 公开页面：落地页、隐私政策、支持

| # | 内容 |
|---|---|
| W-1 | **新的静态站点** `quizbuks-web/shopify-site/`，挂在 `/shopify-app/`。<br>- 做法同商业网站 `quizbuks-web/biz`：无框架，三种语言，`scripts/build.mjs` 生成静态页，经 `jabizApp { spa(...) }` 打包；<br>- 与 `biz` 分开：总体方案定了"普通商户的商业网站不提 Shopify"；<br>- 页面有三个：落地页（`/shopify-app/`）、隐私政策（`/shopify-app/privacy`）、支持（`/shopify-app/support`）；<br>- 英文是主语言（应用商店的审核与多数商户用英文），中文、日文同内容 |
| W-2 | **隐私政策**按实际保存的数据写，逐项对应代码：<br>- 店铺：域名、名称、店员的 Shopify 编号、访问令牌（密封保存）、套餐与用量；<br>- 顾客：已登录顾客的 Shopify 顾客编号、答题记录、领到的码；<br>- 访客：浏览器中的随机键（服务端只存其摘要）；<br>- 不收集姓名、邮箱、地址、订单；<br>- 保存期限：卸载后保留到 Shopify 的 `shop/redact`（卸载后 48 小时），收到即删除；<br>- 三个合规 webhook 怎样处理；<br>- 第三方：YouTube / Vimeo 只在顾客点击后加载；<br>- 联系方式：支持邮箱。<br>文字由我起草，**法律上的审定由需求方负责**（本阶段不做法律意见） |
| W-3 | **支持页**：<br>- 支持邮箱；<br>- 常见问题：怎样把答题块加到商品页、为什么店面不显示答题（没有进行中的活动、当天码发完、套餐用完）、怎样改套餐、卸载后数据怎样处理。<br>内嵌后台每页底部加"帮助"链接，指向支持页（新标签打开） |
| W-4 | 支持邮箱以配置给出（`QB_SHOPIFY_SUPPORT_EMAIL`），内嵌后台与静态站点都读它（静态站点在构建时写入）。邮箱的收件与回复在系统之外，本阶段不做工单 |
| W-5 | 店面的 "Powered by QuizBuks" 改为链接到落地页（新标签打开，`rel="noopener"`），兑现 S3b 的 Q-S3b-2 |

## 2. 内嵌后台的外观与导航（"Polaris 风格"）

| # | 内容 |
|---|---|
| P-1 | **导航改用 App Bridge 的侧栏菜单**（`<ui-nav-menu>`），三项：概况、答题活动、套餐与用量。页面内去掉标签。<br>- 各页对应地址 `/shopify/`、`/shopify/campaigns`、`/shopify/plan`，刷新后停在原页；<br>- 页面标题用 App Bridge 的标题栏（`<ui-title-bar>`）；<br>- App Bridge 已经从 Shopify 的 CDN 载入，这些是它自带的元素，不另加依赖 |
| P-2 | **外观**：继续只用 `@jabiz/ui`（根 `CLAUDE.md` 的规则），在本 SPA 的 CSS 中把主题 token 调成接近 Polaris 的样子：<br>- 字体：系统字体栈，同 Polaris；<br>- 颜色：Polaris 的中性底色与主色；<br>- 圆角、阴影与间距。<br>亮、暗两种外观都要过 axe。见 §5 Q-S4-2 |
| P-3 | 文案统一为 Shopify 后台的说法，例如"商店"而不是"店铺"（中文、日文对照 Shopify 后台的用词） |

## 3. 性能

| # | 内容 |
|---|---|
| F-1 | **店面组件**（Shopify 要求应用块对店面 Lighthouse 分数的影响不超过 10 分）：<br>- 现状：`quiz.js` 压缩后 6 KB，经 App block 的 `javascript` 延迟载入；点击之前不向第三方发请求，不预载视频；<br>- 加一个 CI 测试，以 Playwright 打开一个放了答题块的静态页，断言首屏不阻塞渲染、点击前只请求 `QBS_SF_CAMPAIGN`、没有第三方请求；<br>- 体积门槛照旧 15 KB |
| F-2 | **内嵌后台**（"Built for Shopify" 的标准：LCP ≤ 2.5 s、CLS ≤ 0.1、INP ≤ 200 ms）：<br>- 现状：入口脚本未压缩时 589 KB，gzip 后约 180 KB，全部页面在一个包里；<br>- 改为按页拆分：答题活动、套餐两页用 `React.lazy` 按需载入；<br>- 设入口包的体积门槛（目标 gzip ≤ 120 KB，实测后定数），超过即构建失败，同店面组件；<br>- 联调时在开发店铺用 Shopify 后台自带的性能数据核对 |

## 4. 部署：API key 不再写进镜像（需要平台改动）

S1 的已知问题：内嵌页的 `<meta name="shopify-api-key">` 在构建时写入（`VITE_SHOPIFY_API_KEY`）。staging 与正式是两个 Shopify 应用、两个 Client ID，而部署流程只构建一个镜像，同一镜像部署到两个环境，所以正式环境会拿到 staging 的 key。

| 方案 | 做法 | 取舍 |
|---|---|---|
| **A（建议）** | 平台小阶段：SPA 的入口页支持由配置填写的占位。<br>- 配置为 `jabiz.web.spa[i].index-values.<名>`；<br>- 只允许 `meta` 的 `content` 属性，值做 HTML 转义；<br>- 启动检查：占位都有值。<br>平台在 `1.3/platform` 先做（带示范与测试），再同步。QuizBuks 改为运行时变量 `SHOPIFY_API_KEY`（服务端本来就有这个值） | 一个镜像走两个环境，也符合"配置不进镜像"。平台多一个能力，应用 SPA 都能用 |
| B | 部署流程为两个环境各构建一次 | 不改平台。但构建时间翻倍，staging 验过的镜像不再是上正式的那个 |

## 5. 待需求方确认

| # | 问题 | 建议 |
|---|---|---|
| Q-S4-1 | 公开页面的路径与品牌名 | `/shopify-app/`，品牌 "QuizBuks for Shopify"（总体方案中待最终确认的名字）。要用单独的域名（如 `shopify.quizbuks.com`）就告诉我，路径不变、另配反向代理 |
| Q-S4-2 | 外观：`@jabiz/ui` 加 Polaris 风格的 token（P-2），还是改用 Shopify 的 Polaris Web Components | 前者。后者要给根 `CLAUDE.md` 的"界面组件只用 `@jabiz/ui`"再开一个例外，而 Shopify 的审核并不强制用 Polaris，只要求界面清楚、与后台协调 |
| Q-S4-3 | 支持邮箱地址 | **已确定**（2026-10-28）：`quizbuks@gmail.com`，与招商网站的联系邮箱相同 |
| Q-S4-4 | API key 的注入：方案 A（平台小阶段）还是 B | A |
| Q-S4-5 | 隐私政策的法律审定 | 我按代码起草，需求方或其法律顾问审定后再提交审核 |
| Q-S4-6 | 应用商店的资料（名称、简介、详细说明、关键词、截图说明）是否也由我起草 | 起草英文（中文、日文对照）放在 `docs/quizbuks/shopify/S4-listing.md`。截图与演示视频要等开发店铺联调后由需求方录制 |

## 6. 拆分与测试

- **平台 17g**（Q-S4-4 选了 A；17e、17f 的编号已被占用）：入口页占位、启动检查、示范与测试，然后同步到 `1.3/quizbuks`。
- **S4a**：公开站点（W-1 到 W-4）、"Powered by" 链接（W-5）、支持邮箱配置。
  - 测试：静态站点的生成测试（三种语言的页面与链接都在）；
  - 内嵌页"帮助"链接的测试；
  - 店面组件链接的测试。
- **S4b**：导航与外观（P-1 到 P-3）、性能（F-1、F-2）。
  - 测试：Vitest（按地址显示各页、导航元素）；
  - 亮、暗外观的 axe；
  - 店面组件的 Playwright 性能测试；
  - 入口包的体积门槛。
- 都以 `/code-review` 自查。S4a 的公开页面没有新的接口；S4b 只改前端。17g 要跑 `/security-review`：入口页注入涉及转义。

## 7. 上架前还需要需求方准备（不属于代码）

- Partner Dashboard：
  - App Pricing 的套餐（S3 §8）；
  - 应用商店资料与截图、演示视频；
  - 测试店铺与审核人员的说明；
  - 隐私政策与支持页的地址（W-1）。
- 开发店铺联调：S2、S3 的清单全部走完。
- 正式环境的 `SHOPIFY_API_KEY` / `SHOPIFY_API_SECRET`、`QB_SHOPIFY_APP_HANDLE`、`QB_SHOPIFY_SUPPORT_EMAIL`。

## 8. S4a 的实施（与计划不同或计划未写的）

| # | 内容 |
|---|---|
| I-1 | 公开站点 `quizbuks-web/shopify-site/`（`/shopify-app/`）：首页、隐私政策、支持三页，英中日各一份，生成方式同 `biz` 但独立（没有脚本、没有内联样式）。支持邮箱在构建时写入（`QB_SHOPIFY_SUPPORT_EMAIL`，缺省 `quizbuks@gmail.com`（2026-10-28 需求方确定，与招商网站的联系邮箱相同）；镜像构建参数同名，Gradle 以它为输入，改了即重建） |
| I-2 | 内嵌页的 Client ID 与支持邮箱由平台 17g 在启动时写入（`jabiz.web.spa[].index-values`：`SHOPIFY_API_KEY`、`QB_SHOPIFY_SUPPORT_EMAIL`），镜像不再带 `VITE_SHOPIFY_API_KEY`（S1 的已知问题解决）。内嵌页底部是"帮助与常见问题"（按界面语言链到支持页，新标签）与支持邮箱 |
| I-3 | 店面的 "Powered by QuizBuks" 链到落地页（按店面语言，新标签，`rel="noopener"`）；落地页的地址在构建时取 `SITE_URL`（缺省 `https://quizbuks.com`） |
| I-4 | 应用商店资料草稿在 `S4-listing.md`（英文，附中文、日文对照） |
| I-5 | **需要需求方注意（法律审定时）**：隐私政策照实写了 `shop/redact` 删不掉的记录：已发布答题的内容（引擎的只追加表，S2 Q-S2-2）、记录的变更日志（平台审计，个人字段已遮蔽）、店员的登录账户（以"商店地址/店员编号"命名，S1）。Shopify 要求收到 `shop/redact` 后删除商店数据；2026-10-28 需求方确定：**政策按实际写**，即照现在的实现说明这几项留存；以后实现改变（如开平台阶段做真正的抹去）时同时改政策 |
| I-6 | 隐私政策写了"卸载时 Shopify 删除本应用建立的折扣"（S2 的前提），联调时核实；若不是，改为本应用在卸载时自己删除，或改写政策 |

**测试**：`shopify-site` 的 `site.test.ts`（6 例）：三种语言内容键一致；每页每种语言都生成且互相链接、sitemap 条数；支持邮箱是邮件链接且页面没有脚本、内联样式与事件属性；隐私政策含必需的内容；构建设置的校验；转义。内嵌页 `App.test.tsx` 加 1 例（帮助链接、支持邮箱、开发服务器时不显示）；店面 `quiz.test.ts` 的 badge 用例加上链接的检查。CI：`quizbuks.yml` 加公开页面的步骤，冒烟检查 `/shopify-app/privacy/` 与内嵌页写入的 Client ID。
