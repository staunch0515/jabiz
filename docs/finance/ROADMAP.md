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
| F3 | 应收与销售税 | 14j（发票文件） | 8–10 天 | ☑ 已完成 |
| F4 | 应付、付款与 1099 | 14k（TIN 遮蔽、生成文件存档） | 10–12 天 | ☑ 已完成 |
| F5 | 银行与对账 | 14e | 6–8 天 | ☑ 已完成（F5a–F5d） |
| F6 | 固定资产 | — | 4–5 天 | ☑ 已完成（F6a–F6c） |
| F7 | 多币种 | — | 4–5 天 | ◐ 进行中（F7a） |
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

1. 发票文件与发送（AR-005）先做平台阶段 14j（单据版式的确定性 PDF、经 `DOCUMENT_ISSUE` 签发存档、经 `DOCUMENT_SEND` 以附件发给外部收件人并记录，平台决策 D30）；F3d 在 14j 之后。
2. 外币发票在 F3 记录交易币种金额、发票日汇率与美元金额；已实现、未实现损益在 F7。F3 的账龄以未重估的 INV-1005 验收（合计 158,385.00），FIN-EXP-08 原值在 F7 验收。
3. 应收设置 `FinArSettings`（Controller）：应收 1200、坏账准备 1210、退货与折让 4900、销售税应付 2200；未核销现金、销售折扣科目可选（不自动建科目）；
   没有未核销现金科目时收款必须全部核销；未核销现金科目须是清算科目（CT-005）。
4. 免税证书过期：可配置，缺省拒绝过账；"照常征税"时税码须指定改用的应税税码。
5. 销售税应付只用 2200，辖区明细在发票税行上，报表从子账出；STX-PAY-2512 在 F3 的测试中为手工分录（1010 例外），F4 改为手工付款。
6. 发票缺省不需审批；超过信用额度缺省警告，Controller 可经审批规则改为需审批；坏账核销始终需审批（准备人不能审批）。
7. 分五个 PR：F3a、F3b、F3c、F3d-1（后端）、F3d-2（`finance-web`）。

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
6. 权限：`fin.ar.read`、`fin.customer.maintain`（ReceivablesClerk、Controller）、`fin.tax.maintain`、`fin.ar.settings`（Controller）；
   职责分离（安全审查）：不收税的税码与免税证书需要 `fin.customer.tax`，信用额度需要 `fin.customer.credit`（都只授予 Controller）；
   预定在以后生效的只能是地址与联系人，其余修改即时生效。样例客户文件含免税客户与证书，由 Controller 导入。

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
- [x] 收款员不能把客户改为免税、登记证书、设置信用额度或预定地址以外的修改（`ReceivablesMasterIT`，安全审查的修正）。

已知限制：客户同一时间只能有一个尚未生效的预定修改（平台的流程不能按将来某日读取版本；第二个预定修改得到 409，先取消或等前一个生效）；
共用的辖区（如 `TX`）由税码保存流程只新建、不改名，改名经 `FIN_TAX_JURISDICTION_SAVE`；税率对共用它的所有税码生效。
- [x] 时态表只插入；`./gradlew :finance:check`（`platformCheck` 0 错误 0 警告）、`tools/check-app-paths.sh` 通过。

### F3b 发票与贷项通知单

**要求**
1. 子账过账（平台式的通用部分，财务内）：内部流程 `FIN_SUBLEDGER_POST` / `FIN_SUBLEDGER_REVERSE`（权限 `fin.subledger.post` 不授予任何角色）：
   与单据同一事务，检查借贷平衡、科目可过账、维度、期间对总账与该子账开放；总账号按子账序列（`GJ-AR-<年>-<6 位>`）；账本交易引用单据
   （`sourceEntity` / `sourceId`）并记 `FinPosting`（钻取，GL-021）；作废用账本的冲回。AP、银行、资产以后共用。
2. 发票与贷项通知单是一个实体 `FinInvoice`（`kind`：`INVOICE` / `CREDIT_MEMO`；V7），行 `FinInvoiceLine`；`FinInvoiceTax`（按辖区的税额与每行的应税性、
   证书，只写一次，UI-007）；`FinApplication`（贷项核销到发票，只写一次；F3c 的收款同用）。
3. 流程：`FIN_INVOICE_SAVE` / `DELETE`（草稿，不用号）、`FIN_INVOICE_POST`（计税：目的地、证书、贷项按原发票日期的税率；到期日；外币按发票日即期汇率换算；
   无缺号编号 `INV-<n>` / `CM-<n>`，起始可配置 `finance.ar.invoice-numbers-start` 等，样例 1004 / 2001；过账；超过信用额度警告）、`FIN_INVOICE_VOID`
   （没有核销的已过账单据，在开放期间冲回）、`FIN_CREDIT_APPLY`（同客户同币种，可部分）、`FIN_AR_OPENING`（未结应收的期初项目，不过账，合计须等于期初分录中的
   应收科目，客户经合并决定映射）。
4. 职责分离（安全审查的修正）：
   - 行只能过账到收入类科目（非控制科目）；子账过账只接受调用方声明的控制科目（发票为 `AR`）。
   - 过账贷项通知单与作废需要 `fin.invoice.credit`（Controller），且准备人不能过账或作废自己的单据（`preparedBy`）。
   - 有原发票的贷项通知单：缺省用原发票的税码，只能用原发票上出现过的税码，金额与各辖区税额不超过原发票减去已有贷项；没有原发票的贷项只能用客户的税码。
   - 发票的发货地税码只能是客户的（不收税的税码需要 `fin.customer.tax`，过账时再查一次）；行的税码只能是应税或"非应税服务"。
   - 期初项目只能导入一次；与新单据编号冲突的旧号码、到期早于开票日拒收。
   - 核销的日期须在应收开放的期间；外币贷项与发票汇率不同时拒绝核销（损益在 F7）。
   - 子账冲回只冲该单据自己的交易。
5. 导入 `finance.open_receivables`（样例 `open-receivables.csv`）；迁移对账报告增加 `OPEN_ITEMS` 一节（期初项目对应收科目）。
6. 权限：`fin.invoice.prepare`（ReceivablesClerk）。信用额度"需审批"随 F3c 的坏账核销审批一起做（同一审批对象机制）。

**验收标准**
- [x] INV-1004：税 3,300.00、合计 53,300.00、到期 2026-02-05，四行总账等于 FIN-EXP-02，且每行打开 INV-1004（AR-003、TX-002、TX-003、GL-021，`InvoiceIT`）。
- [x] 税的计算说明：州 6.25% 与地方 2.00% 的税基、税率与生效日，服务行非应税（UI-007、TX-001 验收 1 的报告部分）。
- [x] 已过账的发票不能修改，提示用贷项通知单（AR-004）；编号无缺号（INV-1004…1007、CM-2001 按序）。
- [x] CM-2001：冲回税 165.00，总账行等于 FIN-EXP-02；核销后 INV-1004 未结 51,135.00；超额核销拒绝（AR-006、TX-005）。
- [x] INV-1005（EUR 50,000.00 × 1.0850 = 54,250.00）、INV-1006（俄勒冈无税）、INV-1007（凭 RC-3301 免税，税行引用证书）的总账行等于 FIN-EXP-02（TX-002、TX-004）。
- [x] 作废：冲回后单据净额为零、留两条过账记录；早于单据日期、已核销、已作废的拒绝。
- [x] 信用额度超出时警告；缺少有效证书时拒绝过账；收款员不能给发票或行选不收税的税码；应收子账关闭的期间拒绝（AR-013、TX-004 验收 2、PC-003）。
- [x] 期初未结应收：合计不等于 1200 期初余额则整体拒收；样例 3 张（86,500.00）导入后迁移对账 `OPEN_ITEMS` 差额 0.00；合并决定把旧客户代码的项目归到新客户（DI-002、DI-003）。
- [x] 收款员不能把行记到银行、应付、费用科目，不能过账贷项或作废；Controller 不能过账自己准备的贷项；超过原发票剩余额的贷项拒绝；期初项目不能再导入、旧号码冲突拒收
      （安全与代码审查的修正，`InvoiceIT`）。
- [x] 草稿重存保留行号；编号连续（拒绝与作废之后的下一张连续）；跨客户或关闭期间的核销拒绝；已核销的贷项与期初项目不能作废。
- [x] 过账分录总是平衡（`InvoicePostingTest` 属性测试）；表只插入；`./gradlew :finance:check`、`tools/check-app-paths.sh` 通过。

已知限制：税额按美分舍入（零小数位币种在 F7 处理）；外币核销仅限同汇率（F7）；信用额度"需审批"随 F3c。

### F3c 收款、核销与报表

**要求**
1. 收款 `FinReceipt`（V8）：`FIN_RECEIPT_RECORD`（客户、日期、金额、方式 CHECK/ACH/WIRE/CARD、参考号、存入的银行科目（控制科目 BANK），可同时核销到多张发票），
   编号 `RCPT-<4 位>`（无缺号），即时过账：借银行，贷应收（已核销 + 折扣），折扣借销售折扣科目，其余贷未核销现金；没有未核销现金科目时必须全部核销。
   `FIN_RECEIPT_APPLY`（未核销现金后来核销：借未核销现金 / 贷应收；可付其后开出的发票，即预收）、`FIN_APPLICATION_REVERSE`（反核销收款或贷项：新的一条相反金额的核销记录引用原记录，
   收款的钱回到未核销现金）、`FIN_RECEIPT_REASSIGN`（没有核销的收款改到正确的客户）、`FIN_RECEIPT_VOID`（没有核销的收款作废，钱从银行退出；不是记录人，`fin.receipt.void`）。
   一张收款只核销同一客户、美元、日期不晚于核销日的已过账发票，每张不超过其未结金额。外币收款与核销在 F7。
   反核销与改客户需要 `fin.receipt.adjust`（Controller），且不是收款记录人或贷项准备人（防止一人把一个客户的付款挪给另一个客户）。
2. 折扣（AR-002 验收 2）：钱在条件的折扣日之前收到（收款日，不是后来核销的日子）时可取折扣，不超过条件折扣（发票合计 × 折扣率）减去已取的折扣；借销售折扣科目，与现金一样清偿发票。
   核销建议模板 `finance.ar.receipt_suggestions`：按参考号中的发票号、等于未结金额、等于未结减可取折扣、最早的顺序列出，附当日可取的折扣与折扣截止日（AR-008）。
3. 贷项退款 `FIN_CREDIT_REFUND`（AR-006）：已过账贷项的未结额从银行付还（`fin.invoice.credit`，不是贷项准备人）：借应收 / 贷银行，记为对贷项本身的核销。
4. 坏账核销 `FinWriteOff`（AR-012）：`FIN_WRITE_OFF_REQUEST`（发票未结额的全部或部分、日期、原因）**总是需要审批**：审批对象 `fin.ar.write-off`
   （事实：金额、客户；审批案件即发票），没有规则拦下时拒绝（`FIN_WRITE_OFF_NO_RULE`），`FIN_SETUP` 提出规则 `FIN-WRITE-OFF`（由另一人发布，层级 `fin.writeoff.approve`）；
   批准事件经 `FIN_WRITE_OFF_APPROVAL_RESULT` 过账：借坏账准备 / 贷应收，发票未结为零时状态"已核销"；批准时已不可能（发票已付、期间关闭）则记为 `REFUSED` 并写明原因；驳回 `REJECTED`。
   `FIN_WRITE_OFF_RECOVER`：收回额放回发票（借应收 / 贷坏账准备，不是申请核销的人），再照常收款核销。
   发票记下它等待的核销请求（同时的两个请求在发票版本上冲突），审批结果按请求找到核销；每次请求的内容含请求时刻，旧的批准不会用于新的请求。
5. 信用额度审批（AR-013）：发票过账时经审批对象 `fin.ar.invoice`（事实：美元金额、客户、`overCreditLimit`）；缺省没有规则，只有警告；Controller 经受控变更加规则后，
   超额的发票保持草稿、不用号，批准后（事件标记 `approval`）再过账时找到同一内容的批准并编号；审批的准备人是草稿的准备人（不是过账人）。草稿修改或删除时撤回待审批请求。
6. 报表（SQL 模板，按请求的 `knownAt` 重现当时所记）：
   - `finance.ar.aging`（AR-010）：某日（`agingDate`）各单据的未结额 = 合计 − 当日及以前的核销、折扣、贷项、核销坏账（收回为负），按到期日或发票日分桶（界限可配，缺省 30/60/90），
     美元合计 = 应收科目当日余额；之后日期的核销不影响；单个客户即未结项目对账单（AR-009）。
   - `finance.ar.statement`（AR-009）：期间对账单：期初余额、发票、贷项、作废、收款、折扣、核销坏账、收回、退款与逐行余额、期末余额。
   - `finance.tax.sales_tax`（TX-008、FIN-EXP-13）：按税码的应税销售额、免税或非应税销售额、已收税（贷项在其期间冲减）、单据与证书；
     `finance.tax.sales_tax_return`：按州与辖区的销售额、贷项、已收税、冲回税、应纳税，按原因的免税销售额（无州的非应税服务归入发货税码的州）。
   - `finance.ar.allowance_suggestion`（AR-011）：各桶未结额 × 应收设置中的损失率（`lossRateCurrent`…`lossRateOver`），建议额、坏账准备科目当日余额与调整额。
