# finance — 路线图

应用 finance 的阶段计划。设计见 `00-design.md`，需求原文见 `docs/finance-requirements/`（只读），总体计划与已确认的决定见 `docs/finance-work/00-development-plan.md`。
执行方式与平台相同（根目录 `CLAUDE.md` 第 7 节）：每个阶段先出实施计划、经确认后实现；PR 逐条对照本文件的验收标准。
分支按平台版本线（平台决策 D21）：F0 在线 1.0（`1.0/finance`）。平台阶段 14a–14g 在线 1.1（`1.1/platform`）上进行，已全部合入；
`1.1/finance` 已建立（从 `1.0/finance` 拉出，再合并 `1.1/platform`，见下方"升级到线 1.1"），`1.0/finance` 冻结。F1 起的工作分支为 `1.1/finance-<N>-<名>`。

| 阶段 | 名称 | 依赖（平台） | 预估 | 状态 |
|---|---|---|---|---|
| F0 | 设计与骨架 | — | 3–4 天 | ☑ 已完成（设计待确认） |
| F1 | 总账、期间、日记账与审批 | 14a 14b 14c 14h 14i | 8–10 天 | ☑ 已完成 |
| F2 | 主数据导入、期初与迁移、工资导入 | 14e | 4–5 天 | ☑ 已完成 |
| F3 | 应收与销售税 | 14j（发票文件） | 8–10 天 | ◐ F3a 进行中 |
| F4 | 应付、付款与 1099 | — | 8–10 天 | ☐ |
| F5 | 银行与对账 | 14e | 6–8 天 | ☐ |
| F6 | 固定资产 | — | 4–5 天 | ☐ |
| F7 | 多币种 | — | 4–5 天 | ☐ |
| F8 | 结账、重开、年结 | — | 5–6 天 | ☐ |
| F9 | 财务报表与报告 | 14d | 8–10 天 | ☐ |
| F10 | 控制、审计支持、安全配置 | 14f 14g | 4–5 天 | ☐ |
| F11 | 接口、性能、运维、全场景验收 | — | 6–8 天 | ☐ |

财务阶段之间的依赖：F2、F3、F4 在 F1 之后；F5 在 F3、F4 之后；F6 在 F4 之后；F7 在 F3–F5 之后；F8 在 F1–F7 之后；F9 在 F8 之后；F11 最后。

---

## F0 设计与骨架

**要求**
1. `.jabiz-app-paths`；根目录 `CLAUDE.md` 恢复为平台版本，财务规则写在 `backend/finance/CLAUDE.md`。
2. `docs/finance/00-design.md`（含需求覆盖表）、本路线图。
3. 模块 `backend/finance`（`jabiz.boot-app`，暂用平台后台前端），`deploy/finance/`（compose、Dockerfile），`.github/workflows/finance.yml`。
4. `docs/finance-work/work-items.csv`、`defects.csv`（由需求的 `templates/` 起始）。

**验收标准**
- [x] `./gradlew :finance:check`（`ArchitectureTest`、`FinanceAppIT`、`platformCheck` 0 错误）通过。
- [x] `tools/check-app-paths.sh` 通过（线 1.0 起以 `origin/1.0/platform` 为基准）。
- [x] 打包的 jar 在空数据库上启动、迁移、健康检查 UP，`/` 提供后台页面，匿名调用 `/api` 为 401（本地冒烟；CI 作业 `Finance / package`）。
- [ ] 设计文档经确认。

说明：本地环境没有 Docker，集成测试以 `JABIZ_TEST_DB_*` 连接本地 PostgreSQL 16 运行；compose 与 Dockerfile 未在本地构建，由使用者或以后的 CI 作业验证。

## 升级到线 1.1（2026-09-30）

**要求**
1. `1.1/finance` 从 `1.0/finance` 拉出，合并 `1.1/platform`（`.jabiz-platform-line` = 1.1）。
2. 配置：`jabiz.integrity.key`（`JABIZ_INTEGRITY_KEY`）、`jabiz.security.mfa.key`（`JABIZ_MFA_KEY`）；测试配置的固定密钥；
   CI 作业 `Finance / package` 与 `deploy/finance` 的说明带上两个新密钥（容器首次本地启动时由入口脚本生成）；单点登录按平台 `docs/guide/quickstart.md` 以环境变量配置。
