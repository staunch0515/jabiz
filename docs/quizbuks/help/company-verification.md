# 帮助文案：企业实名认证（F-M3）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-M.md` §6、`operations.md` §7.42。

- 对象：发起方（FAQ 的 `audience` = `SPONSOR`）
- 分类：`ACCOUNT`
- 页面：发起方中心的标签"企业认证"（`/sponsor/profile/verification`）
- 参数（写进文案的数字是缺省值，改了参数时同步改这里）：确认链接 48 小时内有效（`qb.sponsor-verification.link-hours`）；开户赠送额度未认证 1,000、认证后 10,000（`qb.fee-credit.grant.basic` / `.certified`，补发要在赠送开关开着时；补到的是开户赠送发放时的认证金额，之后调高参数不补差）

## FAQ（三条）

- 中文 · 问：怎样进行企业实名认证？
  答：在"发起方中心 → 企业认证"填写公司的正式名称与公司的域名（如 example.co.jp）。如果您账户的邮箱就在这个域名（或其子域名）下，提交后直接进入运营商的审核；否则请填写这个域名下的一个邮箱地址（如 info@example.co.jp），我们会向它发送一次性确认链接：请在登录发起方账户的状态下打开，48 小时内有效，重新发送后旧链接作废。运营商核实域名确实属于贵公司后即认证通过，公开主页会显示"已实名认证"与域名。Gmail、Outlook、QQ 等公共邮箱的域名不能用于认证；同一个域名只能认证一个发起方。
- 日本語 · 問：企業認証はどうすれば受けられますか？
  答：「主催者センター → 企業認証」で会社の正式名称と会社のドメイン（例：example.co.jp）を入力してください。アカウントのメールアドレスがそのドメイン（またはサブドメイン）なら、申請するとすぐに運営パートナーの審査に進みます。そうでなければ、そのドメインのメールアドレス（例：info@example.co.jp）を入力してください。1 回限りの確認リンクを送りますので、主催者アカウントにログインした状態で開いてください（48 時間有効。送り直すと古いリンクは使えなくなります）。運営パートナーがドメインが御社のものであることを確認すると認証が完了し、公開ページに「企業認証済み」とドメインが表示されます。Gmail、Outlook、QQ などの公開メールサービスのドメインは使えません。1 つのドメインで認証できる主催者は 1 つだけです。
- English · Q: How do I verify my company?
  A: In "Sponsor hub → Company verification", enter your company's registered name and its domain (such as example.co.jp). If your account's address is on that domain (or a subdomain), it goes to your operator's review at once; otherwise enter an address on the domain (such as info@example.co.jp) and we send a one-time link to it: open it signed in to your Sponsor account within 48 hours (sending it again makes the earlier link stop working). Once your operator confirms the domain is your company's, you are verified and your public page shows "Verified company" with the domain. Domains of public mail services (Gmail, Outlook, QQ and the like) cannot be used, and one domain verifies one Sponsor only.

- 中文 · 问：认证后有什么不同？改了公司名称或域名会怎样？
  答：公开主页显示"已实名认证"与域名。如果您已经领过开户的赠送额度（1,000），认证通过后补发到 10,000 的差额（只补一次；赠送活动暂停时会在恢复后补发）；还没领的，运营商确认资料完整时直接按认证后的金额发放。提交不同的公司名称或域名，原来的认证会立即结束，直到新的认证通过。运营商或平台可能撤销认证（会告诉您理由），已发放的赠送额度不收回。
- 日本語 · 問：認証されると何が変わりますか？会社名やドメインを変えるとどうなりますか？
  答：公開ページに「企業認証済み」とドメインが表示されます。開設時の付与クレジット（1,000）をすでに受け取っている場合は、認証後に 10,000 との差額が追加されます（1 回だけ。付与キャンペーンが停止中のときは再開後に追加されます）。まだの場合は、運営パートナーが情報の確認を終えたときに認証後の金額で付与されます。別の会社名やドメインで申請すると、新しい認証が承認されるまで今の認証はただちに終了します。運営パートナーやプラットフォームが認証を取り消すことがあります（理由をお知らせします）が、付与済みのクレジットは回収しません。
- English · Q: What changes once I am verified? What if I change the company name or domain?
  A: Your public page shows "Verified company" with the domain. If you were already given the onboarding granted credit (1,000), it is topped up to 10,000 once you are verified (once only; while the grants are paused, it is added when they resume); if not yet, your operator's confirmation gives the verified amount directly. Submitting a different name or domain ends the current verification at once, until the new one is approved. Your operator or the platform may withdraw a verification (you are told why); credit already given is kept.

- 中文 · 问：收不到确认邮件或链接打不开怎么办？
  答：请先查看垃圾邮件文件夹，并确认公司的邮件系统没有拦截外部邮件。链接打不开时，可能是已过期、已使用过、已被后来重新发送的链接取代，或者您登录的不是申请的那个发起方账户：请在"企业认证"页重新发送链接，用最新的一封打开。
- 日本語 · 問：確認メールが届かない、リンクが開けないときは？
  答：迷惑メールフォルダを確認し、会社のメールシステムが外部からのメールを止めていないか確認してください。リンクが使えないときは、期限切れ、使用済み、後から送り直したリンクに置き換えられた、または申請した主催者アカウントでログインしていない、のいずれかです。「企業認証」のページからリンクを送り直し、最新のメールから開いてください。
- English · Q: The confirmation mail does not arrive, or the link does not work.
  A: Check your spam folder, and whether your company's mail system holds back outside mail. A link that does not work has expired, was used already, was replaced by a link sent later, or was opened while signed in to another account than the Sponsor's who applied: send it again from the "Company verification" page and use the newest mail.