7. 周期发票（AR-014）：模板 `FinRecurringInvoice` 与行（经数据视图维护，`fin.invoice.recurring`），`FIN_RECURRING_INVOICE_RUN` 按期间生成草稿（模板的日，
   短月取月末），每个模板每期一张（`recurringKey` 唯一），定时任务 `fin.recurring-invoices` 每月 1 日 06:00；草稿由收款员核对后照常过账（计税、编号、信用额度；
   过账时再查行的税码，模板不能绕过不收税税码的限制）；模板超过 200 个时整次拒绝而不截断。
8. 权限：`fin.receipt.record`、`fin.writeoff.request`、`fin.invoice.recurring`（ReceivablesClerk、Controller）；`fin.receipt.void`、`fin.receipt.adjust`（Controller）；
   `fin.writeoff.approve`、`fin.invoice.approve`（Controller、Approver）；Approver 另有 `fin.ar.read`。
9. STX-PAY-2512（12 月销售税申报的缴款）在 F3 的测试中为手工分录（1010 由 Controller 例外），F4 的手工付款上线后改用付款。

**验收标准**
- [x] RCPT-0001…0003 的总账行等于 FIN-EXP-02；RCPT-0003 后 INV-1003 未结 10,000.00（AR-007，`ReceivablesIT`）。
- [x] 1 月 31 日按到期日的账龄等于 FIN-EXP-08（INV-1005 按未重估的 54,250.00，合计 158,385.00），且等于 1200 当日余额；按发票日分桶；2 月 5 日的收款之后
      重跑 1 月 31 日的账龄不变（AR-010；FIN-EXP-08 原值在 F7 验收）。
- [x] C300 在 1 月 31 日的未结项目对账单列出 INV-1003（10,000.00，逾期 1–30 天）与 INV-1007（25,000.00，当期）；C100 的 1 月对账单期初 32,475.00、期末 51,135.00（AR-009）。
- [x] 1 月销售税报表等于 FIN-EXP-13；申报数据中州与地方的应纳税合计 3,135.00；2200 在 1 月 31 日为 3,135.00（TX-006、TX-008）。
- [x] 坏账准备建议：1% × 148,385.00 + 5% × 10,000.00 = 1,983.85，相对现有 4,000.00 的调整 −2,016.15（AR-011；FIN-EXP 原值 1,987.35 随 F7 的重估）。
- [x] 收款核销到错误的客户：反核销、改客户、再核销，三条核销记录都在；改正前某日的账龄（按 `knownAt`）可重现（AR-008）。
- [x] 2/10 net 30 的发票在折扣期内收款 980.00 + 折扣 20.00：借 1010、4950，贷 1200 1,000.00；折扣期后或超过条件的折扣拒绝；建议模板给出折扣（AR-002 验收 2）。
- [x] 部分核销的余额进入未核销现金并可稍后核销；没有未核销现金科目时必须全部核销；收款作废由他人在无核销时进行；贷项退款不超过其未结额（AR-006、AR-007）。
- [x] 500.00 的坏账核销需审批，申请人不能审批；批准后借 1210、贷 1200，发票状态"已核销"；收回 200.00 后发票重开并收款；驳回的申请不改变发票（AR-012）。
- [x] Controller 加信用额度审批规则后，100,000.00 额度、95,000.00 未结时 10,000.00 的发票待审批、不用号；批准后过账，编号连续（AR-013 验收 1）。
- [x] 2 月的周期发票运行两次只生成一张（AR-014 验收 1）；任意日期的账龄合计等于 1200（`ReceivablesIT`）。
- [x] 收款员不能反核销或改客户，记录人自己也不能；贷项准备人不能退款；申请人不能记录收回；周期模板的不收税行税码在过账时拒绝；预收的钱可付其后的发票，
      折扣按收款日（安全与代码审查的修正，`ReceivablesIT`）。
- [x] 表只插入；`./gradlew :finance:check`（`platformCheck` 0 错误 0 警告）、`tools/check-app-paths.sh` 通过。

已知限制：收款、核销、核销坏账与退款只用美元（外币结算与损益在 F7）；周期发票生成草稿，不自动过账，删除的草稿再次运行时会重新生成；收款作废要求先反核销全部核销；
坏账核销的审批案件是发票，同一发票一次只有一个待审批的核销；收款日由收款员填写（只要期间开放），倒签收款日可取折扣，复核靠银行对账（F5）；
作废、核销等用当时的应收设置中的科目，设置中途改科目时由 Controller 先清空未核销现金；改客户的理由记在操作记录中。

### F3d 后台、发票文件与场景

分两个 PR：F3d-1 后端（公司资料、发票文件、登记簿、FIN-SCN-03），F3d-2 `finance-web`（应收菜单与页面、端到端）。

#### F3d-1 发票文件、登记簿与 FIN-SCN-03

**要求**
1. 公司资料 `FinCompanyProfile`（V9，时态，只一行，键 `COMPANY`）：法定名称、地址、电话、邮箱、汇款说明；只经 `FIN_COMPANY_PROFILE_SET`（`fin.company.maintain`，Controller）写入，
   修改即时生效（已签发的单据保留签发时的副本）。单据上的发出方来自数据而不是配置（D30）；样例公司只给名称与银行，地址与汇款说明由 Controller 填写，`FIN_SETUP` 不代填。
2. 单据版式（`ar.InvoiceDocuments`，`DocumentLayout` Bean）：`finance.ar.invoice`（发出方、账单与发货地址、单号、日期、条件、到期日、客户号、参考号、币种；行；按辖区的税
   与不征税的原因和证书；小计、税、应付合计；汇款说明）与 `finance.ar.credit_memo`（同上，列出所冲发票，不列汇款说明与到期日）。模板：`finance.company.profile`、
   `finance.ar.invoice_document_header`、`…_lines`、`…_taxes`。缺省收件人为客户的 `contactEmail`。金额为单据币种。
3. 流程（`fin.invoice.issue`，`actsOn` 已过账的单据）：`FIN_INVOICE_ISSUE` 以子流程 `DOCUMENT_ISSUE` 签发；`FIN_INVOICE_SEND` 签发并以 `DOCUMENT_SEND` 发往缺省收件人。
   只签发已过账的单据（422 `FIN_INVOICE_NOT_POSTED`）；单据读取时点上还没有公司资料时 422 `FIN_COMPANY_PROFILE_MISSING`（公司资料应在开票前设好）；
   迁移的未结项目不签发（422 `FIN_INVOICE_NOT_ISSUABLE`，其单据由旧系统开出）。
4. 读取时点：发票日当天结束（公司时区）；单据在发票日之后才过账时取过账时刻（新字段 `FinInvoice.postedTime`，过账时写入）——平台按生效时间读取时态数据，
   补录的发票在发票日还不存在。客户地址的修改只能自今天或以后生效（F3a），所以过账后预定的新地址不会出现在旧发票上（AR-001 验收 2）；
   公司资料同样按这一时点读取，之后的修改不影响旧发票。V9 之前过账、没有过账时刻的发票按签发时刻读取。
5. 不开放平台的 `DOCUMENT_ISSUE` / 预览（`document.issue`）给财务角色：那会绕过"只签发已过账单据"的检查。重印、核对用平台的 `/api/documents/runs/{id}/pdf|verify`。
6. 登记簿模板（报表，可 `knownAt` 运行与导出）：`finance.ar.invoice_register`（发票日区间，客户、状态、种类可选；草稿只在按状态筛选时出现；美元列中贷项为负，可直接相加）、
   `finance.ar.receipt_register`（收款日区间，客户、状态可选）。
7. 权限：`fin.invoice.issue`、`document.send`（ReceivablesClerk、Controller）；`document.send.any`、`fin.company.maintain`（Controller）；
   `document.archive.read`（ReceivablesClerk、Controller、Accountant、ExternalAuditor）。
8. 测试：`InvoiceDocumentIT`（内容、重印逐字节相同、核对、地址按发票日、拒绝的情形、只插入）；`FinScn03IT`（FIN-SCN-03 步骤 1–6，邮件由测试的发送器收下）；
   场景回放 `f3_invoice_to_cash.yml`（本场景新开的单据：导入的未结项目在回放中无法按 id 引用，由 `FinScn03IT` 覆盖）。

**验收标准**
- [x] INV-1004 的 PDF 显示公司、客户与两个地址、行、按辖区的税（州 6.25% 2,500.00、地方 2% 800.00）与不征税的服务行、合计 53,300.00、条件、到期日与汇款说明；
      邮件发出的附件与之后会计重印的副本逐字节相同（AR-005 验收 1，`FinScn03IT`、`InvoiceDocumentIT`）。
- [x] 1 月 15 日的发票在客户 2 月 1 日起的新地址之后重新签发与重印，都显示旧地址；2 月的新发票显示新地址（AR-001 验收 2，`InvoiceDocumentIT`）。
- [x] FIN-SCN-03 步骤 1–6 经 API 通过：总账行等于 FIN-EXP-02，账龄等于 FIN-EXP-08（INV-1005 未重估，同 F3c），销售税报表等于 FIN-EXP-13；错核销的收款由 Controller 反核销后再核销，
      三条核销记录都在（`FinScn03IT`、场景回放）。
- [x] 1 月的发票登记簿美元合计 148,385.00 = 1 月过账的发票减贷项；收款登记簿列出 RCPT-0001…0003（UI-004 的数据部分）。
- [x] 草稿、迁移的未结项目、读取时点上没有公司资料时不签发；公司资料之后的修改不改变旧发票的发出方；会计能重印不能签发；没有邮件时不发送；
      公司资料只经其流程写入、只插入（`InvoiceDocumentIT`）。
- [x] `./gradlew :finance:check`（`platformCheck` 0 错误 0 警告）、`tools/check-app-paths.sh` 通过。

#### F3d-2 后台页面与端到端

**要求**
1. 菜单 "Receivables"（`finance-web/src/index.tsx`）：发票、新发票、收款、新收款（各自的权限）；客户、公司资料（平台通用页面）；账龄、对账单、销售税、证书（平台报表页）。
   财务的导入组改名为 "Finance imports"，与平台自己的 "Imports" 页面区分。
2. 发票登记簿 `/receivables/invoices`（`finance.ar.invoice_register`）：日期区间、客户、状态（缺省为已过账、作废、核销，草稿只在按状态筛选时出现）、种类；可排序；
   美元合计与未结合计（贷项为负，精确十进制相加）；超过 500 行时提示；每行打开单据（UI-004）。
3. 发票页 `/receivables/invoices/new|:id`：
   - 草稿：客户（输入联想，带出名称；条件、税码、币种缺省取客户的，可改）、发票日、参考号、说明；行表格（描述、数量、单价、收入科目与税码联想、行金额与小计即时显示，
     服务端重算）；Tab 顺序固定，最后一格 Enter 到下一行（末行时新增），Alt+N / Insert 插行，Ctrl+Delete 删行，Ctrl+S 存草稿，Ctrl+Enter 过账（有改动先保存）（UI-002）。
     服务端的拒绝落到对应单元格（`lines[i].字段`），其余列在上方；过账的警告（信用额度）照常显示；待审批时审批人可在页面上决定。
   - 已过账、作废、核销：只读；事实与合计（外币显示美元与汇率）、行、税的计算说明（按辖区的税基、税率、税率生效日、税额；按行的税码、处理、原因、证书，UI-007）、
     已核销的记录（收款、贷项可打开）、历史；签发人（`fin.invoice.issue`）有平台 `DocumentPanel`（签发经 `FIN_INVOICE_ISSUE`、下载存档、发送），只读者链接到平台的单据页；
     更正：作废（`fin.invoice.credit`，日期与原因）、为该发票开贷项（`?credits=`，带出客户与行，客户不可改）。
4. 收款登记簿 `/receivables/receipts`（`finance.ar.receipt_register`）与收款页 `/receivables/receipts/new|:id`：
   - 新收款：客户、日期、金额（可带分隔符）、方式、参考号、存入的银行科目（控制类 BANK）、说明；客户当日的未结发票按建议排序（`finance.ar.receipt_suggestions`），
     每张可填核销额与折扣，或"按建议核销"（按顺序分配，金额等于未结减折扣时带折扣）；显示已核销与余下的未核销额，超额时不能记录；Ctrl+Enter 记录（`FIN_RECEIPT_RECORD`）。
   - 已有收款：事实、核销记录（发票号可打开），`fin.receipt.adjust` 可反核销（日期与原因），未核销余额可再核销（`FIN_RECEIPT_APPLY`）。
