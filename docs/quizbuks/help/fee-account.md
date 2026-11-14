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

自动付款开启期间，现金到账（刷卡、银行转账、运营商转入）或获得赠送额度后，您未付的账单会按开具先后自动支付，无需操作，付款后约 10–20 分钟内，这段时间付掉的账单会合并成一封邮件告知（含您自己手工付的）。账户有多少付多少，不够的部分仍然未付。

### 日本語 · アカウントの残高が足りないときは？ どこで確認できる？

アカウントにある分だけが支払われ、残りは請求書に未払いとして残ります。未払い・期限超過の請求書があると、新しい公開を申請できないことがあります（画面に理由が表示されます）。「資金 → プラットフォーム利用料アカウント」で現金をチャージしてください（カード、または提供されている場合は銀行振込）。入金されたら、「資金 → 請求書」で未払いの請求書を支払ってください。

「資金 → プラットフォーム利用料アカウント」の上部に合計、付与クレジット、現金が表示されます。その下に、使われる順に残りの一件ごと（期限つき）と、利用料ごとの支払い方が並びます。

### English · What if the account does not cover a fee? Where do I see it?

The account pays as far as it goes; the rest stays on the invoice as unpaid. While an invoice is unpaid or overdue you may be unable to submit new publications (the page says why). Top up cash in "Money → Platform fee account" (by card, or by bank transfer where it is offered), then pay the unpaid invoice in "Money → Invoices".

The top of "Money → Platform fee account" shows the total, the Granted credit and the cash; below it, every amount that is left in the order it is used (with its expiry), and how each fee was paid.

自動支払いが有効になっている間は、現金の入金（カード・銀行振込・運営パートナーからの移動）や付与クレジットの付与があると、未払いの請求書が古いものから自動で支払われます。操作は不要で、支払い後10〜20分ほどで、その間に支払われた請求書（ご自身の手動払いを含む）をまとめて1通のメールでお知らせします。アカウントにある分だけが支払われ、足りない分は未払いのまま残ります。

### 中文 · 贷项凭证是什么？发布提前结束会退钱吗？

贷项凭证减少您应付的平台费，原来的费用和账单不变。它有两种来源：发布提前结束时退回受众规则费未用的天数（是否退回按平台的设置；被平台下架的不退）；平台的财务人员更正某笔费用（由另一位管理员批准）。凭证先抵这笔费用所在账单还欠的部分；预付的费用再把超出的部分退回原来付款的地方（现金或赠送额度，已过期的赠送额度不再退回）；余下的成为贷项，先抵您以后的账单。在"资金 → 账单"的"贷项凭证"一栏可以看到每张凭证并下载 PDF；账单上写明已用凭证抵减的金额。

### 日本語 · 返還インボイスとは？ 公開が早く終わると返金されますか？

返還インボイスは、お支払いいただくプラットフォーム利用料を減らすものです。元の料金と請求書はそのままです。発行されるのは、公開が早く終了したときの対象条件の料金のうち未使用日数分（返金するかどうかはプラットフォームの設定によります。プラットフォームによる取り下げは対象外です）と、プラットフォームの経理担当が料金を訂正するとき（別の管理者が承認します）です。まずその料金の請求書の未払い分に充当し、前払いの料金は残りを支払元（現金または付与クレジット。期限切れの付与クレジットには戻りません）に返金します。それでも残る分は次回以降の請求書に先に充当します。「資金 → 請求書」の「返還インボイス」で一覧と PDF を確認でき、請求書には充当額が記されます。

While automatic payment is on, when cash arrives (by card, by bank transfer, or moved from your Operator) or Granted credit is given, your unpaid invoices are paid by themselves, the oldest first. You do nothing, and the invoices paid within about 10 to 20 minutes (including any you pay by hand) are told to you in one mail. The account pays as far as it goes; what it cannot cover stays unpaid.

### English · What is a credit note? Do I get money back when a publication ends early?

