# 初始化路径的界面盘点：哪里要人填编号、认不出名字（init-ux-audit）

起因：需求方在彩排系统做上线初始化时反映"界面都不友好，用的都是 ID，非常难操作"。本文只读盘点，不改代码：按 `operations.md` §3（首次启动第 2–9 步）、§4、§7.13 与 `launch-checklist.md` 第 2 部分，列出需求方要亲手用的每个页面或流程表单，指出哪里要求输入或只显示 UUID、内部编号、权限码、参数键，给出文件位置、修法与归属。

**核实方式与限度**：读代码、元数据与消息资源（没有起应用）。凡写"未核实"的是读代码时没有证据、需要起应用确认的。文件位置的前缀：`F` = `frontend/src`，`R` = `backend/runtime/src/main/java/com/jabiz/runtime`，`A` = `backend/quizbuks/admin-extension/src`，`J` = `backend/quizbuks/src/main/java/com/jabiz/quizbuks`。

**归属**：**平台**＝要在 `1.3/platform` 改（通用页与通用流程表单；应用分支不改平台目录，CLAUDE.md §8）；**应用**＝QuizBuks 自己能做（admin-extension 页面、`actsOn` 行操作、`eb.display`、消息资源）。
**优先级**：**P0**＝初始化路径上必经、且现在要手填编号或看不懂；**P1**＝路径上会遇到、但有变通；**P2**＝不在路径上或只是影响观感。

## 1. 需求方要用到的页面与流程（按初始化顺序）

| 步骤（出处） | 谁 | 页面 / 流程 | 要填或要看的 |
|---|---|---|---|
| 首个管理员开二次验证（§3.2） | 首个管理员 | 平台管理系统"安全设置" | 无编号 |
| 执行 `QB_SETUP`（§3.3） | 超级管理员 | 流程页 `QB_SETUP` | 无输入（`J/setup/QbSetupProcesses.java:65` 为空 record） |
| 建财务与内容管理员（§3.4） | 首个管理员 | `SEC_USER_CREATE`（勾邀请，不填密码）→ `SEC_USER_ROLE_ASSIGN`；邮件没到时 `SEC_USER_INVITE` | 用户名、邮箱；**用户、角色（编号）** |
| 发布第一批提案（§3.5） | 财务管理员 | "数据 → SysControlChange"，行操作 `CONTROL_CHANGE_PUBLISH` | 选一行；列表上的**参数键、提出人** |
| 建两位超级管理员（§3.5、清单 2.3） | 首个管理员、第一位超级管理员 | `SEC_USER_CREATE` + `SEC_USER_ROLE_ASSIGN`（受保护，要另一位审批） | **用户、角色（编号）** |
| 批准受保护的授权（§3、清单 2.3） | 另一位超级管理员 | 平台管理系统"待办"（`APPROVAL_DECIDE`） | 看待办标题，批准或拒绝 |
| 默认运营商、第一个运营商（§3.5、§7.13） | 超级管理员 | "运营商"页，`QB_OPERATOR_SAVE` | 名称、联系人；**负责人（用户编号）** |
| 第二位能审核的成员、签下一个运营商（§7.13） | 超级管理员、另一位审批 | 运营商页"添加成员"（`QB_OPERATOR_MEMBER_ADD`）；`SEC_USER_ROLE_ASSIGN` 授 `QB_OPERATOR`（受保护） | **用户编号（粘贴）**、角色 |
| 设受控参数（§3.6、清单 2.2、2.4、§4） | 超级管理员提出、财务发布 | `CONTROL_CHANGE_PROPOSE`（接口，表单未演练）→ `CONTROL_CHANGE_PUBLISH` | **目标实体、参数键、值（JSON）**；约 20 个参数，含 11 个科目映射键 |
| 首次上线的资金开关（§4） | 财务 | 财务总览卡片"首次上线的资金开关"、SysControlChange 的撤回与重提 | 卡片每行已是"说明 + 键"；重提 7 个键时要手写参数键与值 |
| 法律文本、帮助问答、邮件文字（§3.9、清单 2.5） | 内容管理员 | "法律文本（草稿）"、`/qb/legal`、"帮助中心问答"、"邮件模版" | 页面从行里带入编号，无手填 |
| 主题词、屏蔽、定时任务、招商网站（清单 2.6） | 内容管理员、超级管理员 | `/qb/topics` 等页面 | 无编号输入 |

## 2. 逐项发现

### P0（初始化路径必经）