5. 纯函数（`receivables/money.ts`、`invoice.ts`、`receipt.ts`）：十进制运算（不经浮点，舍入同服务端）、行金额与小计、当场检查、拒绝的定位、核销建议与余额；均有单元测试。
6. 测试：Vitest（纯函数、发票页的键盘录入与拒绝定位、过账、说明与单据面板、开贷项、收款页的建议与记录与反核销、两个登记簿、菜单）；
   端到端 `e2e/receivables.spec.ts`（收款员只用键盘录入两行发票并过账、税的说明、签发并下载 PDF、登记簿按客户筛选合计并打开、收款按建议核销后发票未结减少）。

**验收标准**
- [x] 收款员只用键盘录入两行发票（应税货物与 NT 服务）并过账：税 247.50（8.25% × 3,000.00）、合计 3,747.50（UI-002 的发票部分，`receivables.spec.ts`、`InvoicePage.test.tsx`）。
- [x] 已过账的发票显示州 6.25%、地方 2% 的税基、税率与生效日及按行的处理（UI-007，`InvoicePage.test.tsx`、`receivables.spec.ts`）。
- [x] 在发票页签发并下载 PDF，文件名为单号；没有签发权限的只读者看到单据页链接（AR-005 的页面部分）。
- [x] 发票登记簿按客户筛选后合计等于所列单据之和（贷项为负），行打开发票；收款登记簿合计收款与未核销额（UI-004）。
- [x] 收款按建议核销，部分核销后发票未结减少；超额核销不能记录；反核销只对有权限者出现（AR-007、AR-008 的页面部分）。
- [x] `pnpm ext:check`、`tools/finance/e2e.sh`（4 个用例）通过。

已知限制：无障碍检查（UI-009，axe）需要平台前端提供 `@axe-core/playwright`，扩展不能自带依赖（D22），留给平台；贷项核销到发票、退款、坏账核销与周期发票仍用平台的流程页；
页面不提供草稿预览（财务角色不持有 `document.issue`，F3d-1 第 5 条）。

## F4 应付、付款与 1099

需求：FIN-AP-001…015、020…022，AP-023 与 TX-007（Should），以及 CT-001、CT-010、SC-001、SC-004、BK-011 的应付部分；主验收 FIN-SCN-04
（FIN-EXP-09 应付账龄、FIN-EXP-14 1099、FIN-EXP-02 的账单与付款行）。计划已确认（2026-10-01，接受全部推荐）：

1. TIN 显示为 `***-**-1234` / `**-***1234`：平台阶段 14k 新增 `MaskStyle.TAX_ID`（D1）。
2. 付款文件、1099 申报文件原样保存并带哈希：平台阶段 14k 的生成文件存档（`FILE_ARCHIVE`、`sys_generated_file`、`GET /api/generated-files/{id}`，平台决策 D31）（D2）。
3. 最小的公司银行账户 `FinBankAccount` 提前到 F4a（账号按 `fin.bank.read` 显示明文；对账单与对账的字段在 F5 扩展）（D3）。
4. AP-007 在 F4b 建最小的资产登记 `FinAsset`；F6 的资产导入按编号匹配已有资产，不重复创建（D4）。
5. 样例科目表没有的现金折扣、使用税应付、供应商预付科目由 Controller 新增（测试中 5900、2210、1310），在应付设置中指定（D5）。
6. 格式：ACH 只做 NACHA CCD/PPD；支票文件与正向支付文件为 CSV；电汇指示为 CSV 生成文件（原为 PDF 单据，F4c 中变更，2026-10-02 确认）；1099 电子申报为一种有公开说明的申报服务 CSV（D6）。
7. 释放付款与银行信息变更声明 `requiresMfa(ALWAYS)`（D7）。
8. 分 PR：平台 14k，F4a 供应商与主数据，F4b 账单，F4c 付款，F4d 1099 与 FIN-SCN-04，F4e `finance-web`。

### F4a 供应商、税务信息、银行信息变更与应付主数据

**要求**
1. 时态实体（V10）：`FinVendor`（代码、法定名称、DBA、汇款地址（可预定自以后某日生效）、联系人、币种、付款条件、缺省费用科目、缺省付款方式、实体类型、
   1099 表与栏、W-9 已收、状态）、`FinVendorTaxInfo`（TIN 类型与号码 `masked(fin.tax.data.read, TAX_ID)`、W-9 文件（策略 `fin.w9`，读取需要 `fin.tax.data.read`）与日期、
   TIN 验证结果、备用预扣）、`FinVendorBankAccount`（路由号、账号 `masked(fin.vendor.bank.read, LAST4)`、账户类型、状态 PENDING / ACTIVE / REJECTED / REPLACED、
   提出人与时间、决定人、审批请求与内容哈希）、`Fin1099Threshold`（纳税年度 × 表 → 阈值）、`FinApSettings`、`FinBankAccount`。数据视图只经流程写入。
2. 流程：`FIN_VENDOR_SAVE`（新建或修改；条件可写天数，`NET<n>` 缺少即建；1099 表与栏一起检查：NEC 栏 1，MISC 栏 1、2、3、6、10；空表清除）、
   `FIN_VENDOR_TAX_SAVE`（TIN 按类型校验并保存为书写形式；遮蔽形式不能写入；应付员可以登记，之后同样只看到遮蔽形式）、
   `FIN_VENDOR_BANK_CHANGE`（二次验证；路由号校验位、账号 4–17 位；同一供应商同时只能有一个待批准的变更；经审批对象 `fin.ap.vendor-bank` 由他人批准，
   没有适用的规则即拒绝；账号不进审批的事实与内容）、`FIN_VENDOR_BANK_APPROVAL_RESULT`（内部，只由平台的审批事件运行：批准后新账户启用、旧账户 REPLACED；驳回 REJECTED；
   记录决定人）、`FIN_AP_SETTINGS_SET`（应付 = `AP` 控制科目，其余非控制科目，缺省银行为有效的公司银行账户）、`FIN_1099_THRESHOLD_SET`（不指定表即 NEC 与 MISC）、
   `FIN_BANK_ACCOUNT_SAVE`（Treasurer，二次验证；总账科目为 `BANK` 控制科目）。
3. 纯计算：`calc.TaxIds`（SSN / ITIN / EIN 的规则与书写形式）、`calc.BankNumbers`（ABA 校验位、账号）；`io.VendorRows`（实体类型与"1099-MISC box 1 (rents)"的文字）。
4. 导入：`finance.vendors`（样例 `vendors.csv`；币种缺省 USD，`terms_days` 读作 `NET<n>`，`tin_on_file` 读作"W-9 已收"；一行无效即整个文件拒收）、
   `finance.ap_thresholds`（样例 `thresholds-1099.csv`，一列阈值同时用于 NEC 与 MISC）。
5. 权限与角色：`fin.ap.read`、`fin.vendor.maintain`、`fin.vendor.bank.maintain`（PayablesClerk）、`fin.vendor.bank.approve`（Controller、Approver）、
   `fin.vendor.bank.read`、`fin.bank.maintain`、`fin.bank.read`、`fin.payment.release`（Treasurer）、`fin.tax.data.read`、`fin.ap.settings`（Controller）、
   `fin.1099.maintain`（PayablesClerk、Controller）、`fin.bill.prepare`、`fin.payment.prepare`（PayablesClerk，F4b/c 使用）；`fin.vendor.bank.result` 不授予任何角色。
6. `FIN_SETUP` 另提出（由他人发布）：审批规则 `FIN-VENDOR-BANK`（每次银行变更需要 `fin.vendor.bank.approve`）、职责分离规则 `FIN-SOD-VENDOR-BANK-RELEASE`
   （`fin.vendor.bank.maintain` × `fin.payment.release`）与 `FIN-SOD-PAYABLES-RELEASE`（`fin.bill.prepare`、`fin.payment.prepare` × `fin.payment.release`）；输出按规则代码列出提出的变更。

计划变更：未结应付的导入（`finance.open_payables` / `FIN_AP_OPENING`）需要账单实体，移到 F4b。

**验收标准**
- [x] 导入 `vendors.csv` 后有 8 家供应商，实体类型与 1099 设置正确（V200、V800 为 NEC 栏 1，V300 为 MISC 栏 1）；一行无效则整个文件拒收（AP-001、DI-001，`PayablesMasterIT`、`VendorRowsTest`）。
- [x] 应付员登记 V800 的 SSN 后看到 `***-**-1234`，不能显示明文；Controller 显示明文时记入 `sys_reveal_record`；操作记录中没有 TIN；遮蔽形式与无效号码被拒（AP-002 验收 1、SC-004，`PayablesMasterIT`、`TaxIdsTest`）。
- [x] 结果流程只相信平台的审批请求与决定记录：持有 `*` 的人直接运行它也不能让待批准的变更生效；同时提出的两个变更在审批锁之下再查一次，后一个被拒（安全审查的修正，`PayablesMasterIT`）。
- [x] V200 的银行信息变更经二次验证提出，提出人不能批准，由 Controller 批准后生效、旧账户 REPLACED；待批准时不能再提出；驳回的账户不启用；审计中有遮蔽后的新旧账号、
      提出人与决定人，没有明文账号（AP-003 的数据部分、CT-010 验收 1、SC-001，`PayablesMasterIT`、`BankNumbersTest`）。
- [x] 同时持有 PayablesClerk 与 Treasurer 的用户出现在职责分离冲突报告中；规则发布后这样的分配被拒绝（CT-001 验收 2，`PayablesMasterIT`）。
- [x] 1099 阈值按年度导入（2025 年 600.00、2026 年 2,000.00），改阈值不需要改代码；应付设置与公司银行账户的科目检查（AP-021 的数据部分，`PayablesMasterIT`）。
- [x] 时态表只插入；`./gradlew :finance:check`（`platformCheck` 0 错误 0 警告）通过。

已知限制：付款建议中的暂停（"bank details pending approval"）在 F4c 验收。

### F4b 账单、供应商贷项、使用税与资产登记

**要求**
1. 时态实体（V11）：`FinBill`（种类 BILL / CREDIT；过账时编号 `BILL-{n}` / `VC-{n}`；供应商与供应商发票号、发票日、收到日、到期日、条件、缺省 1099 表与栏、
   附件（策略 `fin.bill`）、相似单据的原因、状态 DRAFT / POSTED / VOID、审批 NOT_REQUIRED / PENDING / APPROVED / REJECTED、合计、使用税、未结、总账号、作废）、
   `FinBillLine`（金额、科目、使用税码、维度、1099 表与栏）、`FinBillTax`（使用税的计算说明，只写一次）、`FinApApplication`（贷项核销到账单，只写一次）、
   最小的资产登记 `FinAsset`（`FA-{n:3}`，起始号 `finance.fa.asset-numbers-start`；D4）。
2. 流程：`FIN_BILL_SAVE` / `_DELETE`（草稿；同供应商同号（只比字母与数字）拒绝 `FIN_BILL_DUPLICATE`，同供应商、金额、日期而号不同须填原因 `FIN_BILL_POSSIBLE_DUPLICATE`；
   行的 1099 缺省取供应商）、`FIN_BILL_POST`（使用税按行的税码与发票日税率计提，借行科目、贷使用税应付；到期日按条件；编号无缺号；经 `FIN_SUBLEDGER_POST` 记账，
   来源 AP；固定资产成本科目的行经内部子流程 `FIN_ASSET_CREATE` 登记资产；账单经审批对象 `fin.ap.bill` 判断：被规则拦下的账单照常过账、审批为 PENDING，
   批准前不能付款；1099 供应商没有 TIN 时警告备用预扣）、`FIN_BILL_VOID`（`fin.bill.void`，不是准备人；没有核销与付款；冲正，资产停用）、
   `FIN_AP_APPLY` / `FIN_AP_UNAPPLY`（贷项核销到同一供应商的账单及撤回）、`FIN_BILL_APPROVAL_RESULT`（内部，只由审批事件运行，只相信平台的审批请求）、
   `FIN_AP_OPENING`（从 F4a 移来：旧系统的未结账单作为已批准的未结项，不再过账；合计须等于期初分录中 2000 的余额；只能一次）。
3. 纯计算：`calc.BillDuplicates`、`ap.BillPosting`；使用税复用 `calc.SalesTax`。
4. 导入 `finance.open_payables`（样例 `open-payables.csv`）；模板 `finance.ap.aging`（按到期日或发票日分桶，合计等于 2000）、`finance.ap.bill_register`。
5. 权限：`fin.bill.void`（Controller）、`fin.bill.approve`（Controller、Approver）、`fin.ap.internal`（不授予任何角色）；`FIN_SETUP` 提出审批规则 `FIN-AP-BILL-10K`（金额 > 10,000.00）。

计划变更：账单在 F7 之前只用美元（同坏账核销）；供应商预付款是付款，随 F4c；供应商对账单随 F4e 的页面；AP-009 的账龄等于 FIN-EXP-09 要在 F4c 的付款之后验收（F4b 验收账龄合计等于 2000）。

