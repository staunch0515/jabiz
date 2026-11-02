# Q76 发布的前置条件：科技树的第一步（计划，2026-11-02，待需求方确认）

`04-product-direction.md` §5 的候选 D；需求方 2026-11-01 定下顺序 A → D → E，A（Q75，#411）已完成。编号 Q76 是建议（下一个空号），确认时可改。
**这只是计划**：确认 §9 的决定后开发，一个 PR，工作分支 `1.3/quizbuks-76-prerequisites`。

## 0. 为什么

- 对话里的"科技树"（04 §3 的 S4、S5、S6）归结为一件事：**发布之间有先后**。先懂基础再看进阶，进阶的奖励更大；买过的、答过的不再被推同一件事，而是看到下一步。
  有向无环图的边就是"答这份之前要先完成那份"，这一步只做边；可视化、消耗 Kudos 解锁、勋章、跨商户的领域树在 04 §6.3，以后再做。
- Q69 把"认证"做成了持有与门槛（`SPONSOR_APPLY`、`REFERRER`），目录只在代码中，D-Q69-10 为"商户自己的认证、持有者才能答进阶题"留了余地。这一步不动认证：
  **前置条件是发布上的一条规则**，像客户匹配（Q47）一样由商户自选，平台不承担责任；"商户自己的认证"（有有效期、可授予、可收回的身份）仍是 Q69 §7 的以后。
- 它也是"企业的广告不乱投"（S5）与企业内部培训试点（S9：新员工先过基础再上岗）的最小形态。

## 1. 现状

| 部分 | 现在 |
|---|---|
| 客户匹配（Q47） | 发布上的条件 `QbPublicationCriterion`（普通表，带发布方那一对），只经 `QB_PUBLICATION_AUDIENCE_SAVE` 在草稿时整体替换（发布锁内、`baseRevision`），提交即冻结，复制时随发布复制，删除草稿时删除；`INVITED` 不能有条件。匹配的定义只有一个（`AudienceMatch`），`qb.app.feed`（不列出）、`qb.app.publication`（`matched` 进入 `canStart`，页面照样显示以便请人完善资料）、`qb.system.audience-count` 写同一段 SQL，开始答题在锁外既有的 `QueryEntities.all` 中读条件与本人的回答 |
| 认证（Q69、Q70） | 持有即不能答该认证的轮（方案 B）：开始在锁外经 `RoundHolders` 判断，"轮只多读、普通发布一条语句也不加"；两个模板写同样的规则 |
| 一人一次 | `QbAttempt (userId, publicationId)` 唯一；答过的发布首页缺省隐藏（Q34），答题者的会话只经 `taker:QbAttempt` 视图读本人的 |
| 商户主页（Q60） | `/s/<handle>` 列出该商户进行中的公开发布（`qb.public.sponsor-page-publications`），平铺 |
| 向导 | 四步：内容 → 时间与奖励 → **客户匹配**（受众、地区、条件与"查看符合人数"）→ 检查并提交；条件在提交前以单独的流程保存 |
| 审核 | 审批内容不含条件（Q47b"审核时显示条件"未做） |

## 2. 决定（草案，待确认）

