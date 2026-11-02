# Q79：商户中心——商户资料页的重组与布局

来源：需求方 2026-11-02 的八项要求中的第六项（`docs/quizbuks/05-console-requests.md`）："商户资料页面布局太乱，优化页面布局，切实给出一个专业、大气的页面。内容多，要找一个方法来合理组织内容。增加菜单页可以。"
只改商户后台的前端（`quizbuks-web/sponsor`），不改流程、数据与接口。

## 0. 为什么

- "商家资料"（`/onboarding`）一页里叠着两件事：入驻信息（状态、审核结果、名字、公司、简介及其语言、头像、条款）和**公开主页编辑器**（链接名、主要语言、标语及各语言译文、网站、封面、发布状态与提交 / 撤下 / 打开 / 复制）。两套状态与告警、两张表单，单列 `max-w-xl`，层级平、重点不明。
- 与商户自己有关的其他设置散在"设置"组的七项菜单里（入驻、商品、统计、邮件服务器、消息、账户、帮助），店员刚从"奖励"组搬来（Q78）；新商户不知道先做什么、做到哪一步。

## 1. 现状

| 处 | 现在 |
|---|---|
| `pages/OnboardingPage.tsx`（280 行） | `useMySponsor`；`OnboardingStatus`（审核状态、驳回理由）+ `OnboardingForm`（名字、公司、简介、简介的语言（`TextTranslationsFields`）、头像、条款 `TermsAgreement`、提交 / 保存）；下面直接嵌 `SponsorPageSection` |
| `page/SponsorPageEditor.tsx`（390 行） | `SponsorPageSection`（标题、说明、状态 `PageStatus`、已发布时的打开 / 复制链接、`PageForm`：链接名、主要语言、标语、各语言标语、网站、封面；保存 / 提交审核 / 撤下） |
| `products/ProductsPage.tsx`（345） | 商品列表（卡片式 `<li>`）+ 对话框编辑；`/products` |
| `publishing/StaffPage.tsx`（170） | 添加店员（按编号查找、确认）+ `DataTable` 列表；领奖台链接与二维码；`/staff` |
| `publishing/MailServerPage.tsx`（264） | 状态 + 表单（主机、端口、TLS、用户名、密码、发件地址与名称）+ 测试结果；`/mail-server` |
| `stats/StatsPage.tsx`（172） | 主页与商品的浏览、点击按日；`/stats` |
| 入口 | `App.tsx`：`/` 未通过审核即转到 `/onboarding`；菜单"设置"组：入驻（`nav.onboarding` = "Sponsor profile / 商家资料 / スポンサー情報"）、店员（Q78）、商品、统计、邮件服务器、消息、账户、帮助；`/page` 301 到 `/onboarding` |
| 允许的支付方式 | 只在发布向导里看得到（`REWARD_SETTINGS_QUERY`，`qb.sponsor.reward-methods`，由管理员设定） |
| 可复用 | 平台 `@jabiz/ui`：`PageHeader`、`Card`、`DescriptionList`、`Steps`、`Tabs`、`Badge`、`Alert`；商户后台的 `publishing/qr.ts`（二维码图片 / SVG / PNG）、`qb/page.ts` 的 `sponsorPageLink` |
| 测试 | 单元 `page/page.test.tsx`（`PROFILE_PATH = '/onboarding'`、菜单链接 "Sponsor profile"）、`App.test.tsx`（首次登录转到 `/onboarding`）、`products` / `staff` / `mailServer` 各自的测试；e2e `sponsor.spec.ts`、`mail-server.spec.ts`、`a11y.spec.ts`（点菜单 "Sponsor profile"）、`pwa.spec.ts`（`/sponsor/onboarding` 可安装） |

`AppShell` 的菜单以 `isActive(path)` 判断高亮（App 传入），子页面可按前缀匹配。

## 2. 决定（需求方 2026-11-02 确认）