**验收标准**
- [x] 未结应付导入合计 41,300.00 = 2000 的期初余额；合计不符时整体拒收并给出差额；不能再导入；DC-2025-12 带 V200 的 1099 设置（DI-002，`BillIT`）。
- [x] 1 月的 7 张账单的总账行等于 FIN-EXP-02；BILL-DC-2601 借 6400、贷 2000 各 7,500.00，行的 1099 为 NEC 栏 1，到期 2026-02-19；编号无缺号（AP-004，`BillIT`）。
- [x] P-7902 再录一次（写法不同也算）被拒；同金额、同日期、不同号码需要原因才能保存（AP-005，`BillIT`、`BillDuplicatesTest`）。
- [x] P-7902（22,000.00）过账后审批为 PENDING；录入人不能批准；伪造的结果不生效；Controller 批准后为 APPROVED；被驳回的账单作废（AP-006，`BillIT`）。
- [x] BILL-TS-5520（1520）过账后有 FA-003，成本 12,000.00，链接到账单；作废资本化账单时资产停用（AP-007，`BillIT`）。
- [x] 500.00 的贷项核销到 1,200.00 的账单后剩 700.00；超过账单余额的贷项被拒（AP-008，`BillIT`）。
- [x] 1,000.00 的应税采购计提使用税 82.50，借费用 1,082.50、贷 2000 1,000.00、贷 2210 82.50（TX-007，`BillIT`、`BillPostingTest`）。
- [x] 1 月 31 日的应付账龄合计等于 2000 的余额（各步之后都成立）；没有 TIN 的 1099 供应商过账时警告备用预扣（AP-009 的合计部分、AP-002 验收 2，`BillIT`）。
- [x] 安全审查的修正：最后保存草稿的人才能过账，审批的准备人就是此人（他人改过的草稿须由改的人过账）；作废待批准的账单撤回审批请求与待办；
      贷项核销可经 `FIN_AP_UNAPPLY` 撤回（写相反金额），之后可作废；资产成本科目不能记贷项；不能核销到被驳回的账单；重复检查按规范化号码与日期定向查询；
      150 行的账单可保存、重存与过账；期初文件中重复的单据与晚于期初日的单据被拒（`BillIT`）。
- [x] 只经流程写入；时态表只插入；`./gradlew :finance:check` 通过（`BillIT`）。

已知限制：应收发票的"准备人"同样只在新建草稿时记录（他人改过的草稿可由改的人批准），在 F10 的职责分离收尾中按本阶段的做法修正。

### F4c 付款批、付款、预付款与付款文件

**要求**
1. 时态实体（V12）：`FinPaymentRun`（`PAY-RUN-{n:2}`；银行账户、付款日、方式 ACH / CHECK / WIRE、状态 DRAFT / SUBMITTED / APPROVED / RELEASED / CANCELLED、合计、
   准备人、审批请求与内容哈希、批准人、释放人）、`FinPaymentLine`（账单 / 其他付款 / 预付款）、`FinPayment`（`PMT-{n:4}`；支票号、付往的供应商账户、预付款未核销、
   作废日与原因）、`FinPaymentFile`（种类 NACHA / CHECKS / POSITIVE_PAY / WIRE、银行账户与生成日、生成的文件与哈希、状态 ACTIVE / CANCELLED、作废原因）。只经流程写入。
2. 流程：`FIN_PAYMENT_RUN_PROPOSE`（到期日或提前付款折扣、供应商；暂停：未批准、银行信息待批准、ACH 没有启用账户、供应商停用、已在另一批中，输出列出原因）、
   `_ADD` / `_REMOVE`（账单、其他付款到非控制科目（只在 CHECK 与 MANUAL 批中）、供应商预付款）、`_SUBMIT`（最后改动的人提交；审批对象 `fin.ap.payment-run`，没有适用的规则即拒绝）、
   `_APPROVAL_RESULT`（内部，只相信平台的审批请求）、`_CANCEL`（撤回审批请求）、`_RELEASE`（`fin.payment.release` + 二次验证；内容哈希与暂停再查；支票号取自银行账户）、
   内部 `FIN_PAYMENT_RECORD`（每个供应商一笔付款：借应付、贷折扣、贷银行；其他付款与预付款各一笔；写核销、减少未结）、`FIN_PAYMENT_VOID`（`fin.payment.void` + 二次验证；银行持有其文件的 ACH、电汇付款不能作废）、
   `FIN_AP_PREPAYMENT_APPLY`（借应付 / 贷预付）、`FIN_PAYMENT_FILE_GENERATE` / `_CANCEL`。
3. 纯计算：`calc.NachaWriter`、独立的 `calc.NachaValidator`、`calc.CheckFiles`（支票打印与正向支付 CSV）、`calc.WireFile`（电汇指示 CSV）。
4. 权限与角色：`fin.payment.approve`（Controller、Approver）、`fin.payment.void`（Controller、Treasurer）；Treasurer 另有 `file.generated.read`（读取付款文件）。
   `FIN_SETUP` 提出审批规则 `FIN-AP-PAYMENT`（每批需要 `fin.payment.approve`）与职责分离规则 `FIN-SOD-PAYMENT-APPROVE`（`fin.payment.prepare` × `fin.payment.approve`）。

计划变更：职责分离"准备人不释放"由平台规则 `FIN-SOD-PAYABLES-RELEASE` 保证，流程不另写检查（CLAUDE.md 第 4 节）；供应商账户的数据视图查询上限提高到 10,000，
付款流程只读启用与待批准的账户，避免被截断时漏掉待批准的变更；读到上限的查询一律拒绝而不是少读。
**电汇指示由 PDF 单据改为 CSV 生成文件（变更 F4 计划 D6 的电汇部分，2026-10-02 确认）**：平台单据中遮蔽字段一律遮蔽（22 §3.1），PDF 只能显示 `****1234`，银行无法据以汇款。
其他付款没有收款账户，因此只在支票批与新增的 MANUAL 批（在银行文件之外付，如州税务网站）中；STX-PAY-2512 以 MANUAL 批记录。代码审查的修正：作废需要二次验证，
银行持有文件的 ACH、电汇付款不能作废；付款人名称随批准锁定；NACHA 修饰符按银行与日期计；校验器另查服务类别、日期、零金额与收款人名称。

**验收标准**
- [x] 1 月 8 日对 1 月 20 日前到期的已批准账单建议付款，选中 P-7781 与 CPL-1225，合计 32,300.00；V200 的银行信息待批准时 DC-2025-12 被暂停（"bank details pending approval"），
      未批准的账单与已在批中的账单不能加入（AP-010、AP-003，`PaymentIT`）。
- [x] 应付员提交的 PAY-RUN-01 不能由其本人批准（即使持有批准权限），Controller 批准；未批准不能释放；释放需要二次验证且应付员不能释放；伪造的审批结果不生效，驳回回到草稿；
      ACH 批不能加入其他付款；银行持有文件的付款不能作废（AP-011、SC-001、CT-001，`PaymentIT`）。
- [x] PAY-RUN-01 与 PAY-RUN-02 的付款分录合计等于 FIN-EXP-02（借 2000 32,300.00 / 19,000.00，贷 1010）；DC-2025-12、MP-2026-01、JR-014 已付并链接到付款，V200 的付款付往批准后的账户（AP-012，`PaymentIT`）。
- [x] PAY-RUN-01 的 NACHA 文件通过校验器，一个 CCD 批、2 个条目、合计 32,300.00，哈希与存档一致；PAY-RUN-02 为 CCD 与 PPD（V800 为个人）两批；同一批再生成被拒，作废（带原因）后可重新生成，
      文件标识修饰符变为 B；只有释放付款的人能读取（AP-013、BK-011，`PaymentIT`、`NachaWriterTest`（含 200 次性质测试）、`CheckFilesTest`）。
- [x] STX-PAY-2512：在银行文件之外付给 Texas Comptroller（MANUAL 批），借 2200 3,300.00、贷 1010；控制科目不能作为其他付款；TS-5520 于 2 月 14 日电汇，
      电汇指示 CSV 带付款账户与收款人账户全号、原样保存（AP-015、AP-013，`PaymentIT`）。
- [x] 2 月的支票批：提前付款折扣（借 2000 1,000.00、贷 5900 20.00、贷 1010 980.00），支票号 10001–10003 取自银行账户；支票打印文件与正向支付文件列出支票号、日期、金额与付款人；
      V500 的预付款（借 1310 / 贷 1010）核销到其账单（借 2000 / 贷 1310），不能核销到其他供应商的账单（AP-008、AP-010、BK-011，`PaymentIT`）。
- [x] 支票 10001 于 2026-02-10 作废（需要二次验证）：当日冲正、CS-0126 重新打开、原付款仍可见（VOID）；不能再作废；新的正向支付文件将其标为 V（AP-014，`PaymentIT`）。
- [x] 1 月 31 日的应付账龄等于 FIN-EXP-09（46,300.00）且等于 2000 的余额（AP-009，`PaymentIT`）。
- [x] 只经流程写入；时态表只插入；`./gradlew :finance:check`（`platformCheck` 0 错误 0 警告）通过。

已知限制：付款日不与当天比较：批准后晚于付款日才释放的批仍按原付款日过账，提前付款折扣按建议时的付款日计算（期间控制阻止过到已关闭的期间）；
预付款核销后不能撤回或作废；电汇指示是 CSV，各银行的电汇格式在 F11 的接口中适配。

### F4d Form 1099 与 FIN-SCN-04

**要求**
1. 时态实体（V13，只写一次）：`Fin1099Amount`（付款计入 1099 的金额，按供应商 × 年度 × 表 × 栏，来源 PAYMENT / PREPAYMENT / VOID）、`Fin1099Filing`（已申报的记录，
   TIN `masked(fin.tax.data.read, TAX_ID)`，ORIGINAL / CORRECTION 与所更正的记录）；公司资料加 `taxId`（付款人 EIN）。
2. 付款：`FIN_PAYMENT_RECORD` 写 `Fin1099Amount`（账单现金按行的表与栏分摊，期初未结项用账单的表与栏；预付款按供应商；方式 CARD 与其他付款不计入）；
   `FIN_PAYMENT_VOID` 写相反金额，年度为原金额的年度（12 月的支票 1 月作废，仍从 12 月所在年度扣除）；新的付款方式 CARD（公司卡，不出银行文件）。
3. 报表 `finance.ap.form_1099`、审核报表 `finance.ap.form_1099_review`；流程 `FIN_1099_ISSUE`（收件人副本 PDF）、`FIN_1099_EXPORT`（申报服务 CSV）、`FIN_1099_CORRECT`。
4. 纯计算：`calc.Form1099Allocation`、`calc.Form1099File`。权限 `fin.1099.file`（Controller，另授 `file.generated.read`）。

**验收标准**
- [x] FIN-SCN-04 第 1–6 步按角色完成：1 月账单、P-7902 待 Controller 批准、P-7902 再录被拦；TS-5520 生成 FA-003；PAY-RUN-01 经 Controller 批准、Treasurer 以二次验证释放、
      NACHA 文件通过校验；V200 的银行信息变更使其账单暂停，由第二人批准后才付；STX-PAY-2512 记录；PAY-RUN-02 付款（分录 = FIN-EXP-02），其文件不能生成两次；
      应付账龄 = FIN-EXP-09（46,300.00）（`FinScn04IT`）。
- [x] 2026 年的 1099 报表 = FIN-EXP-14：V200 NEC 1 9,000.00、V300 MISC 1 8,500.00 达到阈值 2,000.00，V800 1,500.00 未达到；MP-2026-01 计为租金（AP-020 验收 1、AP-021 验收 1）。
- [x] 以公司卡付给 V200 的金额不计入；作废的支票付款冲回，12 月付出、次年 1 月作废的支票从原年度扣除（AP-020 验收 2、`FinScn04IT`）。
- [x] 2027 年阈值由 2,000.00 改为 600.00 后，V800 的 1,500.00 成为应申报，代码不变（AP-021 验收 2）。
- [x] V800 没有 TIN 时审核报表列出它；登记后审核报表为空（AP-022 验收 1、FIN-SCN-04 第 6 步）。
- [x] 收件人副本：付款人 EIN 全号、收件人 TIN 截断（`**-***4567`）、金额 9,000.00；未达阈值的供应商不出副本；应付员不能签发（AP-022）。
- [x] 导出：每个达到阈值的供应商 × 表 × 栏一行（V200、V300），金额等于报表；只有持有 `fin.1099.file` 的人能读取文件；同一年度不能再导出；
      之后又付 V200 500.00，更正导出含标为 CORRECTED 的 9,500.00，再更正为空；只改 TIN 的更正、低于阈值的 0.00 更正、新达到阈值的补报原始记录（AP-022 验收 2、AP-023）。
