# 帮助文案：共享视频的台账（F-S14）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-S14.md`、`operations.md` §7.32。

- 参数（写进文案的是缺省值，改了参数时同步改这里）：等待确认超过 7 天提醒双方（`qb.video-use.remind-days`）；少于 5 的数字不显示（`qb.stats.min-group`）。
- 内部备注（不写进 FAQ）：平台不代收代付；台账不进平台的会计账本。

## 给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `PUBLISHING`）

### 中文 · 共享视频的台账是什么？

你的发布用了其他发起方共享的视频时，你们之间的费用由你们自己约定、直接结算。台账是平台替双方保存的记录：
- **使用方**（用视频的一方）在发布页的"共享视频的台账"中填写期间、计价方式（固定价、每千次曝光成功、每位去答题的学者）与价格，保存后"提交给提供方"。
- **提供方**（共享视频的一方）在"资源库"页"我共享的视频"下的"我的视频的使用台账"中看到提交的台账：与约定一致时确认，否则写原因拒绝。使用方看到原因，更正后重新提交。
- 每次修改、提交、确认、拒绝都会留下新的版本，旧的不会被改掉。已确认的台账也可以更正，但要请提供方重新确认。
- 等待确认超过 7 天时，平台会提醒双方。

台账旁边是双方都能看到的同一组数字：推送被打开、曝光成功、去答题的人数与合格人数。数字每天更新一次，发布结束后定稿，不再变化。为保护学者的隐私，少于 5 的数字显示为"少于 5"。按数字与价格算出的参考金额只供你们结算时参考。

平台不代收代付：费用请双方直接结算。

### 日本語 · 共有動画の台帳とは何ですか？

あなたの公開でほかの主催者が共有した動画を使うとき、費用は主催者どうしで取り決め、直接精算します。台帳は、プラットフォームが双方のために保管する記録です。
- **利用する主催者**（動画を使う側）は、公開のページの「共有動画の台帳」に期間、料金の決め方（固定価格、カウントした表示 1,000 回あたり、回答に進んだテイカー 1 人あたり）と価格を入力し、保存してから「提供者に提出する」を押します。
- **提供者**（動画を共有した側）は、「リソースライブラリ」ページの「共有している動画」の下にある「自分の共有動画の利用台帳」で提出された台帳を確認します。取り決めどおりなら確認し、違う場合は理由を書いて差し戻します。利用する主催者は理由を見て修正し、もう一度提出します。
- 修正、提出、確認、差し戻しのたびに新しい版が残り、前の版は書き換えられません。確認済みの台帳も修正できますが、提供者にもう一度確認してもらいます。
- 確認待ちが 7 日を超えると、双方にお知らせします。

台帳の横には、双方が同じ数字を見られます：開かれたプッシュ、カウントした表示回数、回答に進んだ人数、合格した人数。数字は 1 日に 1 回更新し、公開の終了後に確定して変わらなくなります。テイカーのプライバシーを守るため、5 未満の数字は「5 未満」と表示します。数字と価格から計算した参考金額は、精算の参考です。

プラットフォームは代金の受け取りや支払いを代行しません。費用は主催者どうしで直接精算してください。

### English · What is the ledger of a shared video?

When your publication uses a video another Sponsor shared, the two of you agree on the fee and settle it directly. The ledger is the record the platform keeps for both of you:
- **The Sponsor using the video** fills in, on the publication's page under "Ledger of the shared video", the period, how it is priced (a fixed price, per 1,000 exposures counted, or per Taker who went on to answer) and the price, saves it and sends it to the provider.
- **The provider** (the Sponsor who shared the video) finds it on the "Asset library" page under "Uses of my shared videos", below "Videos you share": they confirm it when it is what you agreed, or send it back with a reason. The Sponsor using the video reads the reason, corrects it and sends it again.
- Every correction, submission, confirmation and sending back leaves a new revision; earlier ones are never overwritten. A confirmed entry can be corrected too, and then goes back to the provider to confirm again.
- After 7 days waiting for the provider, both of you are reminded.

Next to the ledger, both of you see the same figures: the pushes opened, the exposures counted, the Takers who went on to answer and those who passed. They are updated once a day and become final when the publication ends. To protect the Takers' privacy, figures below 5 are shown as "Fewer than 5". The reference amount, from the figures and the price, is for your own settlement.

The platform does not collect or pay on anyone's behalf: settle the fee between yourselves.