3. 设计文档：平台依赖改为"已有（线 1.1）"；§15 写明 14g 的具体用法（`requiresMfa`、`withinDataPeriod`、`f.masked`、访问审查）。
   这些用法在对应实体出现时实施（F1 起，F10 验收）。

**验收标准**
- [x] 合并无冲突；`tools/check-app-paths.sh` 以 `origin/1.1/platform` 为基准通过。
- [x] `./gradlew :finance:check`（`ArchitectureTest`、`FinanceAppIT`、`platformCheck`）通过。
- [x] CI（`build`、`Finance / package` 等）在 `1.1/finance` 上通过。

## F1 总账、期间、日记账与审批

需求：FIN-GL-001…006、010…018、020、022；FIN-PC-001、PC-003；FIN-CT-001（日记账部分）、CT-002、CT-003、CT-005（标记）；FIN-FX-001、FX-002（主数据）。
验收口径：FIN-SCN-02 步骤 1–5；JE-0001…0003 的总账行等于 FIN-EXP-02。计划已确认（2026-09-30，接受全部推荐）。分三个 PR：

- **F1a**（`1.1/finance-1a-accounts`）：纯计算、科目、维度、币种与汇率、财年与期间及其状态、`FIN_SETUP` 与角色、科目模板、迁移 V1。
- **F1b**（`1.1/finance-1b-journals`）：日记账全部流程与表、编号与审批规则、过账与 `FinPosting`、控制科目例外、附件、冲回、周期分录与自动冲回、试算表与账户查询模板、`FinScn02IT`。
- **F1c**（`1.1/finance-1c-grid`）：`finance-web` 的录入网格与日记账页面、Vitest 与 Playwright、性能摸底、需求追踪表。

开始时发现平台没有日期类型：先在平台补齐（14h，`f.asDate()`），再在其上实现（设计 §2、§4.2）。
应用改为只用英语、区域 en-US（FD4，`jabizApp { languages("en"); region = "en-US" }`）。需求的疑问记在 `docs/finance-work/01-requirement-questions.md`。

**F1a 要求**
1. 纯计算（`calc`）：`Money`（远离零舍入、最大余数分摊）、`FiscalCalendar`（财年与期间、第 13 期）、`BookingTime`（过账日 → 芝加哥零点）、`PeriodPolicy`（期间检查）。
2. 科目：平台 `LedgerAccount` + `FinAccount`（财务类型、正常余额、报表行、现金流分类、控制类别、清算标记、必填维度）；
   流程 `FIN_ACCOUNT_CREATE` / `_UPDATE` / `_DEACTIVATE` / `_REACTIVATE` / `_DELETE`（`fin.account.maintain`）；有发生额的科目不能删除。
3. 科目模板：`FIN_COA_TEMPLATE_PREVIEW` / `_APPLY`（GL-002）。
4. 维度：`FinDepartment`、`FinLocation` 与两个 `LedgerDimension`（GL-006）。
5. 币种与汇率：`FinCurrency`、`FinExchangeRate`（FX-001、FX-002），经数据视图维护，更正保留历史。
6. 财年与期间：`FinFiscalYear`、`FinPeriod`，`FIN_FISCAL_YEAR_CREATE`、`FIN_PERIOD_SET_STATE`、`FIN_PERIOD_SET_SUBLEDGER_STATE`（PC-001、PC-003）。
7. `FIN_SETUP`：9 个角色及其 F1 权限、本位币 USD；幂等；任何角色都没有 `ledger.post` / `ledger.reverse` / `ledger.account.write`。
8. 迁移 `V1__finance_gl.sql`（全部为只追加的时态表）；配置：账本 USD 两位、公司时区、财年末 12 月；英语消息。