- [x] 代码审查的修正：负数栏与未达阈值的表不申报、不打印；导出从汇总报表读取，不受 5,000 行上限；并发导出不会重复申报（唯一索引）；收件人副本需要 TIN 与地址；分摊经 `Money.allocate`。
- [x] 只写一次的表只插入；`./gradlew :finance:check`（`platformCheck` 0 错误 0 警告）通过。

已知限制：CARD 付款仍贷所选银行账户的总账科目（信用卡负债与还款在 F5 的银行模块中细化）；IRS 对 MISC 各栏的不同门槛简化为同一表的阈值；
预付款在付款时按供应商的表与栏计入，核销到行的表与栏不同的账单时不改分类；申报文件是一种申报服务 CSV，IRS FIRE 格式在 F11 的接口中再做。

### F4e 后台页面与端到端

**要求**
1. 菜单 "Payables"（`finance-web/src/index.tsx`）：账单、新账单、付款批、新付款批、付款（各自的权限）；供应商、应付设置、银行账户（平台通用页面）；
   应付账龄、供应商对账单、1099 汇总、1099 审核（平台报表页）。导入组加供应商、1099 阈值、未结应付。
2. 账单登记簿 `/payables/bills`（`finance.ap.bill_register`）：日期区间、供应商、状态、种类、审批；可排序；合计与未结（贷项为负，精确十进制相加）；超过 500 行时提示；每行打开单据。
3. 账单页 `/payables/bills/new|:id`（`?kind=CREDIT` 为供应商贷项）：
   - 草稿：供应商（输入联想，带出名称、缺省科目与 1099 表栏）、供应商发票号、发票日、收到日、条件、说明；行表格（描述、金额（可不带分隔符，离开时显示千分位）、科目、
     使用税码、部门、1099 表与栏，当场检查表与栏的组合）；Tab 顺序固定，最后一格 Enter 到下一行，Alt+N / Insert 插行，Ctrl+Delete 删行，Ctrl+S 存草稿，Ctrl+Enter 过账（UI-002）。
     服务端的拒绝落到对应单元格；同供应商、金额、日期的相似账单（`FIN_BILL_POSSIBLE_DUPLICATE`）出现"不是重复的原因"一栏，填写后再保存（AP-005）。
   - 已过账、作废：只读；事实与合计（使用税、未结、审批状态，审批人可在页面上决定）、行与 1099 表栏、使用税的计算说明（按行与辖区的税基、税率、生效日、税额，UI-007）、
     付款与贷项的核销记录、历史；作废（`fin.bill.void`，日期与原因）。
4. 付款批：登记簿 `/payables/runs`（新模板 `finance.ap.payment_run_register`）；建议页 `/payables/runs/new`（付款日、方式、银行账户（缺省取应付设置）、到期日、供应商、
   提前付款折扣、说明）；付款批页 `/payables/runs/:id`：刚建议时列出被暂停的账单与原因；行（草稿可删）与新增（按账单号加账单、其他付款、预付款）；
   提交、取消（原因）、审批人决定（平台 `ApprovalPanel`）、释放（`fin.payment.release`，确认后提交，二次验证由平台弹出）；释放后的付款（`fin.payment.void` 可作废）
   与文件（按方式生成 NACHA / 支票打印 / 正向支付 / 电汇指示，下载存档字节，以原因作废）。
5. 付款登记簿 `/payables/payments`（新模板 `finance.ap.payment_register`）：日期区间、供应商、方式、状态；未作废的合计；每行打开付款批。
6. 供应商对账单（新模板 `finance.ap.vendor_statement`，从 F4b 移来）：期初应付、账单、贷项、作废、付款、折扣与核销的预付款，逐笔余额，期末余额等于该供应商的账龄合计。
7. 纯函数 `payables/bill.ts`（行的当场检查、合计、输入转换、拒绝定位）有单元测试。

**验收标准**
- [x] 应付员只用键盘录入 5 行账单（金额不带分隔符）并过账，合计 4,500.00；表栏组合错误当场标出且不能过账；相似账单需要原因才保存（UI-002 验收 1、AP-005，
      `e2e/payables.spec.ts`、`BillPage.test.tsx`、`bill.test.ts`）。
- [x] 已过账账单显示使用税的辖区、税率与生效日、1099 表栏、付款记录与待审批状态，审批人在页面上决定；有权限者可作废（UI-007、AP-006，`BillPage.test.tsx`）。
- [x] 账单登记簿按供应商筛选后合计等于所列单据之和（贷项为负），行打开账单；付款批、付款登记簿合计（取消与作废的不计）并打开付款批（UI-004，`registers.test.tsx`、`e2e/payables.spec.ts`）。
- [x] 应付员建议支票付款批并提交，Controller 在付款批页批准，Treasurer 以二次验证登录后释放，生成支票打印与正向支付文件并下载，正向支付文件含支票号、日期、金额与付款人；
      账单未结为 0.00（AP-010…013、BK-011，`e2e/payables.spec.ts`、`PaymentRunPage.test.tsx`）。
- [x] 供应商对账单：V200 的 1 月期初 9,000.00、账单、付款、期末 7,500.00 = 账龄；付款批与付款登记簿模板（`PaymentIT`）。
- [x] `pnpm ext:check`（79 个测试）、`tools/finance/e2e.sh`（5 个用例，可在同一数据库上重复运行）、`./gradlew :finance:check` 通过。

计划变更：端到端的准备（`preparePayables`）发布财务初始化提出、尚未发布的控制变更（按原因 "Finance setup:" 识别的审批与职责分离规则，其他待发布的变更不动），由 Controller 发布（四眼），每次运行新建一个登记了二次验证的
Treasurer；公司银行账户只在没有时建立（再保存会重置支票号）。

已知限制：释放的二次验证在端到端中由登录时的验证码满足（登录后的验证在有效期内），弹出的验证码框由平台的端到端覆盖；账单页不提供附件上传（平台通用页面可上传，策略 `fin.bill`）；
供应商贷项核销到账单（`FIN_AP_APPLY` / `_UNAPPLY`）、预付款核销与 1099 的签发、导出、更正仍用平台的流程页；无障碍检查同 F3d-2，留给平台。
账单页保存草稿时原样带回页面不显示的附件、原账单与行地点（不会清掉经接口或导入写入的值）；付款批的加行与提议按请求体使用幂等键。
等待审批的账单与付款批页面按固定间隔刷新，直到有结论（审批可能数日未决，平台没有推送）；付款批日期早于账单日期时（只对预付款核销检查），
两日期之间的供应商对账单计入付款而账龄两者都不计，二者暂不一致。

## F5 银行与对账

需求：FIN-BK-001…008、011，BK-009、BK-010（Should），DI-001 的未达银行项目；主验收 FIN-SCN-05（FIN-EXP-10）。
计划已确认（2026-10-02，接受全部推荐）：

1. 在途资金科目由 Controller 新增（测试中 1090），在银行设置 `FinBankSettings` 中指定；同日转账一笔分录，跨日转账经在途科目两笔（D1）。
2. 未达项目的期初以导入 `finance.bank_opening_items` 带入：切换日的对账单余额 + 未达项目 = 期初分录中现金科目的余额，否则整体拒收；不过账，可被匹配（D2）。
3. 对账单格式：CSV（样例格式，映射可调）、BAI2、camt.053（应用自己的解析器，XML 禁止 DTD）；OFX 不做，留给 F11（D3）。
4. 重复：同一文件平台拒绝（409）；不同文件中的同一行按"账户 + 银行参考号"（无参考号时按日期、金额、说明与当日序号的哈希）只存一次（F5a 中改为拒收并说明，见下）；
   期初 + 各行 ≠ 期末，或期初不等于上一张对账单的期末，整体拒收（D4）。
5. 自动匹配只给建议（置信度与原因），由人批量接受（D5）。
6. 签核：审批对象 `fin.bank.reconciliation`，`FIN_SETUP` 提出规则 `FIN-BANK-REC`；批准时以 `REPORT_ISSUE` 签发调节表，重印取存档（D6）。
7. 由对账单行生成分录：规则按说明关键字预填科目与编号前缀（`BANK-FEE-2601`，同月第二笔加 `-2`）（D7）。
8. pain.001 不做，留给 F11；BK-011 已在 F4 完成（D8）。
9. 分 PR：F5a 账户、转账、对账单与期初未达项；F5b 匹配；F5c 调节表、签核与报表、FIN-SCN-05；F5d `finance-web` 与端到端。

### F5a 账户、转账、对账单与期初未达项

**要求**
1. 时态实体（V14）：`FinBankSettings`（在途科目、匹配的日期窗口（缺省 3 天）、过期支票天数（缺省 90））、`FinBankTransfer`（`TRF-{n:4}`；来源、目标、金额、
   汇出日、到账日、状态 IN_TRANSIT / COMPLETED / VOID、两笔分录）、只写一次的 `FinBankStatement`（账户、起止日、期初与期末余额、行数、格式）与
   `FinStatementLine`（日期、银行参考号、说明、金额、类型代码、行键；账户 × 行键唯一）、`FinBankOpening`（切换日、对账单余额、账面余额）与
   `FinBankOpeningItem`（未达项目，只写一次）。`FinBankAccount` 加对账单格式；一个现金科目只对应一个银行账户（BK-001）。
2. 流程：`FIN_BANK_SETTINGS_SET`（Controller；在途科目不是控制科目）、`FIN_BANK_TRANSFER_POST` / `_RECEIVE` / `_VOID`（Treasurer；同币种、不同账户；
   经 `FIN_SUBLEDGER_POST` 记账，来源 BANK）、`FIN_BANK_STATEMENT_RECORD`（导入的组流程：期初 + 行 = 期末；与上一张衔接；
   同一账户同一截止日的对账单只记一次，再次送来（任何格式）拒收并说明已记录；含已存行的对账单拒收并列出这些行；文件中的账号末四位须与账户一致）、`FIN_BANK_OPENING_ITEMS`（每个账户一次）。
3. 纯计算：`io.Bai2Parser`、`io.Camt053Parser`、`calc.StatementCheck`（合计、行键）。
4. 导入：`finance.bank_statement`（CSV，参数为账户）、`finance.bank_statement_bai2`、`finance.bank_statement_camt053`、`finance.bank_opening_items`。
5. 权限：`fin.bank.statement.import`（Accountant、Treasurer、Controller）、`fin.bank.transfer`（Treasurer）、`fin.bank.settings`（Controller）、
   `fin.bank.activity.read`（Accountant、Treasurer、Controller、ExternalAuditor）。

计划变更：D4 的"不同文件中的同一行跳过并列出"改为拒收并说明：平台的导入报告只含拒绝的原因，不含流程跳过了什么，拒收才能让用户看到；
整张对账单已记录时说明"已记录、未增加任何行"，含已存行时列出这些行，什么都不存。

**验收标准**
- [x] 1010 与 1050 各对应一个银行账户；读取时账号一律遮蔽（`****6789`），持有 `fin.bank.read` 的 Treasurer 逐值显示（记入显示记录），Accountant 不能；
      同一科目不能再对应另一个账户（BK-001，`BankStatementIT`）。
- [x] 1050 → 1010 转账 50,000.00 同日入账：一笔分录，1050 减少、1010 增加 50,000.00；跨日转账先借在途科目、到账时转入目标账户；未设在途科目时拒绝；
      作废冲回各笔（BK-002，`BankStatementIT`；两边对账单的匹配随 F5b）。
- [x] 未达项目期初：CHK-1045 3,200.00，对账单余额 253,200.00 − 3,200.00 = 1010 的期初 250,000.00；不符时整体拒收并给出差额；每个账户一次（DI-001，`BankStatementIT`）。
- [x] `bank-statement-2026-01.csv` 导入后存 10 行，期末 256,555.00 通过检查；再导入同一文件被告知已导入；同一对账单的 BAI2 与 camt.053 文件不增加任何行并说明已记录；
      合计不符或与上一张不衔接的文件整体拒收（BK-003，`BankStatementIT`）。
- [x] 解析器拒收畸形文件、带 DTD 的 XML 与超限的文件（`Bai2ParserTest`、`Camt053ParserTest`）。
- [x] 代码审查的修正：转账记下汇出时的在途科目，到账从它转出（之后改设置不影响途中的钱），设置未给的值保留；对账单须在切换日之后、按顺序记录
      （先于已记录的对账单拒收 `FIN_BANK_STATEMENT_ORDER`，切换日未带入拒收），切换日不能在对账单之后带入；BAI2 的行取报告日（起息日不是记账日），
      客户参考号不作参考号；camt.053 先取记账日、`PRCD` 余额的次日为起始日，只保留需要的路径并限制嵌套、名称长度、余额与值的个数；
      BAI2 严格日期、合计不溢出、组与文件尾的控制合计；没有行的对账单拒收（`Bai2ParserTest`、`Camt053ParserTest`、`BankStatementIT`）。
- [x] 只经流程写入；只写一次的表只插入；`./gradlew :finance:check` 通过。

