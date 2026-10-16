# Q9d 科目映射、汇总凭证与 CSV 导出、充值收据（计划，2026-10-16 确认）

分支 `1.2/quizbuks-9d-journal-export`（从 `1.2/quizbuks` @ Q9c 合并后拉出）。只改 `.jabiz-app-paths` 中的路径，不改平台。

## 1. 目标与范围

| 需求 | 内容 | Q9d |
|---|---|---|
| F-01 科目映射 | 按交易类型配置借贷科目代码 | 平台账本本身就是复式总账（分析 4.3），所以映射是"平台科目 → 外部会计系统的科目代码"，不是按交易类型另记分录。每个平台科目一个受控参数（四眼，D40） |
| F-02 汇总凭证 | 按天 / 月、按科目汇总，凭证号唯一，附明细 | 流程 `QB_JOURNAL_EXPORT`：一期一张汇总凭证，凭证号经 `NumberSequence`（不缺号、不重复），明细为同一期间的分录汇总行 |
| F-03 推送 / 导出 | API 推送或 CSV，记录推送状态与财务系统返回的凭证号 | 只做通用 CSV（设计 Q13）：CSV 以 `FILE_ARCHIVE` 原样存档到 `sys_generated_file`；财务把它导入外部系统后，以 `QB_JOURNAL_EXPORT_CONFIRM` 记下外部凭证号。API 推送以后按选定的系统加 |
| F-09 充值收据 | 充值后商家可下载的收据 | 平台单据：版式 `qb.topup-receipt`（`DocumentLayout`），商家在钱包页对已到账的充值签发并下载（`DOCUMENT_ISSUE` 存档字节，重印取存档） |

不做：F-05 三方对账、F-07 期间锁定（只用"已导出的期间不再导出，迟到的分录进入下一次导出"，见 D-Q9d-3），抽成发票（抽成当前为 0，F-09 的"另开服务费发票"以后做）。

## 2. 设计

### D-Q9d-1 科目映射（F-01）

- 受控参数 `qb.journal.account.<平台科目代码>`（文本，≤ 20 字符，`[A-Za-z0-9._-]`），每个可过账的明细科目一个（1110、1120、2110、2120、2130、4110、5110）；
  缺省值为空。`QB_SETUP` 以受控变更提出（与其他资金参数相同，另一位管理员发布）。
- 导出时任何一个有余额变动的科目没有映射即整体拒绝（422 `QB_JOURNAL_ACCOUNT_UNMAPPED`，列出缺少的科目）。
- 新增科目时启动检查 `QbJournalMappingCheck`：每个可过账科目都在映射参数清单中（参数值可空，值的完整性在导出时检查）。

### D-Q9d-2 汇总凭证（F-02）

- 实体 `QbJournalExport`（只写一次 W；导出确认另记 `QbJournalExportConfirmation`，只写一次）：
  `exportId`、`periodFrom`、`periodTo`（日期，UTC 日界）、`granularity`（`DAY` / `MONTH`）、`voucherNo`（唯一）、`knownAt`（导出时刻，即本次的截止）、
  `previousExportId`、`lineCount`、`debitTotal`、`creditTotal`、`fileId`（`sys_generated_file`）、`fileSha256`、`exportedBy`。
- 凭证号：`NumberSequence` `qb.journal`（格式 `QBJ-{yyyy}-{n:5}`，按年为范围）。
- 汇总行：外部科目代码 ×（借 / 贷）× 可选的商家（D-Q9d-4），金额为本次包含的分录合计；一张凭证借贷合计相等（不等即拒绝，说明有分录未被包含或映射错误）。
- 明细附件：同一个 CSV 的第二部分（或第二个文件，见问题 D-3）列出每个汇总行包含的平台交易编号与金额，供审计追溯。

### D-Q9d-3 导出范围、重复与迟到（F-02 "重复推送不会重复入账"、需求 7.3-5）

- 一次导出包含：过账时间 < 期末、**且**记录时间在上一次导出的 `knownAt` 之后、至本次 `knownAt` 为止的全部分录（第一次导出从最早的分录开始）。
  所以每条分录恰好出现在一次导出中：过账日在已导出期间但迟到记录的分录（例如退款、冲正）进入下一次导出，不改已导出的凭证。
- 期间按顺序导出：只能导出紧接上一期的期间；同一期间不导出两次（实体唯一约束 `(periodFrom, granularity)`）。导出在命名锁 `qb.journal` 内进行。
- 冲正照常是新的分录（平台账本只冲正不修改），出现在其记录时刻所属的那次导出中。

