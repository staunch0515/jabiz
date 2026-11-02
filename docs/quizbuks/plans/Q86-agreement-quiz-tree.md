# Q86 协议答题、平台之树与"持有认证"作前置（计划，2026-11-03，待需求方确认 §9）

需求方 2026-11-03 的要求：（1）各种协议同意之后加**一个答题环节**，确认用户了解协议内容，作为平台的特色，引导其他网站跟风；**已有的东西都不改**（同意照旧、不拦任何操作）；
（2）协议答题与科技树（Q85）**统一考虑**。编号 Q86 是建议（Q83、Q84、Q85 已占用），确认时可改。**这只是计划**：确认 §9 后开发，一个 PR，工作分支 `1.3/quizbuks-86-agreement-quiz`。

## 0. 为什么

- 协议只被"同意"不被"读懂"是所有网站的通病。QuizBuks 的本行就是用几道题证明一个人看懂了一段内容：把协议本身当作一份答卷，答对即持有"协议理解"的认证，是最自然的特色，而且**一行既有逻辑都不用改**：
  Q69 已经把"考试即资格"做成了机制（官方账号出题、运营商登记为认证的一轮、合格持有一年、按版本重考），Q77 给了可验证的证书与公开认证页，Q81 给了随机抽题。
- 与科技树统一：Q85 画的是一个商户的树。**平台自己也是一个商户**（官方账号），它的发布就是平台之树的节点：条款理解 → 有奖活动规则理解 → 成为商户须知 → 推荐资格。答题者在平台之树上的位置，就是"我在这个平台上走到哪里了"。
  再把 04 §5-D 留下的"持有认证 Y"作为前置条件补上，商户的树就能从平台之树的节点长出来（"先通过有奖活动规则理解，才能答我的进阶题"），两棵树连成一片，这才是 04 §6.3 说的"平台出根节点、商户赞助分支节点"。
- 法律上，考试**不能代替同意**（定型約款的有效同意靠文本展示与明示同意，没有判例要求考试），所以答题是同意之后的、自愿的、可验证的"理解记录"，不是门槛（需求方"已有的不改"）。

## 1. 现状

- **法律文本**（Q7b，`messaging.QbLegal`）：六种（`TERMS_APP`、`TERMS_SPONSOR`、`PRIVACY`、`COMMERCE_NOTICE`、`AI_NOTICE`、`CONTEST_RULES`），按版本发布，`requiresConsent` 的版本须重新同意；答题者在 `/welcome` 同意 `APP_KINDS`（条款、隐私），商户在入驻时同意 `SPONSOR_KINDS`；有须重新同意的新版时两个前端显示全屏的 `ConsentGate`，服务端只拦资金与合约类操作。
- **认证**（Q69，`certification`）：目录只在代码中（`Certifications.ALL`：`SPONSOR_APPLY`、`REFERRER`，各一年、版本 1）；持有按需算出（在该认证各轮发布上合格、有效期内、轮的版本 = 当前版本，或人工授予）；轮只能是官方账号的发布（每个运营商一个官方账号，总部直营也有）；"每月换模版重新发布"是运营商的业务操作（D-Q70-2）。
- **前置条件**（Q76）：只限"本商户的另一个发布"（`PASSED` / `ANSWERED`）；"持有认证 Y"明确留到以后（04 §5-D、Q69 §7）。
- **树**（Q85）：节点 = 商户提交过的发布，边 = 前置；商户看客户的位置，答题者看自己的位置；官方账号的主页已经按同样的规则画树。
- **证书与公开页**（Q77）：持有的认证可签发 PDF、可核实、可在公开认证页显示。

## 2. 决定（草案）

