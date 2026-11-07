# 首次上线的开发安排（3 个会话）与平台基础的实施计划

**状态**：2026-11-07 需求方确认（分工、顺序、平台基础的计划、D73 的规则）；**规格写清楚的项目一次授权：写好计划即开工**。规格见 `docs/quizbuks/first-launch-spec.md`（以下"F-xx""P-x"均指它）。本文件是开发的总安排：谁做什么、按什么顺序、怎样不互相冲突，以及第一批（平台基础）的实施计划。

## 1. 三个会话

| 会话 | 名称 | 分支 | 范围（按顺序） |
|---|---|---|---|
| **S1** | 平台 | `1.3/platform` 的工作分支 `1.3/phase-17z?-<名>` | P0、P8 → P1 → P7 → P3 → P4（先原型）→ P2 → P5 → P6；每个平台 PR 合入后，开一个 `1.3/platform` → `1.3/quizbuks` 的合并 PR |
| **S2** | 结构与钱 | `1.3/quizbuks-<编号>-<名>` | F-S1 运营商化 → F-S2 知识结构图 T1a → F-S6、F-S7 题库与抽题、乱序 → F-M1～F-M5 账户、赠送、提交检查 → F-M6～F-M10 各项费用 → F-M11～F-M15 归属、结算、账单、月报与核对、注销 → F-S3、F-S4、F-S5 结构图界面、后台点亮、节点证书（等 P3） |
| **S3** | 内容与界面 | `1.3/quizbuks-<编号>-<名>` | F-F7（合并扫码修复）、F-F8、F-F9 → **F-F5 全产品改名（尽早）** → F-C1～F-C3 资源库与计量 → F-S8、F-S9 屏蔽与主题词 → F-C11 资料包 → F-S10、F-S11 短视频与境外视频（等 P5）→ F-S12～F-S14 视频共享、推送与台账（与 S2 的 F-M8 对齐）→ F-C4 发起方包（等 F-S2）→ F-C5～F-C7 从官网生成（等 P6、F-M9）→ F-C8、F-C9 邮件模版接入（等 P2）→ F-C10 AI 问题解答 → F-F3、F-F4 后台与 App 迁到新框架（等 P3、P4）|

最后由一个会话（建议 S3）做 F-F10～F-F14：资金开关、样板发起方的支持、闭环 e2e、CI 与运维、上线文档与上线准备清单。

**为什么这样分**：平台必须先行（根 CLAUDE.md 第 8 节），单独一个会话；钱与结构互相依赖最多（运营商、知识结构图的点亮赠送、各项费用），放在同一个会话；内容与界面大多独立，并且改名要尽早做完，免得别的会话不断加旧词。

## 2. 共同的规则（三个会话都照此）

1. **只依照规格**：`docs/quizbuks/first-launch-spec.md`。规格写不清楚的，先问需求方，答案写回规格再写代码。
2. **每一项先写实施计划，写好即开工**（需求方 2026-11-07 对规格写清楚的项目一次授权）：计划（要改的模块和类、表与迁移、测试清单、风险）写在该项 PR 的第一个提交（`docs/quizbuks/plans/impl/<项目>.md`）与 PR 描述中，需求方事后看 PR。规格没写清楚、或计划要偏离规格的，先问需求方，答案写回规格再写代码。平台基础的计划即本文件第 4 部分。
3. **闭合、完整**：规格中每一项的"运营闭合"与"验收"全部做到才算完成；没有测试的不算完成。
4. **平台先行**：平台缺的能力只由 S1 在 `1.3/platform` 上做；S2、S3 发现平台缺东西，告诉需求方，由 S1 排进去，不在应用里绕过。
5. **迁移编号**：应用的迁移（`db/migration/V<n>__quizbuks_*.sql`）在**合并前**按 `1.3/quizbuks` 当时的最大号 + 1 重新编号（Flyway 不允许乱序；staging 由 `1.3/quizbuks` 部署）。平台的迁移（`db/jabiz`）只有 S1 加。
6. **常有冲突的文件**（三语消息、`application.yml`、`QbParams`、`QbRoles`、`QbLedger`、`qb.admin.monthly`、两个 SPA 的 `src/qb/`）：经常把 `1.3/quizbuks` 合并进自己的分支，冲突时两边都保留；只合并，不变基。
7. **PR 与 CI**：每项一个 PR，CI 绿才合并；能在本机跑的 e2e 先跑（`tools/quizbuks/e2e.sh`），跑不了的在 PR 中写明。PR 描述逐条对照规格的"验收"。
8. **进度**：每个会话在 `docs/quizbuks/first-launch-progress.md` 中维护自己那几行（项目、状态、PR、未完成），只改自己的行。
9. **称呼**：新写的界面文字一律用 学者 / テイカー / Taker、发起方 / 主催者 / Sponsor。