### D-Q9d-4 商家辅助核算（需求 7.3-4）

- 2110 商家预收款按商家分行（CSV 带"商家编号"列，用于外部系统的客户辅助核算）；用户（2120、2130）只按科目汇总，不带用户。见问题 D-2。

### D-Q9d-5 余额校验未通过时停止导出（需求 7.4、Q6-12）

- 导出要求期末之后至少有一次每日核对（`QbReconRun`）且最近一次为 `OK`；否则 422 `QB_JOURNAL_RECON_NOT_OK`。差异修正后再导出。

### D-Q9d-6 CSV 格式

- UTF-8（带 BOM，便于日本的表格软件）、逗号分隔、全部文本单元格加引号、以 `= + - @` 开头的加 `'`（同平台示范，防止公式）。
- 列：凭证号、日期（期末日）、行号、借 / 贷、外部科目代码、平台科目代码、商家编号（仅 2110）、金额（JPY 整数）、摘要（期间与类型）。
- 文件名 `<凭证号>.csv`；读取权限 `qb.admin.finance.read`；下载经 `GET /api/generated-files/{id}`（平台记下载记录）。

### D-Q9d-7 充值收据（F-09）

- 版式 `qb.topup-receipt`：区块显示模板 `qb.sponsor.topup-receipt`（参数 `topUpId`；只读本人的 `PAID` 充值：金额、到账时间、Stripe 流水号末尾、商家名称与地址）
  与发出方模板 `qb.system.issuer`（平台运营方的名称、地址、登记号——来自数据：新实体 `QbIssuerProfile`（单行，只经流程改，受控），不是配置）。
- 流程 `QB_TOPUP_RECEIPT_ISSUE`（`qb.topup`，本人、已验证）：充值必须 `PAID`；以子流程 `DOCUMENT_ISSUE` 签发（单据日期 = 到账时间），同一充值再次请求即返回已有的单据（不重复签发）。
  退款不改收据；部分或全额退款在收据上不体现，商家的交易页（Q9b）显示退款。
- 商家角色加 `document.archive.read`（只能读到自己签发、与自己同范围的单据，22 §4.3）。
- 商家后台钱包页：已到账的充值行加"收据"（签发或下载存档 PDF）。标题与区块文字在消息 `document.qb.topup-receipt[.<键>]`，三语；中、日文 PDF 需要 `jabiz.reports.pdf.fonts`（同 Q9c 的已知问题，见问题 D-5）。

## 3. 页面（后台扩展 `/qb/finance/journal`，要求 `qb.admin.finance.read`）

- 导出列表（期间、凭证号、行数、借贷合计、外部凭证号、导出人、下载 CSV）；"导出下一期"（按钮要求 `qb.journal.export`，显示将导出的期间与预览：汇总行、缺少的映射、核对状态——预览是试运行 `DRY_RUN`）；
  "记录外部凭证号"（`qb.journal.confirm`）。
- 科目映射页：显示 7 个参数的当前值，修改链到平台的受控变更（提出 / 发布），不另写编辑。

## 4. 流程与权限

| 流程 | 权限 | 说明 |
|---|---|---|
| `QB_JOURNAL_EXPORT` | `qb.journal.export`（财务），要求二次验证 | 命名锁 `qb.journal` → 检查顺序、核对、映射 → 汇总 → `AssignNumber` → 生成 CSV → `FILE_ARCHIVE` → 记录 |
| `QB_JOURNAL_EXPORT_CONFIRM` | `qb.journal.confirm`（财务） | 记外部凭证号（每次导出一次） |
| `QB_TOPUP_RECEIPT_ISSUE` | `qb.topup`（本人，已验证） | 见 D-Q9d-7 |
| `QB_ISSUER_PROFILE_SET` | 受控变更（超级管理员提出，另一位管理员发布） | 见问题 D-4 |

## 5. 迁移

- `qb_journal_export`、`qb_journal_export_confirmation`（只写一次，唯一索引按规则建立，`jabiz_protect_append_only`）、`qb_issuer_profile`。

## 6. 测试

- **IT** `JournalExportIT`：映射缺少 → 422；核对未通过 → 422；汇总行与 Q9c 月报、平台 `account_balances` 一致；借贷平衡；凭证号连续；
  同一期间第二次 → 拒绝；迟到的分录（期间已导出后才记录、过账日在该期间）出现在下一次导出且只一次；CSV 原样存档、哈希一致、公式注入被中和；权限矩阵（内容管理员、商家 403；无二次验证 → 拒绝）。
