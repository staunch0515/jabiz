# F-F5 全产品改名：实施计划（S3）

规格：`docs/quizbuks/first-launch-spec.md` F-F5。来源：Q91 §0、§2、§3、§6b；`backend/quizbuks/CLAUDE.md` 的称呼规则。

## 用词

| | 中文 | 日文 | 英文 |
|---|---|---|---|
| 答题的一方 | 学者（取代 答题者、答题人） | テイカー（取代 回答者） | Taker（取代 quiz taker） |
| 发布的一方 | 发起方（取代 商户、商家） | 主催者（取代 スポンサー、加盟店） | Sponsor（取代 merchant） |

完整的三语词表：`docs/quizbuks/glossary.md`（发起方、学者、Kudos、Quiz、测验、问卷、发布、知识结构图、平台费账户、赠送额度、AI 额度）。

## 做法

1. **用词测试**（先写）：`tools/quizbuks/terms/terms.test.mjs`，在 `quizbuks.yml` 的 `web` 作业中运行。扫描范围：
   - 三个前端：App、发起方页面、平台管理系统扩展（不含测试文件）；
   - 维护页、招商网站 `biz`、品牌档案；
   - 服务端消息与资源（`db/` 迁移除外：那是历史，Flyway 也校验它们的校验和）；
   - 服务端 Java 里的中日文显示文字（角色名、字典）；
   - `docs/quizbuks/legal/`、`docs/quizbuks/store/`、troubleshooting 的"帮助页文案"。
   出现停用词即失败；例外写在测试的 `EXCEPTIONS` 中并说明原因（现有一条：一个注释里的计划文件名 `Q29-merchant-mail-server.md`）。
2. **机械替换**，脚本按以上范围（含测试文件与 e2e，使断言跟着文字走）：
   - 中文 商户 / 商家 → 发起方，答题者 / 答题人 → 学者；日文 スポンサー / 加盟店 → 主催者，回答者 → テイカー（`回答者` 只出现在日文文字中）。
   - 英文 merchant → Sponsor，quiz taker → Taker。
   - 英文作名词的 sponsor / taker 首字母大写（规格的 "the Sponsor / a Taker"）。只改句子里的字符串（消息值、带空格的字符串字面量、Markdown 正文），不改标识符、路径、占位符 `{{…}}` / `{…}`、代码注释；动词 sponsor（"May sponsor others' benefits"）保持小写。
3. **逐条复核**：
   - 合同条款（`contest-rules.*`）中"主催者"原本就是法律上的"主办方"，改后句子重复，按意思改写（第 1 条）；
   - 商店资料中 Apple / Google "不是主催者"的那句是商店要求的原文，`listing.test.mjs` 的禁用词改为 `スポンサー` 与"该句以外的 主催者"；
   - Java 中同一行有中、日两种旧词的字典与角色名，逐个改对。
4. **文档**：`backend/quizbuks/CLAUDE.md` 的称呼规则、`operations.md`、troubleshooting、法律文本的说明 `legal/README.md`。法律文本处于开发阶段，直接改文字，不发新版本，不要求重新同意（Q91 §0-10）。

不改代码标识（`taker`、`sponsor`、路由、消息键、权限、流程名），不加表与迁移。

## 测试

- 用词测试（含"测试本身能找到旧词、扫描的目录不为空"）。
- App、发起方页面、平台管理系统扩展、`biz` 的 lint / typecheck / Vitest；共用文件夹 `src/qb` 两边相同；维护页与商店资料的测试。
- 后端 `:quizbuks:test` 全部（消息、品牌、场景快照：快照中的字典标签与站内信文字随之更新，用 `-Dscenario.update-snapshots=true` 重写并逐个查看差异）。
- e2e 在 CI 中跑（断言随替换一起改）。

## 不做 / 留给需求方

- QuizBuks for Shopify（`backend/quizbuks-shopify`、`quizbuks-web/shopify*`、`storefront`）：不在首次上线，那里的"商户"指 Shopify 店铺。
- 帮助中心的 FAQ 存在数据库中（`QbFaqEntry`），由内容管理员照 troubleshooting 的新文案改；样板指南（Claude Doc）中"后台有时叫商户"一句由需求方删除。
- 已有安装中 `QB_SETUP` 建好的角色名不会被改（`QbSetupRecord` 只补从未有过的项）：staging 由管理员在平台管理系统中改一次；正式系统从空开始。
- 发起方自定义称呼（Q91 §0-11 起的付费产品）。

## 风险

- 英文大写的规则是机械的，个别句子读起来可能别扭；评审时请抽看英文。用词测试只管停用词，不管大小写。
