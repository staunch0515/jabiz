# Q32 视觉精致化（计划，2026-10-25 确认）

需求方（2026-10-25）：现在的界面"太素，不够精致"；去年的设计稿（翠绿色系、照片卡片、底部导航、欢迎轮播）更好。
对答题人 App、商家后台进一步精致化、配色、加合适的图片，招商网站也要。确认：翠绿色；图片用我们自己画的插画（不用外部照片）；加欢迎轮播；三个都做。

## 1. 已确认的决定

| # | 决定 |
|---|---|
| D-Q32-1 | **品牌色只以覆盖 token 表达**（平台 D34 第 3 条、D44 第 3 条）：两个 SPA 共用的 `src/qb/brand.css`（CI 比较两个目录）在 `@jabiz/ui/theme.css` 之后覆盖 `--primary` / `--ring` / `--accent` / `--sidebar-primary` / `--chart-1` 等，亮色主色 `#047857`（白字 5.3:1）、暗色 `#6ee7b7`（深字 ≥ 11:1），都满足平台 12 §11 的对比度；另有品牌 token `--qb-hero-*`（横幅渐变，最浅处白字 ≥ 5.3:1）与 `--qb-leaf` / `--qb-coin` / `--qb-sky` / `--qb-ink`（插画），经 `@theme inline` 成为 `qb-*` 颜色类。页面与组件仍不写死色值 |
| D-Q32-2 | **插画自己画**（共用的 `src/qb/Illustrations.tsx`，Q32a 时在 `app/src/art/`，Q32b 移入，内联 SVG，`aria-hidden`，颜色取 token 随外观变化）：字标（硬币 + 叶子）、答题、奖励、领奖、通过的奖杯、空状态、以及没有封面的发布的四种占位图案（按发布编号稳定选取）。不引入外部图片、不热链 |
| D-Q32-3 | **欢迎轮播**（`/`，未登录且本设备没看过时，`localStorage` `qb.intro.seen`）：三页（答题、赚 Kudos、领奖）、下一步 / 跳过 / 已有账号登录，都去登录页并记住；e2e 的登录直接打开 `/login`，不受影响。登录、注册、找回密码等平台积木的 `brand` 换成字标 |
| D-Q32-4 | **App 首页**：渐变横幅（问候 + 一句话 + 插画）；卡片改为两列封面网格（封面或占位图案、奖励徽章、名称、商家头像或首字母、完成人数），视频卡片跨两列；U-10 改为"390×844 一屏 ≥ 4 张完整可见"（卡片约 165px 高，横幅与封面各占去一些；02-design.md 同步改）。空状态带插画；结果页通过时显示奖杯插画；底部导航用卡片背景与阴影。PWA 的 `theme-color` 与图标改为翠绿（两个前端的图标由脚本重画） |
| D-Q32-5 | **商家后台（Q32b）**：同一 `brand.css`；侧栏与登录页的字标；概览页的渐变横幅（问候公司名、审核状态与去入驻的按钮、插画随状态）；指标卡带图标（`Metric` 的 `icon`，装饰）；题目、发布、邀请列表的空状态带插画（`EmptyState`，放在 `DataTable` 的 `empty`）；发布向导的步骤带图标。插画移到共用的 `src/qb/Illustrations.tsx`（两个 SPA 相同），新增概览与入驻两幅 |
| D-Q32-6 | **招商网站（Q32c）**：`site.css` 的令牌改为翠绿（文字对比度在文件头注明），加插画令牌；页头页脚用字标（内联 SVG 硬币，不再引用图标文件）；首页英雄区保留随语言与外观变化的 CSS 手机样机（不用真实截图：截图只有一种语言、一种外观，且会过时），样机旁加奖励插画；"怎么用"四步各带一幅插画；商户、答题人、问卷三页的英雄区右侧各一幅插画；重点价格卡的卡头用品牌浅色；图标与 App 相同。插画在 `page.mjs` 中以模板字符串写出（形状同 `qb/Illustrations.tsx`，另加一幅“分享”（手机上的二维码），颜色取站点令牌），一律 `aria-hidden`；窄于 1180px 时首页英雄区的插画缩小、移到手机样机下方，不被样机遮住 |

## 2. 改动（Q32a）

| 部分 | 改动 |
|---|---|
| 共用 | `app/src/qb/brand.css` = `sponsor/src/qb/brand.css`，两个 `index.css` 引入 |
| App | `art/Illustrations.tsx`、`pages/IntroPage.tsx`、`App.tsx`（字标、轮播路由、底部导航）、`pages/FeedPage.tsx`（横幅、网格卡片、空状态）、`pages/ResultPage.tsx`（奖杯）、`messages.ts`（`intro.*`、`home.tagline`，三语）、`vite.config.ts`（`themeColor`）、`public/icon-*.png`；`package.json` 加 `vitest-axe`（同商家后台，页面测试过 axe） |
| 测试 | `App.test.tsx`（轮播：翻页、开始、跳过、看过即直达登录，axe）；`Play.test.tsx`（封面网格、占位图案、空状态）；`qb/brand.test.ts`（两种外观下品牌 token 的对比度，两个 SPA 相同）；e2e `a11y.spec.ts` 加轮播两页 |

## 2b. 改动（Q32b）

| 部分 | 改动 |
|---|---|
| 共用 | `src/qb/Illustrations.tsx`（自 `app/src/art/` 移入，加 `GrowthArt`、`PlanArt`） |
| 商家后台 | `App.tsx`（字标）、`pages/DashboardPage.tsx`（横幅）、`analytics/Metric.tsx`（`icon`）与 `DashboardCharts.tsx`、`EmptyState.tsx` 与三个列表、`publishing/PublicationWizard.tsx`（步骤图标）、`messages.ts`（`dashboard.greeting`，三语） |
| 测试 | `App.test.tsx`（字标、横幅问候）、`analytics.test.tsx`（指标图标）、`content.test.tsx`（空状态）；e2e `a11y.spec.ts` 已覆盖概览、题目与登录页 |

## 2c. 改动（Q32c）

| 部分 | 改动 |
|---|---|
| 招商网站 | `src/assets/site.css`（令牌、`.hero-split`、`.hero-art`、`.steps .art`、`.mock .art`、`.plan.highlight`）、`src/templates/page.mjs`（`wordmark`、`art`、`ART`、步骤与英雄区）、`public/icon-*.png` |
| 测试 | `test/content.test.ts`（字标在页头页脚、首页五幅插画、三个功能页各一幅、插画都 `aria-hidden`）；e2e `biz.spec.ts` 已对四页两种外观跑 axe |

## 3. 风险

- 两列网格在很长的名称下只显示两行（`line-clamp-2`），完整名称在发布页。
- 暗色外观下插画的黄色硬币对比不受无障碍规则约束（装饰），文字从不放在插画上。