| # | 决定 |
|---|---|
| D-Q86-1 | **协议答题是认证**。目录加三种：`TERMS_APP_UNDERSTOOD`（答题者：条款 + 隐私政策）、`CONTEST_RULES_UNDERSTOOD`（答题者：有奖活动规则）、`TERMS_SPONSOR_UNDERSTOOD`（商户：商户条款 + 隐私 + 特定商取引法表记）。有效期一年；**版本仍在代码中**（D-Q69-1 不改）：法律文本发布了**须重新同意**的新版，由开发升目录的版本并由运营商出新一轮（同 D-Q70-2 的"重新发布是业务操作"）；措辞修订不升。新增启动检查 `CERTIFICATION_LEGAL`：目录的版本落后于对应法律文本的须同意版本即**警告**（不是错误：先发文本再出题是正常顺序）。 |
| D-Q86-2 | **同意不改、不拦**：同意流程、`ConsentGate`、服务端的拦截一字不动。答题是同意**之后**的自愿环节：欢迎页保存并同意后、`ConsentGate`"同意并继续"后、商户入驻通过后的概况页，各显示一张"确认你的理解"卡（协议名、几道题、几分钟、奖励、"开始"链到当前一轮的发布；没有进行中的轮即不显示；已持有即显示"已通过，至 …"）。账户页"我的认证"与 Q77 的证书、公开认证页照常显示它们。 |
| D-Q86-3 | **题目与轮次照 Q69 / Q70**：官方账号的发布，题库 ≥ 15 题、每人抽 5 题（Q81 `pickCount`）、同分、合格线 = 满分、答完显示解析（题目的"解析"字段已有）；奖励由官方账号出（发布至少一档 > 0，D-Q69-6；建议 5 Kudos，记平台推广费用 5120）；每月一轮；三种语言各出（文本以日文为准，题目由法务校对一致）。题目初稿可用 Q8a 的 AI 出题从法律文本生成，**必须经法务校对**（题目也是对条款的解释）。内容不在开发内。 |
| D-Q86-4 | **平台之树 = 总部官方账号的主页树**（Q85 已画）：官方账号用 Q76 的前置把自己的发布连起来（条款理解 → 有奖活动规则理解 → 成为商户须知 → 推荐资格），不需要新机制。普通参数 `qb.tree.platform-handle`（总部官方账号的主页 handle，缺省空）：有值时 App 账户页"我的认证"加入口"平台之树"，首页的问候横幅在用户还没点亮根节点时提示一次（可关）。运营商自己的官方账号照常有自己的树。 |
| D-Q86-5 | **"持有认证"作前置**（补 04 §5-D）：`qb_publication_prerequisite` 加列 `certification`（目录中的键；与 `required_publication_id` **二选一**，CHECK；要求固定为"持有"），满足 = 此刻持有（`CertificationHolding` 的定义：有效期内、版本当前，或人工授予）。商户在向导"客户匹配"一步的前置区块可选目录中**对象是答题者**的认证（`TERMS_APP_UNDERSTOOD`、`CONTEST_RULES_UNDERSTOOD`、`REFERRER`、`SPONSOR_APPLY`）。首页不列出、发布页"先持有：X"链到当前一轮、`qb.system.audience-count` 同算，照 D-Q76-6 三处同一规则。 |
| D-Q86-6 | **答题路径**：有认证前置的发布，开始时在锁外**多一条语句**读本人的持有（`RoundHolders` 的轮与记录，只在有认证前置时），与 Q76 的"有前置多一条"同一做法；普通发布不变，`AttemptStatementBudgetIT` 加一个用例、数字不变。 |
| D-Q86-7 | **树上的认证节点**：商户的树（`qb.sponsor.tree`、`qb.app.sponsor-tree`）把认证前置画成一个**平台节点**（菱形、灰底、写认证名；答题者的状态：持有 = 已点亮、否则锁定），点它去平台之树（有 `qb.tree.platform-handle` 时）或去当前一轮的发布；平台节点不计人数、不列名单（它不是商户的发布）。`qb/tree.ts` 的节点加 `kind: 'publication' \| 'certification'`。 |
| D-Q86-8 | **不做**：跨商户的发布前置（仍限本商户）；把考试当门槛（同意、充值、提现、提交发布都不要求通过）；商户自己定义的认证（D-Q69-10 仍以后）；自动从法律文本出题并发布（AI 只给初稿）。 |

## 3. 后端（`backend/quizbuks`）

### 3.1 迁移（编号以合入时为准，Q83 的计划占了 V64）

- `ALTER TABLE qb_publication_prerequisite ADD COLUMN certification varchar(40)`；`ALTER COLUMN required_publication_id DROP NOT NULL`；`CHECK ((required_publication_id IS NULL) <> (certification IS NULL))`；唯一约束改为 `(publication_id, required_publication_id)` 与 `(publication_id, certification)` 两条（部分唯一索引）。普通表，可改。
- `QB_SETUP`：新参数 `qb.tree.platform-handle`（普通参数，文本，缺省空）；三种认证的字典值与消息 `qb.certification.<键>`（三语）。

### 3.2 代码

- `certification.Certifications`：三种新认证（对象、一年、版本 1）、`legalKinds()`（认证对应的法律文本种类，给启动检查与入口卡）；`CertificationHolding` 不变。
- 启动检查 `CertificationLegalCheck`（`PlatformCheck`）：目录版本 < 法律文本的须同意版本（`LegalRules`）→ 警告。
- `publishing.QbPrerequisites` / `PrerequisiteRules`：`certification` 的校验（目录中、对象是答题者、二选一、不重复）；`play.PrerequisiteCheck.Prerequisite` 加 `certification`，`unmet` 多一个输入：本人持有的认证键集合；`play.PrerequisiteRows`：有认证前置时锁外多读持有（`RoundHolders.holdingsOf`）。
- 模板：`qb.app.feed`、`qb.app.publication`、`qb.system.audience-count` 的 `NOT EXISTS` 加认证分支（持有的 SQL 复用 feed 里 `heldUntil` 那段）；`qb.sponsor.publication-prerequisites` 返回认证名；`qb.sponsor.tree` / `qb.app.sponsor-tree` 的 `prerequisites` JSON 加 `certification`；`qb.public.sponsor-page-publications` 同；新模板 `qb.app.understanding`（本人三种协议理解的持有与当前一轮的发布编号，给入口卡；`qb.me`）。
- 不改：同意的流程与拦截、`QB_CERTIFICATION_*`、Q77 的证书。

