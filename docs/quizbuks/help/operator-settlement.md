# 帮助文案：运营商的月结算单（F-M12）

给运营商看的说明（三语）：结算单与运营商管理系统「结算」页上每一行的意思。帮助中心的 FAQ 有运营商这一对象（`OPERATOR`，只给登录后的运营商成员，运营商管理系统菜单"帮助"）；这里是底稿，由内容管理员在平台管理系统的"帮助中心问答"里新建问答（对象选"运营商帮助（登录后）"，英语必填，勾选发布）并粘贴下面各段，总部也可在签约与培训时交给运营商。功能改了先改这里。用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。

- 对象：运营商（负责人与看账单的成员）
- 页面：运营商管理系统「结算」（结算列表、点开一个月看明细）与「账户」；结算单 PDF（`qb.operator_settlement`）
- 规则：`docs/quizbuks/plans/impl/F-M12.md`；运维步骤：`docs/quizbuks/operations.md` §7.60
- 发起方的帮助不变：发起方看不到运营商的结算。

## 帮助页

### 中文 · 月结算单怎么读

**什么时候结算**：平台每天检查一次，为每个运营商结算它最早的、已经结束而还没有结算的月份。一个月要等它的配送费、曝光费已结算、后付费用的月账单已开出才结算，最多等到月末之后几天（平台设定，现在是 5 天）；过了期限照结，还没到的费用在下一次结算中算入（标"迟到"），不会丢。上个月没结算之前，不会先结下一个月。

**结算单上的各行**：
- **您的发起方的平台费（税前）**：这次算入的、您负责的发起方的平台费。每笔费用只算一次；期间早于本月的（如次月才记的配送费）标"迟到"，按本月的比例算。
- **其中成本部分（不分成）**：费用中平台付出去的成本（如 AI 费的成本），以及不分成的种类，不进分成的基数。
- **其中赠送额度支付（不分成）**：发起方用平台承担的赠送额度付的部分，不进分成的基数。
- **平台分成比例、您的分成**：基数 ×（1 − 平台分成比例），向下取整到日元。
- **每月固定费、获客费用、贷项凭证扣减**：同以前，从您的分成中扣除。
- **以前月份赠送的分成扣除**：费用在以前的月份已经算入，赠送额度到这个月才付（或退回）时，按那一次结算的比例在这里扣（退回时为负，还给您）。
- **作废费用的扣除**：以前已算入的费用，账单之后作废了，按当时的比例扣回您得过的分成。
- **上月结转、结转下月**：分成不够扣上面各项时，不够的部分结转到下个月。
- **赠送额度应扣回、赠送额度扣回**：发起方用由运营商承担的赠送额度（如入驻赠送）付了费用，那部分照常分成给您，再从您的分成中扣回同额（含税）。哪些赠送由平台承担、哪些由运营商承担，按平台与您的合同。
- **赠送额度扣回上月结转、结转下月**：分成不够扣回时，没扣完的结转到下个月继续扣；与上面的"结转"分开记。赠送额度退回多于当月用掉的，差额还给您，不结转。
- **应付金额**：记入您在平台上的账户的金额，从不为负；平台不会向您追收现金。

**明细**：在「结算」点开一个月，可以看到这次算入的每一笔费用、作废的费用与赠送额度的支付，按发起方、种类列出，可以导出。只看得到您自己运营商的明细。

**发起方改派之后**：费用与赠送额度跟着费用记下时的运营商走。发起方改派给别的运营商之后，以前的发布的费用仍在原运营商结算。

**合同结束**：停止服务的运营商照常结算（不收固定费）。最后仍有没扣完的赠送额度扣回时，平台不自动追缴，由财务与您商定。

### 日本語 · 月次精算書の読み方

**いつ精算するか**：プラットフォームは毎日 1 回、各運営パートナーについて、終わっていてまだ精算していない最も古い月を精算します。その月の配信料・露出料の精算と後払い料金の月次請求書がそろうのを待ちますが、待つのは月末から数日（プラットフォームの設定、現在 5 日）までです。期限を過ぎるとそのまま精算し、間に合わなかった料金は次の精算で計上します（「遅れて計上」と表示）。なくなることはありません。前の月が精算されるまで、次の月を先に精算することはありません。

