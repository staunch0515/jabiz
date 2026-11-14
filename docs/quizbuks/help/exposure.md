# 帮助文案：共享视频的曝光（F-S13）

给学者与发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-S13.md`、`operations.md` §7.31。

- 参数（写进文案的是缺省值，改了参数时同步改这里）：连续看 10 秒算一次（`qb.exposure.seconds`）；竖屏模式中每 5 屏一个推送（`qb.exposure.push.every`）；每位学者每天（日本时间）至多计 200 次（`qb.exposure.daily-cap-per-user`）。
- 曝光费的参数（F-M8；写进文案的是缺省值，改了参数时同步改这里）：购买至少多少次由 `qb.exposure.min-purchase` 决定（占位值 100，上线前由需求方定，提交时页面会写明）；剩余不足一成时提醒（`qb.exposure.low-ratio` 缺省 0.1）；价格由运营商定、在总部的范围内（`qb.exposure.unit.min` / `.max`）。文案不写具体金额。
- 内部备注（不写进 FAQ）：曝光费随 F-M8 上线：提交时购买并冻结预付、进行中可追加购买、每月与结束时结算、剩余不足一成时提醒；页面见发布详情的"共享视频的曝光"一节（`qb.sponsor.publication-exposure`）。开关 `qb.exposure.billing.enabled` 打开之前提交的发布没有已购次数，仍显示"不限"。

## 一、给学者（FAQ 的 `audience` = `TAKER`，分类 `ANSWERING`）

### 中文 · 竖屏模式里为什么会出现我没点的 Quiz？

在"短视频"的全屏竖屏模式中，每滑几屏会出现一个用了其他发起方共享视频的 Quiz（推送），几家发起方轮流出现。打开过一次的推送不会再推给你。你屏蔽的内容（发布、Quiz、发起方、视频提供方）和打开"只看喜欢的主题"后不符合的 Quiz 都不会被推送：付多少钱都越不过你的设置。首页的内容不变。

### 日本語 · 縦型モードに、自分で選んでいないクイズが出てくるのはなぜですか？

「ショート動画」の全画面の縦型モードでは、何画面かごとに、ほかの主催者が共有した動画を使っているクイズ（プッシュ）が表示されます。複数の主催者が順番に表示されます。一度開いたプッシュは、あなたにはもう表示されません。あなたがブロックしたもの（公開・クイズ・主催者・動画の提供者）や、「好きなトピックだけ表示」をオンにしたときに合わないクイズは、プッシュされません。いくら支払ってもあなたの設定は越えられません。ホームの内容は変わりません。

### English · Why does the vertical mode show quizzes I did not tap?

In the full-screen vertical mode of "Shorts", every few screens a quiz using a video another Sponsor shared appears (a push), several Sponsors taking turns. A push you opened once is not shown to you again. Nothing you blocked (a quiz, every round of a quiz, a Sponsor, a video provider) and, with "Show only the topics I like" on, no quiz outside your topics is pushed: no payment gets past your settings. Your home page stays as it is.

## 二、给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `PUBLISHING`）

### 中文 · 用了共享视频的发布，曝光怎么计？

你的发布用了其他发起方共享的视频时，学者看得够久即计一次曝光：
- **竖视频**：在 App 的竖屏模式中连续播放 10 秒（视频不到 10 秒时看完即算）。你的发布在那里作为推送出现，和其他发起方轮流；首页一排的预览不计。
- **横视频**：在开始答题页，视频在画面上且 App 在前台连续 10 秒。首页的自动播放不计。

每位学者每个发布至多计一次；你自己、视频的提供方和你们的店员看不计；每位学者每天有上限。用你自己的视频（自己的资料）不计曝光。
发布详情的"费用"标签中，"共享视频的曝光"一节显示已购次数（没有购买时为"不限"）、已成功、计费、推送被打开与去答题的人数。买的次数用完后，发布会在一分钟内自动结束（结束原因"曝光已用完"），已经开始的答题照常完成，你会收到邮件；同时可能多计几次，超出已购的不收费。

### 日本語 · 共有動画を使った公開の表示回数はどう数えますか？

ほかの主催者が共有した動画をあなたの公開で使うと、テイカーが十分な時間見たときに 1 回の表示としてカウントします。
- **縦型の動画**：アプリの縦型モードで 10 秒続けて再生されたとき（10 秒未満の動画は最後まで再生されたとき）。あなたの公開はそこでプッシュとして、ほかの主催者と順番に表示されます。ホームの一列のプレビューはカウントしません。
- **横型の動画**：クイズの開始ページで、動画が画面に表示され、アプリが前面にある状態で 10 秒続いたとき。ホームの自動再生はカウントしません。

テイカー 1 人につき 1 つの公開で 1 回までです。あなた自身、動画の提供者、それぞれのスタッフの視聴はカウントしません。テイカーごとに 1 日の上限があります。あなた自身の動画（自分の資料）は表示回数をカウントしません。
公開の詳細の「費用」タブの「共有動画の表示回数」に、購入した回数（購入していないときは「上限なし」）、カウント済み、課金の対象、開かれたプッシュ、回答に進んだ人数を表示します。購入した回数を使い切ると、公開は 1 分以内に自動で終了し（終了の理由「表示回数を使い切り」）、始まっている回答は通常どおり最後までできます。メールでお知らせします。終了までの間に少し多くカウントされることがありますが、購入した回数を超えた分は課金されません。

### English · How are exposures counted for a publication using a shared video?

When your publication uses a video another Sponsor shared, each Taker who watches it long enough counts one exposure:
- **Vertical videos**: 10 seconds of play without a break in the App's vertical mode (a video shorter than 10 seconds: played to the end). Your publication appears there as a push, taking turns with other Sponsors; the preview on the home page's shelf does not count.
- **Horizontal videos**: 10 seconds on the quiz's start page with the video on the screen and the App in front. Autoplay on the home page does not count.

At most once per Taker and publication; your own views, the video provider's and your staff's are not counted; each Taker has a daily limit. Your own videos (your own materials) do not count exposures.
The "Exposures of the shared video" section on the "Fees" tab of your publication shows the exposures bought ("Unlimited" when none were bought), counted, charged, the pushes opened and the Takers who went on to answer. Once the exposures bought are used up, the publication ends within a minute (the reason: "Exposures used up"); attempts already started finish as usual, and you get an email. A few more may be counted meanwhile: what goes beyond what you bought is not charged.

### 中文 · 曝光费怎么收？怎么追加购买？

用了其他发起方共享视频的发布，在平台收取曝光费时要**购买曝光次数**：
- **提交时购买**：在发布向导的"购买的曝光次数"填写要买多少次（不能少于平台规定的最少次数，提交时页面会写明）。每次曝光的价格由您所属的运营商定，提交时即定下，之后不变。提交时平台费账户会**冻结**"次数 × 单价 + 税"作为预付款；预付款不是费用，先放着，用多少扣多少。
- **用多少收多少**：每月 2 日结算上个月计入的曝光，发布结束后做最后一次结算；按计入的次数收费，**不会超过您购买的次数**：超出的曝光在您追加购买之前不收费；追加购买后，此前已计入、超出的次数会随新买的一并收费，以新的总数为限。先从冻结的预付款扣，不够再从平台费账户扣。
- **结束后退回**：发布结束后，冻结的款项里没有用掉的部分会在约一天内（最长约 25 小时）退回平台费账户（赠送额度退回原来的赠送额度）。发布退回草稿、被驳回或撤回时，冻结的款项全部退回。
- **追加购买**：发布进行中，在发布详情"费用"标签的"共享视频的曝光"一节点"追加购买曝光"，按提交时的单价再买若干次，追加的款项（含税，页面先显示冻结的金额，账户不够时写明差多少）立即从平台费账户冻结。剩余不足一成时，平台会通知您（站内信和邮件），买了更多之后会再通知一次。审核中的发布请先撤回再追加。
- 用您自己的视频、挑战与店面的发布不收曝光费。
- 对计入的次数有疑问：先看发布详情里的已成功次数与历次结算（每月一行，写明次数、金额与税），仍有疑问请通过"帮助"联系我们；确有错收的，更正都以贷项凭证进行，不会改动原来的记录。

### 日本語 · 露出料はどう請求されますか？ 追加購入はできますか？

ほかの主催者が共有した動画を使う公開では、プラットフォームが露出料を請求する場合に**表示回数を購入**します。
- **提出時の購入**：公開ウィザードの「購入する表示回数」に購入する回数を入力します（プラットフォームが定める最低回数以上。提出時に画面に表示されます）。1 回あたりの価格は所属する運営パートナーが決め、提出時に固定され、その後は変わりません。提出時に、プラットフォーム利用料アカウントから「回数 × 単価 + 税」が**前払い**として確保されます。前払いは料金ではなく、使った分だけ引かれます。
- **使った分だけ**：毎月 2 日に前月にカウントされた表示を精算し、公開が終わると最後の精算を行います。カウントされた回数に応じて請求され、**購入した回数を超えることはありません**。超えた表示は、追加購入するまでは請求されません。追加購入すると、それまでにカウント済みで超えていた分も、新しい回数と合わせて新しい合計を上限に請求されます。確保した前払いから先に引かれ、足りなければプラットフォーム利用料アカウントから引かれます。
- **終了後の返却**：公開が終わると、確保した金額のうち使われなかった分が 約 1 日以内（最長で約 25 時間）にプラットフォーム利用料アカウントへ返却されます（付与クレジットは元の付与クレジットへ）。公開が下書きに戻された、却下された、取り下げられたときは、確保した金額がすべて返却されます。
- **追加購入**：公開中は、公開の詳細の「費用」タブにある「共有動画の表示回数」で「表示回数を追加購入」を押し、提出時の単価でさらに購入できます。追加分は税込みですぐにプラットフォーム利用料アカウントから確保されます（確保する金額は先に画面に表示され、足りないときは不足額も表示されます）。残りが 1 割以下になるとお知らせ（メッセージとメール）が届き、追加購入のあとにもう一度届きます。審査中の公開は、いったん取り下げてから追加してください。
- 自分の動画を使う公開、チャレンジ、店舗の掲載には露出料はかかりません。
- カウントされた回数について疑問があるときは、まず公開の詳細で、カウント済みの回数と精算（月ごとに 1 行、回数・金額・税）をご確認ください。解決しない場合は「ヘルプ」からお問い合わせください。誤って請求した場合の訂正は返還インボイスで行い、元の記録は変更しません。

### English · How is the exposure fee charged? Can I buy more?

A publication that uses a video another Sponsor shared **buys exposures** when the platform charges the exposure fee:
- **Bought when you submit**: in the publication wizard, "Exposures to buy" is how many you buy (at least the least the platform sets; the page says it when you submit). The price of one exposure is set by your Operator and fixed when you submit; it does not change after. When you submit, the Platform fee account **sets aside** "number × price + tax" as a prepayment. A prepayment is not a fee: it waits, and only what is used is taken.
- **You pay for what is used**: on the 2nd of each month the exposures counted in the month before are settled, and a last settlement follows when the publication ends. You are charged for the exposures counted, **never more than you bought**: exposures beyond that are not charged until you buy more; once you do, those already counted beyond what you had bought are charged together with the new ones, up to the new total. The prepayment is used first, then the Platform fee account.
- **What is left comes back**: after the publication ends, what was set aside and not used goes back to the Platform fee account in about a day (25 hours at most) (Granted credit goes back to the Granted credit it came from). If the publication returns to its draft, is rejected or is withdrawn, everything set aside goes back.
- **Buying more**: while the publication runs, open the "Exposures of the shared video" section on the "Fees" tab of its page and choose "Buy more exposures": you buy more at the price frozen at your submission, and the amount, with tax, is set aside from the Platform fee account at once (the page shows it first, and what is missing if the account is short). When a tenth or less is left you are told (a message and an email), and again after you buy more. A publication in review has to be withdrawn first.
- Publications that use your own videos, challenges and storefronts pay no exposure fee.
- If you question the exposures counted: look first at the counted exposures and the settlements on the publication's page (a row a month with the exposures, the fee and its tax); if it does not settle it, contact us from "Help". A fee charged wrongly is corrected by a credit note; the original record is never changed.