## 3. 会话之间的依赖与汇合点

| 等待方 | 等什么 | 来自 |
|---|---|---|
| S2、S3 的所有新页面 | P3 框架组件（在此之前按现有页面写，P3 合入后迁移） | S1 |
| S3 的 F-F3、F-F4 | P3、P4 | S1 |
| S3 的 F-S11 | P5 `ClientCountry` | S1 |
| S3 的 F-C5～F-C7 | P6 链接图片下载；F-M9 AI 额度 | S1、S2 |
| S3 的 F-C8、F-C9 | P2 邮件模版 | S1 |
| S3 的 F-S12～F-S14 | F-M8 曝光费（收费一侧） | S2 |
| S3 的 F-C4 | F-S2 知识结构图（发起方包第 2 版带图） | S2 |
| S2 的 F-S3、F-S4 | P3 | S1 |
| 所有 PR 的 e2e 稳定 | P1 刷新令牌宽限 | S1 |

S1 在每个平台 PR 合入 `1.3/platform` 后，立即开 `1.3/platform` → `1.3/quizbuks` 的合并 PR（CI 绿即合并），并在进度文件中注明"P-x 已到 1.3/quizbuks"。

## 4. 平台基础的实施计划（S1）

### P0 核对待评审的平台阶段（0.5 天）
- 核对 `docs/ROADMAP.md` 中 17zc、17zf-b、17zg、17ze、17y-7、17z-6、17zb-2、17zd 的状态与 `1.3/platform` 上是否已合入；改正状态。列出与首次上线有关、尚未合入的。
- 不写代码。

### P8 平台 CI 的 Playwright 作业固定 Ubuntu 24.04（0.5 天）
- `.github/workflows/ci.yml` 中装 Playwright 的作业 `runs-on: ubuntu-24.04`（`quizbuks.yml` 已改，`e490cc2d`）。
- 验收：平台 CI 绿。

### P1 刷新令牌的重用宽限（17zi，决策 D73，1.5–2 天）
- **决策 D73**（写进 `docs/design/09-decisions.md`，取代 D12 第 1 条"旧令牌再出现即吊销"的一部分）：被换下的刷新令牌在 `jabiz.security.refresh.reuse-grace`（缺省 30 秒，上限 120 秒，启动检查）内再次出现、且它换出的后继**还没有用过**、且是同一入口时，**补发一个新的兄弟令牌**（同一会话族），每个被换下的令牌至多补发一次；超过时间、后继已用过、已补发过或入口不同，照旧吊销整个会话。
- **改动**：
  - `runtime/security/RefreshTokenStore`：`inTransaction` 的重用分支先判断能否补发（读该令牌的使用时刻与其后继是否用过），能则在新事务中补发，不能则照旧 `revokeSession`。核对 `issue` 是否记下父令牌；没有则给令牌表加列 `parent_hash`（只追加表只加列，`SealedColumnsHistory`）。
  - 新的只追加表 `sec_refresh_token_regrant`（主键 = 被换下的令牌的哈希），主键保证至多补发一次；登记完整性封存（启动检查 `INTEGRITY`）与抹除范围（`ErasurePurger`、`ErasureIT`）。
  - 登录记录：补发记一条（原因 `REUSE_GRACE`），告警日志降为 INFO；吊销照旧 WARN。
  - `frontend/packages/client/src/api/session.ts`：续期拿到新令牌后先写入存储、再用；"复制标签页"的情形由服务端的补发兜住。
  - 文档：`docs/design/10-security.md` 的刷新令牌一节；`docs/ROADMAP.md` 17zi。
- **测试**：`RefreshTokenStoreIT`：宽限内后继未用 → 补发且会话仍有效；再补发第二次 → 吊销；后继已用 → 吊销；超时 → 吊销；不同入口 → 吊销；时态表没有 UPDATE / DELETE。前端 Vitest：先存后用。e2e：页面在续期中刷新不被登出（构造慢的续期）。
- **风险**：宽限内被盗的令牌可以换得一个令牌；以较短的上限与"后继未用"限制，且记录在登录记录中。

### P7 受控变更列表显示参数名（0.5 天）
- 通用后台 `SysControlChange` 的列表显示 `paramKey` 并可按它筛选（数据视图的显示字段与筛选）；测试：前端列表测试、axe。