- **IT** `TopUpReceiptIT`：只有本人的 `PAID` 充值能签发；重复请求返回同一单据；PDF 下载为存档字节；另一商家 404。
- **场景**：`topup.yml` 末尾导出一期并确认（快照随提交）。
- **Vitest**：扩展的导出页（预览、缺映射与核对状态的提示、按钮按权限）；商家后台钱包页的收据按钮。
- **e2e**：财务导出一期并下载 CSV；商家签发并下载收据；`a11y.spec.ts` 加导出页。

## 7. 风险

- **外部系统格式**：通用 CSV 未必能直接导入 freee / Money Forward；以后按选定的系统加格式（只改生成 CSV 的部分）。
- **期间与时区**：期间按 UTC 日界；日本会计以 JST 为准时差 9 小时。见问题 D-1。
- **二次验证**：财务角色已要求 MFA；e2e 中用不要求 MFA 的测试角色（同 Q9c）。

## 8. 已确认（2026-10-16，全部按建议）

| 编号 | 问题 | 结论 |
|---|---|---|
| D-1 | 期间的日界用 UTC 还是 JST | **JST**（会计期间按日本日期，凭证日期 = JST 期末日）；Q9c 的月报不改（仍为 UTC），在其说明中注明两者的差别 |
| D-2 | 粒度与辅助核算：按月一张凭证、2110 按商家分行 | **按月**（可选按天以后加）；**2110 按商家分行**，用户只汇总 |
| D-3 | 明细附件放在同一 CSV 还是第二个文件 | **第二个文件**（`<凭证号>-detail.csv`，同样存档），导入用的 CSV 只含汇总行 |
| D-4 | 收据的发出方信息（公司名、地址、登记号）谁来维护 | **超级管理员经受控变更**（四眼，与资金参数相同） |
| D-5 | 收据的语言 | **按商家的语言**（英 / 中 / 日）；同时为 QuizBuks 配置 `jabiz.reports.pdf.fonts`（中、日字体，顺带解决 Q9c 的已知问题） |

## 9. 预估

约 4 天：映射、导出流程与 IT 2 天，收据 1 天，页面、e2e 与文档 1 天。

## 实施记录（Q9d，2026-10-16）

分支 `1.2/quizbuks-9d-journal-export`（计划提交 2172606 之上）。只改 `.jabiz-app-paths` 中的路径，不改平台；迁移 V9。

- **后端**：
  - 包 `journal`：`QbJournal`（实体 `QbJournalExport`、`QbJournalExportConfirmation`，只写一次 W）、`JournalPeriods`（日本日期的月、截止时刻）、`JournalLines`（汇总行、映射检查、两个 CSV，纯 Java）、
    `JournalProcesses`（`QB_JOURNAL_PREVIEW`、`QB_JOURNAL_EXPORT`、`QB_JOURNAL_EXPORT_CONFIRM`）、`JournalCodes`、启动检查 `JournalMappingCheck`、序列 `qb.journal`（`QBJ-{年}-{n:5}`，按年）。
  - 模板 `qb.system.journal-first`、`qb.system.journal-lines`（取数规则见 D-Q9d-3 与 CLAUDE.md）、`qb.admin.journal-exports`、`qb.admin.journal-mapping`。
  - 收据：`wallet.TopUpReceipts`（版式 `qb.topup_receipt`、流程 `QB_TOPUP_RECEIPT_ISSUE`、实体 `QbTopUpReceipt`），模板 `qb.sponsor.topup-receipt`、`qb.sponsor.receipt-issuer`；错误码 `QB_RECEIPT_NOT_PAID`、`QB_RECEIPT_NO_ISSUER`（`WalletCodes`）。
  - 参数：科目映射 7 个 `qb.journal.account.<科目>`、开具方 `qb.issuer.name|address|registration-no`，都受控、首个值为空，由 `QB_SETUP` 提出。
  - 权限：`qb.journal.export`、`qb.journal.confirm`、`file.generated.read` 给财务（超级管理员随之）；`document.archive.read` 给商家。
  - 迁移 V9：三张只追加表（`jabiz_protect_append_only`），各自只含主键的唯一索引，另有 `(period_from, granularity)`、`voucher_no`、`previous_export_id`、确认的 `export_id`、收据的 `topup_id` 唯一索引。
  - `qb.admin.monthly` 的说明注明其月份按 UTC、分录导出按日本日期（D-1）；月报页的提示同。
  - 字体（D-5）：`application.yml` 的 `jabiz.reports.pdf.fonts: ${JABIZ_REPORTS_PDF_FONTS:}`；镜像安装 `fonts-ipafont-gothic`（日文）与 `fonts-droid-fallback`（中文）并设置该变量。平台的 `app` 不配置它；
    平台的 PDF 写入器只接受 TrueType 轮廓的单个字体文件（`.ttc` 与 CFF 的 Noto CJK 不行），所以选了这两个。
  - 每日核对的时间可配置（`quizbuks.reconcile.run-cron`，环境变量 `QB_RECONCILE_RUN_CRON`，缺省不变）：只给 e2e，正式模式或正式密钥下设成其他值即启动失败（`TransferScheduleCheck` 一并检查）。