| # | 决定 |
|---|---|
| D-Q76-1 | **前置条件是发布上商户自选的规则**：一个发布可以要求答题者先在**本商户的另一个发布**上 **合格过**（`PASSED`）或 **答过**（`ANSWERED`，完成即可，不论合格）；可以列几个（至多 5 个），全部满足才算（"且"）。没有前置的发布照旧人人可答。只限同一商户的发布（跨商户与"商户自己的认证"是以后，§7） |
| D-Q76-2 | **满足 = 在前置发布上有已完成的会话**（`SUBMITTED` / `EXPIRED`），`PASSED` 另要求 `passed = true`；学过就算，没有有效期，前置发布结束了也算。只看 App 的会话（`taker:QbAttempt`），店面的不算 |
| D-Q76-3 | **存储与写入照客户匹配**：普通表 `qb_publication_prerequisite`（发布、前置发布、要求、发布方那一对），只经 `QB_PUBLICATION_PREREQUISITES_SAVE` 在草稿时整体替换（发布锁内、`baseRevision`、revision + 1），提交即冻结，复制时随发布复制（仍指向原来的前置发布），删除草稿时一起删除 |
| D-Q76-4 | **前置发布的条件**：本商户的、已提交过（不是草稿）、不是本发布、不是复制自同一来源的自己；**不能成环**（保存时沿本商户发布的前置链走，本发布不得出现在所选前置的链上，深度上限 20）。前置发布被下架、结束都照旧算（已有的合格不收回）；商户以后也不能删它（提交过的发布本来不能删） |
| D-Q76-5 | **邀请制的发布也可以设前置**（与条件不同：邀请清单决定谁看得到，前置决定按什么顺序答，企业内部培训"先过基础才能上岗"正是这个用法） |
| D-Q76-6 | **三处同一规则，与客户匹配并列**：`qb.app.feed` **不列出**不满足的（首页只放现在能答的，与 `matched` 一致）；`qb.app.publication` 多 `prerequisitesMet`（进入 `canStart`）与 `prerequisites`（每条：前置发布的编号、名字（按显示语言）、要求、本人是否已满足），页面照样显示，并以"先完成：X"链到前置发布；`qb.system.audience-count` 的"符合人数"同样只算满足前置的人 |
| D-Q76-7 | **开始答题**：前置行在锁外既有的 `QueryEntities.all` 中读（发布编号已知）；本人在前置发布上的会话另读一条语句，**只在有前置时**（像 `RoundHolders.load`：普通发布的查询为空、不发语句），都在锁外；不满足即 422 `QB_PREREQUISITE_NOT_MET`（带未满足的前置发布编号）。`AttemptStatementBudgetIT` 的数字不变（预算的发布没有前置）；有前置的开始多一条锁外语句，写进预算的说明 |
| D-Q76-8 | **商户主页按前置分层**（最小的"品牌体验树"）：`qb.public.sponsor-page-publications` 多 `prerequisites`（前置发布编号与要求的 JSON）；App 的商户主页把进行中的公开发布按"没有前置 → 前置都在上一层"分层显示（基础在上、进阶在下，一条发布一行；前置不在进行中的发布算作上一层的根），每条写"先完成：X"；是否已满足由发布页决定（公开模板不知道读者）。商户后台的预览同一规则 |
| D-Q76-9 | **审核与分析不改**：审批内容不绑定前置（与条件一样，Q47b 一并补"审核时显示条件与前置"）；分析、推荐（Q70）、现金与奖品的流程不读前置 |
| D-Q76-10 | **文案与隐私**：前置指向的是商户自己的发布，不涉及答题者的个人信息；隐私政策不改 |

## 3. 后端（`backend/quizbuks`）

### 3.1 迁移 V59 `quizbuks_prerequisites`

```sql
CREATE TABLE qb_publication_prerequisite (
    prerequisite_id         varchar(36)  PRIMARY KEY,
    publication_id          uuid         NOT NULL,
    publisher_kind          varchar(20)  NOT NULL,
    publisher_id            varchar(128) NOT NULL,
    required_publication_id uuid         NOT NULL,
    requirement             varchar(10)  NOT NULL CHECK (requirement IN ('PASSED', 'ANSWERED')),
    version                 bigint       NOT NULL,
    CONSTRAINT uk_qb_publication_prerequisite UNIQUE (publication_id, required_publication_id),
    CONSTRAINT ck_qb_publication_prerequisite_self CHECK (publication_id <> required_publication_id)
);
-- The sponsor's chain (the cycle check, "which publications require this one").
CREATE INDEX qb_publication_prerequisite_required_idx ON qb_publication_prerequisite (publisher_id, required_publication_id);
```

普通表（草稿期间替换）。模板读本人的会话用已有的 `qb_attempt_once (user_id, publication_id)`。

### 3.2 发布（`publishing`）

- 新包内文件 `publishing.PublicationPrerequisites`：实体 `QbPublicationPrerequisite`（`PublisherKind.declare(eb, false)`，`requirement` 为字典 `urn:jabiz:dict:quizbuks:prerequisite-requirement`），默认数据视图 `processOnlyWrites`、商户视图 `sponsor:`（`fromContext("publisherId")` + `fixed("publisherKind", SPONSOR)`，只读），常量 `MAX_PREREQUISITES = 5`、`MAX_DEPTH = 20`。
- 流程 `QB_PUBLICATION_PREREQUISITES_SAVE`（`qb.publication.write`，`requiresVerifiedEmail`、`refuseBanned`）：`PublicationEditing.lockAndLoad` → 读本发布的前置行、本商户全部发布的前置行（环检查）与所选前置发布（商户视图，一条 `QueryEntities.all`）→ 校验（§2 D-Q76-4：`QB_PREREQUISITE_NOT_OWN`、`QB_PREREQUISITE_DRAFT`、`QB_PREREQUISITE_SELF`、`QB_PREREQUISITE_REPEATED`、`QB_PREREQUISITE_TOO_MANY`、`QB_PREREQUISITE_CYCLE`，一次报告全部）→ 照 `PublicationAudience.replace` 替换行 → `PublicationEditing.write`（revision + 1）。
  纯 Java：`publishing.PrerequisiteRules`（环的判断、上限、要求的字典），`PrerequisiteRulesTest`。
- 复制（`PublicationProcesses` 的 copy）与删除草稿：照条件的写法连带复制、删除。
- 卡片不加列：前置在答题前查，不随卡片冻结（行本身提交后不再改）。