| # | 决定 |
|---|---|
| D-Q79-1 | **一个页组"商户中心"**（`/profile`），带子导航；它取代"设置"组里的入驻、店员、商品、邮件服务器四项菜单，菜单"设置"组收为：**商户中心、主页统计、消息、账户、帮助**。菜单项的文字沿用 `nav.onboarding`（"Sponsor profile / 商家资料 / スポンサー情報"），e2e 的选择器不变。 |
| D-Q79-2 | **子页**：`/profile`（概况）、`/profile/basics`（基本信息：现在的入驻表单）、`/profile/page`（公开主页：现在的编辑器）、`/profile/products`（商品，现页搬入）、`/profile/staff`（店员，现页搬入）、`/profile/mail-server`（邮件服务器，现页搬入）。旧路径 `/onboarding`、`/page`、`/products`、`/staff`、`/mail-server` 在前端 `Redirect` 到新路径（书签与 e2e 的 `/sponsor/onboarding` 照常可用）；`/` 未通过审核时转到 **`/profile/basics`**（第一次要填的就是它），通过后照旧转到概览。 |
| D-Q79-3 | **子导航**：`lg` 以上在左侧一列（`<nav aria-label>` + 链接，当前页 `aria-current="page"`，每项带图标与一句说明）；`lg` 以下是页顶一行可横向滚动的链接（同一组件，样式随断点变），不用 `Tabs`（它们是链接，各有地址）。 |
| D-Q79-4 | **概况页**（新）：（1）**状态卡**：审核状态与时间、驳回理由、下一步按钮（去填基本信息 / 去发布主页）；（2）**完成度清单**（`Steps`，每项"已完成 / 去完成"链到子页）：基本信息填好（公司与简介）→ 公开主页已发布 → 至少一件上架的商品 → 至少一位店员 → 邮件服务器已验证（后三项是可选的，标"可选"）；（3）**主页预览卡**：头像、公司、标语、封面、链接名，按发布状态显示"已发布 / 草稿 / 审核中"，已发布时给打开、复制与**二维码**（`publishing/qr.ts`，下载 PNG / SVG）；（4）**允许的支付方式**（只读，`REWARD_SETTINGS_QUERY`，注明由平台设定）。只读现有数据：`readMySponsor`、`readMySponsorPage`、商品、店员、邮件服务器与支付方式的现有读取，不加接口。 |
| D-Q79-5 | **表单页的版式**：`PageHeader`（标题、一句说明、右侧主要操作）；内容分成 `Card` 区块（基本信息：身份 / 简介与语言 / 头像 / 条款；公开主页：链接与语言 / 标语 / 封面与网站 / 发布状态）；`lg` 以上两栏——左栏表单（`max-w-2xl`），右栏一张**说明卡**（审核看什么、公开主页在哪里显示、字段怎么用，`DescriptionList`）与实时预览（主页：由表单当前值画出的预览卡，不是 iframe）；保存条在表单底部固定（`sticky bottom-0`，只在有改动时显示）。错误与成功提示各只一处（`Alert` / `role="status"`）。字段、校验、流程调用一字不改。 |
| D-Q79-6 | **搬入的三页**（商品、店员、邮件服务器）只换外壳：加 `PageHeader` 与 `Card` 分区、放进页组；表单、列表、对话框与 testid 不变。 |
| D-Q79-7 | 统计页（`/stats`）保留在"设置"组（它是数据，不是设置）；概况页只给一行"最近 7 天浏览 / 点击"（`qb.sponsor.page-stats` 的现有读取）并链到统计页——若现有模板按期读取不便，则只给链接。 |
| D-Q79-8 | **留白与字号**按平台 12 §11 与品牌 token（Q32）：页组的主内容 `max-w-5xl`，卡片间距 `gap-6`，区块标题 `text-base font-semibold`，说明 `text-muted-foreground text-sm`；两种外观都过 axe。不写颜色值。 |

## 3. 改动（`quizbuks-web/sponsor/src`）

### 3.1 新目录 `profile/`

- `paths.ts`：`PROFILE_PATH = '/profile'` 与子路径、`profileSectionOf(path)`、旧路径表 `LEGACY_PROFILE_PATHS`（`/onboarding` → basics 等）。
- `ProfileLayout.tsx`：`PageHeader`（"商户中心"）+ 子导航 `ProfileNav`（`data-testid="profile-nav"`，项 `profile-nav-<section>`，`aria-current`）+ 内容区；两栏的容器 `ProfileColumns`（左 `children`、右 `aside`，`lg:grid-cols-[minmax(0,1fr)_20rem]`）。
- `OverviewPage.tsx`（`/profile`）：状态卡 `profile-status`、清单 `profile-checklist`（项 `profile-check-<key>` 带 `data-done`）、预览卡 `profile-preview`（`profile-preview-open` / `-copy` / `-qr`）、支付方式 `profile-methods`。清单的判断是纯函数 `checklist.ts`（输入：sponsor、page、商品数、店员数、邮件服务器状态 → 各项 done / optional），单测。
- `BasicsPage.tsx`（`/profile/basics`）：现在的 `OnboardingStatus` + `OnboardingForm` 拆成区块；右栏说明卡（`profile-basics-aside`）。`OnboardingPage.tsx` 删除；`useMySponsor` 移到 `profile/api.ts`（或留在原处由新页引用——以不改导入面为准）。
- `PagePage.tsx`（`/profile/page`）：`SponsorPageEditor` 的 `PageForm` 拆成区块；右栏预览卡 `profile-page-preview`（随表单值变）。`SponsorPageSection` 删除（它只被 onboarding 用）。
- `ProductsPage` / `StaffPage` / `MailServerPage`：各加 `PageHeader` 与 `Card`，导出路径常量改为 `profile/paths.ts` 的；文件位置不动。

