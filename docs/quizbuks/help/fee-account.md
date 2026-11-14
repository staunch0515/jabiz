# 帮助文案：费用与平台费账户、学分即将开放（F-F10b）

给发起方与学者看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-F10.md` §1.6、`docs/quizbuks/plans/impl/F-M.md`、`operations.md` §4 与 §7.38、§7.39。

- 文案不写具体金额：金额由运营商的价目表与平台的参数决定，写"见价目表"或点名参数；写进文案的缺省数字只有赠送额度的两档与有效期（`qb.fee-credit.grant.basic` 1,000、`.certified` 10,000、`.valid-days` 90，改了参数时同步改这里）。
- 首次上线的状态（写进文案，`FirstLaunchSwitches` 的值）：学分不使用（发起方的说法是"学分暂不使用"，学者的是"学分只显示、还不能使用"；不能用现金换学分、不能换回现金、不转账、不提现；发布不能设学分奖励）；赠送额度开；`qb.fee.monthly` 与抽成为 0。学分开放的日期不承诺；开放那天删去首次上线的句子，按开放后的功能改写本文案与 AI 使用手册（`manual-*.md`）。文案不承诺平台届时会单独通知（没有这样的通知功能），只请读者留意公告。
- 页面：发起方页面"资金 → 平台费账户"（`/sponsor/money/account`）、"资金 → 账单"；学者 App"我的"最上面的"学分"（带"即将开放"）。

## 一、给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `ACCOUNT`）

### 中文 · 平台收哪些费用？什么时候扣？

平台费有这几类：**月费**、**受众规则费**（客户匹配中按天收费的条件）、**配送费**（按开始答题的人数）、**曝光费**（购买的曝光次数）、**AI 费**（用 AI 生成或翻译 Quiz）。每一类是否已经开始收取、金额多少，以您的运营商设定的价目表为准，见"资金 → 账单"和运营商给您的价目表；还没有开始收取的费用不会扣款。

扣款的时点由各类费用的规则决定，开始收取时会在价目表中写明。月费等按月结算的费用开具账单，到期前从平台费账户支付。每一笔费用都会写明用途和金额，在"资金 → 账单"可以看到，并可下载账单。

### 日本語 · プラットフォーム利用料にはどんな種類がある？ いつ引かれる？

プラットフォーム利用料は次の種類です。**月額利用料**、**対象条件の料金**（顧客マッチングのうち日割りで課金される条件）、**配信料**（回答を開始した人数に応じて）、**露出料**（購入した露出回数）、**AI 利用料**（AI でクイズを作成・翻訳したとき）。それぞれの料金をすでに請求しているか、いくらかは、運営パートナーが設定した価格表によります。「資金 → 請求書」と、運営パートナーからの価格表をご覧ください。請求を始めていない料金が引かれることはありません。

引かれるタイミングは料金の種類ごとの規則で決まり、請求を始めるときに価格表に記されます。月額利用料など月ごとに精算される料金は請求書になり、期日までにプラットフォーム利用料アカウントから支払われます。どの料金も用途と金額が記され、「資金 → 請求書」で確認と請求書のダウンロードができます。

### English · Which platform fees are there, and when are they taken?

The platform's fees are of these kinds: the **monthly fee**, the **audience rule fee** (the customer-matching conditions that are charged by the day), the **distribution fee** (by the number of people who start answering), the **exposure fee** (the exposure you buy) and the **AI fee** (when AI drafts or translates a quiz). Whether each kind is being charged yet, and how much, is set in your Operator's price list: see "Money → Invoices" and the price list your Operator gave you. A fee that is not being charged yet is never taken.

When a fee is taken is set by the rule of its kind, and the price list says so once the fee is being charged. Fees settled by the month, such as the monthly fee, are invoiced and paid from the Platform fee account by the due date. Every fee states what it is for and how much; you see them in "Money → Invoices" and can download the invoice.

### 中文 · 平台费账户由什么组成？先用哪一部分？

平台费账户有两部分：**赠送额度**（平台或运营商赠送给您的平台费额度，有到期日）和**现金**（您充值或由运营商转入的钱，只能用来付平台费）。付费时先用赠送额度（最早到期的先用），再按进账先后用现金。赠送额度只能付平台费，不能换成现金或学分，到期没用完的部分作废。开户的赠送额度：运营商确认资料完整后发放，未认证的发起方 1,000，企业认证通过后补到 10,000，自发放起 90 天有效（金额与天数是现在的缺省值，由平台参数决定）。

### 日本語 · プラットフォーム利用料アカウントは何でできている？ どこから先に使われる？

プラットフォーム利用料アカウントは 2 つの部分でできています。**付与クレジット**（プラットフォームや運営パートナーが付与する利用料のクレジット。有効期限があります）と、**現金**（チャージした金額や運営パートナーから移された金額。利用料の支払いにだけ使えます）です。支払いでは、まず付与クレジット（期限が早いものから）、次に現金（入金の早い順）が使われます。付与クレジットは利用料の支払いにだけ使え、現金やクドスには換えられません。期限までに使わなかった分は失効します。開設時の付与クレジットは、運営パートナーが情報の確認を終えたときに付与されます。未認証の主催者は 1,000、企業認証が済むと 10,000 まで追加され、付与から 90 日間有効です（金額と日数は現在の既定値で、プラットフォームのパラメータで決まります）。

### English · What is the Platform fee account made of, and what is used first?

The Platform fee account has two parts: **Granted credit** (platform fee credit the platform or your Operator gives you, with an expiry date) and **cash** (money you topped up or your Operator moved to you, which can pay platform fees only). A fee is paid from the Granted credit first (the one that expires first), then from the cash in the order it arrived. Granted credit pays platform fees only: it cannot be turned into cash or Kudos, and what is unused at its expiry is voided. The onboarding Granted credit is given once your Operator confirms your details are complete: 1,000 for an unverified Sponsor, topped up to 10,000 when your company is verified, valid for 90 days from the grant (these are the current defaults of the platform's parameters).

### 中文 · 账户里的钱不够付怎么办？在哪里看？

账户有多少付多少，其余留在账单上，显示为未付。有未付或逾期的账单时，可能无法提交新的发布（页面会写明原因）。请在"资金 → 平台费账户"充值现金（刷卡，或在提供时用银行转账），然后在"资金 → 账单"支付未付的账单。

在"资金 → 平台费账户"顶部看合计、赠送额度和现金；下面按使用顺序列出每一笔剩余（含到期日）和每一笔费用是怎样付的。

### 日本語 · アカウントの残高が足りないときは？ どこで確認できる？

アカウントにある分だけが支払われ、残りは請求書に未払いとして残ります。未払い・期限超過の請求書があると、新しい公開を申請できないことがあります（画面に理由が表示されます）。「資金 → プラットフォーム利用料アカウント」で現金をチャージしてください（カード、または提供されている場合は銀行振込）。入金されたら、「資金 → 請求書」で未払いの請求書を支払ってください。

「資金 → プラットフォーム利用料アカウント」の上部に合計、付与クレジット、現金が表示されます。その下に、使われる順に残りの一件ごと（期限つき）と、利用料ごとの支払い方が並びます。

### English · What if the account does not cover a fee? Where do I see it?

The account pays as far as it goes; the rest stays on the invoice as unpaid. While an invoice is unpaid or overdue you may be unable to submit new publications (the page says why). Top up cash in "Money → Platform fee account" (by card, or by bank transfer where it is offered), then pay the unpaid invoice in "Money → Invoices".

The top of "Money → Platform fee account" shows the total, the Granted credit and the cash; below it, every amount that is left in the order it is used (with its expiry), and how each fee was paid.

### 中文 · 贷项凭证是什么？发布提前结束会退钱吗？

贷项凭证减少您应付的平台费，原来的费用和账单不变。它有两种来源：发布提前结束时退回受众规则费未用的天数（是否退回按平台的设置；被平台下架的不退）；平台的财务人员更正某笔费用（由另一位管理员批准）。凭证先抵这笔费用所在账单还欠的部分；预付的费用再把超出的部分退回原来付款的地方（现金或赠送的平台费 Kudos，已过期的赠送不再退回）；余下的成为贷项，先抵您以后的账单。在"资金 → 账单"的"贷项凭证"一栏可以看到每张凭证并下载 PDF；账单上写明已用凭证抵减的金额。

### 日本語 · 返還インボイスとは？ 公開が早く終わると返金されますか？

返還インボイスは、お支払いいただくプラットフォーム利用料を減らすものです。元の料金と請求書はそのままです。発行されるのは、公開が早く終了したときの対象条件の料金のうち未使用日数分（返金するかどうかはプラットフォームの設定によります。プラットフォームによる取り下げは対象外です）と、プラットフォームの経理担当が料金を訂正するとき（別の管理者が承認します）です。まずその料金の請求書の未払い分に充当し、前払いの料金は残りを支払元（現金または付与クレジット。期限切れの付与クレジットには戻りません）に返金します。それでも残る分は次回以降の請求書に先に充当します。「資金 → 請求書」の「返還インボイス」で一覧と PDF を確認でき、請求書には充当額が記されます。

### English · What is a credit note? Do I get money back when a publication ends early?

A credit note lowers what you owe in platform fees; the fee and its invoice stay as they were. One is issued when a publication ends early, for the unused days of its audience rule fee (whether they are given back depends on the platform's setting; a takedown by the platform is not), or when the platform's finance staff correct a fee (approved by a second administrator). It first pays what the fee's invoice still owes; for a prepaid fee it then gives the rest back to where it was paid from (cash or granted credit; granted credit that has expired is not given back); anything still left pays your next invoices first. See each credit note and download its PDF under "Credit notes" in "Money → Invoices"; an invoice shows what credit notes paid of it.

### 中文 · 账单上写着"付款情况"，是现在的吗？

不是。账单的 PDF 是您**第一次下载那天**做的，写明"付款情况截至"那一天：赠送额度、预付款、现金、贷项凭证各付了多少，还有多少没付。之后再付款，PDF 不会改（它是一份固定的文件）；最新的付款情况在"资金 → 账单"里点开那一行看。金额、税与明细永远不变；税按税率分别小计。已作废的账单仍可下载原件，页面会写明作废的日期。

### 日本語 · 請求書の「支払状況」は現在のものですか？

いいえ。請求書の PDF は**初めてダウンロードした日**に作られ、「支払状況（基準日）」としてその日の付与クレジット・前払金・現金・返還インボイスでの支払額と、未払い額が記されます。その後に支払っても PDF は変わりません（固定された書類です）。最新の支払状況は「資金 → 請求書」で該当の行を開くと確認できます。金額・税・明細は変わらず、税は税率ごとに小計されます。取り消された請求書も、すでに発行された原本はダウンロードでき、取消日が表示されます。

### English · Is the "payment status" on an invoice current?

No. An invoice's PDF is made the **day you first download it**, and states the payment status as of that day: how much was paid with Granted credit, the prepayment, cash and credit notes, and what is still owed. Later payments do not change the PDF (it is a fixed document); the latest status is under "Money → Invoices", on the row you open. The amounts, tax and lines never change; tax is subtotalled by rate. A voided invoice's original can still be downloaded, and the page says the day it was voided.

### 中文 · 学分现在能用吗？

**首次上线时学分暂不使用。** 现在发起方页面的菜单中没有"钱包"，不能用现金换学分，学分不能换回现金，也不能转账或提现；发布暂时不能把学分设为奖励（奖励方式是到店领取的奖品、电子奖品等）。支付平台费用的是"平台费账户"，不是学分钱包。学分何时开放使用，请留意平台的公告；此前请不要为学分充值。

### 日本語 · クドスは今使えますか？

**運用開始時、クドスはまだ使用できません。** 今は主催者ページのメニューに「ウォレット」はなく、現金からクドスへの交換、クドスから現金への交換、送金、出金はできません。公開でクドスを報酬に設定することもできません（報酬は、お店で受け取る景品やデジタル景品などです）。プラットフォーム利用料の支払いに使うのは「プラットフォーム利用料アカウント」で、クドスのウォレットではありません。クドスがいつ使えるようになるかは、プラットフォームのお知らせをご確認ください。それまでクドスのためのチャージはしないでください。

### English · Can I use Kudos now?

**At the first launch, Kudos are not in use yet.** For now the Sponsor pages have no Wallet in the menu: you cannot turn cash into Kudos or Kudos back into cash, transfer them or withdraw them, and a publication cannot offer Kudos as its reward (rewards are prizes collected in store, digital prizes and the like). Platform fees are paid from the "Platform fee account", not from the Kudos wallet. Watch the platform's announcements for when Kudos open for use; until then please do not top up for Kudos.

## 二、给学者（FAQ 的 `audience` = `TAKER`，分类 `REWARDS`）

### 中文 · "我的"里的学分为什么显示 0，还写着"即将开放"？

学分是答题奖励的一种点数。**首次上线时学分只显示、还不能使用**，所以"我的"最上面的"学分"是 0 并标着"即将开放"：现在发起方给的奖励是到店领取的奖品和电子奖品等，不发学分，你也不会因此少得任何东西。学分何时开放使用，请留意 App 中的公告；已经得到的奖品和证书不受影响。我们现在不承诺开放的日期。

### 日本語 · 「マイページ」のクドスが 0 で「近日公開」と出ているのはなぜ？

クドスは、回答の報酬のひとつのポイントです。**運用開始時、クドスは表示のみで、まだ使えません**。そのため「マイページ」の一番上の「クドス」は 0 で「近日公開」と表示されています。今、主催者が用意する報酬はお店で受け取る景品やデジタル景品などで、クドスは付与されません。そのために受け取れるものが減ることはありません。クドスがいつ使えるようになるかは、アプリのお知らせをご確認ください。すでに受け取った景品や証明書には影響しません。公開の時期は、今のところお約束できません。

### English · Why does Kudos show 0 on "Me", with "Coming soon"?

Kudos are a kind of points for rewards. **At the first launch, Kudos are shown but cannot be used yet**, so the "Kudos" at the top of "Me" is 0 and says "Coming soon". The rewards Sponsors give now are prizes you collect in store, digital prizes and the like, not Kudos, so you are not missing anything. Watch the announcements in the app for when Kudos open for use; the prizes and certificates you already have are not affected. We cannot promise a date yet.