### 3.3 答题（`play`）

- 纯 Java `play.PrerequisiteCheck`：输入前置行与本人在那些发布上的会话 → 未满足的前置发布编号列表（`AttemptStatus.finished`、`passed`）。
- `AttemptProcesses.start`：锁外第一条 `QueryEntities.all` 加 `PrerequisiteRows.of(发布)`（本发布的前置行）；之后一步 `PrerequisiteRows.loadAttempts`（`taker:QbAttempt`，`publicationId IN (前置发布)`，没有前置即查询为空、不发语句）；计算步骤"Refuse unmet prerequisites"在"Refuse an unwatched video"之前，只对新开始（`starting(ctx)`），422 `QB_PREREQUISITE_NOT_MET`（`params`：未满足的发布编号与名字不在应答中，App 按发布页的 `prerequisites` 显示）。渠道的开始（`ChannelPlay`）不读前置（店面没有前置）。

### 3.4 模板

- `qb.app.feed`：`AND NOT EXISTS (SELECT 1 FROM {{QbPublicationPrerequisite}} pr WHERE pr.publicationId = c.publicationId AND NOT EXISTS (SELECT 1 FROM {{QbAttempt}} a WHERE a.publicationId = pr.requiredPublicationId AND a.status <> 'IN_PROGRESS' AND (pr.requirement = 'ANSWERED' OR a.passed)))`（`QbAttempt` 是 `taker:` 视图，只有本人的）。
- `qb.app.publication`：同一段作 `prerequisitesMet`（进入 `canStart`），另给 `prerequisites`：`jsonb_agg` 每条前置的 `publicationId`、`name`（前置卡片的名字，按显示语言取 `translations`）、`requirement`、`met`。
- `qb.system.audience-count`：同一段（`QbAttempt` 的默认视图按 `userId`）。`AudienceIT` 比较模板与 Java 的做法照样覆盖：`PrerequisiteIT` 对同一组数据比较三个模板与 `PrerequisiteCheck`。
- `qb.public.sponsor-page-publications`：多 `prerequisites`（编号与要求的 JSON，不含是否满足）。
- `qb.sponsor.publication-prerequisites`（新，`qb.publication.write`）：本人某发布的前置与各前置发布的名字、状态（向导与发布页显示）；`qb.sponsor.publications` 已有的列表供向导选择（排除草稿与本发布由页面做、服务端再查）。

### 3.5 文案

- 错误码（`PublishingCodes`，三语）：`QB_PREREQUISITE_NOT_OWN`、`QB_PREREQUISITE_DRAFT`、`QB_PREREQUISITE_SELF`、`QB_PREREQUISITE_REPEATED`、`QB_PREREQUISITE_TOO_MANY`、`QB_PREREQUISITE_CYCLE`；`PlayCodes.PREREQUISITE_NOT_MET = QB_PREREQUISITE_NOT_MET`。
- 实体 `entity.QbPublicationPrerequisite[.字段]`、字典的两个值、流程 `process.QB_PUBLICATION_PREREQUISITES_SAVE`、模板的列。

## 4. 前端

### 4.1 商户后台（`quizbuks-web/sponsor`）

- 向导"客户匹配"一步加区块 **"前置条件"**（`publishing/Prerequisites.tsx`）：一行一条，下拉选本商户已提交过的发布（`qb.sponsor.publications`，排除草稿、本发布、已选过的；显示名字、状态与期间），要求"合格过 / 答过"二选一，"添加"至多 5 条、可删；保存随 `saveConditions` 的位置以 `QB_PUBLICATION_PREREQUISITES_SAVE` 保存（`baseRevision`），服务端的问题按 `prerequisites[i]` 显示；"查看符合人数"照旧（计数已算上前置）。
- 发布页（非草稿）显示冻结的前置（`qb.sponsor.publication-prerequisites`）；复制的发布带上。
- 预览（`preview/model.ts`）：`PublicationView.prerequisites`；商户主页的预览按 D-Q76-8 分层。
- `publishing/api.ts`、`ids.ts`、`messages.ts`（三语）。

### 4.2 App（`quizbuks-web/app`）

- `qb/play.ts`：`PublicationView.prerequisitesMet`、`prerequisites: { publicationId, name, requirement, met }[]`；商户主页的发布带 `prerequisites`。
- 发布页：`canStart` 为假且有未满足的前置时，`Action` 显示"先完成"卡片（`detail-prerequisites`）：每条前置一行，已满足的打勾，未满足的链到 `/p/<id>`（"先完成：X"）；服务器的 422 `QB_PREREQUISITE_NOT_MET` 照既有的错误提示显示。
- 商户主页 `/s/<handle>`：按 D-Q76-8 分层（`sponsorPage/levels.ts` 纯函数：从公开模板的 `prerequisites` 算层级，有环或缺失按根处理），每层一个小标题（"基础""进阶 1""进阶 2"…），有前置的卡片下写"先完成：X"；无前置关系时与现在一样平铺。
- `qb/` 两份同步。