### P3 应用框架组件（17zj，4–5 天）
- **`@jabiz/ui` 新增与修改**：
  - `AppShell`：侧栏可收成图标窄条（记在本机）、内容最大宽度（主题 token `--content-max`、表单 `--content-form-max`）、手机底部菜单（`BottomBar`，4 项 + "更多"抽屉）与浮动主按钮（`Fab`）。
  - `ShellNav`：区域（一级）与徽标；"更多"中的完整菜单。
  - `PageHeader`：标题前的图标、**主按钮紧跟标题**、说明一行、`PageTabs`（页内标签，可横向滚动，`aria-current`）。
  - 全局"新建"菜单 `GlobalCreate`（应用给出条目）。
  - 三种页面模板：`ListPage`（工具栏：搜索、筛选小标签、卡片 / 表格切换，复用 17zc 的卡片模式）、`DetailPage`（右侧固定的状态与下一步栏，手机上在内容之前）、`FormPage`（单栏限宽、底部固定的保存条与"未保存"提示）。
- **示范**：平台示范 `app` 的门户与通用后台各用一处（不改通用后台的整体布局，只加可选能力）。
- **文档**：`docs/design/12-frontend.md` 增节"应用框架与页面模板"；`docs/ROADMAP.md` 17zj。
- **测试**：每个组件的 Vitest（含键盘操作）与 `expectAccessible` 亮暗两种外观；手机宽度的快照；通用后台的 e2e 不变。
- **风险**：现有应用页面的样式回归；新能力都是可选的，不改缺省布局。

### P4 入口页模板（17zk，2–3 天；先原型）
- **先做原型**给需求方看（与 Q108 的原型同一种做法），确认后实现。
- `SignInForm` / `SignUpForm` 合为一页、以标签切换；大屏两栏（左边品牌与一句话，由应用给出）、手机上下；快捷方式（通行密钥、Google、Facebook、LINE、邮件链接；Apple 只在 profile `apple` 打开时出现，首次上线不显示）在前、密码在后；"我是另一方"的入口链接；`MailLinkPage` 各页用同一模板。
- 不改认证流程与接口（只改界面）。
- **测试**：现有认证积木的测试全部通过；新布局的 Vitest 与 axe；平台 e2e 的登录用例。

### P2 邮件文字与信纸的在线定制（17zh-a/b/c，决策 D72，6–8 天）
- 照 Q107 与规格 F-C8、F-C9：`MailText`、`MailLayout`（只追加，带版本）、`MailTextRules`（core）、`MAIL_TEXT_*` / `MAIL_LAYOUT_*` 流程、受控变更目标 `mail-text` / `mail-layout`（安全类与信纸都须另一位管理员发布）、`DeliverMail` 取生效的文字与信纸并记版本、预览、测试发送（已渲染内容的投递，不生成令牌）、标题长度改为配置；**待办通知与单据邮件改为经同一处渲染**；通用后台两页（列表、编辑、预览、测试发送、恢复默认、历史与版本对比）。
- 链接规则写进 `MailTextRules`：令牌、登录链接、退订链接只能出现在以 `{entryUrl…}` 或 `{baseUrl}` 开头的链接目标中，标题中不能有；其他链接只许本站与 `jabiz.mail.texts.allowed-link-hosts`；正文禁止图片。
- 分三个 PR：a 文字与规则、b 信纸、c 后台页面。
- **测试**：见 Q107 §10 与规格 F-C8、F-C9 的验收；`StatementBudgetIT` 不变。

### P5 按 IP 的国家 `ClientCountry`（17zl，决策 D74，2–3 天）
- 地址库：本地文件（`jabiz.geoip.database`），DB-IP 的 IP to Country Lite（CSV，CC BY 4.0，每月更新；需求方 2026-11-07 确认）；IPv4 与 IPv6；查不到返回"未知"。
- `ClientCountry`：由 `ClientAddresses` 的地址查国家；流程中以平台步骤在锁外取得（0 条新语句）；结果不保存、不进 `input_summary`、不进观测标签。
- 不缓存的接口 `GET /api/client/region`（只说是否中国大陆，或给出国家代码；`Cache-Control: no-store`）。
- 启动检查：地址库缺失或超过一定天数未更新即告警（非 dev 缺失即报错）。
- 文档：`operations.md` 的地址库更新与许可署名；隐私政策的一句（应用侧）。
- **测试**：单元测试（v4、v6、未知、内网）；IT（可信代理之后的地址、接口不缓存）；平台示范的一例。

### P6 链接图片的服务器下载（决策 D75，2–3 天）
- 只限 https；每一跳都必须是公网地址，按解析出的地址连接（防 DNS 重绑定）；至多 3 跳（参数）；只收图片（按内容判定）；大小与时限上限（参数）；不带凭据与 Cookie；结果走上传的同一条流水线（重新编码、去掉 EXIF），以请求人登记。
- 平台步骤或流程（供 Q104 的资料包与 Q100 使用）；`ExternalService` 的声明不适用于任意主机，按 D75 单独的安全规则实现。
- **测试**：私网地址、重定向到私网、DNS 重绑定、非图片、超大、超时、超过跳数；本机桩。