已知限制：导入人须能读取文件策略的文件（平台导入的规则），所以持有 `fin.bank.statement.import` 的人能下载对账单原件，其中有完整账号；
`FIN_BANK_STATEMENT_RECORD` 标为内部，但平台的内部流程仍可直接请求，持有导入权限者可不经文件记录对账单（与上传伪造的文件同样，后者留有文件）；
没有银行参考号的行以说明参与行键，不同格式的说明不同时，同一对账单再次送来报告"不同的对账单"而不是"已记录"（同样拒收）；
对账单的日期只检查余额衔接，不要求紧接上一张的截止日（BAI2 没有起始日）；OFX 与 pain.001 留给 F11。

### F5b 匹配

**要求**
1. 只写一次的实体（V15）：`FinBankMatch`（动作 MATCH / UNMATCH、所撤销的匹配、方式 AUTO / MANUAL / ENTRY、金额、置信度、原因、操作人与时刻）、
   `FinBankMatchItem`（对账单行或账面项：现金科目上的一笔总账交易 LEDGER 或切换日的未达项 OPENING；当时的日期、金额与标签；
   每次匹配的"轮次"由唯一索引防止同时重复匹配）、`FinBankEntry`（由对账单行生成的分录，`BANK-FEE-2601`，每行一次）；时态的 `FinBankEntryRule`
   （关键字、方向 PAYMENT / DEPOSIT / ANY、科目、编号前缀）。
2. 模板：`finance.bank.book_items`（未匹配的账面项：交易在现金科目上的金额、单据号、客户或收款人、支票号、付款批）、
   `finance.bank.statement_items`（未匹配的对账单行）、`finance.bank.match_history`（每次匹配与撤销：谁、何时、方式、原因、所涉及的行与账面项）。
3. 纯计算 `calc.BankMatcher`：金额相等且在日期窗口内（缺省 3 天），或为同一付款批同日付款的合计；行中写明支票号的支票可以更早；
   置信度 = 金额 50 + 日期（同日 20）+ 支票号 25 + 名称 10/15，并给出原因；同样好的候选降低 30 并说明；每行、每个账面项只给一次。
4. 流程（`fin.bank.reconcile`：Accountant、Controller）：`FIN_BANK_MATCH_PROPOSE`（只读）、`FIN_BANK_MATCH`（一对一、一对多、多对一，不许多对多；
   合计相等；都须未匹配且属于该账户；金额取账面，不取调用方）、`FIN_BANK_MATCH_ACCEPT`（批量接受，全部或全不）、`FIN_BANK_UNMATCH`（新记录，两者都留）；
   `FIN_BANK_ENTRY_RULE_SAVE`（`fin.bank.settings`，Controller；非控制科目）、`FIN_BANK_ENTRY_FROM_LINE`（按指定或第一条适用的规则、或给出的科目生成分录，
   以行的日期经 `FIN_SUBLEDGER_POST` 记账（来源 BANK），并立即与该行匹配）。

**验收标准**
- [x] 1 月对账单与账面：自动匹配给出 CSV 中 `expected_match` 的 8 个匹配（CHK-1045、RCPT-0001…0003、PAY-RUN-01 与 PAY-RUN-02 的批合计、JE-0001、STX-PAY-2512），
      各带置信度与原因；手续费、利息与 PAYROLL-2601 不被匹配（BK-004 验收 1，`BankMatcherTest`；全套账面的回放在 F5c 的 `FinScn05IT`）。
- [x] 经流程：收款与未达支票的建议被批量接受，之后不再是未匹配项；再次接受整体拒收；两笔转账手工匹配到同日的一笔借记；
      合计不等、多对多、已匹配、他行的项、无权限者被拒（BK-004、BK-005，`BankMatchIT`）。
- [x] "ACCOUNT SERVICE FEE" 45.00 与 "LOAN INTEREST" 300.00 按规则生成 BANK-FEE-2601（6800 / 1010）与 BANK-INT-2601（7100 / 1010）并已匹配；
      已匹配的行不能再生成；无规则可用时须给出科目（BK-005 验收 1，`BankMatchIT`）。
- [x] 一个匹配撤销后再匹配：历史中两次匹配与撤销都在，带操作人、时刻与原因；不能撤销两次（BK-006 验收 1，`BankMatchIT`）。
- [x] 代码审查的修正：账面项只取切换日之后过账的（期初分录与之前的由未达项代表）；批量接受时重新运行匹配，只接受此刻的建议，置信度与原因取自匹配
      （`FIN_BANK_MATCH_NOT_PROPOSED`），手工匹配一律记为 MANUAL；分录编号经平台编号序列（按前缀与月份计数，无缺号）；不经规则自选科目需要
      `fin.bank.settings`（`FIN_BANK_ENTRY_FREE_ACCOUNT`）；摘要按长度截断；付款的冲正不带收款人、支票号与付款批；同样好的候选从行与账面项两边判断，
      输入顺序不影响建议；轮次按银行账户计（转账在两个账户各匹配一次）（`BankMatcherTest`、`BankMatchIT`）。
- [x] 只写一次的表只插入；`./gradlew :finance:check` 通过。

已知限制：已匹配的账面项被作废（冲正）时，冲正是新的未匹配项，原匹配不变，两者在调节表中抵消；签核后不能撤销的限制随 F5c（已完成）。

### F5c 调节表、签核与报表

**要求**
1. 时态实体 `FinBankReconciliation`（V16）：每个账户每个对账单截止日一份；对账单余额、在途存款、未达付款、调整后银行余额、账面余额、未入账的对账单行、差额、
   状态 PREPARED / SUBMITTED / SIGNED_OFF、准备人、审批请求与内容哈希、复核人、签核时刻、签发的报表与其哈希。
2. 模板 `finance.bank.reconciliation`（报表）：对账单余额；截止日仍未达的账面项（在途存款、未达付款，含切换日的未达项，逐期带入，BK-009）；调整后银行余额；
   账面余额；未入账的对账单行；差额 = 调整后银行余额 − 账面余额；准备人与复核人。一项只经"所有行与项都在截止日或之前"的未撤销匹配才算已达。
3. 流程：`FIN_BANK_REC_PREPARE`（`fin.bank.reconcile`；该日须有对账单；可反复准备）、`FIN_BANK_REC_COMPLETE`（由最后准备的人；差额为零；之前各期已签核；
   重新计算后经审批对象 `fin.bank.reconciliation` 请复核，内容为各行；无规则拒绝）、`FIN_BANK_REC_APPROVAL_RESULT`（内部，只信平台的审批请求；
   批准且内容未变则签核，否则或驳回回到 PREPARED）、`FIN_BANK_REC_WITHDRAW`（完成人撤回等待中的调节表）、
   `FIN_BANK_REC_ISSUE`（签核后以 `REPORT_ISSUE` 签发一次，按签核时点读取）。
   已提交或签核的调节表所覆盖的日期内，匹配不能撤销，也不能新建（`FIN_BANK_MATCH`、`_ACCEPT`、`FIN_BANK_ENTRY_FROM_LINE`；`FIN_BANK_MATCH_RECONCILED`）。
4. 权限与规则：`fin.bank.rec.review`（Controller）；`FIN_SETUP` 提出规则 `FIN-BANK-REC`（所有调节表，复核人 `fin.bank.rec.review`）。
5. 模板 `finance.bank.stale_checks`（BK-009）与 `finance.bank.cash_position`（BK-010）。

计划变更：D6 的"批准时签发"改为批准时签核、随后由对账人员以 `FIN_BANK_REC_ISSUE` 签发（按签核时点 `asOf` / `knownAt` 读取，内容即签核时的账面）：
平台的 `REPORT_ISSUE` 要求调用人持有模板的权限，审批事件以没有权限的系统身份运行，不能签发；改平台不在本阶段范围内。

**验收标准**
- [x] 差额 10.00（未入账的手续费）不能完成；由行生成分录后差额为零；只有最后准备的人能完成；没有规则 `FIN-BANK-REC` 时不能完成，规则发布后提交审批；
      准备人不能签核自己的调节表，另一位 Controller 签核后可签发一次（BK-007、BK-008，`BankReconciliationIT`）。
- [x] 1 月未签核时 2 月不能完成；提交后账面变化，批准不签核而回到准备；签核后计入其中的匹配不能撤销，之后的匹配可以（`BankReconciliationIT`）。
- [x] 95 天的未达支票在过期支票报表中列出，匹配后不再列出（BK-009 验收 1，`BankReconciliationIT`）。
- [x] FIN-SCN-05：1 月全套账面（收款、PAY-RUN-01/02、STX-PAY-2512、JE-0001、PAYROLL-2601），对账单只导入一次（CSV 再次 409，BAI2、camt.053 说明已记录），
      8 个建议匹配被接受，手续费与利息生成分录，一个匹配撤销再做；调节表 = FIN-EXP-10（256,555.00 − PAYROLL-2601 45,000.00 = 211,555.00 = 账面，差额 0.00）；
      Accountant 完成、Controller 签核；2 月过账后重印的报表逐字节相同，1 月重新计算不变（`FinScn05IT`）。
- [x] 现金头寸：1010 账面 211,555.00、对账单 256,555.00，待付含 P-7902 22,000.00（2026-02-08 到期）（BK-010 验收 1，`FinScn05IT`）。
- [x] 代码审查的修正：之前的对账单没有调节表时同样不能完成（只看上一张对账单的调节表是否签核，逐期归纳）；同一内容已获批准的请求再次完成时直接签核
      （平台沿用已批准的请求，不再视为"无规则"）；签核的日期内也不能新建匹配或由行生成分录；完成人可撤回等待中的调节表；
      审批结果流程对平台记录不支持的输入不做任何事并正常回答（`BankReconciliationIT`）。
- [x] 调节表只追加版本；`./gradlew :finance:check` 通过。

已知限制：内部流程 `FIN_BANK_REC_APPROVAL_RESULT` 仍可被持有其权限者直接请求（平台的限制，同 F5a），它只按平台的审批记录行事；
同一内容的已批准请求再次完成的路径在差额为零的约束下几乎不可达，未单独测试；签核后、签发前若有回溯到该期的过账，报表仍按签核时点读取（与签核内容一致），但账面已变，需另做调整（期间关闭随 F6）；
现金头寸的未结金额取单据的当前记录（以 `knownAt` 运行得到过去某时的记录）；调节表的区段以代码显示（`DEPOSIT_IN_TRANSIT` 等），页面的显示名随 F5d。

### F5d 后台页面与端到端

**要求**
1. 菜单 "Cash and bank"（`finance-web/src/index.tsx`）：银行匹配、银行调节表（自己的页面）；对账单导入（CSV、BAI2、camt.053）、未达项目导入（平台导入向导）；
   对账单、转账、对账规则、银行设置（平台通用页面）；新转账（平台流程页）；现金头寸、过期支票、匹配历史（平台报表页）；已签发的调节表（平台报表存档）。
2. 银行匹配 `/bank/matching?bank=`：选择银行账户；服务端的建议（行、账面项、置信度、原因）勾选后一起接受；未匹配的对账单行与账面项并列，勾选后合计相等（且不是多对多）才可匹配，
   可写原因；对账单行一键按规则生成分录（持有 `fin.bank.settings` 者可指定科目）；历史（匹配与撤销、方式、人、时刻、原因），未撤销的匹配以原因撤销。只读者只看不改。
3. 银行调节表：列表 `/bank/reconciliations`（按账户筛选；为对账单截止日准备新的）；调节表页 `/bank/reconciliations/:id`：数字（对账单余额、在途存款、未达付款、
   调整后余额、账面、差额）与按区段显示名列出的各行（当前计算；签核后以签发的报表为准）；准备中可重新计算与完成（确认）；等待复核时复核人用平台 `ApprovalPanel` 决定，
   完成人可撤回，页面定时重读直到有结论；签核后签发一次；已签发的报表下载 PDF / CSV（存档字节）。
4. 每个动作都是服务端的流程，拒绝原样显示；页面按权限只显示可做的动作。

**验收标准**
- [x] 匹配页：建议勾选后一起接受；手工匹配在合计不等时不能提交，相等时以所选行与项提交；由行生成分录；撤销需要原因，已撤销的匹配不再提供撤销；只读者没有任何改动入口
      （`bank/MatchingPage.test.tsx`）。
- [x] 调节表页：数字与区段；完成需确认，服务端的拒绝（如差额不为零）显示在页面；等待复核时显示审批与撤回；签核后签发，签发后可下载存档报表；列表与准备入口按权限
      （`bank/ReconciliationPage.test.tsx`、`bank/ReconciliationListPage.test.tsx`）。
- [x] 端到端：在本次运行自己的银行账户上，会计接受 2 个建议匹配、把手续费生成分录、撤销一个匹配并手工重做；准备 1 月调节表（调整后 885.00 = 账面，差额 0.00）并完成；
      Controller 在调节表页签核并签发，下载 PDF（`e2e/bank.spec.ts`）。
