# F-F5b 不再用"后台"：实施计划（S3）

规格：`docs/quizbuks/first-launch-spec.md` F-F5（全产品改名）的补充。来源：需求方 2026-11-07 的决定——人读到的文字中不再出现"后台"
（以及日文"管理画面"、英文 "console" / "Administration" 这类同义的叫法）。在 F-F5（PR #469）的分支上做，只改用词，不改功能。

## 新的名字

| | 中文 | 日文 | 英文 |
|---|---|---|---|
| 发起方一侧，作名称 | 答题拼好价发起方 | クイズdeおトク 主催者 | QuizBuks for Sponsors |
| 发起方一侧，在句子中（如"请在发起方页面修改后重新申请"） | 发起方页面 | 主催者ページ | the Sponsor app |
| 运营商一侧（`/operator/`，原"运营商后台"） | 运营商管理系统 | 運営会社管理システム | Operator Management System |
| 平台管理（`/admin/`，原"管理后台 / 管理画面 / Administration"） | 答题拼好价平台管理系统 | クイズdeおトク 管理システム | QuizBuks Management System |

## 品牌（Q61）

名称中的产品名不写死，照品牌档案取该语言的名字：

- SPA：`BRAND.names.<语言>`（`src/qb/brand.ts`），如发起方 SPA 的 `brand` 中文 `${BRAND.names.zh}发起方`、英文 `${BRAND.names.en} for Sponsors`、日文 `${BRAND.names.ja} 主催者`；
  平台管理系统中文 `${BRAND.names.zh}平台管理系统`、日文 `${BRAND.names.ja} 管理システム`、英文 `${BRAND.names.en} Management System`。
- 服务端消息：记号 `@QB_BRAND_NAME@`（`processResources` 按语言替换）；招商网站：`src/brand.mjs`；后台扩展：`BRAND_NAMES.<语言>`。
- **中文名称用中文品牌名**"答题拼好价"（不是 QuizBuks）：Q61 规定中文文字以中文名称呼产品，需求方表中的"答题拼好价平台管理系统"即按此写。
- Java 中的字典标签（公告与帮助的对象"App / 发起方"）取不到品牌名，用句子中的叫法：发起方页面 / 主催者ページ / Sponsor app。

运营商管理系统不带品牌名（需求方的表如此）。

## 范围

- **A 人读到的文字**：两个 SPA 的 `messages.ts` 与共用的 `src/qb/messages.ts`（两边逐字节相同）、`index.html` 标题、后台扩展的消息、
  服务端 `messages_{en,zh,ja}.properties`、Java 中的显示文字（字典）、招商网站 `biz/src/content`、`docs/quizbuks/legal/`（草稿，直接改，不发新版本）、
  `docs/quizbuks/store/`、品牌档案的说明。
- **B 运维与帮助**：`operations.md`、`troubleshooting.md`（含"帮助页文案"）、`launch-checklist.md`、`tools/quizbuks/content/README.md`。
- **C 规格、设计与指南**：`first-launch-spec.md`、`first-launch-dev-plan.md`、`glossary.md`、`0*.md`、`docs/quizbuks/` 顶层其他文档、`backend/quizbuks/CLAUDE.md`。
  `first-launch-progress.md` 只改本会话（S3）的行。设计文档中指"在后台运行"的"后台"改为"异步"等说法，"Stripe 后台"改为 Stripe 的产品名 Stripe Dashboard。
- **D 测试**：断言可见文字的测试（e2e、Vitest、Java、`tools/quizbuks/**`）随文字改；`listing.test.mjs` 的禁用词仍禁旧词并加上"后台"。
- **E 用词测试**：`tools/quizbuks/terms/terms.test.mjs` 加一条检查（见下）；`glossary.md` 加四个名字的三语写法。

不改：`docs/quizbuks/plans/**`（存档）、`docs/quizbuks-requirements/**`（需求方原文）、Shopify 自己的叫法（"Shopify 后台""Shopify 管理画面"、Shopify admin）、
Google Play Console 等其他厂商的产品名、代码标识、路由、消息键（`console`、`consoleHint` 等）、迁移（`db/`）、测试内部的编号、代码注释。

## 新的测试

`terms.test.mjs` 增加"不再用的叫法"：在 A 范围与 troubleshooting 的帮助页文案、`operations.md`、`troubleshooting.md` 中，
"后台"、"管理画面"、"主催者コンソール"、"Sponsor console"、英文的 "Administration"（作名称）出现即失败。
例外写在测试中并说明原因：Shopify 后台 / Shopify 管理画面（Shopify 自己的叫法）、Play Console（Google 的产品名）。
保留原有两个测试；并检查测试本身能找到这些词。

`rename.py` 只做逐词替换；"后台"要按上下文选名称或句子中的叫法，不适合机械替换，本次逐条手改，不往脚本里加映射（在脚本头注说明）。

## 风险

- 名称与句子中的叫法要逐句选；英文 "the Sponsor app" 与 "QuizBuks for Sponsors" 的分界、日文"主催者ページ"放进句子的通顺程度，请需求方抽看（报告中列出拿不准的句子）。
- 已有安装中帮助中心的 FAQ 存在数据库（`QbFaqEntry`），由内容管理员照 troubleshooting 的新文案改。
- 场景快照中的字典标签（公告对象、领奖方式）随之变化，用 `-Dscenario.update-snapshots=true` 重写并逐个查看差异。