- **前端**：
  - 后台扩展：`/qb/finance/journal`（预览下一期、导出、导出列表、下载凭证与明细 CSV、记录外部凭证号）、`/qb/finance/journal/mapping`（只读，修改链到平台的受控变更），菜单"分录导出"；
    `journal-api.ts`、文案并入 `finance-messages.ts`（三语）。
  - 商家后台钱包页：已到账的充值行加"收据"（签发或取已有的，再下载存档 PDF），失败时显示服务端的话。
- **测试**：
  - IT `JournalExportIT` 2 个：三个月按顺序导出——未结束、核对在月末之前、未映射一次报告；映射代码不合格；不是下一期；预览与导出；凭证与平台 `account_balances`（截止时刻的记录）
    及月报（同一 UTC 月）逐科目一致；2110 按商家分行、2120 汇总；借贷平衡；两个文件原样存档、哈希一致、BOM、全部单元格加引号、公式被中和（引用 `=HYPERLINK(…)`）；同一期再导出被拒；
    迟到的分录（过账于已导出的月、导出后才记录）只出现在下一次导出；导出前 5 分钟（截止边距内）记录的分录进入再下一次；核对发现差异时拒绝、差异消除后通过；第三个月没有自己的分录也照常导出；
    三次导出的明细合起来恰好是全部交易各一次；凭证号连续（同年 00001 起，跨年按年重新计数）；外部凭证号只记一次、未知导出 404；两张表没有 UPDATE / DELETE 且有触发器。
    权限矩阵：内容管理员、商家对三个流程与两个模板 403；无二次验证的财务 403 `MFA_REQUIRED`；文件对内容管理员 403、对只有 `file.generated.read` 的人 404。
  - IT `TopUpReceiptIT`：开具方未设时拒绝；未到账的拒绝；本人签发（英文 PDF 的文字含开具方、登记号与金额）；退款后再请求（另一种语言）得到同一单据、逐字节相同的 PDF；日文收据的标题与标签；
    其他商家 404（流程与 PDF）；内容管理员 403；不支持的语言 400；每笔充值一张、只插入。
  - 单元 `JournalLinesTest` 6 个（汇总行与顺序、映射与平衡、单元格、CSV、日本日期的期间与截止、映射的启动检查）；`TransferScheduleCheckTest` 加核对的时间；`QbRolesTest`、`MessagesTest`、`SetupIT` 随之更新。
  - 场景：`topup.yml` 末尾由超级管理员提出、另一位管理员发布 7 个映射，月末之后核对，导出十月（`QBJ-2026-00001`，6 行、借贷各 5,480），记录外部凭证号；快照随提交。
    `QB_SETUP` 多了 10 个提案，其余场景改为发布全部 22 个提案（快照只有生成编号的移位与由编号决定的选项顺序）。
  - Vitest：扩展 `journal.test.tsx` 7 个；商家后台 `wallet.test.tsx` 加 3 个。
  - e2e `journal.spec.ts` 2 项（会计导出下一期并下载凭证与明细、记录外部凭证号、映射页；商家下载收据、再下载逐字节相同）；`a11y.spec.ts` 的财务页加分录导出（带预览）与科目映射（亮、暗）。
