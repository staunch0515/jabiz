# Q61 产品名与点数名按语言（2026-10-31）

需求方（2026-10-31）：

- 平台的日语名字 **クイズdeおトク**（Quiz de Otoku），日语的 Kudos 为 **クドス**；中文名字 **答题拼好价**，中文的 Kudos 为 **学分**。
- "总的平台品牌是 QuizBuks，英文保持不变。中文在显示的地方变。""答题是学习，不是赌博，所以确定用学分。"
- "按多语言的方式处理就可以。前面有些地方没有被多语言化，现在就是改造成多语言的。"

## 1. 名称

| | en | zh | ja |
|---|---|---|---|
| 产品名 | QuizBuks | 答题拼好价 | クイズdeおトク |
| 点数 | Kudos | 学分 | クドス |

- **一种语言的文字用该语言的名字**：页面、邮件、站内信、单据、维护页、官网与 Shopify 介绍站、管理与运营商后台（平台的 `AdminBrand.name` 本就可以按语言给出）。
- **只能有一个名字的地方仍是 QuizBuks**（品牌档案的 `name`）：装到主屏幕的 App 名与 PWA 清单、未知语言时的标签页标题、发件人（`JABIZ_MAIL_FROM`）、
  `quizbuks/brand.properties`（分录摘要的前缀等）、版权行、Stripe 的商品名。商店中的 App 名按地区在商店后台登记（商店暂缓）。

## 2. 实现

- **品牌档案**：`brand.json` 加 `names: {en, zh, ja}`（三种都必填，`brands/brand.mjs` 检查）；`name` 留作一个名字的场合。见本档案 `sample` 同样给出三种。
  SPA 的 `BRAND.names`、静态站的 `BRAND.names`、管理后台扩展的 `BRAND_NAMES`（`brands/admin.mjs` 生成的 `brands.generated.ts` 带 `names`，扩展以它作平台的按语言的名字）。
- **文字**：各语言的消息写 `${BRAND.names.<语言>}`（扩展 `${BRAND_NAMES.<语言>}`）；后端消息仍写 `@QB_BRAND_NAME@`，`processResources` 按文件的语言替换（`messages_zh*` 为中文名，`messages_ja*` 为日文名，其余为英文名；两个模块的 `build.gradle.kts`）。
  中文、日文中名字与前后的汉字、假名之间不加空格。
- **点数名不是品牌档案的一项**（Q54 §4 的待定仍待定）：中文、日文的文字直接写"学分""クドス"。金额：
  - 前端：`qb/kudos.ts` 的 `KUDOS_NAMES` / `kudosName()`，`formatKudos` 与平台的金额格式随界面语言（切换语言时 `setAmountUnit` 重设）；输入框的单位用 `kudosName()`。
  - 站内信：`MessageTexts.kudos(金额)` 按每种语言写单位（消息 `qb.kudos.unit`）。
  - 邮件：平台的邮件参数是文字、没有语言，所以参数只给数字（`MailValues.amount`），单位写在各语言的模板中（`{amount} 学分`）。
- **检查**：`brand-profile.test.ts`（两个 SPA）与 `brands/brand.test.mjs` 同时拒绝源码中写死的中文、日文名；维护页的测试检查每种语言的名字；`kudos.test.ts` 检查单位随语言。

## 3. 不改的

- 英文的一切文字（名字与 Kudos 都不变）。
- 数据库中的内容：法律文本、帮助问答、已发出的单据与站内信（站内信在创建时以三种语言写成，Q7-15）。法律文本与问答由内容管理员在后台修改。
- 标识符：包名、表、权限、模板与流程的名字、`qb/kudos.ts` 的函数名等。