### 4.3 后台扩展

- 不改（Q47b 时一并显示条件与前置）。

## 5. 测试

- **单元**：`PrerequisiteRulesTest`（上限、重复、自己、环：直接、间接、经已结束的发布；深度上限）、`PrerequisiteCheckTest`（答过 / 合格过、进行中不算、`EXPIRED` 算答过、几个前置全部满足才算）；App `sponsorPage/levels.test.ts`（分层：无前置、一条链、菱形、前置不在列表中、环）。
- **集成**：`PrerequisiteIT`：保存与校验（不是本商户的 / 草稿 / 自己 / 重复 / 超过 5 / 环）；提交即冻结（提交后保存 422 `QB_PUBLICATION_NOT_DRAFT`）；复制带上、删除草稿删掉；邀请制的发布可以设；首页不列出未满足的、合格后列出；发布页的 `prerequisitesMet` 与 `prerequisites`（名字按语言）；开始被拒（422，继续进行中的会话不查）、满足后开始；`ANSWERED` 对不合格的会话满足、`PASSED` 不满足；三个模板与 `PrerequisiteCheck` 对同一组数据结论相同；`qb.system.audience-count` 只算满足的人；商户主页模板的 `prerequisites`。
  `AttemptStatementBudgetIT` 数字不变；新增一个用例断言有前置的开始多且只多一条锁外语句（照 `RoundHolders` 的写法）。`OperatorScopesIT`、`VerifiedEmailWritesIT` 自动覆盖新流程与数据视图。
- **场景回放**：现有场景的快照不变（没有前置即所有人）。
- **前端**：商户后台 `publishing.test.tsx`（区块的添加 / 删除 / 二选一、保存的输入、服务端问题的显示、发布页的显示、预览分层；axe）；App `Play.test.tsx`（未满足时的"先完成"链接与禁用、满足后开始；商户主页的分层；axe）。
- **e2e** `prerequisites.spec.ts`：商户以 API 建两个发布 A、B（B 要求合格过 A）；答题者的首页只有 A；答 A 合格后首页出现 B；B 的开始页在合格前显示"先完成：A"。

## 6. 文档

- `02-design.md` §3.3（新实体）、§9（开始前的三条规则：匹配、持有、前置）；`03-plan.md` 加 Q76 一行；`04-product-direction.md` §5-D 指向本计划；`backend/quizbuks/CLAUDE.md` 的"客户匹配"一节旁加"前置条件"一段（规则只在 `PrerequisiteCheck`，四处同一段 SQL，开始只在有前置时多一条锁外语句）；Q69 计划 §7 注明"商户自己的认证"仍是以后、第一步由 Q76 的前置条件代替。

## 7. 估计

| 部分 | 天 |
|---|---|
| 后端：表与实体、保存流程与规则、开始的检查、四个模板、文案、IT | 2 |
| 商户后台：向导区块、发布页、预览、测试 | 1 |
| App：发布页"先完成"、商户主页分层、测试 | 1 |
| e2e、场景、文档 | 0.5 |
| **合计** | **4.5–5** |

## 8. 风险

| 等级 | 风险 | 对策 |
|---|---|---|
| 中 | 首页不列出锁着的发布，答题者不知道有进阶可解锁 | 商户主页按层显示路径（D-Q76-8）；发布页从分享链接打开时显示"先完成"；以后再议首页的"锁着的卡片"（04 §6.3） |
| 中 | 商户把前置设得太深、太多，没人能答到 | 上限 5 条、深度 20；向导显示前置发布的状态与完成人数；"查看符合人数"算上前置 |
| 低 | 环：A 要求 B、B 要求 A 使两者都没人能答 | 保存时沿链检查（D-Q76-4），数据库另有自我引用的检查约束 |
| 低 | 前置发布是邀请制而本发布公开：公开的人无法满足 | 保存时警告（不拦）：向导显示前置发布的受众 |
| 低 | 有前置的开始多一条锁外语句 | 只在有前置时；锁内不变；写进预算的说明 |

## 9. 需要需求方确认的

1. D-Q76-1：前置有"合格过"与"答过"两种；至多 5 条；只限本商户的发布。
2. D-Q76-6：首页**不列出**未满足前置的发布（与客户匹配一致），路径在商户主页上显示；还是首页也显示带锁标的卡片？
3. D-Q76-5：邀请制的发布也可以设前置。
4. D-Q76-8：商户主页按前置分层显示。
5. 编号 Q76。