- **结果**：`:quizbuks:check` 457 个测试全部通过（含 `JournalExportIT` 2 个、`TopUpReceiptIT` 1 个、`JournalLinesTest` 6 个）与 `platformCheck`（0 错误）；扩展 Vitest 57 个（新增 7 个）与 `ext:check`；商家后台 lint、typecheck 与 Vitest 108 个（新增 3 个）；两个 SPA 的 `app:check` 通过；e2e 全部 41 项对打包的 jar、Mailpit 与 Stripe 桩通过（新增 `journal.spec.ts` 2 项，`a11y.spec.ts` 财务页加两页）；`tools/check-app-paths.sh origin/1.2/platform` 通过。本机以 IPA Gothic 签发一张日文收据，`pdftotext` 读出标题与标签为日文（不是 `?`）。
- **偏离计划**：
  - **开具方是受控参数，不是实体 `QbIssuerProfile` 与流程 `QB_ISSUER_PROFILE_SET`**：平台的受控变更只认平台的 `ControlTarget`（返回 `Mono` 的运行时接口，应用不能实现，ArchUnit 也不许），
    应用实体无法"只经受控变更"修改。D-4 要的四眼由受控参数 `qb.issuer.*` 满足，模板 `qb.sponsor.receipt-issuer` 从参数读取（数据，随单据存档）。平台缺口：应用自定义受控变更的目标。
  - **预览是另一个流程 `QB_JOURNAL_PREVIEW`，不是 `DRY_RUN`**：平台的流程接口不接受试运行（`ExecutionOptions.DRY_RUN` 只供导入等平台入口）。预览与导出共用同一组读取与汇总步骤，预览不取锁、不取号、不写文件与记录，
    把问题作为数据返回（`unmapped`、`invalid`、`reconOk`、`periodEnded`、`balanced`、`ready`），页面以自己的三语文案显示；导出照常以 422 一次报告全部问题。预览不要求二次验证（不写入）。平台缺口：流程接口的试运行。
  - **导出的输入是月份**（`month`，须为下一期），防止页面预览之后别人先导出了一期而导出了另一个月。
  - **截止时刻**是导出时刻前 10 分钟（`JournalPeriods.SETTLE`），而不是导出时刻：分录的记录时刻是过账流程开始的时刻，导出时尚未提交的过账若记录时刻早于截止就会两次都被漏掉。
  - **取数条件**：计划写"记录时间在上一次导出的 `knownAt` 之后"；这会漏掉过账于本期之后、而在上一次截止之前记录的分录。实际条件是"过账于期末之前、记录于截止之前，且不在以前的导出中
    （过账于上一期末之后，或记录于上一次截止之后）"；因期末与截止都单调递增，以前的导出合起来正好是"过账于上一期末之前且记录于上一次截止之前"。
  - **文件的对象**：`FILE_ARCHIVE` 的对象（实体、主键）留空：导出记录的主键在插入时才生成，而记录要带文件编号；文件由导出记录指向。
  - **收据按签发时刻读取**，不是"单据日期 = 到账时间"：到账早于开具方设置时，按到账时刻读不到开具方；收据上另写到账时间（UTC）。收据号即充值编号（不另设序列）。
  - **收据上的商家**只有公司名与联系人：商家没有地址字段（入驻只收集公司与联系人）。
  - **收据的语言**由页面的界面语言给出（`language` 必填，en / zh / ja），只在首次签发时起作用。
  - **收据表**是只写一次的时态实体 `QbTopUpReceipt`（计划只列三张表中的 `qb_issuer_profile`，它被受控参数代替）。
  - **e2e 的会计**：首个管理员没有邮箱，过不了导出的 `requiresVerifiedEmail()`；测试另建一个已验证邮箱的用户，给它不要求二次验证的测试角色 `QB_E2E_FINANCE`（同 Q9c 的 `QB_E2E_PUBLISHER`，CI 的 `QB_ADMIN_ROLES` 加上它），
    并以每分钟核对（`QB_RECONCILE_RUN_CRON`）等到一次干净的核对。e2e 先以 `LEDGER_POST` 补记一笔上个月（日本时间）的银行与 Stripe 余额之间的分录，新库的第一期因此是已结束的上个月；
    同一数据库再次运行时已导出到本月，测试改为检查页面说明"本月未结束"并下载最近一次导出。
- **已知限制**：
  - 一次导出所取的分录（按交易、科目、借贷合并后）超过平台流程读取上限（`jabiz.process.max-read-rows`，缺省 10 万行）即 422 整体拒绝；明细 CSV 超过 `jabiz.files.generated.max-bytes`（缺省 10 MiB，约 8 万行）同样拒绝。
    规模变大时需要分次导出或提高上限（记为后续）。
  - 只有按月（`granularity` = MONTH）；按天以后加（D-2）。通用 CSV 未必能直接导入 freee / Money Forward（计划第 7 节）。
  - 收据金额写日元（JPY）：收款凭证必须写实际收的币种，这是"不写 Kudos 以外的币种名"的例外，同一张上另写存入钱包的 Kudos。
  - 字体只在镜像中配置；镜像外未设 `JABIZ_REPORTS_PDF_FONTS` 时中、日文字仍为 `?`。IPA Gothic 不含部分简体字，由 Droid Sans Fallback 补上。
  - 退款不改收据（D-Q9d-7）；收据不能作废或重开。