| # | 位置 | 现状 | 修法 | 归属 |
|---|---|---|---|---|
| P0-1 | **通用流程表单的引用字段**：`F/meta/processForm.ts:55-77`（`inputKindOf` 只认 password、date-time、date、email，其余都是 text）；`F/components/SchemaForm.tsx:197-199`（默认是普通文本框） | `SEC_USER_ROLE_ASSIGN` 的 `userId`、`roleId`（`R/security/protect/ProtectedChangeProcesses.java:72`）、`SEC_USER_INVITE` 的 `userId`（`R/security/InvitationProcesses.java:66`）、`CONTROL_CHANGE_PUBLISH` 的 `changeId` 都是要手填 UUID 的文本框。只有从实体行点操作（`actsOn`，`F/components/RowActions.tsx:82`，`F/pages/ProcessFormPage.tsx:38-45` 预填）时才自动带入；`SEC_USER_ROLE_ASSIGN` 的 `roleId` 没有 `actsOn`（`actsOn` 只给 USER，`ProtectedChangeProcesses.java:331`），所以授角色时角色编号永远要手填 | 流程输入 record 的引用组件在元数据（JSON Schema 扩展）里声明所引用的实体，`SchemaForm` 据此渲染可搜索的选择器（已有 `F/components/ReferenceSelect.tsx`，现只给实体表单用，`EntityField.tsx:75-83`）：用户按姓名与邮箱、角色按名称搜索，显示名字、提交编号。同时给 `SEC_USER_ROLE_ASSIGN` 在 ROLE 上也声明 `actsOn`（从角色行"授给某人"）。OpenAPI 是否已带 uuid 格式或引用信息**未核实**（前端没有读取） | 平台 |
| P0-2 | **`SecUser`、`SecRole` 没有 `eb.display`**：全仓 `eb.display(` 只在 `R/approval/ApprovalEntities.java:85、123`（`ruleCode`）；`F/meta/references.ts:30-38` 的 `referenceSourceOf` 要求目标实体有显示字段，没有就退成纯编号输入 | 所有引用用户或角色的通用列表、详情与表单（用户角色分配列表、会话、历史）在没有 `display` 时只能显示编号；P0-1 的选择器也依赖它 | 给 `SecUser`（显示名、用户名）与 `SecRole`（三语名称）声明 `eb.display`；是 P0-1 的前提 | 平台 |
| P0-3 | **受控变更列表与详情**：`R/approval/ApprovalEntities.java:131-160`（`proposedBy`、`publishedBy` 是 `asText(100)`，不是引用）；`F/pages/DatasetListPage.tsx:136-137` 只对 `reference` 类型的字段做名字标签；没有 SysControlChange 的专用列表页 | 财务发布时看到的"提出人""发布人"是原始用户编号，认不出是谁，也就无法判断"是不是另一个人提出的"；变更的内容在 `changeValues`（JSON 文本）里，参数的说明不显示。`targetKey` 列已有，是人认得的键 | 提出人与发布人改为用户引用（`asReference(USER)`，注意只追加表的迁移规则）或在列表与详情用 `UserName`（`frontend/packages/client/src/components/UserName.tsx`）；详情页把 `changeValues` 解析成"参数 = 新值（旧值）"，键旁显示 `param.<键>` 的说明（P0-4） | 平台 |
| P0-4 | **参数键没有说明**：平台没有消费 `param.<键>` 消息（`F/`、`R/` 里 grep 没有命中；非完整证明）；应用的 `param.*` 三语各 215 条键集一致，但**缺 16 个 `qb.*` 参数**：`qb.ai.credit.{expired-held-after-hours, low-amount, low-recent-days, min-purchase}`、`qb.ai.fee.{enabled, markup, site-profile.calls, site-profile.output-tokens, site-profile.pages, unbilled-after-hours, usd-jpy}`、`qb.audience.refund.missing-after-hours`、`qb.content.import.batch-size`、`qb.content.max-questions`、`qb.play.max-questions-per-attempt`、`qb.reward.kudos.enabled`（`J/setup/QbParams.java`、`R/messages_*.properties`） | 需求方在受控变更页只看到键；其中 `qb.ai.fee.enabled`、`qb.reward.kudos.enabled` 就在首次上线的开关里 | ①应用：补齐这 16 条三语说明（并加一个测试：每个受控参数都有三语 `param.<键>`，防止再缺）；②平台：受控变更页与审批页读取并显示 `param.<键>` | 应用 + 平台 |
| P0-5 | **提出受控变更的表单**：`R/approval/ControlChanges.java:53`（`ProposeInput(targetEntity, targetId, delete, Map values, effectiveTime, reason)`）；`F/components/SchemaForm.tsx:191-194`（`Map` 落到 JSON 文本域） | 要提出的参数键与值只能手写 JSON，`targetId` 手填 UUID；运营文档（§3.6、清单 2.2）要求提出约 20 个参数（含 11 个科目映射键）、首次上线要重提 7 个键，全靠手写；operations 文档自己也写"表单未演练，以接口为准" | **应用能先做**：把已知的三类提案做成专用页面或行操作——①财务总览卡片"首次上线的资金开关"（`A/FundSwitches.tsx`）每行加"按首次上线的值提出"（预填键与值，只让人确认）；②科目映射页（`A/JournalMappingPage.tsx`，现为只读）每行加"修改"：科目按"编码 名称"选、键由页面带入；③收据开具方、转账阈值等参数一页（键与说明固定）。**平台后补**：通用提案表单按参数目录渲染（选参数→按类型显示输入→显示说明），不再手写 JSON | 应用先、平台后 |
| P0-6 | **运营商"添加成员"要粘贴用户编号**：`A/OperatorPage.tsx:257-262`（纯 `Input`），标签 `operator.members.userId`（`A/operator-messages.ts:73、285`），提示没有说编号从哪里取 | 同页的"改派"（`OperatorPage.tsx:360-363`）与新建运营商的负责人（`A/OperatorsPage.tsx:88-92`）在有 `usersRead` 权限时已用 `UserPicker`（`A/pickers.tsx:75`，按姓名、邮箱、公司搜索），成员添加是唯一漏掉的地方 | 改用 `UserPicker`；`QB_OPERATOR_MEMBER_ADD` 的 `operatorId`、`userId`（`J/operator/OperatorProcesses.java:99`）仍是裸字符串，加 UUID 格式校验 | 应用 |
| P0-7 | **批准受保护授权的页面**：平台管理系统"待办"（`F/pages/TasksPage.tsx:127-141`）只显示服务端给的 `task.title` 与 `ApprovalPanel`（`F/components/ApprovalPanel.tsx`：理由框与批准、拒绝按钮），不显示"准备人、给谁授什么角色" | 批准人看不到自己在批准什么，只能凭标题。标题的内容取决于 `ApprovalCase.reference`（服务端，**未逐项核实**每个受保护流程给的是名字还是编号） | 核实每个受保护流程（`SEC_USER_ROLE_ASSIGN`、`SEC_ROLE_PERMISSION_ADD`、`SEC_USER_IDENTITY_LINK`、`SEC_USER_SET_PASSWORD`、`SEC_MFA_RESET`、`SEC_USER_EMAIL_SET`）的 `reference` 为"给 <姓名> 授 <角色名>"；`ApprovalPanel` 显示申请内容的名字化摘要（用 `UserName`）与准备人。这是导入后仍必须手工的步骤之一（见第 3 节） | 平台 |

