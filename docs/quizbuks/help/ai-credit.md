# 帮助文案：AI 额度（F-M9）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`（中文"AI 额度"，日文"AI クレジット"）；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-M9.md`、`operations.md` §7.56。

- 参数（写进文案的是缺省值，改了参数时同步改这里）：倍数 `qb.ai.fee.markup`（1.5，即提供方价目的 150%）；汇率 `qb.ai.fee.usd-jpy`（每美元 150 日元）；最低购买额 `qb.ai.credit.min-purchase`（未设置时不限，设置后页面会写明）；税率 `qb.fee.tax-rate`。文案不写具体的单价。
- 内部备注（不写进 FAQ）：开关 `qb.ai.fee.enabled` 关着时请求不收费、不能购买、可以退回；从官网生成只在收费开着时可用。请求时按"可能的最大费用"（含税）预留，作业结束时按有回答的用量收费，至多收到预留为止，超出部分由平台承担。审核人的翻译核对与 AI 帮助不收费。

## 给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `BILLING`）

### 中文 · AI 额度是什么？怎么收费？

用 AI 出题、翻译和从官网生成资料，费用从你的 **AI 额度**中扣。AI 额度要先在"资金 → AI 额度"中购买：先用你平台费账户中的赠送，不够的部分用现金。

费用按 AI 提供方的价目计算：你的请求实际用到的量 × 价目 × 150%，按当时的汇率换成日元，**向上取整到 1 日元**（有用量时至少 1 日元），每月合计后另加消费税。页面上不显示美元。
- 发起请求时，会先按这次请求"可能的最大费用"（含税）从额度中**预留**；额度不够时请求会被拒，页面给出购买的入口。
- 作业结束时按实际用量计算，**不超过预留**；预留多出的部分立即还给你。
- 每月 1 日把上个月结束的作业合计成一笔 AI 费，从额度中扣，赠送先扣。
- **调用超时或没有回答的部分不收费**。作业失败时，已经有回答的用量照收（包括平台自动重试时有回答的部分）。
- 审核人的翻译核对、AI 帮助（右下角的问答）不收费，也不用你的额度。

### 中文 · AI 额度能退吗？赠送会过期吗？

能退。在"资金 → AI 额度"中可以退回任意金额，或"全部可退"：先退现金，再退赠送（到期最晚的先退），退回你的平台费账户。进行中请求的预留、已结束但还没扣的费用，要等结算后才能退。被封禁期间不能退，解封后照常。

额度中的赠送仍然有它原来的到期日：到期（或因点亮的节点不再持有而被作废）后，这部分不能再用于新的请求，也不能退回，每天的到期处理会把它作废；进行中的请求已经占用的部分照常用完。现金部分不会过期。

### 日本語 · AI クレジットとは何ですか？料金はどのように決まりますか？

AI による問題作成・翻訳・公式サイトからの資料作成の料金は、**AI クレジット**から差し引かれます。AI クレジットは「資金 → AI クレジット」で先に購入してください。プラットフォーム料金アカウントの付与分から先に使い、足りない分は現金で支払います。

料金は AI の提供元の価格表で計算します：リクエストで実際に使った量 × 価格 × 150% を、その時点の為替レートで円に換算し、**1 円単位に切り上げ**ます（利用があれば最低 1 円）。月ごとに合計し、消費税を加えます。画面にドルは表示しません。
- リクエストの時点で、そのリクエストに**かかりうる最大の料金**（税込）を AI クレジットから**予約**します。足りないときはリクエストを受け付けず、購入への入口を表示します。
- ジョブが終わると実際の利用量で計算し、**予約額を超えることはありません**。予約の余った分はすぐに戻ります。
- 毎月 1 日に、前の月に終わったジョブを 1 件の AI 利用料にまとめて AI クレジットから差し引きます（付与分から先に）。
- **タイムアウトや応答のなかった呼び出しは請求しません**。ジョブが失敗した場合も、応答のあった利用分は請求します（プラットフォームの自動再試行で応答があった分を含みます）。
- 審査担当の翻訳チェックと AI ヘルプ（右下の質問）は無料で、あなたの AI クレジットは使いません。

### 日本語 · AI クレジットは返金できますか？付与分に期限はありますか？

返金できます。「資金 → AI クレジット」で任意の金額、または「返金できる全額」を返金できます。現金から先に、次に付与分（期限の遅いものから）を、プラットフォーム料金アカウントへ戻します。処理中のリクエストの予約分と、終了したもののまだ差し引かれていない料金は、精算が済むまで返金できません。利用停止中は返金できず、解除後は通常どおりです。

AI クレジットの中の付与分は、もとの有効期限のままです。期限切れ（または点灯したノードを保持しなくなったための取消）の後は、新しいリクエストにも返金にも使えず、毎日の期限処理で取り消されます。処理中のリクエストがすでに使っている分は通常どおり使われます。現金の分に期限はありません。

### English · What is the AI credit, and how is it charged?

Writing questions, translating and drafting from your website with AI are paid from your **AI credit**. Buy it first under "Money → AI credit": the granted credit in your platform fee account is used first, the rest paid in cash.

The fee follows the AI provider's price list: what your request actually used × the price × 150%, in yen at the rate of the day, **rounded up to the yen** (at least 1 yen when anything was used), with consumption tax added to each month's total. The pages show no dollars.
- When you ask, the **most this request may cost** (with tax) is **held** from your credit; when the credit cannot hold it, the request is refused and the page offers a purchase.
- When the job ends it is charged what it used, **never more than what was held**; the rest goes back to you at once.
- On the 1st of each month the jobs that ended the month before are charged from the credit as one AI fee, the granted credit first.
- **Calls that timed out or got no answer are not charged.** A job that failed is charged what was answered (a retry the platform made counts when it was answered).
- A reviewer's translation check and the AI help (the questions at the bottom right) are free and do not use your credit.

### English · Can I get the AI credit back? Does granted credit expire?

Yes. Under "Money → AI credit" give back any amount, or "all that may be given back": the cash first, then the granted credit (the latest to expire first), back to your platform fee account. What the requests still running hold, and the fees of finished requests not charged yet, can be given back once they are settled. While your account is suspended you cannot give it back; afterwards you can.

Granted credit in the AI credit keeps its expiry: once it expired (or was voided because the lit node is no longer held) it is neither used for new requests nor given back, and the daily expiry voids it; what a running request already holds of it is used as usual. Cash does not expire.