### 平台基础的合计与顺序
- 合计约 **20–26 天**（P2 6–8、P3 4–5、P4 2–3、P1 1.5–2、P5 2–3、P6 2–3、P0/P7/P8 1.5）。
- 顺序：P0、P8（当天）→ P1（解除 e2e 偶发失败）→ P7 → P3 → P4 原型 → P2 → P5 → P4 实现 → P6。

## 5. 给三个会话的开工提示词

需求方新开会话时，把对应的一段原样贴入（仓库与分支照会话的设置）。

**S1 平台**
```
你负责 QuizBuks 首次上线的平台工作（会话 S1）。先读：根目录 CLAUDE.md、backend/quizbuks/CLAUDE.md、docs/quizbuks/first-launch-spec.md（尤其"平台工作总表"）、docs/quizbuks/first-launch-dev-plan.md（第 2、3、4 部分）。
范围与顺序：第 4 部分的 P0、P8 → P1 → P7 → P3 → P4（先做原型给需求方看）→ P2 → P5 → P4 实现 → P6。平台工作只在 1.3/platform 的工作分支上做（分支名 1.3/phase-17z?-<名>，编号取 ROADMAP 中的空号），每项一个 PR 合入 1.3/platform；合入后立即开 1.3/platform → 1.3/quizbuks 的合并 PR。新的平台决策（D72–D75）写进 docs/design/09-decisions.md，并在 PR 中列出。第 4 部分即已确认的实施计划，写好各项的细化即开工（需求方已一次授权）；偏离时先告诉需求方。每完成一项，更新 docs/quizbuks/first-launch-progress.md 中 S1 的行。
```

**S2 结构与钱**
```
你负责 QuizBuks 首次上线的结构与钱（会话 S2），分支 1.3/quizbuks。先读：根目录 CLAUDE.md、backend/quizbuks/CLAUDE.md、docs/quizbuks/first-launch-spec.md（F-S1～F-S7、F-M1～F-M15）、docs/quizbuks/first-launch-dev-plan.md（第 2、3 部分）。
范围与顺序：F-S1 → F-S2 → F-S6、F-S7 → F-M1～F-M5 → F-M6～F-M10 → F-M11～F-M15 → F-S3、F-S4、F-S5（等平台的 P3 框架合入）。每一项先写实施计划（要改的类、表与迁移、测试、风险；写进 docs/quizbuks/plans/impl/<项目>.md 与 PR 描述），写好即开工（需求方已对规格写清楚的项目一次授权）；规格没写清楚或要偏离规格的，先问需求方；工作分支 1.3/quizbuks-<编号>-<名>，每项一个 PR，CI 绿才合并。平台缺能力时告诉需求方（由 S1 做），不在应用中绕过。迁移编号在合并前按 1.3/quizbuks 的最大号 + 1 重新编号。答题路径的语句数以 AttemptStatementBudgetIT 为准。每完成一项，更新 docs/quizbuks/first-launch-progress.md 中 S2 的行。
```

**S3 内容与界面**
```
你负责 QuizBuks 首次上线的内容与界面（会话 S3），分支 1.3/quizbuks。先读：根目录 CLAUDE.md、backend/quizbuks/CLAUDE.md、docs/quizbuks/first-launch-spec.md（F-C1～F-C11、F-S8～F-S14、F-F3～F-F14）、docs/quizbuks/first-launch-dev-plan.md（第 2、3 部分）。
范围与顺序：F-F7（合并扫码修复 PR 458）、F-F8、F-F9 → F-F5 全产品改名（尽早，一次做完）→ F-C1～F-C3 → F-S8、F-S9 → F-C11 → F-S10、F-S11（等 P5）→ F-S12～F-S14（与 S2 的 F-M8 对齐）→ F-C4（等 F-S2）→ F-C5～F-C7（等 P6 与 F-M9）→ F-C8、F-C9（等 P2）→ F-C10 → F-F3、F-F4（等 P3、P4）→ 最后 F-F10～F-F14 与上线准备清单。每一项先写实施计划（写进 docs/quizbuks/plans/impl/<项目>.md 与 PR 描述），写好即开工（需求方已对规格写清楚的项目一次授权）；规格没写清楚或要偏离规格的，先问需求方；工作分支 1.3/quizbuks-<编号>-<名>，每项一个 PR，CI 绿才合并。平台缺能力时告诉需求方（由 S1 做）。迁移编号在合并前按 1.3/quizbuks 的最大号 + 1 重新编号。每完成一项，更新 docs/quizbuks/first-launch-progress.md 中 S3 的行。
```

## 6. 已确认（2026-11-07）

分工与顺序、平台基础的实施计划（含 D73：30 秒、上限 120 秒、补发一次）、规格写清楚的项目一次授权（写好计划即开工，需求方事后看 PR）。