**F1a 验收标准**
- [x] 样例公司 36 个科目经 API 录入后属性齐全；重复代码被拒（GL-001，`ChartOfAccountsIT`）。
- [x] 汇总科目不可过账；停用的科目拒绝过账但仍在科目表中；有发生额的科目不能删除、提示停用；改名的历史列出新旧名、操作人与时间（GL-003、GL-004，`ChartOfAccountsIT`）。
- [x] 科目模板先预览（不保存）再复制，可排除；已有科目时拒绝（GL-002，`ChartTemplateIT`）。
- [x] 日历年 12 期与第 13 期、非日历财年、财年不重叠；期间与子账状态只经流程、需要关账权限（PC-001、PC-003，`PeriodStateIT`、`FiscalCalendarTest`、`PeriodPolicyTest`）。
- [x] 维度值校验账本行；汇率按 `fx-rates.csv` 录入，更正保留新旧值与操作人；一对一日一类型一个汇率（GL-006、FX-001、FX-002，`SetupAndMasterDataIT`）。
- [x] `FIN_SETUP` 建立 9 个角色与 USD，再次运行不变；需要二次验证；没有角色能直接写总账（`SetupAndMasterDataIT`、`FinanceRolesTest`）。
- [x] 纯计算的单元测试与属性测试（`MoneyTest`、`FiscalCalendarTest`、`BookingTimeTest`、`PeriodPolicyTest`、`AccountTypesTest`）。
- [x] 所有 `fi_` 表只插入（各 IT 的 `assertOnlyInserted`）；`./gradlew :finance:check`（含 `platformCheck`）与 `tools/check-app-paths.sh` 通过。

**F1b 要求**
1. 日记账实体（迁移 `V2__finance_journals.sql`，全部只追加）：`FinJournal`、`FinJournalLine`、`FinJournalAttachment`、`FinPosting`（每笔总账交易的过账日、期间、来源、总账号、来源单据）、
   `FinRecurringTemplate` / `FinRecurringLine`；除周期模板（`fin.recurring.maintain`）外都只经流程写入（`processOnlyWrites`）。
2. 流程：`FIN_JOURNAL_SAVE`（只由准备人；替换行；任何修改退回草稿并使批准与控制科目例外失效，撤回待审请求）、`FIN_JOURNAL_DELETE`（未编号的草稿）、
   `FIN_JOURNAL_SUBMIT`（一次报告全部问题：行、借贷平衡及差额、科目可过账、控制科目、维度、期间；编号；审批规则）、内部的 `FIN_JOURNAL_POST`（内容哈希一致、期间未关）、
   `FIN_JOURNAL_APPROVAL_RESULT`（订阅批准/驳回事件）、`FIN_JOURNAL_GRANT_CONTROL_EXCEPTION`（不是准备人）、`FIN_JOURNAL_REVERSE`、`FIN_JOURNAL_ATTACH` / `_DETACH`。
3. 编号：单据号 `JE-nnnn` 按财年（`fin.journal`，唯一键（财年，单据号））在提交时取得；总账号 `GJ-MAN-<财年>-nnnnnn`（`fin.gl`）在过账时取得；被拒的提交不占号。
4. 审批（14b）：`ApprovalSubject` `fin.journal`（事实 amount、source、manual、afterPeriodEnd）；`FIN_SETUP` 提出规则 `FIN-MANUAL-10K`（手工且 > 10,000.00 → `fin.journal.approve`），另一人发布；
   不需审批时直接过账并记录已评估的规则版本。
5. 附件：两个文件策略（`fin.journal.support`：PDF 与图片；`fin.journal.sheets`：CSV 与 XLSX），记下内容哈希；过账后不能增删。
6. 周期分录与自动冲回：`FIN_RECURRING_RUN`（模板 × 期间各一次，日期为期末，照常审批）、`FIN_AUTO_REVERSE_RUN`（`<原号>-R`，期间关闭时跳过并说明），各有定时任务（公司时区）。
7. 报表模板（按过账日）：`finance.gl.trial_balance`（汇总科目合计下级、第 13 期可排除、`knownAt`）、`finance.gl.account_inquiry`（期初、逐行与滚动余额、期末）、`finance.gl.journal_register`。

