# F-C8 邮件文字的定制：QuizBuks 一侧的接入（S4）

规格：`docs/quizbuks/first-launch-spec.md` F-C8（来源 `Q107-mail-templates.md`）。平台能力是 D72（平台 17zh-a、17zh-c：`MailText`、`MAIL_TEXT_*`、受控变更 `mail-text`、`MailTextRules`、待办通知与单据邮件经同一处取文字、「邮件模版」页 `/mail/texts`），已在 `1.3/platform` 做完并同步过来。本项只做 QuizBuks 一侧：授权、模版的显示名与示例值、安全通知的纳入、缺省文字核对、运维文档、测试。QuizBuks 的邮件文字改名随 Q91「学者」改名一并做，不在本项。

## 做什么

1. **授权**（`QbRoles`、`QbRolesTest`）：见 §问题 1。平台规定：提出与发布一份邮件文字（以及信纸，F-C9）的人，除了受控变更自己的 `control.propose` / `control.publish`，还须持有 `mail.text.write` 并有已验证的邮箱（`MailTextControlTarget.requireWriter`）。建议：
   - 内容管理员：`mail.text.read`、`mail.text.write`、`control.propose`（改普通通知即时生效；改安全类与信纸提出）；
   - 财务管理员：`mail.text.read`、`mail.text.write`（它已有 `control.publish`，是发布的另一位）；
   - 超级管理员：不授予（职责分离：规格与 Q107 §8 第 3 条）。
2. **每个模版的显示名与示例值**（`NotificationMails`、`AccountMails`、`InvitationMails`、`ProspectMails`、`SharedVideoMails`、`VerificationMails` 共 24 个模版）：
   - 显示名：消息 `mail.<名>.name`（三语）。现有 21 个有（偏好页用），补齐其余 3 个，管理员的列表用它；
   - 示例值：每个模版对每个参数声明 `t.sample(参数, 值)`（预览与测试发送用；没有则显示 `[参数名]`）。示例值是中性的虚构值，不放真实的公司名或邮箱，不写 "QuizBuks"（品牌用 `Brand`）；
   - 新单元测试 `MailTemplatesCoverageTest`：每个 QuizBuks 模版有三语的 `name`、每个参数都有 `sample`，以后新增的模版缺了即失败。
3. **安全通知纳入审批**：声明了令牌或登录链接的模版（`qb.sponsor-verification.confirm`、`qb.invitation` 等）平台自动判定为须另一位管理员发布。除此之外我建议只把 `qb.bank-account.changed`（收款账户已改：被改成误导文字会诱导转账）用 `t.protectedText()` 纳入；其余（转账、充值、发布结果）是普通通知，保存即生效。请确认清单（§问题 2）。
4. **缺省文字核对**：现有缺省文字须过平台的 `MailTextRules`（启动检查 `MAIL`：占位符与令牌位置不合格即启动失败，图片、HTML、外部主机、长度只告警）。跑 `./gradlew :quizbuks:platformCheck`，有问题的缺省文字在本项里改（不改文意）。其中商家邮件的 `{entryUrl.sponsor}` 链接、退订链接的位置要特别看。
5. **用户可见处的用词**：模版显示名与页面文案照 `glossary.md`（发起方、学者、运营商、平台管理系统、发起方页面），不出现"后台"；平台管理系统的「邮件模版」页的菜单位置沿用平台的（Q108 的八区域是发起方页面的，与此无关，不另做）。
6. **运维文档**：`operations.md` 新增一节（号取开 PR 时最大号 +1，先向总控核号），写明：谁能改（内容管理员保存、安全类由内容管理员提出、财务管理员发布）、怎么审批与「待审批」的状态、遇到 409 怎么办、「已失效，正在使用缺省」与「缺省已更新」怎么处理、**出了误导内容的邮件怎么立即恢复默认**（即时生效，两类都是）、测试发送只发给自己、参数 `jabiz.mail.texts.max-subject-length` / `max-body-length` / `allowed-link-hosts` 与告警 `jabiz.mail.text.fallback`。链接的主机限制与官网主机的关系同 F-C9。
7. `backend/quizbuks/CLAUDE.md`：在邮件一段补一句「邮件文字的在线定制只经平台的 `MailText`；QuizBuks 的模版必须有 `name` 与 `sample`（`MailTemplatesCoverageTest`）」。

## 不做

- 发起方自定义邀请邮件、A/B 测试、按地区的文字、富文本编辑器（规格 §不做）。
- 邮件文字页面本身（平台已有）；缺省文字随 Q91 的改名。
- 迁移（平台的迁移已同步；QuizBuks 预计没有）。

## 测试