### 3.2 `App.tsx`

- 路由：`/profile*` → `ProfileLayout` 里按 `profileSectionOf` 选页；旧路径 → `<Redirect>`；`/` 未通过审核 → `/profile/basics`。
- 菜单：`settings` 组 = 商户中心（`PROFILE_PATH`，图标 `Store`）、统计、消息、账户、帮助；`isActive` 对 `/profile` 按前缀。

### 3.3 文案（`src/messages.ts` 三语）

`profile.*`：`title`、`intro`、`nav.<section>` 六项与各一句说明、`status.*`、`checklist.*`（五项的标题、说明、"可选"、"去完成"、"已完成"）、`preview.*`（"已发布 / 草稿 / 审核中 / 未建"、打开、复制、二维码、下载）、`methods.*`、`aside.*`（说明卡的文字）。现有 `onboarding.*`、`page.*`、`products.*`、`staff.*`、`mailServer.*` 的文字照用。

## 4. 测试

- 单元：
  - `profile/profile.test.tsx`：子导航（六项、`aria-current`、窄屏为一行——以 `matchMedia` 桩）；旧路径转向；`/` 未通过审核转到 `/profile/basics`、通过后到概览；概况页的状态卡、清单（各种组合：新商户全未完成；已发布主页 + 一件商品；邮件服务器已验证）、预览卡（已发布时有打开 / 复制 / 二维码）、支付方式；axe 两种外观。
  - `checklist.test.ts`：纯函数。
  - 现有 `page/page.test.tsx`、`App.test.tsx`、`products` / `staff` / `mailServer` 的测试：路径常量与菜单断言改为新路径，页面行为断言不变。
- e2e：`a11y.spec.ts` 点 "Sponsor profile" 后多一次 `expectAccessible('sponsor profile overview')`，再点子导航到基本信息；`sponsor.spec.ts` 等按新路径与子导航；`pwa.spec.ts` 的 `/sponsor/onboarding` 经转向仍可安装（断言最终地址）。本机不运行，CI 跑。
- `pnpm lint`、`pnpm typecheck`、`pnpm test`、`pnpm app:check ../quizbuks-web/sponsor`。

## 5. 文档

- `02-design.md` §7：商户中心一节（页组、子页、概况的四块、旧路径转向）。
- `backend/quizbuks/CLAUDE.md` 第 5 节：商户中心（`profile/`）、新设置页放进页组、不另加菜单项。
- `05-console-requests.md` 第六项标"已实现"；`03-plan.md` 的 Q79 行。

## 6. 估计

| 部分 | 天 |
|---|---|
| 页组、子导航、路由与转向、菜单 | 0.5 |
| 概况页（状态、清单、预览与二维码、支付方式）与清单函数 | 1 |
| 基本信息与公开主页两页的区块化与右栏 | 1 |
| 三页搬入、文案三语、单元测试、e2e 选择器、文档 | 0.5 |
| **合计** | **3** |

## 7. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 两张表单（入驻、主页）的测试多、按 testid 写 | 只换外壳：字段、testid、流程调用不动；页面文件拆分时逐个跑原测试 |
| 低 | 旧地址与书签 | 前端 `Redirect`；`pwa.spec.ts` 的 `/sponsor/onboarding` 仍可达 |
| 低 | 概况页读五份数据 | 都是已有读取、React Query 缓存；任一失败只影响那一块（各块自己的加载 / 错误态） |
| 低 | 菜单项变少，老用户找不到商品 / 店员 / 邮件服务器 | 概况页的清单与子导航都列出；商户后台的帮助页加一句 |