**F1b 验收标准**
- [x] 草稿、提交、编号、过账；小额分录直接过账并记录规则版本（GL-010、GL-013、GL-015 第 2 条，`JournalLifecycleIT`）。
- [x] 超过 10,000.00 的手工分录等待他人批准；准备人即使有审批权限也被拒；驳回须写理由（GL-015 第 1、3 条，CT-001，`JournalLifecycleIT`、`FinScn02IT`）。
- [x] 批准后的修改使批准失效、须重新批准；待审请求随修改撤回（GL-014、CT-003，`JournalLifecycleIT`）。
- [x] 提交一次报告全部问题并显示差额；关闭与软关闭期间、第 13 期（GL-011、PC-003，`JournalLifecycleIT`）。
- [x] 控制科目只在 Controller 授权例外后接受手工行，修改使例外失效（GL-005、Q1，`JournalLifecycleIT`）。
- [x] 过账后的分录不能修改或删除，通用接口也不能；冲回金额相反并引用原分录，只能冲回一次（GL-012，`JournalLifecycleIT`）。
- [x] 20 笔并发提交的单据号与总账号连续无重复（GL-013，`JournalLifecycleIT`）。
- [x] 周期分录每期一次、再次运行不生成；自动冲回在冲回日生成 `<原号>-R`，期间关闭时等待（GL-017、GL-018，`JournalAutomationIT`）。
- [x] 附件带内容哈希，过账后不能增删、文件不能删除（GL-016，`AttachmentIT`）。
- [x] 试算表按过账日汇总、第 13 期可排除、按 `knownAt` 重现；账户查询的期初、滚动与期末余额（GL-003、GL-022，`GlReportsIT`）。
- [x] FIN-SCN-02 第 1–5 步；JE-0001…JE-0004 的总账行等于 FIN-EXP-02；单据号连续（`FinScn02IT`）。
- [x] 自查后的加固：迟到的旧审批决定不生效；批准后科目被停用则不过账、可修改重提；冲回日与第 13 期标记校验；冲回分录的行固定；
  附件只由准备人在草稿时增删且只能附自己的上传；不能过账的周期模板被跳过；自动冲回不提前、只看未冲回的分录（`JournalLifecycleIT`、`JournalAutomationIT`、`AttachmentIT`）。
- [x] 纯计算的单元与属性测试（`JournalValidatorTest`）；所有 `fi_` 表只插入；`./gradlew :finance:check` 与 `tools/check-app-paths.sh` 通过。

**F1c 要求**
1. `finance-web/`：财务的后台扩展（D22，经 `@jabiz/admin` 引用平台，菜单"General ledger"），只写元数据表达不了的页面：
   - 日记账登记簿 `/gl/journals`（模板 `finance.gl.journal_register`：日期范围、状态、排序、合计，每行打开分录）。
   - 分录页 `/gl/journals/new` 与 `/gl/journals/:id`：表头与录入网格（GL-020、UI-002/003）——从表格粘贴整块、复制回表格、向下填充、撤销与重做、
     Enter 与方向键换行、Alt+N 插行、Ctrl+Delete 删行、Ctrl+S 保存、Ctrl+Enter 提交；金额不用分隔符；科目与维度输入联想（模板 `finance.gl.account_lookup`）；
     当场检查（科目、金额、维度）与实时借贷合计和差额；服务端的拒绝显示在它指出的单元格上。审批（平台 `ApprovalPanel`）、控制科目例外、冲回、附件与哈希。
   - 试算表与账户查询用平台的报表页面运行（菜单项指向它们）。主数据仍用生成的页面。
2. 测试：Vitest（网格模型、网格组件、分录页、登记簿）；Playwright（`finance-web/e2e`，`tools/finance/e2e.sh`）对打包的应用；CI `finance.yml` 增加 `web` 与 `e2e` 作业。
3. 性能摸底：生成器 `tools/finance/perf/`（直接写库）与 `probe.sh`，结果与对设计 Q1 的结论写入 `docs/finance/perf.md`。
4. 需求追踪表 `docs/finance/traceability.md`（F1 的需求 → 实现 → 测试）。