**精算書の各行**：
- **貴社主催者のプラットフォーム料金（税抜）**：今回計上した、貴社が担当する主催者のプラットフォーム料金です。1 件の料金は 1 回だけ計上します。期間が今月より前のもの（翌月に記録される配信料など）は「遅れて計上」とし、今月の割合で計算します。
- **うち原価部分（取り分対象外）**：料金のうちプラットフォームが外部に支払う原価（AI 料金の原価など）と、取り分の対象でない種類です。取り分の基数に入りません。
- **うち付与クレジット支払い（取り分対象外）**：主催者がプラットフォーム負担の付与クレジットで支払った部分です。取り分の基数に入りません。
- **プラットフォームの取り分の割合、貴社の取り分**：基数 ×（1 − プラットフォームの取り分の割合）を円未満切り捨てで計算します。
- **月額固定費、獲得費用、返還インボイスの差し引き**：これまでどおり貴社の取り分から差し引きます。
- **過月分の付与クレジットの取り分差し引き**：料金は以前の月に計上済みで、付与クレジットでの支払い（または返還）が今月になったものは、その精算の割合でここで差し引きます（返還のときはマイナスで、貴社に戻ります）。
- **取消料金の差し引き**：以前に計上した料金の請求書が後から取り消されたとき、当時の割合で、お受け取りの取り分を差し引きます。
- **前月繰越、翌月繰越**：取り分で上の項目を差し引ききれないとき、残りを翌月に繰り越します。
- **付与クレジット回収の予定額、付与クレジットの回収**：主催者が運営パートナー負担の付与クレジット（入会特典など）で料金を支払った部分は、通常どおり取り分の対象となり、同額（税込）を貴社の取り分から回収します。どの付与クレジットがプラットフォーム負担か運営パートナー負担かは、プラットフォームと貴社の契約によります。
- **付与クレジット回収の前月繰越、翌月繰越**：取り分で回収しきれないとき、残りを翌月に繰り越して回収します。上の「繰越」とは別に記録します。付与クレジットの返還が当月の利用より多いときは、差額を貴社にお戻しし、繰り越しません。
- **お支払い金額**：プラットフォーム上の貴社の口座に記録する金額です。マイナスになることはなく、プラットフォームが現金を請求することもありません。

**明細**：「精算」で月を開くと、今回計上した料金、取り消した料金、付与クレジットの支払いが 1 件ずつ、主催者・種類ごとに表示され、エクスポートできます。表示されるのは貴社の明細だけです。

**主催者の担当が移ったあと**：料金と付与クレジットは、料金が記録されたときの運営パートナーに属します。主催者が別の運営パートナーに移っても、以前の公開の料金は元の運営パートナーで精算します。

**契約の終了**：サービスを終えた運営パートナーも通常どおり精算します（月額固定費はかかりません）。最後に回収しきれない付与クレジットが残っても、プラットフォームが自動で請求することはなく、経理担当と貴社で相談します。

### English · Reading your monthly settlement

**When it is settled**: once a day the platform settles, for each operator, its earliest month that has ended and has no settlement yet. A month waits until its distribution fees and exposure fees are settled and the monthly invoices of postpaid fees are issued, but only for a few days after the month's end (a platform setting, now 5 days). After that it is settled anyway, and the fees not there yet are counted in the next settlement (marked "Late"), never lost. A month is never settled before the month before it.

**The lines of the settlement**:
- **Platform fees of your Sponsors (before tax)**: the platform fees of the Sponsors you look after counted this time. Each fee is counted once; one of an earlier period (a distribution fee recorded the next month, say) is marked "Late" and shared at this month's rate.
- **Of which cost (not shared)**: what of a fee the platform pays out as cost (the cost of an AI fee, for instance), and the kinds that are not shared. Not in the base of your share.
- **Of which paid with granted credit (not shared)**: what Sponsors paid with granted credit the platform bears. Not in the base of your share.
- **Platform share, your share**: the base × (1 − the platform's share), rounded down to the yen.
- **Monthly fixed fee, prospecting costs, credit notes deducted**: deducted from your share, as before.
- **Granted credit share deducted, earlier months**: a fee counted in an earlier month and paid (or given back) with granted credit only this month is deducted here at that settlement's rate (negative when given back: it returns to you).
- **Voided fees deducted**: a fee counted before whose invoice was voided later; the share you had of it is deducted at the rate of the time.
- **Carried in, carried to next month**: when your share does not cover the lines above, the rest is carried to the next month.
- **Granted credit to take back, granted credit taken back**: what Sponsors paid with granted credit the operator bears (the onboarding credit, for instance) is shared with you as usual, and the same amount (with tax) is taken back from your share. Which granted credit the platform bears and which the operator bears is set by your contract with the platform.
- **Granted credit take-back carried in, carried to next month**: when your share does not cover the take-back, the rest is carried to the next month and taken back then, apart from the other carry. When more granted credit is given back than was used in the month, the difference is paid to you, not carried.
- **Payable**: what is recorded in your account on the platform. It is never negative; the platform never asks you for cash.

**Lines**: open a month in "Settlements" to see each fee counted this time, each voided fee and each payment with granted credit, by Sponsor and kind; you can export them. You only see your own operator's lines.

**After a Sponsor moves**: fees and granted credit stay with the operator recorded on the fee. When a Sponsor moves to another operator, the fees of its earlier publications are still settled with the former one.

**When a contract ends**: an operator that has stopped its service is settled as usual (no monthly fixed fee). If granted credit is still left to take back at the end, the platform does not claim it automatically; finance agrees it with you.