A credit note lowers what you owe in platform fees; the fee and its invoice stay as they were. One is issued when a publication ends early, for the unused days of its audience rule fee (whether they are given back depends on the platform's setting; a takedown by the platform is not), or when the platform's finance staff correct a fee (approved by a second administrator). It first pays what the fee's invoice still owes; for a prepaid fee it then gives the rest back to where it was paid from (cash or granted credit; granted credit that has expired is not given back); anything still left pays your next invoices first. See each credit note and download its PDF under "Credit notes" in "Money → Invoices"; an invoice shows what credit notes paid of it.

### 中文 · 账单上写着"付款情况"，是现在的吗？

不是。账单的 PDF 是您**第一次下载那天**做的，写明"付款情况截至"那一天：赠送额度、预付款、现金、贷项凭证各付了多少，还有多少没付。之后再付款，PDF 不会改（它是一份固定的文件）；最新的付款情况在"资金 → 账单"里点开那一行看。金额、税与明细永远不变；税按税率分别小计。已作废的账单仍可下载原件，页面会写明作废的日期。

### 日本語 · 請求書の「支払状況」は現在のものですか？

いいえ。請求書の PDF は**初めてダウンロードした日**に作られ、「支払状況（基準日）」としてその日の付与クレジット・前払金・現金・返還インボイスでの支払額と、未払い額が記されます。その後に支払っても PDF は変わりません（固定された書類です）。最新の支払状況は「資金 → 請求書」で該当の行を開くと確認できます。金額・税・明細は変わらず、税は税率ごとに小計されます。取り消された請求書も、すでに発行された原本はダウンロードでき、取消日が表示されます。

### English · Is the "payment status" on an invoice current?

No. An invoice's PDF is made the **day you first download it**, and states the payment status as of that day: how much was paid with Granted credit, the prepayment, cash and credit notes, and what is still owed. Later payments do not change the PDF (it is a fixed document); the latest status is under "Money → Invoices", on the row you open. The amounts, tax and lines never change; tax is subtotalled by rate. A voided invoice's original can still be downloaded, and the page says the day it was voided.

### 中文 · 账户被暂停时，我的钱怎么办？

账户被暂停期间不能登录，进行中的发布也随之暂停；**钱不会被没收**。平台费账户里的现金冻结：不能充值、不能提现，解除暂停后照常使用。赠送额度不能自己使用，也不再发放新的，但仍**按原来的有效期到期**，暂停期间到期的照常失效。暂停的发布到结束时间（或由管理员结束）后照常结算，预付款里没用完的退回原处：现金回到平台费账户，赠送额度回到原来那一笔（已过期的随即失效）。未付的平台费照常计入，账单照常到期。欠学者的现金奖励仍须结清。解除暂停后，可以在发起方页面"资金 → 平台费账户"查看余额与明细。有疑问请联系客服。

### 日本語 · アカウントが停止されたとき、お金はどうなりますか？

アカウントの停止中はログインできず、実施中の公開も一時停止になります。**お金が没収されることはありません**。プラットフォーム利用料アカウントの現金は凍結され、チャージや出金はできません。停止が解除されると通常どおり使えます。付与クレジットは自分では使えず、新しい付与もありませんが、**もとの有効期限どおりに失効**し、停止中に期限が来たものは通常どおり失効します。一時停止した公開は、終了時刻（または管理者による終了）の後に通常どおり精算され、前払金の使われなかった分はもとの場所に戻ります。現金はプラットフォーム利用料アカウントへ、付与クレジットはもとの付与分へ戻ります（期限切れのものはその場で失効します）。未払いのプラットフォーム利用料は通常どおり計上され、請求書は通常どおり期限を迎えます。テイカーへの現金特典の支払いは引き続き必要です。停止が解除された後、主催者ページの「資金 → プラットフォーム利用料アカウント」で残高と明細を確認できます。ご不明な点はサポートにお問い合わせください。

### English · What happens to my money while my account is suspended?

While your account is suspended you cannot sign in, and your running publications are paused; **your money is not taken away**. The cash in your Platform fee account is frozen: you cannot top up or withdraw it, and you can use it as usual once the suspension is lifted. You cannot spend Granted credit yourself and no new credit is granted, but it still **expires on its original date**, also during the suspension. A paused publication is settled as usual when it ends (at its end time, or when an administrator ends it), and what its prepayment did not use goes back where it came from: cash to your Platform fee account, Granted credit to the grant it came from (voided at once if that grant has expired). Platform fees still count and invoices still fall due. Cash rewards owed to Takers still have to be paid. Once the suspension is lifted, see the balance and its details under "Money → Platform fee account" in the Sponsor app. If anything is unclear, please contact support.

### 中文 · 删除账户时，账户里的钱怎么办？

申请删除账户时，页面会先列出与您有关的事项，每一条都要确认后才能申请；里面的数字是申请那一刻的，账户在冷静期过后才删除，实际**按删除那一刻的账户为准**。删除时：进行中的发布随之结束；它们的预付款要等结算完（通常一两天）才释放，所以钱的收尾会晚一两天。之后把还没开账单的平台费一并开出账单，用平台费账户里的钱从最早的账单起付清，先用赠送额度、再用现金；付不完的部分仍然是欠款。付完之后，剩下的赠送额度全部作废，不能折现；剩下的现金留在已关闭的账户里，**不会退到银行**。删除后才到账的充值同样进入已关闭的账户，仍有欠款时自动用来付。删除后才开出的账单上没有公司名，账单与金额照常存档。建议删除前先付清账单，并等充值到账或取消。

### 日本語 · アカウントを削除すると、アカウントのお金はどうなりますか？

アカウントの削除を申請すると、先に関係する事項が一覧で表示され、すべて確認してから申請できます。表示される金額は申請した時点のもので、アカウントは待機期間の後に削除されるため、実際は**削除時点のアカウントの残高**によります。削除時には、実施中の公開が終了します。その前払金は精算（通常 1〜2 日）が済んでから解放されるため、お金の整理は 1〜2 日遅れて行われます。その後、まだ請求書になっていないプラットフォーム利用料もあわせて請求書にし、プラットフォーム利用料アカウントの残高から古い請求書の順に、付与クレジット、次に現金の順で支払います。支払いきれない分は未払いのまま残ります。支払いの後に残った付与クレジットはすべて失効し、現金には換えられません。残った現金は閉じたアカウントに残り、**銀行には返金されません**。削除後に入金されたチャージも閉じたアカウントに入り、未払いがあれば自動的に支払いに充てます。削除後に発行される請求書には会社名が記載されませんが、請求書と金額は通常どおり保管されます。削除の前に請求書を支払い、チャージの入金を待つか取り消すことをおすすめします。

### English · What happens to the money in my account when I delete it?

When you ask to delete your account, the page first lists what concerns you, and you confirm each item before you can ask. Its figures are those of the moment you ask; the account is deleted after a cooling-off period, so **what is in the account when it is deleted counts**. On deletion your running publications end. Their prepayments are released once they are settled (usually a day or two), so the money is wound up a day or two later. Then the platform fees not invoiced yet are invoiced, and the invoices are paid oldest first from your Platform fee account, Granted credit first, then cash; what it does not cover stays owed. After that, the Granted credit left is voided and cannot be turned into cash; the cash left stays in the closed account and **is not refunded to a bank**. A top-up that arrives after the deletion goes into the closed account too, and pays what is still owed. Invoices issued after the deletion carry no company name; they and their amounts are kept as usual. Before deleting, we suggest you pay your invoices and wait for top-ups to arrive, or cancel them.

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