**F1c 验收标准**
- [x] 从表格粘贴 50 行：全部出现，无效单元格标出，合计显示差额直到平衡（GL-020 验收 1，`e2e/journal.spec.ts`、`JournalGrid.test.tsx`）。
- [x] 只用键盘录入、保存并提交一笔分录（UI-002，`e2e/journal.spec.ts`）；粘贴、复制、向下填充、撤销（UI-003，`grid.test.ts`、`JournalGrid.test.tsx`）。
- [x] 提交后等待审批，会计无审批入口，Controller 在分录页批准后过账（FIN-SCN-02 第 2 步，`e2e/journal.spec.ts`）。
- [x] 服务端拒绝落在对应单元格，跳过的空行不错位（`JournalEntryPage.test.tsx`）；已过账分录只读并可冲回。
- [x] 登记簿排序、筛选、精确合计并打开分录（UI-004 的日记账部分，`JournalListPage.test.tsx`）。
- [x] 性能摸底完成并写入 `docs/finance/perf.md`；追踪表覆盖 F1 的全部需求。
- [x] `pnpm ext:check`（扩展的类型、lint、测试）、`./gradlew :finance:check`、`tools/check-app-paths.sh` 通过；端到端在 CI 中运行。

**F1c 发现的平台缺口**（需在线 1.1 的平台分支上修，财务分支不改平台）
1. 时态数据的规模（`docs/finance/perf.md` §4）：唯一性检查与模板读时态实体都先对全表 `DISTINCT ON`；大数据量下过账 > 1 s、账户查询与试算表超时。建议平台阶段 14i。
2. `frontend/tsconfig.extension.json` 的路径映射读不到只在 `exports` 中声明类型的包（`react-router`）：`finance-web/tsconfig.json` 暂时重复平台的映射并补上这一条。
3. 登录后总是进入 `/data`，不进入扩展声明的 `home`（与 12 §9 的规定不符）：端到端测试登录后经菜单进入日记账。

以上三项由平台阶段 14i（staunch0515/jabiz#44、#45，决策 D29）解决，财务在 F1d 中采用。

### F1d 采用平台 14i（时态数据的规模）

**要求**
1. 合并 `1.1/platform`（14i）。过账记录 `FinPosting` 声明为只写一次（`t.writeOnce()`）：V3 建只含主键的唯一索引；账本的交易与分录已由平台声明。
2. 平台的新启动检查（唯一约束缺少支撑索引即告警）对财务报出的 5 处，在 V3 中补齐复合索引（汇率、期间开始日、日记账号、分录行、循环分录行）。
3. 去掉 F1c 的两个变通：`finance-web/tsconfig.json` 只 `extends` 平台的基础配置；端到端测试登录后直接落在扩展的 `home`（日记账登记簿）。
4. 在 F1c 的压测数据上重新计时，写入 `docs/finance/perf.md` §6。
5. 重复运行端到端（`--repeat-each=10`）发现并修复的问题：
   - 新分录 Ctrl+Enter 提交后有时仍显示"草稿"：保存后打开该分录的读取尚在途中时提交完成，失效刷新并入了那次读取（TanStack Query 对尚无数据的查询不取消在途读取）。
     提交后先取消在途读取再刷新（`JournalEntryPage.test.tsx` 先失败后通过）。
   - 登记簿每页 50 行、无缺省顺序：分录多时新分录可能不在第一页。缺省按分录号倒序（最新在前，`JournalListPage.test.tsx`）。
   - 端到端测试在分录页出现之前填写表头：等待分录页出现。

**验收标准**
- [x] 50 行分录提交并过账 p95 ≤ 1 秒；一个科目一个月的账户查询 ≤ 2 秒（FIN-NF-002 的这两项，`perf.md` §6：p95 0.65 s、0.26 s）。
- [x] `platformCheck` 0 错误 0 警告；`./gradlew :finance:check`、`pnpm ext:check`、`tools/check-app-paths.sh` 通过；端到端在同一库上重复 10 次（20 次运行）全部通过。
- 试算表仍超时：按设计 Q1 的结论由期间余额解决（F8 之前），不是本阶段的目标。

## F2 导入、期初、迁移

需求：FIN-DI-001、DI-002、DI-003（Should）、DI-004，FIN-GL-019，FIN-PC-002。计划已确认（2026-10-01，接受全部推荐）：
范围按方案 A——F2 建财务的导入框架并完成总账侧；客户、税码、未结应收与客户合并在 F3，供应商、1099 阈值、未结应付在 F4，未达银行项目在 F5，资产在 F6，
各子账的期初项目不再过账、合计须等于期初分录中的控制科目余额（设计 §13.1），"子账 = 控制科目"随各阶段验收，FIN-SCN-01 在 F6 完整通过。分两个 PR：