## 8. 需要需求方确认的

1. D-Q79-1 / 2：菜单收为"商户中心 + 统计 + 消息 + 账户 + 帮助"，商品、店员、邮件服务器成为商户中心的子页（旧地址转向）。
2. D-Q79-4：概况页的四块（状态、完成度清单、主页预览与二维码、允许的支付方式）；清单的五项与"可选"的三项。
3. D-Q79-5：表单页两栏（左表单、右说明与预览），保存条底部固定。
4. D-Q79-7：统计页留在"设置"组，概况只给一行与链接。
5. 新商户第一次登录落在 `/profile/basics`。
6. 编号 Q79。

## 9. 实施记录（2026-11-02，分支 `1.3/quizbuks-79-sponsor-hub`）

需求方 2026-11-02 确认 §8 的六项（"同意 Q79 的决定，开始开发"）。按计划实施：

- `profile/paths.ts`（`PROFILE_PATH`、六个节、`profilePath` / `profileSectionOf`、旧地址表 `LEGACY_PROFILE_PATHS`）；各页原来导出的路径常量（`PRODUCTS_PATH`、`STAFF_PATH`、`MAIL_SERVER_PATH`、`SPONSOR_PAGE_PATH`）改为指向节的地址，引用处不改。
- `profile/ProfileLayout.tsx`：页头 + 子导航 `profile-nav`（`profile-nav-<节>`，当前 `aria-current="page"`；`lg` 左列带一句说明，窄屏一行横向滚动）+ 内容；`ProfileColumns`（左表单、右 `aside`，`lg` 两栏）。
- `profile/OverviewPage.tsx`：状态卡 `profile-status`（下一步 `profile-next`：未通过 → 基本信息，已通过没发布主页 → 公开主页）、清单 `profile-checklist`（`checklist.ts` 纯函数，`profile-check-<key>` 带 `data-done`，可选项标注，"去完成 / 打开"链到节）、主页预览卡 `profile-preview`（`PreviewCard.tsx`，已发布时 `profile-links`：打开、复制、二维码对话框 `profile-qr-dialog` 下载 PNG / SVG，复用 `publishing/qr.ts`）、奖励方式 `profile-methods`（`REWARD_SETTINGS_QUERY`）、统计页的链接。只读现有数据。
- `profile/BasicsPage.tsx`（原 `pages/OnboardingPage.tsx` 搬入并改名）：表单分三张卡（身份、简介与语言、标志），右栏说明卡 `profile-basics-aside`；`useMySponsor` / `SPONSOR_QUERY` 移到 `profile/api.ts`（仪表盘、预览页改引用）。
- `page/SponsorPageEditor.tsx`：`SponsorPageSection` → `SponsorPagePage`；表单分三张卡（地址与语言、标语、封面与网站），操作条 `sponsor-page-actions` 固定在底部；右栏实时预览 `profile-page-preview`（随表单值变，公司与标志取基本信息）与说明卡；"来自基本信息"的提示链到基本信息节（文案 `page.fromProfile` 三语改为不说"上方"）。
- 商品、店员、邮件服务器三页只换外壳：页内标题由 h1 改为 h2（页组已有 h1），字段、列表、对话框与 testid 不变。
- `App.tsx`：`/profile*` 由 `profileSectionOf` 选节；旧地址 `Redirect`；`/` 未通过审核 → `/profile/basics`；菜单"设置"组收为商户中心（文字沿用 `nav.onboarding`）、统计、消息、账户、帮助；"奖励"组不再有店员（它成了节；Q78 若先合入，这里合并时以本分支为准）。
- 文案三语 `profile.*`（`src/messages.ts`）。
- 测试：新 `profile/profile.test.tsx`（概况四块、二维码、子导航与 `aria-current`、新商户落在基本信息、五个旧地址的转向、已通过没发布的"下一步"）与 `checklist.test.ts`；`page.test.tsx`、`App.test.tsx`、`publishing.test.tsx` 改为新地址与结构；e2e `a11y.spec.ts`（概况与各节的 axe）、`mail-server.spec.ts`、`reward-methods.spec.ts` 经子导航到节（本机未运行，CI 跑）；`pwa.spec.ts` 的 `/sponsor/onboarding` 仍由后端给出页面壳（前端转向）。
- 未做：概况不给统计数字，只给链接（D-Q79-7 的后半）；统计页仍在"设置"组。