- [x] 代码审查的修正：撤回只对准备人显示，审批只对持有 `fin.bank.rec.review` 且不是准备人的复核人显示；匹配历史按时间倒序取最近 500 条（超过时提示），
      最近的匹配总能撤销；重新读取后只计仍在的建议，手工匹配只提交仍未匹配的行；每次打开撤销框原因为空；银行账户选择可搜索（端到端不受账户数量影响）
      （`bank/*.test.tsx`、`e2e/bank.spec.ts`）。
- [x] `pnpm ext:check`（94 个测试）、`tools/finance/e2e.sh`（6 个用例，在同一数据库上连续运行三次均通过）、`./gradlew :finance:check` 通过。

计划变更：端到端的准备（`prepareBank`）每次运行新建现金科目与银行账户（切换日没有未达项），没有期初分录时先过一笔最小的期初分录；转账与对账单经接口建立，对账单导入向导由平台的端到端覆盖。

已知限制：匹配页一次最多显示 500 个未匹配行或账面项（超过时提示）；调节表页的各行是当前计算，签核后的记录是签发的报表；签发需要用户操作（见 F5c 的计划变更）；
无障碍检查同前，留给平台。

## F6 固定资产

需求：FIN-FA-001…007、009，FA-003 中的工作量法（Should）；FA-008、FA-010（Could）按 FD6 留到最后。主验收 FIN-EXP-11：DEP-2601 = 4,000.00、FA-003 全寿命合计 12,000.00、
滚动表成本 190,000.00 → 202,000.00、累计折旧 58,000.00 → 62,000.00、资产子账 = 1500/1510/1520/1590，FIN-SCN-01 完整通过。
计划已确认（2026-10-02，接受全部推荐）：

1. 资产类别 `FinAssetClass`（Controller）：成本、累计、费用科目，缺省方法、年限、惯例，资本化门槛；一个成本科目只属于一个类别，账单资本化按成本科目取类别（D1）。
2. 登记资产（账单或取得分录）低于类别门槛即拒绝并提示改为费用（D2）。
3. "分录"来源由资产模块的取得流程 `FIN_FA_ACQUIRE` 过账（借成本科目、贷所给科目，来源 FA）：手工分录不能直接记入控制科目（D3）。
4. F4b 登记的资产按成本科目带入类别缺省值；没有类别的资产为"未分类"，折旧运行整体拒收并列出（D4）。
5. 计算（纯函数 `calc.Depreciation`）：直线；200% / 150% 余额递减按资产年度，直线更高时自该年起转直线；惯例全月（处置当月不计）、月中（首末各半月）、次月（处置当月计）；
   按资产 × 月舍入，尾差在每个资产年度与寿命的末月，全寿命合计 = 成本 − 残值；导入的资产以导入的累计为准，与计划之差在当前资产年度末月调整（D5）。
6. 估计变更自指定期间起：剩余可折旧额按新的剩余年限与残值，不重述；只能从尚未运行折旧的期间起（D6）。
7. 折旧运行每月一次（`DEP-2601`）：一张凭证按（费用科目、累计科目、部门）汇总，明细到资产；同期再运行不过账；已结期间拒绝；逐月连续；只冲回最近一期且未结（D7）。
8. 处置（出售、报废、核销）：处置月按惯例在处置分录中补提（该月已运行则不提），之前月份须已运行；转出成本与累计，收款记入所给科目，损益记入资产设置中的科目（测试中 7400，由 Controller 新增）（D8）。
9. 期初导入 `finance.fixed_assets`：切换日前投入的资产连同累计折旧登记、不过账，成本按科目合计 = 期初分录中的成本科目余额、累计合计 = 累计折旧科目，否则整体拒收；
   切换日后投入的行须对应账单已登记的同号资产，只核对并补齐类别、方法、年限，否则拒收（D9）。
10. 工作量法：资产记总工作量，每期录入用量，运行按用量计提，未录用量的期间整体拒收（D10）。
11. FA-008 减值、FA-010 MACRS 不在 F6（D11）。
12. 报表：资产登记簿与滚动表（SQL 模板，任意期间）；折旧表的过去取运行明细，预测由纯函数经只读流程给出（D12）。
13. 分 PR：F6a 类别、登记、计算、期初导入与取得；F6b 运行、估计变更、处置、工作量；F6c 报表、FIN-SCN-01 与 FIN-EXP-11 回放、`finance-web` 与端到端（D13）。

权限：`fin.fa.read`（Accountant、Controller、Executive、ExternalAuditor）、`fin.fa.maintain`（Controller：类别、设置、资产、估计变更、处置）、`fin.fa.run`（Accountant、Controller：折旧运行与用量）。

### F6a 类别、登记、计算、期初导入与取得

**要求**
1. 实体（V17）：时态的 `FinAssetClass`（类别代码、名称、成本 / 累计折旧 / 折旧费用科目、缺省方法、年限、惯例、资本化门槛；成本科目唯一）与 `FinFaSettings`（处置损益科目）；
   `FinAsset` 增加类别、方法、年限、残值、惯例、总工作量、保管人、状态（IN_SERVICE / FULLY_DEPRECIATED / DISPOSED）、来源（BILL / ACQUISITION / OPENING）、
   切换日的累计与本登记簿起算月、已计提至、累计折旧（后两者由 F6b 的运行写）。
2. 纯计算 `calc.Depreciation`（D5）：直线、200% / 150% 余额递减（按资产年度、转直线、不低于残值）、全月 / 月中 / 次月惯例、按月舍入与尾差、切换日起算（`opening`）、
   估计变更的重算（`restart`）、工作量法（`byUse`）、处置月的份额（`disposalShare`）。
3. 流程：`FIN_FA_CLASS_SAVE`、`FIN_FA_SETTINGS_SET`（`fin.fa.maintain`，Controller）；账单资本化 `FIN_ASSET_CREATE` 按成本科目取类别与缺省值、检查门槛；
   `FIN_FA_ACQUIRE`（借类别成本科目、贷所给科目——银行或非控制科目，来源 FA）；`FIN_FA_ASSET_SAVE`（描述、地点、保管人、部门；未计提过折旧的资产可改类别与条件）；
   `FIN_FA_OPENING` 与导入 `finance.fixed_assets`（`fin.migration`）。
4. 权限 `fin.fa.read`、`fin.fa.maintain`、`fin.fa.run` 授予计划所列角色；资产数据视图改为 `fin.fa.read` 读取。

计划变更：D9 中"切换日后投入、尚未由账单登记的行拒收"改为不带入并在结果中列出：FIN-SCN-01 在设置阶段导入样例登记簿，早于 1 月的账单（TS-5520 → FA-003）；
这类资产不属于期初余额，之后由其账单或取得登记；账单在先时，该行补齐资产的类别与条件，内容不符（科目、成本、日期或带有累计）则整体拒收。
另：带入时已有累计折旧的资产与已计提过的资产一样，条件只经估计变更修改；取得的资产所在"地点"是实物位置，不作为总账的地点维度过账。

**验收标准**
- [x] FA-001 每月 2,000.00；FA-002 1 月 1,666.67，6 月补足年度为 1,666.65，第 2 年 14,285.71；FA-003 1 月 333.33、全寿命恰为 12,000.00；余额递减转直线、
      150%、残值、三种惯例；FA-001 自 2 月延长 12 个月为 1,489.36；属性测试：任意成本、残值、年限、方法、惯例下全寿命合计 = 成本 − 残值，自任一月份重新起算合计不变
      （FA-003、FA-004、FA-006 的计算，`DepreciationTest`）。
- [x] 类别：成本科目须为 `FA_COST` 且只属一个类别、累计为 `FA_ACCUM`、费用为非控制的费用科目；处置损益科目须为损益类非控制科目；Accountant 不能维护（FA-001，`AssetIT`）。
- [x] 样例登记簿：成本或累计与期初分录不符整体拒收；带入 FA-001、FA-002（累计 48,000.00、10,000.00，自 2026-01 起算）；FA-003 留给账单；只能带入一次（DI，`AssetIT`）；
      账单在先时补齐 FA-003 的类别与条件，内容不符的行整体拒收（`AssetOpeningAfterBillsIT`）。
- [x] BILL-TS-5520 生成的 FA-003 属 Computer equipment：36 月、直线、全月（FA-001 验收 1）；门槛以下的资本化账单行使过账被拒；没有类别的资产稍后归类并取类别缺省值；
      取得 45,000.00 借 1500 贷 1010（来源 FA）；控制科目、门槛以下、未知类别与无权限者被拒（`AssetIT`）。
- [x] 代码审查的修正：登记簿中的方法名称可用旧系统的写法（Straight-line、Double declining）；切换日后投入的行按成本科目、成本与投入日对应已登记的资产，
      不按编号（编号由序列按账单先后给出），几项相同时须编号一致；尚未登记的行只能是类别缺省条件；寿命在切换日前已结束而未提足的资产拒收；
      有资产的类别不能改成本与累计折旧科目；停用类别的科目上资本化的账单被拒；只认已过账的期初分录（`AssetIT`、`AssetOpeningAfterBillsIT`）。
- [x] 资产表只追加版本；`./gradlew :finance:check` 通过。

已知限制：折旧运行、估计变更、处置、工作量随 F6b；报表与页面随 F6c。资产编号序列的起始号（`finance.fa.asset-numbers-start`）须设在旧登记簿的编号之上，
否则之后的账单可能取到已带入的编号而过账失败；取得的资产不记总账交易号（由过账的来源单据找到）；`FIN_FA_ASSET_SAVE` 不能把部门、地点、保管人清空；
月中惯例下第 1 个资产年度按 11.5 个月计，余额递减的第 1 年相应少提，全寿命合计不变。

### F6b 折旧运行、估计变更、处置、工作量

**要求**
1. 实体（V18）：`FinDepreciationRun`（时态：月份、轮次、运行号 `DEP-2601`、过账日、合计、资产数、状态 POSTED / REVERSED）；只记一次的 `FinDepreciationLine`（每资产一行：
   金额、运行后累计、用量、运行前的"已计提至"）、`FinAssetChange`、`FinAssetDisposal`；可更正的 `FinAssetUsage`（资产 × 月唯一）；`FinAsset` 增加已用工作量。
2. `FIN_FA_DEPRECIATION_RUN`（`fin.fa.run`）：自切换日的次月起逐月连续；已运行的月份返回原运行、不过账；FA 子账或总账已结拒绝；有未分类资产或缺用量的工作量法资产整体拒收并列出；
   一张凭证（来源 FA，月末日）按（费用、累计、部门）汇总；资产写累计、已计提至，提足者转 FULLY_DEPRECIATED。计划由 `fa/AssetPlans` 给出（D5、D7、D10）。
3. `FIN_FA_DEPRECIATION_REVERSE`（`fin.fa.run`）：只冲回最近一次且期间未结；冲回分录、资产复原；之后同月以下一轮（`DEP-2601-2`）重跑（D7）。
4. `FIN_FA_CHANGE_ESTIMATE`（`fin.fa.maintain`）：新年限或残值自资产下一个未计提的月份起，以当时的累计重新起算；残值不得使可折旧额低于已提累计；新寿命须延续到起算月（D6）。
5. `FIN_FA_DISPOSE`（`fin.fa.maintain`）：出售、报废、核销；处置月之前的月份须已运行，处置月未运行时按惯例补提（全月 0、月中一半、次月全月）；
   借累计、借收款科目（银行或非控制科目），贷成本，差额记资产设置的损益科目（D8）。`FIN_FA_USAGE_RECORD`（`fin.fa.run`）：该月运行前可更正（D10）。

计划变更：冲回之后资产若已有估计变更（起算月晚于该月）或处置（日期不早于该月月初），该运行不能再冲回；一个月没有应提的折旧时仍记下运行（合计 0）、不过账，下月照常连续。
处置月已运行时不调整该月已提的折旧（D8"该月已运行则不提"）。运行的过账号经 `FinPosting` 的单据号找到，不另存在运行上（同转账）。

**验收标准**
- [x] 1 月运行：DEP-2601 = FIN-EXP-11 的 6700 4,000.00 / 1590 (4,000.00)，明细 FA-001 2,000.00、FA-002 1,666.67、FA-003 333.33；再运行不过账；
      2 月先于 1 月、FA 子账已结的月份被拒；付款员无权运行（FA-005，`DepreciationIT`）。
- [x] 冲回最近一次：分录净额为零、资产恢复为带入时的累计；重跑为 DEP-2601-2（`DepreciationIT`）。
- [x] FA-001 自 2026-02 延长 12 个月：下月 1,489.36、1 月不变；同一起算月不能再改；之后 1 月的运行不能冲回（FA-006）。
- [x] FA-002 于 2026-01-31 以 60,000.00 出售：收益 1,666.67（借 1590 11,666.67、借 1010 60,000.00、贷 1510 70,000.00、贷 7400 1,666.67）；没有损益科目、没有收款、
      收款记入应付控制科目、会计员处置均被拒（FA-007）。