### F2a 导入框架、科目表与汇率、期初、迁移决定与对账

**要求**
1. 导入（平台 14e）：文件策略 `fin.import`；`finance.chart`（样例科目表与模板的列）、`finance.fx_rates`（长表；宽表经映射）、`finance.opening_balances`。
2. 汇率只经新流程 `FIN_EXCHANGE_RATE_SET` 写入（手工与导入同一流程；同日同类型再次设置即更正，旧值留在历史）。
3. 期初期间 0（`FinPeriod.opening`，V4）与 `FIN_OPENING_POST`：一笔平衡分录（来源 `OPENING`、过账来源 `OPN`），不受审批与控制科目限制，每套账一次；
   其他分录找不到期初期间；`FIN_OPENING_CLOSE` 关闭后不再打开；期初后不能建更早的财年。
4. 迁移决定 `FinMigrationDecision` 与 `FIN_MIGRATION_DECIDE`（科目映射，决定人与时间）；迁移对账报告 `finance.migration.reconciliation`。
5. 权限：`fin.import`、`fin.migration`（Controller）；Treasurer 有 `fin.import`。
6. 测试：`FinanceImportIT`、`OpeningIT`、`FinanceImportsTest`、`OpeningLinesTest`；场景回放 `scenarios/finance/f2_setup_books.yml`（finance 的第一个场景，`ScenarioTest`）。

**验收标准**
- [x] 样例科目表与汇率文件原样导入，0 拒收；一行无效则整个文件拒收、指出行与原因、无任何变化；同一文件不能再导入（DI-001 验收 1 的总账部分、验收 2 的同类情形）。
- [x] 不平的期初文件拒收、显示差额、不过账；样例期初文件过账后 2025-12-31 试算表等于 FIN-EXP-01（PC-002 验收 2、验收 1 的总账部分）。
- [x] 迁移对账报告每个科目与合计差额 0.00，列出决定、决定人与时间（DI-002 验收 1 的总账部分、DI-003）。
- [x] 期初只能一次、期初期间不接受其他分录、关闭后不再打开；表只插入（`OpeningIT`）。
- [x] 账套已在使用（已过账或有期间关闭）时不能再开账；期初分录不能冲回；决定之后建立的同名科目读作其自身（`OpeningInUseIT`、`OpeningGuardsIT`，代码与安全审查的修正）。
- [x] `./gradlew :finance:check`（含 `platformCheck` 0 错误 0 警告）、场景回放、`tools/check-app-paths.sh` 通过。

### F2b 日记账导入、工资导入、菜单

**要求**
1. `finance.journals`：按单据号分组，每组交给新流程 `FIN_JOURNAL_IMPORT`（草稿，来源 `IMPORT`，单据号记入 `FinJournal.externalRef`，V5），
   缺省随即提交：与手工录入相同的检查、编号与审批；同一单据只导入一次（`externalRef` 唯一）。平台的外部引用是逐行的，一张分录有多行，所以由财务以字段唯一保证。
2. 工资：时态实体 `FinPayrollMapping`（提供商代码 → 科目、借贷、部门；Controller 经数据视图维护，`fin.payroll.maintain`）；`finance.payroll` 整个文件为一次发放，
   交给 `FIN_PAYROLL_IMPORT`：按映射换算并合并（`PayrollLines`），生成单据号为发放号（`PAYROLL-2601`，来源 `PAYROLL`）的分录并提交；未映射的代码拒收；
   映射到银行科目（控制科目 BANK）视为 Controller 维护映射时给出的长期例外，记在分录上（例外人"payroll mapping"）；其他控制科目拒收；同一发放只导入一次。
3. 工资提供商文件版式（需求只说"外部提供商"）：`code,department,amount`，参数为发放号、发放日、说明；示例在测试资源 `payroll/provider-2026-01.csv`。
4. 权限：Accountant 有 `fin.import`、`fin.payroll.import`；Controller 有 `fin.payroll.maintain`。
5. finance-web 菜单"Imports"：日记账、工资、期初、科目表的导入（平台导入向导）、迁移对账报告、导入历史，各按其权限显示。
6. 测试：`JournalImportIT`、`PayrollImportIT`、`PayrollLinesTest`（含 jqwik）、`index.test.tsx`；端到端 `finance-web/e2e/imports.spec.ts`。