### P1（路径上会遇到，有变通）

| # | 位置 | 现状 | 修法 | 归属 |
|---|---|---|---|---|
| P1-1 | **没有 `usersRead` 时的运营商页**：`A/OperatorsPage.tsx:90`、`A/OperatorPage.tsx:363` 退回成粘贴编号的框 | 持 `qb.admin.operators.write` 却没有读用户权限的人（运营商的负责人）只能粘贴编号 | 提供只需运营商写权限的用户查找模板（按已验证邮箱精确查找，不暴露用户清单）；`UserPicker` 在这种情况下用它 | 应用 |
| P1-2 | **`SEC_ROLE_PERMISSION_ADD` 的 `permission`** 是自由文本（`ProtectedChangeProcesses.java:75`）；**权限码没有说明**：应用与平台消息里都没有 `permission.*`，`QbPermissions` 约 108 个码在角色权限页只能显示原码 | 给角色加权限要手写权限码 | 平台：流程输入声明"取自权限目录"，表单渲染可搜索的下拉；应用：补 `permission.<码>` 三语说明（并加覆盖测试）。角色本身已有三语名称（`J/…/QbRoles.java:232-274`） | 平台 + 应用 |
| P1-3 | **`SEC_USER_CREATE` 的 `tenantId`、`SEC_USER_INVITE` 的 `entry`** 是自由文本（`R/security/UserProcesses.java:47`，`InvitationProcesses.java:66`） | 要知道填什么 | 只有一个租户时隐藏并取缺省；`entry` 做成取自 `jabiz.security.entries` 的下拉 | 平台 |
| P1-4 | **`QB_SPONSOR_OPERATOR_ASSIGN`、成员添加没有 `actsOn`**；`QbOperatorMember`、`QbSponsorOperator` 没有 `eb.display`（`J/operator/QbOperators.java:150-190` 之间无）；全应用只有 11 处 `actsOn`，运营商只有 `OperatorPlans.java:147` | 引用它们的通用页显示编号；行操作靠自写页面 | 声明 `eb.display` 与 `actsOn` | 应用 |
| P1-5 | **`CONTROL_CHANGE_PUBLISH` 的前置核对**：发布前要确认"不是自己提出的"（职责分离），页面不提示 | 点了才被拒绝（422） | 行操作在提出人是当前用户时灰掉并说明原因（`proposedBy` 修好后 P0-3 才能做） | 平台 |

