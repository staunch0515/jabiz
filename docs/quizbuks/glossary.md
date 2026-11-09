# QuizBuks 三语词表

F-F5（`docs/quizbuks/first-launch-spec.md`）固定的用词。界面、邮件、站内信、推送、帮助、法律文本、商店资料一律用下表的词；新写的文字照此。
停用的词由用词测试 `tools/quizbuks/terms/terms.test.mjs` 检查（CI：`quizbuks.yml` 的 `web` 作业），例外写在测试中并说明原因。
只改显示的文字：代码标识（`taker`、`sponsor`、路由、消息键、权限、流程名）不改。

| 概念 | 中文 | 日文 | 英文 | 停用 |
|---|---|---|---|---|
| 答题的一方 | 学者 | テイカー | Taker | 答题者、答题人、回答者；Quiz taker |
| 发布的一方 | 发起方 | 主催者 | Sponsor | 商户、商家；スポンサー、加盟店；merchant |
| 代管发起方的一方 | 运营商 | 運営パートナー | Operator | 運営会社（需求方 2026-11-07：日文统一为運営パートナー；专指法律上的公司时除外） |
| 共享视频的所有者 | 视频提供方（上下文清楚时写"提供方"） | 動画の提供者 | video provider | 视频制作方、制作方 |
| 奖励的点数 | Kudos（显示为"学分"） | Kudos（显示为"クドス"） | Kudos | 对外不写币种名（`backend/quizbuks/CLAUDE.md` §4） |
| 一套题目 | Quiz | Quiz | Quiz | |
| 计分的 Quiz | 测验 | クイズ | Quiz | |
| 不计分的 Quiz | 问卷 | アンケート | Survey | |
| 上架的一次活动 | 发布 | 公開 | Publication | |
| 知识的结构 | 知识结构图 | 知識マップ | knowledge map | |
| 付平台费的账户 | 平台费账户 | プラットフォーム利用料アカウント | Platform fee account | |
| 赠送的平台费额度 | 赠送额度 | 付与クレジット | Granted credit | |
| AI 的额度 | AI 额度 | AI クレジット | AI credit | |

## 各部分的名字（F-F5b，需求方 2026-11-07）

人读到的文字中不用"后台"（日文不用"管理画面""コンソール"，英文不用 "console" / "Administration" / "back office"）指产品的任何部分。

| 部分 | | 中文 | 日文 | 英文 | 停用 |
|---|---|---|---|---|---|
| 发起方一侧（`/sponsor/`） | 作名称（标题、按钮"打开…"） | 答题拼好价发起方 | クイズdeおトク 主催者 | QuizBuks for Sponsors | 发起方后台、商家后台；主催者管理画面、主催者コンソール；Sponsor console |
| | 在句子中 | 发起方页面 | 主催者ページ | the Sponsor app | |
| 运营商一侧（`/operator/`） | | 运营商管理系统 | 運営パートナー管理システム | Operator Management System | 运营商后台；自社の管理画面；運営会社管理システム；back office |
| 平台管理（`/admin/`） | | 答题拼好价平台管理系统 | クイズdeおトク 管理システム | QuizBuks Management System | 管理后台；管理画面；Administration |

- 名称中的产品名不写死（Q61）：SPA 用 `BRAND.names.<语言>`（如 `${BRAND.names.zh}发起方`、`${BRAND.names.en} for Sponsors`），服务端消息用 `@QB_BRAND_NAME@`，招商网站用 `src/brand.mjs`。中文用中文名"答题拼好价"，不用 QuizBuks。
- 文档中可省去品牌名，写"平台管理系统"。
- 其他公司产品自己的叫法照旧："Shopify 后台""Shopify 管理画面"、Google Play Console、Anthropic Console、 Stripe Dashboard 。
- 用词测试 `terms.test.mjs` 的 `RETIRED_NAMES` 检查这些叫法。

## 写法

- **英文**：两个称呼当作专有的称呼，首字母大写，写成不随称呼变形的样子："the Sponsor"、"a Taker"、"Sponsors' pages"。动词 sponsor（赞助）照常小写。
- **日文**：不加"様"；"主催者"与"テイカー"直接放进句子（"主催者のウォレット"、"テイカーに表示"）。
- **中文**："发起方""学者"直接替换旧词；"学者人数"之类的组合照常用。
- 应用代表商店（Apple、Google）声明"不是活动的主办方 / 主催者 / sponsor"的那句话里，"主催者""sponsor"是法律上的意思，不是本表的称呼（`tools/quizbuks/store/listing.test.mjs` 只允许这一处）。

## 不在本次范围

- QuizBuks for Shopify（`backend/quizbuks-shopify`、`quizbuks-web/shopify*`、`storefront`）：不在首次上线；那里的"商户"指 Shopify 店铺，不是发起方。
- 发起方自定义称呼（Q91 §0-11 起的付费产品）。
- 已经由 `QB_SETUP` 建好的角色名（`QbSetupRecord` 只补从未有过的项）：已有安装中的角色名由管理员在平台管理系统中改一次（正式系统从空开始，不受影响）。