**验收标准**
- [x] 一张不平的分录与一个无效科目：什么都不过账，两行都报告原因（GL-019 验收 1，`JournalImportIT`）。
- [x] 工资文件导入生成 PAYROLL-2601，一张平衡的四行分录，与 FIN-EXP-02 相同；超过 10,000.00 待审批，批准后过账（GL-019 验收 2、DI-004 验收 1，`PayrollImportIT`）。
- [x] 导入的分录照常审批（小额直接过账、大额待审批）、照常拒绝控制科目；同一单据、同一发放只导入一次（`JournalImportIT`、`PayrollImportIT`）。
- [x] 映射合并的借贷差额等于输入的有符号合计、每科目每部门一行（`PayrollLinesTest` 属性测试）。
- [x] 后台经菜单导入一张分录并在登记簿中看到它已过账（e2e，重复 3 次通过）；`./gradlew :finance:check`、`pnpm ext:check`、`tools/check-app-paths.sh` 通过。

## F3 应收与销售税

需求：FIN-AR-001…010、012，TX-001…006、008，GL-021（应收），UI-007（税），AR-011、013、014（Should）；DI-001、002、003 的应收部分。
TX-007（使用税）随 F4；AR-015（Could）不做。计划已确认（2026-10-01，接受全部推荐）：

1. 发票文件与发送（AR-005）先做平台阶段 14j（单据版式的确定性 PDF、经 `REPORT_ISSUE` 签发存档、以附件发给外部收件人并记录）；F3d 在 14j 之后。
2. 外币发票在 F3 记录交易币种金额、发票日汇率与美元金额；已实现、未实现损益在 F7。F3 的账龄以未重估的 INV-1005 验收（合计 158,385.00），FIN-EXP-08 原值在 F7 验收。
3. 应收设置 `FinArSettings`（Controller）：应收 1200、坏账准备 1210、退货与折让 4900、销售税应付 2200；未核销现金、销售折扣科目可选（不自动建科目）；
   没有未核销现金科目时收款必须全部核销；未核销现金科目须是清算科目（CT-005）。
4. 免税证书过期：可配置，缺省拒绝过账；"照常征税"时税码须指定改用的应税税码。
5. 销售税应付只用 2200，辖区明细在发票税行上，报表从子账出；STX-PAY-2512 在 F3 的测试中为手工分录（1010 例外），F4 改为手工付款。
6. 发票缺省不需审批；超过信用额度缺省警告，Controller 可经审批规则改为需审批；坏账核销始终需审批（准备人不能审批）。
7. 分四个 PR：F3a、F3b、F3c、F3d。

### F3a 客户、付款条件、税的主数据与计税

**要求**
1. 时态实体（V6）：`FinCustomer`（代码、法定名称、账单与发货地址（修改自今天或以后某日生效，不追溯）、联系人、币种、付款条件、信用额度、缺省税码、状态）、`FinPaymentTerms`
   （净天数、折扣百分比与天数、月末起算）、`FinTaxJurisdiction`（州、县、市、特别区）、`FinTaxRate`（辖区 × 生效起止日）、`FinTaxCode`（辖区组合、种类
   应税 / 免税 / 非应税、原因、是否需要证书、证书过期时改用的税码）、`FinExemptionCertificate`（客户 × 州：类型、号码、文件、签发与到期日）、`FinArSettings`。
2. 流程：`FIN_CUSTOMER_SAVE`（新建或修改，可指定今天以后的生效日；过去的日期拒绝 `FIN_CUSTOMER_PAST_DATE`）、`FIN_EXEMPTION_CERTIFICATE_SAVE`、`FIN_PAYMENT_TERMS_SAVE`、`FIN_TAX_JURISDICTION_SAVE`、
   `FIN_TAX_RATE_SET`（新税率自某日起，上一税率随之截止）、`FIN_TAX_CODE_SAVE`、`FIN_AR_SETTINGS_SET`；数据视图只经流程写入。