## 4. 前端

- **共用** `qb/tree.ts` / `qb/TechTree.tsx`：节点 `kind`，认证节点的画法与链接；`qb/play.ts` 的 `PublicationPrerequisite` 加 `certification`。
- **App**：`UnderstandingCard`（欢迎页保存后、`ConsentGate` 之后、账户页"我的认证"）；发布页"先持有：X"；账户页"平台之树"入口与首页横幅的一次性提示；Q77 的认证名文案 `certificates.names.<键>`（三语）。
- **商户后台**：向导前置区块加"持有认证"一组单选（对象是答题者的目录项）；摘要与发布页显示；入驻通过后的概况页加"确认你的理解"卡（`TERMS_SPONSOR_UNDERSTOOD`）；Q85 的树画平台节点。
- **后台扩展**：认证页的目录列表显示三种新认证与它们对应的法律文本版本（只读）。

## 5. 测试

- `CertificationsTest`（目录、`legalKinds`）、`CertificationLegalCheckTest`、`PrerequisiteRulesTest`（认证分支）、`PrerequisiteCheckTest`（持有满足、过期不满足）；`PrerequisiteIT` 加：认证前置的保存、首页 / 发布页 / 开始随持有变化、`audience-count` 一致、`AttemptStatementBudgetIT` 的"有认证前置的开始 = 普通开始 + 1"；`TreeIT` 加平台节点；`UnderstandingIT`（入口模板、三种认证的持有与版本重考）。
- Vitest：App 的入口卡三处、账户页入口、树的认证节点；商户后台向导的认证前置与树；`qb/tree.test.ts`。
- e2e `agreement-quiz.spec.ts`：新用户同意后看到"确认你的理解"卡 → 答对 → 账户页持有、平台之树上点亮；商户的进阶题要求持有它 → 持有前锁定、持有后可开始。
- 场景 `setup.yml` 快照随新参数、字典更新；`certifications.yml`（若有）加一轮。

## 6. 文档

`02-design.md` §3.3（前置的两种）、§7.1 / §7.2（入口卡与平台之树）；`backend/quizbuks/CLAUDE.md` 第 4 节"认证与门槛""前置条件""科技树"各补一句；`04-product-direction.md` §5-D 与 §6.3 指向本计划；`operations.md`：出协议题与登记轮次的操作步骤、`qb.tree.platform-handle`；`03-plan.md` 的 Q86 行；本文件 §10 实施记录。

## 7. 估计

| 部分 | 天 |
|---|---|
| 目录、启动检查、参数、文案 | 0.5 |
| 认证前置：迁移、规则、开始路径、四个模板、IT | 2 |
| 树的平台节点（共用组件、两端模板与页面） | 1 |
| App：入口卡三处、账户页、发布页、测试 | 1.5 |
| 商户后台：向导、概况页卡、测试 | 1 |
| e2e、场景、文档 | 0.5 |
| **合计** | **6.5** |

题目内容（三语、法务校对）与运营步骤（标记官方账号、出题、登记轮次、每月换版）不在开发内。

## 8. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 题目能被背答案，"理解"流于形式 | 题库 ≥ 15 抽 5、每月换版、合格线满分、答完显示解析（学到即可，不追求防背） |
| 中 | 答对给学分是否触及景品表示法 | 与现有"成为商户须知"的 10 Kudos 同一问题，法务一并问；若不许，奖励改为 1 Kudos 或由法务定 |
| 中 | 目录版本与法律文本版本脱节 | 启动检查警告 + `operations.md` 的步骤；以后可改为目录版本自动取法律文本的须同意版本（D-Q86-1 留的路） |
| 低 | 认证前置让商户的发布依赖平台的内容 | 向导里写明"答题者先要通过平台的 X"，发布页链到当前一轮；没有进行中的轮时前置仍成立（持有的人照常），新人去不了即首页不列出 |
| 低 | 答题路径 +1 语句 | 只在有认证前置时，同 Q76 的做法，预算写明 |

## 9. 需要需求方确认的

1. D-Q86-1 / D-Q86-2：协议答题做成认证，自愿、不作任何门槛；三种（答题者的条款 + 隐私、有奖活动规则；商户的条款）。
2. D-Q86-3：每人抽 5 题、合格线满分、每月一轮、奖励 5 Kudos 由官方账号出；题目三语由法务校对，AI 只给初稿。
3. D-Q86-4：平台之树 = 总部官方账号的主页树，参数 `qb.tree.platform-handle` 指向它。
4. D-Q86-5 / D-Q86-7：这一期一并做"持有认证"作前置，商户的树可以挂到平台的节点上。
5. 编号 Q86，一个 PR，约 6.5 天，一条迁移。