- `MailTemplatesCoverageTest`（上面第 2 条）；`QbRolesTest`（授权）。
- `MailTextIT`（QuizBuks 一侧，真实 PostgreSQL）：内容管理员保存一封普通通知的中文正文 → 发信用新文字、投递记录带版本号；安全类（`qb.sponsor-verification.confirm`）`MAIL_TEXT_SAVE` 被拒 422；内容管理员提出 → 本人发布被拒 → 财务管理员发布后生效；`qb.bank-account.changed` 同；令牌放在非 `{entryUrl}` 开头的链接、标题中有令牌、正文中有图片、指向清单以外主机，都被拒；恢复默认即时生效；测试发送只到本人、不生成 `sys_mail_token`；待办通知与单据邮件可以定制；标题长度按配置；时态表上没有 UPDATE / DELETE。（平台已有同类 IT，这里验 QuizBuks 的角色、模版声明与缺省文字接得上。）
- `StatementBudgetIT` 不变。
- e2e `mail-text.spec.ts`：①内容管理员改 `qb.publication.approved`（或另一封普通通知）的中文正文，触发后 Mailpit 收到新文字，恢复默认后收到原文；②改验证邮箱（`qb.sponsor-verification.confirm`）须由另一位管理员发布（测试用的 `QB_E2E_PUBLISHER` 需加上 `mail.text.write`，仅测试），发布前发出的仍是缺省文字；③「邮件模版」页亮暗外观过 axe。
- 前端无新代码，不加 Vitest。

## 风险

- 安全类文字被改成诱导内容：四眼加平台的硬规则（令牌只在本站链接中）；合并前经 Fable 审（总控安排）。
- 缺省文字不合格会让启动失败（占位符、令牌位置）：本项先跑 `platformCheck`。
- 角色分配的变化（财务管理员得 `mail.text.write`）会扩大其可改的范围：它只能保存普通通知，安全类须别人提出；请需求方知晓。

## 要总控决定的问题

1. 授权分配（见做什么第 1 条）：内容管理员提出、财务管理员发布，超级管理员不授予。同时影响 F-C9。是否照此？
2. 须审批的安全通知清单：自动判定的（带令牌或登录链接）之外，只加 `qb.bank-account.changed`，是否够？是否还要加 `qb.payout.action-needed`（收款账户受限）、转账失败等与钱有关的通知？
3. 运维文档是新开一节还是并入已有的「邮件」一节（`operations.md` 里邮件的章节在哪一节，我定稿时对齐）：请核号后告诉我。

## 总控的决定（2026-11-11 批准）

1. 授权照建议（内容管理员 `mail.text.read|write` + `control.propose`；财务管理员 `mail.text.read|write`；超级管理员不授予）。授权改动放在先开的 F-C9 的 PR，`QbRolesTest` 写明；本项在其合入后（合并最新的 `1.3/quizbuks`）再做。`QB_E2E_PUBLISHER` 加 `mail.text.write`（只在测试里）。
2. 须审批的安全通知：自动判定的之外，用 `t.protectedText()` 纳入 `qb.bank-account.changed`、`qb.payout.action-needed`、`qb.transfer.failed`。充值类（`qb.topup.*`）和发布结果仍是普通通知。
3. 官网主机、标志、页脚见 F-C9 的决定；链接的主机限制同。
4. `operations.md` 的节号：总控后来定为谁先合并谁拿号；F-C9（#626）用 7.44，本项取开 PR 时的下一个空号（现为 7.45），一项一节并互相引用。
5. F-C8 合并前须经 Fable 审（涉及邮件令牌，总控安排）；顺序在 F-C9 之后。

## Fable 审查后的修改（2026-11-11）

- **受保护的通知**改按原则判定：凡是让收件人付款、转账，或讲到收款账户、付款方式的，一律 `protectedText()`。24 个模版逐个过后，清单为：`qb.bank-account.changed`、`qb.payout.action-needed`、`qb.transfer.sent`、`qb.transfer.failed`、`qb.fee.invoice-issued`、`qb.fee.invoice-overdue`、`qb.topup.paid-cash`、`qb.publication.funds-short`（共 8 个）；`qb.topup.paid`、`qb.topup.granted`、`qb.fee-credit.expiring` 只是不涉及付款方式的确认，仍是普通通知。上面"做什么"第 3 条与总控的决定第 2 条以此为准。
- **`mail.text.write` 由职责分离规则保护**（在 F-C9 的 PR 里：`qb.sod.mail-texts`，管理角色与角色分配的人不能持有它；先前"列为平台受保护授权"的做法已撤回，因为那样建立期之后没人能批准任命，见 F-C9.md）。
- **断言**：`sys_mail_text_version` / `sys_mail_layout_version` 只追加（`MailTextIT`、`MailLayoutIT`）；令牌在非 `{entryUrl}` 开头的链接里与在标题里都被拒；"发信用新文字且投递记录带版本号""测试发送只到本人、不生成令牌、限流"由平台的 `MailTextIT`（`anOrdinaryTemplatesTextsAreInEffectOnceSavedAndTheDeliveryRecordsTheirVersion`、`theTestMailGoesToItsOperatorOnlyWithoutATokenAndIsLimited`）覆盖；"同一人发布自己提出的"在 QuizBuks 里先被 `qb.sod.control-changes` 挡在提出之前（`MailLayoutIT`），平台的 `CONTROL_SAME_PERSON` 是第二道（平台 `MailTextIT.securityTextsChangeOnlyThroughAControlledChangeAnotherPersonPublishes`）。
- 「邮件模版」页的亮暗 axe 检查加在 `mail-text.spec.ts`；`mail-text.spec.ts` 的"等待期间发出的验证邮件仍是缺省文字"补上断言。