3. 纯计算：`calc.PaymentTerms`（到期日、折扣日）、`calc.SalesTax`（目的地原则、证书有效期、行的税码；每张发票 × 辖区对应税行合计后舍入，最大余数法分摊到行，
   每个税额附计算说明：税基、税率、生效日）。
4. 导入：`finance.customers`（样例 `customers.csv`；地点"TX (Austin)"读作州与城市；免税证书一栏读出州、类型、号码与到期日；`terms_days` 读作付款条件
   `NET<n>`，没有即建）、`finance.tax_codes`（样例 `tax-codes.csv`，参数为税率生效日；备注"state x% + local y%"读作州与地方两个辖区，`certificate required`
   读作需要证书的免税，零税率读作非应税；显式的列优先）。迁移决定新增 `CUSTOMER`（旧客户代码并入现有客户）：并入的行不建客户，迁移报告列出决定。
5. 报表 `finance.ar.certificates`：需要证书而在某日没有有效证书的客户、即将到期的证书。
6. 权限：`fin.ar.read`、`fin.customer.maintain`（ReceivablesClerk、Controller）、`fin.tax.maintain`、`fin.ar.settings`（Controller）、`fin.tax.data.read`。

**验收标准**
- [x] 样例客户与税码文件原样导入，0 拒收：四个客户的币种、条件、税码；C300 有证书 RC-3301（到期 2027-12-31）；一行无效则整个文件拒收；同一文件不再导入
      （AR-001 验收 1、DI-001，`ReceivablesMasterIT`）。
- [x] 预定自下月 1 日起的地址：按之前的日期读取为旧地址，按 1 日读取为新地址；过去的日期拒绝（AR-001 验收 2 的数据部分；按签发副本重印在 F3d）。
- [x] net 30、2026-01-06 → 到期 2026-02-05；2/10 net 30 → 折扣日 2026-01-16；月末变体；折扣日不晚于到期日（`PaymentTermsTest` 含属性测试，AR-002 的计算部分）。
- [x] TX-AUSTIN = 州 6.25% + 地方 2.00% = 8.25%；2026-04-01 的税率变更不影响之前的日期（TX-001，`TaxRatesTest`、`ReceivablesMasterIT`）；INV-1004 的行计税
      3,300.00，服务行 NT 不计税；俄勒冈不计税；C300 凭证书免税并引用证书，证书过期时按配置拒绝或改用税码（TX-002…004 的计算部分，`SalesTaxTest` 含属性测试：
      各行分摊之和 = 各辖区税额）。
- [x] 合并客户的决定、决定人与时间出现在迁移报告；并入的客户不另建；已存在的客户不能作为被并入的旧代码（DI-003 验收 1）。
- [x] 证书报表在某日列出缺少、即将到期与已过期的证书（TX-004 的报告部分）。
- [x] 时态表只插入；`./gradlew :finance:check`（`platformCheck` 0 错误 0 警告）、`tools/check-app-paths.sh` 通过。

### F3b 发票与贷项通知单（计划）

发票与行、按辖区的税行、贷项通知单与核销到发票、过账（借 1200 / 贷收入与 2200，来源 `AR`，行带来源单据，GL-021）、作废（冲正）、无缺号编号 `INV-<n>` / `CM-<n>`
（起始 1004 / 2001）、信用额度检查、税的计算说明（UI-007）；未结应收的期初导入（不过账，合计 = 1200 期初余额，合并决定生效）。验收：INV-1004、CM-2001 的总账行与 FIN-EXP-02 相同。

### F3c 收款、核销与报表（计划）

收款、核销与反核销（只追加，历史可重现）、折扣、坏账核销与收回（审批）、账龄、对账单、销售税报表（FIN-EXP-13）、坏账准备建议、周期发票。

### F3d 后台、发票文件与场景（计划，14j 之后）

发票页、收款与核销页、登记簿与菜单；发票文件签发与发送；FIN-SCN-03（`FinScn03IT`、场景回放）与端到端。

## F4 — F11

范围、需求编号与验收口径见 `docs/finance-work/00-development-plan.md` §5.2；每个阶段开始时把详细要求与验收标准写入本节。