### P2（不在路径上或观感）

- `A/JournalMappingPage.tsx:60`：科目映射第三列是原样的参数键（`<code>`），没有说明。P0-5 的科目映射改页时一并给说明。应用。
- `entity.*` 缺 4 个：`QbEntryFeeSettlement`、`QbOperatorSettlementLine`、`QbSetupRecord`、`QbTreeNodeCount`（脚本对比，界面是否可见**未逐一确认**）。应用，加覆盖测试。
- 帮助问答（`QbFaqEntry`）没有专用维护流程，走通用实体页（`J/messaging/QbLegal.java:266` 有 `display("question")`）。不影响初始化，但新增时对象、排序要手填。应用，可后做。
- 字段消息 `entity.<实体>.<字段>` 的全应用缺口脚本不可靠，**未核实**；Shopify 的受控参数说明**未核实**。
- 已核实没有问题的：运营商列表显示运营商名（`OperatorsPage.tsx:141`）；成员行显示姓名（`OperatorPage.tsx:213`）；发起方行显示公司名；法律文本页没有编号输入、发布人与撤回人用 `UserName`（`A/LegalPage.tsx:207、216`）；法律与帮助手册的发布编号由页面从行里带入；财务总览"首次上线的资金开关"每行是"说明 + 键"（`A/FundSwitches.tsx:66-78`，36 个说明；键集与注册表是否逐个一致**未核实**）；`UserName` 已用在审计、历史、作业、单据、邮件文字的受控变更面板等处，但不在通用数据视图列表、通用受控变更与审批面板。

## 3. 哪些会被"初始化文件 + 一次导入"（F-INIT）绕开，哪些仍要手工

F-INIT 的设计由 S1 在做，此处按总控转述的范围推断，**待 S1 定稿后逐项核对**。

**导入可能整体绕开**（只要导入文件能给出名字、由平台解析成编号）：`SEC_USER_CREATE` 与邀请（P0-1 在这一步的表单）、不受保护的角色分配、运营商与成员（`QB_OPERATOR_SAVE`、`QB_OPERATOR_MEMBER_ADD`，P0-6）、科目映射与参数的**提出**（P0-5 的手写 JSON）、法律文本草稿、帮助问答。

**导入后仍要手工，所以必须首先友好**（按平台规则不能由导入代做）：
1. **批准受保护的授权**（超级管理员、`QB_OPERATOR` 等的角色分配，需另一位管理员审批，决策 D48）：P0-7、P0-1 的审批端；
2. **发布受控提案**（四眼：提出人不能发布，CLAUDE.md "审批与职责分离"）：P0-3、P0-4、P1-5；
3. **本人设密码与二次验证**（管理员建的账号由本人经邀请设定，决策 D63）：界面是平台的入口页，不在本文范围，但邀请邮件的文字与链接要随之核对；
4. **法律文本的发布**（每次要二次验证，`QB_LEGAL_PUBLISH`）：页面无编号输入，已友好；
5. **打开各开关的发布**：财务总览卡片（已是"说明 + 键"），重提 7 个键靠 P0-5 的"按首次上线的值提出"。

所以即使 F-INIT 做成，**P0-3、P0-4、P0-7（以及 P0-5 里的"发布"一侧）仍是必做**；P0-1、P0-2、P0-5 的手工填写部分、P0-6 在导入覆盖到之前仍是需求方的主要痛点。

## 4. 建议的拆法与顺序

1. **应用，可立即做**（不碰平台，一个 PR）：P0-6（`UserPicker`）、P0-5 的应用侧专用页（首次上线开关的"按首次值提出"、科目映射的修改）、P0-4 的 16 条参数说明与覆盖测试、P1-4、P2 的消息缺口。
2. **平台，同一条线内一个 PR**：P0-2（`SecUser`、`SecRole` 的 `eb.display`）→ P0-1（流程表单的引用选择器与 ROLE 的 `actsOn`）→ P0-3、P0-4（受控变更列表与详情的名字化、参数说明）→ P0-7（审批面板的摘要）→ P0-5 平台侧（参数目录驱动的提案表单）。
3. 先核实两个"未核实"：P0-7 各受保护流程的 `ApprovalCase.reference`、P0-1 的 OpenAPI 输入 schema 是否已带引用信息——都是半小时的读码或起应用确认。
4. 平台改动要改 `docs/design/` 与 OpenAPI 快照（`pnpm gen:api`），并加前端测试与 axe 检查（CLAUDE.md §4"前端"）。