- [x] 工作量法：2 月缺用量整体拒收；用量可更正、运行后不能再改；150 / 1,000 单位 = 1,500.00；没有类别的资产（1530 上的账单）使运行整体拒收，归类后计入（DEP-2602 = 3,422.69）。
- [x] 报废 FA-003（3 月，全月惯例）：当月不提，损失 11,333.34；4 月处置在 3 月运行前被拒；运行、明细、变更、处置、用量、资产表只追加版本；`./gradlew :finance:check` 通过。

- [x] 代码审查的修正：资产与运行明细、变更、用量的数据视图写入批量放宽到 10,001（101 项资产的运行与冲回，原先超过 100 即被平台拒收）；
      处置月是否已运行按资产自己的"已计提至"判断，已运行月份之后才登记的资产处置时补足（1 月投入、2 月核销：补提 100.00）；
      工作量法资产处置月缺用量即拒收；运行只读所需资产的类别，变更超过读取上限即拒收而不截断（`DepreciationIT`）。

已知限制：一次运行至多 10,000 项资产；工作量法资产不能改总工作量（只能改残值）；不支持部分处置；投入日早于已运行月份的资产由下一次运行一次补足。

### F6c 报表、FIN-SCN-01 与 FIN-EXP-11、页面与端到端

**要求**
1. 报表（SQL 模板，D12）：`finance.fa.register`（某日的登记簿：成本、带入与已过账运行的累计、净值、当日状态；不含当日或以前处置的资产）、
   `finance.fa.roll_forward`（期间内按类别与科目：期初成本、增加、处置、期末成本；期初累计、本期折旧（运行与处置月）、处置转出、期末累计）、
   `finance.fa.depreciation_schedule`（已提折旧，按月按资产，含处置月）；预测为只读流程 `FIN_FA_SCHEDULE_PROJECT`（`fin.fa.read`），与运行同一计划（`fa/AssetPlans`）。
2. FIN-SCN-01 一个测试类：用户与角色无冲突，科目表、财年、审批规则，客户、供应商、税码、汇率、资产类别，不平衡的期初文件先被拒，期初余额与应收、应付、资产期初；
   试算表 = FIN-EXP-01，应收、应付、资产子账 = 控制科目，付款员看到遮蔽的 TIN；之后 1 月账单与折旧运行，按 FIN-EXP-11 逐项核对。
3. `finance-web`（`assets/`）：资产列表与资产页（已提折旧、未来 12 个月）、折旧运行页（运行下一个月、冲回最近一次）；菜单"Fixed assets"另含取得、三张报表、
   资产记录（通用页上的估计变更、处置、用量行操作）、类别、设置与登记簿导入。端到端 `assets.spec.ts`：每次运行后冲回，下一个运行月不随测试次数变化。

计划变更：资产页按 ID 读取资产（通用读取接口），不经列表筛选；登记簿报表从切换日起有效，切换日前某日的累计按带入值显示。

**验收标准**
- [x] FIN-SCN-01：FIN-EXP-01、三个子账与控制科目一致、不平衡文件被拒、无职责冲突、TIN 遮蔽（`FinScn01IT`）。
- [x] FIN-EXP-11：FA-001 / 002 / 003 的成本、1 月折旧 2,000.00 / 1,666.67 / 333.33、累计与净值逐项一致；DEP-2601 = 6700 / 1590 4,000.00；
      滚动表成本 190,000.00 → 202,000.00（增加 12,000.00）、累计 58,000.00 → 62,000.00（FA-009 验收 1）；FA-003 预测 35 个月、合计恰为 12,000.00（`FinScn01IT`）。
- [x] 1–3 月含变更、出售、报废、核销与冲回后，登记簿与滚动表期末都等于 1500 / 1510 / 1520 / 1530 / 1590；处置月折旧出现在折旧表中（`DepreciationIT`）。
- [x] 页面：运行结果、只冲回最近一次且须填原因、无权限者只读；资产页三部分与处置后无预测（Vitest 7 项）；`assets.spec.ts` 与全部 7 个端到端连续两次通过。
- [x] 代码审查的修正：资产已计提或已处置时账单不能作废（`FIN_BILL_ASSET_DEPRECIATED`）；作废账单的资产在登记簿中保留到作废日，滚动表把作废列为处置
      （3 月 5 日入账、20 日作废的柜子：3 月 10 日登记簿 1530 = 总账 6,200.00，处置合计 88,200.00）；滚动表期末之后过账的折旧不计入处置转出，资产期末为零；
      折旧表的处置行带类别；预测从下一次运行的月份起，首月补足此前未运行的月份（1 月投入、2 月后才登记的打印机：3 月 300.00、4 月 100.00），寿命已过时给出一行补足；
      页面上已处置与作废资产不显示净值、作废资产标注"Voided with its bill"、冲回后清除运行结果；端到端在前后冲回遗留的运行，始终从 1 月运行（`DepreciationIT`、`BillIT`）。
- [x] `./gradlew :finance:check`、`pnpm ext:check`、`platformCheck` 通过。

已知限制：页面上不直接做估计变更、处置与用量录入（用资产记录通用页的行操作）；预测不含工作量法资产；F6 的 FA-008、FA-010 留到最后（D11）。
平台问题（不在应用分支修改）：测试中平台的链路追踪（OpenTelemetry `BatchSpanProcessor` 入队时的锁）偶尔被 BlockHound 判为阻塞，使某个测试类的首个请求返回 500，
其后的用例连带失败（本地与 CI 各见过，如 `AssetOpeningAfterBillsIT`）。财务的测试配置因此不采样链路（`management.tracing.sampling.probability=0.0`：
未采样的 span 不进入该队列），财务测试不检查链路；平台的根本修正（在 `JabizBlockHoundIntegration` 中放行）留给平台分支。

## F7 多币种

需求：FIN-FX-003…005、007，FX-006（Should）；FX-001、002 已在 F1 完成。主验收 FIN-EXP-12（INV-1005 EUR 50,000.00 @1.0850 = 54,250.00；1 月末重估 FXR-2601 未实现收益 350.00、
2 月 1 日 FXR-2601-R 冲回；2 月 20 日收款 @1.0800 已实现损失 250.00 记 7200）、FIN-EXP-17 中的汇兑项目，FIN-SCN-09 完整通过。
计划已确认（2026-10-02，接受全部推荐）：

1. 汇率取单据日的即期（SPOT）汇率；当日没有时取此前至多 5 天内最近的一天（天数在设置中），仍没有即拒绝；可手工给出汇率（D1）。
2. 设置 `FinFxSettings`（Controller）：已实现损益科目 7200、未实现损益科目 7210、重估汇率类型（缺省 CLOSING）、容许天数（缺省 5）；科目须为损益类非控制科目（D2）。
3. 外币收款：按收款汇率折美元；每次核销的已实现损益 = 收款侧美元 − 发票账面美元，记 7200；最后一次核销恰好结清发票的美元；不同汇率的贷项通知单核销同样记损益（D3）。
4. 外币账单与付款：只用电汇与手工付款（WIRE / MANUAL），ACH 与支票只付美元（D4）。
5. 外币日记账行：只能记入非控制科目（控制科目只由子账过账），每个外币借贷各自相等（D5）。
6. 期末重估 `FIN_FX_REVALUE`：开放的外币应收、应付（与外币银行）按期末汇率重估，过账 `FXR-2601`，次月首日自动冲回 `FXR-2601-R`；同月再运行返回原运行，已结期间拒绝（D6）。
7. 重估可模拟（用当前汇率算出与原运行的差额，不写入）；原运行可原样重现（D7）。
8. 账龄显示交易币种与美元（重估后）；报表 `finance.fx.gains_losses` 按单据列已实现与未实现损益（D8）。
9. 外币银行账户（FX-006，Should）：只做余额重估，对账单仍按账户币种（D9）。
10. FIN-EXP-17 只核对其中的汇兑项目，其余随 F8–F9（D10）。
11. 分 PR（D11，见下方计划变更）。

权限：`fin.fx.run`（Accountant、Controller：重估运行与模拟）、`fin.fx.settings`（Controller）。

计划变更（F7a 实施中）：账单、付款与应付核销目前没有美元列，外币应付牵涉付款批、付款文件、1099 与应付账龄，单独成为 F7b；
F7a = 设置、取汇率、应收结算（收款、贷项核销）与日记账外币行；F7c = 重估、模拟与重现、外币账龄与损益报表、外币银行、FIN-SCN-09；页面与端到端在最后一个 PR。

### F7a 设置、取汇率、应收结算与日记账外币行

**要求**
1. 实体与迁移（V19）：时态的 `FinFxSettings`；`FinReceipt` 增加 `exchangeRate`、`amountUsd`、`unappliedAmountUsd`，`FinApplication` 增加 `sourceAmountUsd`、`fxGainLoss`，
   日记账行增加 `currency`、`foreignAmount`、`exchangeRate`（都只由流程写）。已有的美元收款与核销是只追加的行，不回填：读取时汇率空为 1、美元金额空为交易金额。
2. 取汇率 `fx/FxRates`：单据日或此前容许天数内最近的 SPOT 汇率，缺少即 422 `FIN_FX_NO_RATE`（说明容许天数）；发票过账改用它（D1）。
3. `FIN_FX_SETTINGS_SET`（`fin.fx.settings`）：科目须为损益类非控制科目（`FIN_FX_SETTINGS_ACCOUNT`）、汇率类型为 SPOT / CLOSING / AVERAGE；需要记损益而没有设置时 422 `FIN_FX_NO_SETTINGS`。
4. 收款（D3）：币种取自客户，汇率为所给或收款日的；银行借美元 = 外币 × 收款汇率；每笔核销按发票汇率折出发票侧美元，结清发票的那次取发票剩余的美元，
   差额记 7200（收益贷、损失借）；未核销部分按收款汇率挂账，日后核销以收款汇率计收款侧；冲回核销连同损益一起冲回。外币折扣不支持（只美元）。
5. 贷项通知单核销：贷项与发票汇率不同即记已实现损益（原先拒绝）；冲回同样冲回损益。
6. 日记账外币行（D5）：输入行可给 `currency` 与 `exchangeRate`，按所给或过账日的汇率折美元；外币行不能记入控制科目（例外也不行）；每个外币借贷须相等
   （`FIN_JOURNAL_UNBALANCED_IN_CURRENCY`，账本同样要求）；冲回照原样带外币；账本分录记币种、外币金额与汇率。
7. 权限 `fin.fx.run`、`fin.fx.settings` 授予计划所列角色。

**验收标准**
- [x] INV-1005 EUR 50,000.00 于 2026-01-12：汇率 1.0850、美元 54,250.00；1 月 14 日取 12 日的汇率；1 月 20 日超出 5 天被拒（FX-003 验收 1，`FxReceivablesIT`）。
- [x] 2 月 20 日收款 EUR 50,000.00 @1.0800：银行 54,000.00、已实现损失 250.00 记 7200（FX-004 验收 1，FIN-EXP-12 的结算部分，`FxReceivablesIT`）。
- [x] 部分收款的收益 28.00、90.00，最后一次恰好结清发票美元；未核销收款日后按收款汇率核销（损失 12.00）并冲回；贷项通知单 @1.0800 核销 @1.0920 的发票损失 12.00 并冲回；
      过程中应收账龄美元合计始终等于 1200；美元客户与外币折扣被拒（`FxReceivablesIT`）。
- [x] 设置：科目须为损益类非控制科目、汇率类型有效、Clerk 无权；没有设置时需要记损益的结算被拒（`FxReceivablesIT`）。
- [x] 日记账：EUR 1,000.00 @1.0850 = 1,085.00，行与账本分录都带 EUR、外币金额与汇率；冲回照原样；所给汇率照用；外币不平衡、无汇率、控制科目（有例外也）被拒
      （`FxReceivablesIT`、`JournalValidatorTest`）。
- [x] 代码审查的修正：作废外币收款按美元冲回银行与未核销（`unappliedAmountUsd` 归零）；日记账外币行的币种须存在、金额不超过该币种小数位；
      导入与子账过账只收美元行（`FIN_JOURNAL_FOREIGN_NOT_HERE`，不再静默当作美元）；改期的冲回草稿沿用原行汇率（新日期没有汇率也可）（`FxReceivablesIT`）。
- [x] `./gradlew :finance:check`、`platformCheck` 通过。

已知限制：外币收款的退款、外币核销（应收核销）不支持（拒绝并说明以收款或贷项结清）；折扣只限美元；外币应付随 F7b；重估、报表、外币银行、FIN-SCN-09 随 F7c；页面随最后一个 PR。

## F8 — F11

范围、需求编号与验收口径见 `docs/finance-work/00-development-plan.md` §5.2；每个阶段开始时把详细要求与验收标准写入本节。
