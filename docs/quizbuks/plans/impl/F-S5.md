# F-S5 实施计划：节点证书（T5 的最小部分）

规格：`docs/quizbuks/first-launch-spec.md` F-S5（A2-12 取代 Q90 §2.3 的"有效期随节点"：证书不写有效期）；来源 `Q90-trees.md` §0-11、§0-16、§2.5、§8 T5，`Q77-certificates.md`（版式、核实页、公开认证页），`Q94-first-launch.md` §3 第 5 项，闭环 F-F12 第 5、6 步。负责：S3（改派 2026-11-10 总控，原属 S2）。状态：**草案，待总控审阅**（§8 的点待定）。依赖：F-S2（点亮）、F-S4（发起方点亮）已合入。

## 0. 现状（2026-11-10 盘点，基线 `origin/1.3/quizbuks`，迁移最大号 V110）

- **Q77 证书表与流程仍在，但不能签发**：`certification/CertificateProcesses.java` 的 `QB_CERTIFICATE_ISSUE`（第 87–103 行）自 F-S2c（D8）起只抛 422 `QB_CERTIFICATE_PAUSED`；`QB_MY_CERTIFICATIONS`（第 74 行，`held()` 第 138 行）只列"未到期、没有持有开始之后的撤销"的**按认证签发**的证书。签发代码（随机键、`DOCUMENT_ISSUE`、`verifyAddress`）已在 F-S2c1 删除，只能从 git（`057cc631^`）参考。
- **表 `qb_certificate`**（V61）按"一次持有"建模：`certification varchar(40) NOT NULL`、`certification_version`、`held_from`、`valid_until NOT NULL`，唯一约束 `(user_id, certification, valid_until)`；没有主体种类（只有 `user_id`）、点亮记录、图、节点、名称、颁发方。实体 `QbCertificates`、数据视图（默认 / `me:` / `public:` / 公开撤销）在 `CertificateConfig.java`。
- **核实页与公开认证页**：公开模板 `qb.public.certificate`（按键）、`qb.public.taker-page`（按公开名，每种认证取最新一张）；撤销来自只读表 `qb_certification_record`，名称与颁发方来自只读表 `qb_sponsor_certification`（`sp:<id>`）。F-S2 §D8 留下的两张只读表的说明："F-S5 改为节点证书时迁移后再删"（F-S2.md 第 53、60 行）。前端：`quizbuks-web/app/src/certificates/`（`CertificatesPart` 画"暂停签发"提示、`PublicCertificates` 的 `VerifyPage` / `TakerPage`）、`App.tsx` 第 466 行的公开路由、`frame/MePage.tsx` 的"证书"数字、管理系统扩展 `UserSummaryPage` 的"证书"一节。
- **版式 `qb.certificate`**（`CertificateConfig.qbCertificateLayout`）：权限 `qb.me`、对象 `QbCertificate`、事实含 `validUntil`、颁发方来自 `qb.me.certificate-issuer`（参数 `qb.issuer.name`，或发起方认证的发起方名）。**单据只有签发人本人读得到**（22 §4.3："与签发人相同的数据范围"），所以 PDF 必须由持有人自己的请求签发——收据（`TopUpReceipts`）的做法：首次请求签发并存档，再请求返回原单据。
- **点亮**：`TreeLighting`（`QB_TREE_EVALUATE`，`internal()`，锁 `qb.tree-lit:<种类>:<主体>`）对两种主体写 `QbTreeLit`；点亮后以 `PublishEvent` 要求发放（F-S4b 的 `qb.tree.grant-due`，由 `fee.LightGrants.settle` 在发起方钱包锁内结算，每日任务 `qb.tree-grant-retry` 补发）。节点的定义在 `QbTreeNodeDef`（每版本）与 `QbTreeNode`（生效版本的冻结行，F-S4b 的 `grantAmount` 即这样加入：定义 → `TreeProcesses` 草稿保存 / 采用 / 生效写入 → 点亮读冻结行 → 点亮记录上冻结）；版本内容哈希 `TreeApprovals.content`（第 40 行起）含节点的各字段，四眼审批的就是它。
- **撤销**：`QB_TREE_LIT_REVOKE`（四眼）写只追加的 `QbTreeLitRevocation(litId, revokedAt, reason…)`，发事件 `qb.tree.lit-revoked`；F-S2 的说明写明"节点证书随之作废在 F-S5"（operations.md 第 787 行）。
- **删除账号**：`TakerErasure` 已删 `QbCertificates.CERTIFICATE` 的行并清 `qb.certificate` 的 PDF（第 78–80 行）；`SponsorErasure` 没有证书条目；`QbTreeLit` 按"留记录、清主体"。
- **没有的**：节点上的"证书"声明；节点证书的签发；发起方一侧的证书页；按证书号查询的管理页（`qb.admin.certificates` 只有平台、按用户列）；运营商的查询；分享停用；每日核对。

## 1. 拆分（2 个 PR，依次）

| PR | 内容 | 迁移 |
|---|---|---|
| **F-S5a** 后端 | 节点的证书声明（定义、四眼、副本照抄）、点亮时冻结、签发（事件 + 逐个主体的内部流程 + 每日补发）、持有人的签发 PDF / 分享开关 / 列表、公开核实页与认证页、管理查询、删除账号、核对、语句数 | 一个（V111，临时，先绿先合，合并前按最大号 + 1） |
| **F-S5b** 界面、e2e 与文档 | App"证书"（含撤销的标记、分享开关、下载）、发起方页面"证书"标签、核实页新状态、管理系统扩展（节点编辑器的证书字段、按号查询）、e2e（闭环第 5、6 步）、帮助"怎样核实证书"、`operations.md`、`backend/quizbuks/CLAUDE.md` | 无 |

## 2. F-S5a 后端

### 2.1 节点怎样"设了证书"

- 节点定义加 `certificateNames`（`I18nText`，可空；非空即"点亮此节点颁发证书"，值是证书上的名称，三语至少一种）。加在 `QbTrees.NODE_DEF_ENTITY`、`NODE_ENTITY`（生效版本的冻结行）、`LIT_ENTITY`（点亮时冻结，只追加表只能加列）；写法与 F-S4b 的 `grantAmount` 逐处对应：`TreeProcesses.NodeInput`（第 114 行）、`insertDef`（第 349 行）、复制（第 100–105 行）、保存（第 657、695 行）、生效写入（第 1006 行）、读出（第 835、850 行）、`TreeApprovals.content`（只在设置时写入，没有证书的版本哈希不变）。
- 规则（`TreeRules`，新代码 `QB_TREE_CERTIFICATE_INVALID`：名称为空白或过长；`QB_TREE_CERTIFICATE_COPY`）：**平台模板、发起方自己的图**可以设；**运营商的副本**照抄模板的证书、不能改（与 `GRANT_COPY` 同一做法，§8 第 1 点）。不限主体：学者点亮的平台图、发起方的图、发起方点亮的平台模板都可设。改动随版本经已有的审核（发起方的图由运营商另一位成员审，平台模板由平台四眼）：这是对"冒用证书名"的控制（Q90 §风险 4），证书上同时印颁发方。
- 管理系统扩展的编辑器（`TreeEditor.tsx`、`tree-draft.ts`）、发起方编辑器（`sponsor/src/tree/`）加"点亮后颁发证书（证书名称）"字段；在字段加入之前保存必须原样带回读到的值（F-S4b 的教训，`tree-draft.test.ts`）。

### 2.2 签发：点亮后的事件 + 内部流程 + 每日补发（照 F-S4b 的发放）

- **点亮时只冻结，不签发**：`TreeLighting.light()` 在写 `QbTreeLit` 时按候选节点的冻结行写入 `certificateNames`（有则发事件）；点亮后增加一个 `PublishEvent.when`：事件 `qb.tree.certificate-due`，载荷 `{subjectKind, subjectId}`（同 `qb.tree.grant-due`）。`QB_TREE_EVALUATE` 不读资料、不抽随机数、不排 PDF，语句数只多事件的一条写入；`AttemptStatementBudgetIT`（答题提交只发 `qb.tree.passed`）**不变**。
- **内部流程 `QB_TREE_LIT_CERTIFICATE`**（`internal()`，权限新 `qb.certificate.system`，包 `certification`）：输入主体；锁 `qb.certificate:<种类>:<主体>`（不与点亮锁、发布锁、钱包锁同取）；读该主体的点亮记录（有冻结名称的）、撤销、已有证书行、图与图的主人；**每条有名称、没有撤销、没有证书行的点亮写一张证书行**。幂等与恰好一次：行上 `lit_id` 唯一（`UNIQUE(lit_id)`）、锁内重读、重复事件什么也不写。核实键用 `RandomBytesStep` 一次取足（沿用 `QbCertificates.KEY_BYTES`），多张时每张取前一次的哈希链（`SHA-256(种子 ‖ 点亮编号)` 的前 128 位，种子不外泄），不由点亮编号推出。
- **写入内容（全部冻结）**：持有人名（学者：资料的显示名；发起方：公司名；读 `QbAccounts` 一次）、证书名称（三语）、颁发方名、点亮时刻（`heldFrom`）、图与节点编号、`subjectKind`。**颁发方 = 图的主人**：平台图 → 受控参数 `qb.issuer.name`（沿用 Q77，空则用消息 `certificate.issuer.platform`）；发起方的图 → 其公司名；运营商的副本 → 运营商名（§8 第 1 点）。持有人名为空时写 `-`（同 Q77）。
- **每日补发**：任务 `qb.tree-certificate-retry`（`JobDefinition.forEach`，每天 03:41，`jabiz.fiscal-year-zone`，模板 `qb.system.tree-certificates-due`：有冻结名称、没有撤销、没有证书行的点亮，按主体去重），每项调同一个流程。事件失败、点亮时事件丢失、操作员以 `JOB_RUN_REQUEST` 补跑都由它兜住。
- **PDF 在持有人首次下载时签发**（§8 第 2 点）：证书"点亮即有"，核实页立刻可用；PDF 是单据，只有签发人读得到，因此由持有人自己的流程签发并存档，之后一律取存档字节。流程 `QB_CERTIFICATE_ISSUE`（改造，输入 `certificateId` + `language`，`qb.me`，`requiresVerifiedEmail`、`refuseBanned`，锁 `qb.certificate-doc:<证书>`，经 `me:` 数据视图找：别人的 404）与发起方的 `QB_SPONSOR_CERTIFICATE_ISSUE`（权限 `qb.sponsor.me`，同一辅助方法，`me-sponsor:`）；已有该证书的单据即返回；否则 `DOCUMENT_ISSUE`（版式 `qb.node_certificate` / `qb.node_certificate_sponsor`，主体 `QbCertificate`，参数：证书编号、证书名称（取所选语言，缺则取英文再取任一种）、核实地址 `<jabiz.mail.base-url>/taker/verify/<键>`，颁发方名取冻结值）。单据上**没有有效期一栏**（新版式的事实只有证书名称、持有人、点亮日期、颁发方、核实地址与"以核实页为准"的说明）。
- **保留版式 `qb.certificate`** 与旧证书的下载原样不动（旧 PDF 不重排）。保留期：`TopUpConfig.qbDocumentRunRetention` 不加（证书不是账目记录，删除账号时随之清除，同 Q77）。

### 2.3 撤销、分享与读取

- **撤销不写证书行**：核实页与列表读证书行的 `lit_id` 对应的撤销记录（新增只暴露 `litId`、`revokedAt` 两列的公开数据视图 `public:QbTreeLitRevocation`，不含原因与主体）；一次撤销立即生效，不依赖事件。撤销后再点亮得到**新的点亮记录与新的证书**，旧证书保持"已撤销"（§8 第 3 点）。撤销流程一字不改；持有人的站内信 `TREE_LIT_REVOKED` 的三语文字加一句"证书同时作废"（仅当该点亮有证书）。
- **分享开关**：证书行 `shared`（缺省 true：核实键本身 128 位随机，不列在任何地方；§8 第 4 点）；流程 `QB_CERTIFICATE_SHARE_SET`（学者 / 发起方各一个入口，同 2.2 的做法，`certificateId`、`shared`，只改本人的、只改 `shared`）。停用后核实页只显示"持有人已停用此链接"（不含名称与证书），公开认证页不列；再开即恢复，核实键不变。写入经 `DatasetEntityManager`，平台审计自动记录前后值。
- **读取**：`QB_MY_CERTIFICATIONS`（保留流程代码，App 旧调用不变）输出改为证书列表 `certificates`：每项 `source`（`NODE` / `CERTIFICATION`）、证书名称（三语）、颁发方名、`litAt`、`revokedAt`、`shared`、`verifyKey`、`runId`（有则）、旧证书的 `heldUntil`；旧证书的 `held` 字段在 F-S5a 中保留（App 未改前照常可用），F-S5b 中 App 改用 `certificates` 后删去该字段（同一项内完成，不留到以后）。发起方的列表 `QB_SPONSOR_MY_CERTIFICATES`（`qb.sponsor.me`，只读，同一读法，`me-sponsor:` 视图固定 `subjectKind = SPONSOR`）。`me:` 视图补固定条件 `subjectKind = USER`，避免既是学者又是发起方的人在 App 看到发起方的证书。
- **签发 / 撤销留记录**：行的插入与 `shared` 的改动由平台写入路径记入 `sys_audit_record`（新字段无需遮蔽，持有人名按已有做法用 `f.auditMasked()`）；撤销本身已有记录（四眼 + 撤销行 + 审计）；证书 PDF 的签发是平台的单据存档。

### 2.4 公开页、管理查询、删除账号

- **`qb.public.certificate`**：`valid_until` 可空（节点证书没有）；结果加 `source`、`certificateNames`、`issuerName`（节点证书取行上冻结的；旧证书沿用现有的 `sp:` 连接）、`revokedAt`（旧：只读撤销表；节点：撤销数据视图；两个来源互斥）、`shared`（false 时其余列由 SQL 置空）。不含用户编号。页面状态：`VALID` / `REVOKED` / `DISABLED`（节点），旧证书再有 `EXPIRED`；**节点证书不显示有效期**。
- **`qb.public.taker-page`**：节点证书逐张列出（`subjectKind = USER`、`shared`、没有撤销，按点亮时刻倒序）；旧证书保持"每种认证最新一张"。
- **管理查询**：`qb.admin.certificates` 加列（来源、主体种类、图、节点、名称、颁发方、撤销时刻）与按证书号 `verifyKey` 的筛选（已有）；新增运营商的 `qb.operator.certificates`（`qb.operator.use`，写法同 `qb.operator.tree-lit`：成员数据视图 + 图的归属，只含本运营商的副本与其发起方的图上的证书；别的运营商与平台图的证书查不到，404 / 空）；运营商不用 `withinEntryScope`（证书不分入口范围，归属经图判定，IT 逐项断言）。
- **删除账号**：`TakerErasure` 条目改为 `delete(CERTIFICATE, "userId", "holderName", "verifyKey", "certificateNames")` 并 `.documents` 加 `qb.node_certificate`；`SponsorErasure` 加同一实体（发起方的证书行与 `qb.node_certificate_sponsor` 的 PDF），`ErasureIT` 的全表搜索覆盖新列。核实页此后"查无此证"，不泄露个人信息；`QbTreeLit` 照旧只留编号（冻结的证书名称不是个人信息；它在 `ErasedEntity.keep` 中无需列）。
- **每日核对**：`TreeUpkeep` 的 `qb.admin.tree-health` 加一类 `CERTIFICATE_MISSING`（有冻结名称、没有撤销、没有证书行的点亮；该检查在 19:23 UTC，在 03:41 的补发之后，所以出现即是真的失败，不需要宽限参数），进入已有的每日待办 `qb.tree-health`。

### 2.5 迁移 V111（临时，先绿先合，合并前按最大号 + 1）

- `qb_tree_node_def`、`qb_tree_node`、`qb_tree_lit_version` 各加 `certificate_names jsonb`（只加列，不碰封存过的列，`SealedColumnsHistory` 通过）。
- `qb_certificate`：`certification`、`certification_version`、`valid_until` 去掉 `NOT NULL`；加 `source varchar(12) NOT NULL DEFAULT 'CERTIFICATION'`、`subject_kind varchar(10) NOT NULL DEFAULT 'USER'`、`lit_id varchar(36)`（`UNIQUE`，空值不比较，满足启动检查对唯一约束的索引要求）、`tree_id`、`node_id`、`certificate_names jsonb`、`issuer_name varchar(200)`、`shared boolean NOT NULL DEFAULT true`；`CHECK`：`source = 'NODE'` 时 `lit_id`、`tree_id`、`node_id`、`certificate_names`、`issuer_name` 非空且 `valid_until` 为空，`CERTIFICATION` 时 `certification`、`valid_until` 非空；索引 `(user_id, subject_kind, issued_at)`。不回填。

## 3. F-S5b 界面、e2e 与文档

- **App**（`quizbuks-web/app/src/certificates/`）：`CertificatesPart` 去掉"暂停签发"的提示与"没有认证"的说明，列出证书（名称、颁发方、点亮日期；撤销的标"已撤销"并不能分享；旧证书保留有效期一行）；每张："下载"（首次触发 `QB_CERTIFICATE_ISSUE`，语言取界面语言，再经 `/api/documents/runs/{id}/pdf`）、"复制核实链接"、"停用 / 恢复分享"。`MePage` 的"证书"数字只数没撤销的。`VerifyPage` 加 `DISABLED` 状态、节点证书不画有效期；`TakerPage` 列节点证书。三语文案、axe（亮暗）。
- **发起方页面**：发起方中心（`shell/areas.ts` 的 `hub`）加标签"证书"（`profilePath('certificates')`，只加标签，F-S4c 的"挑战"标签同一做法）：列表、下载、分享开关。`@jabiz/client` 的调用与类型两个 SPA 各自的 `qb/` 相同。
- **管理系统扩展**：节点编辑器加证书名称字段（模板与发起方的图；副本只读）；新页"证书查询"（平台菜单与运营商菜单各一处，按证书号或用户，显示状态与点亮记录链接）；`UserSummaryPage` 的"证书"一节加新列。
- **e2e**：`certificates.spec.ts` 改写（旧的"暂停"画面去掉）：学者在平台图上合格 → 点亮 → App 出现证书 → 下载 PDF → 未登录打开核实页显示有效、没有有效期一栏 → 停用分享显示"已停用"；运营商的发起方在"挑战"点亮模板节点（F-S4c 的 `sponsor-challenge.spec.ts` 同一场景）→ 发起方页面的"证书"出现 → 平台撤销点亮后核实页显示"已撤销"；删除账号后核实页"查无此证"。键盘与 axe（亮暗）。
- **文档**：帮助"怎样核实证书"（`docs/quizbuks/help/knowledge-map.md` 加一节，学者与发起方各一问，三语；写明：核实页显示什么、没有有效期、撤销与删除账号后的显示、怎样停用链接；运营商的称呼日文用運営パートナー）；`operations.md` §7.27 加"节点证书"（签发、补发、任务 `qb.tree-certificate-retry`、`CERTIFICATE_MISSING`、查询、颁发方名与 `qb.issuer.name` 须在上线前设好）与告警表一行；`backend/quizbuks/CLAUDE.md` 的"证书"一段改写；`taker-app.md` 的"证书"备注；进度表；术语（`glossary.md`：节点证书）。

## 4. 测试清单

- **单元**：`TreeRulesTest`（证书名称的规则、副本不能改）、`CertificateKeysTest`（键的派生不重复、不含点亮编号）。
- **IT**：`NodeCertificateIT`（点亮即有一张、重复事件与重复补发只一张、同一点亮不会有两张；没有设证书的节点不发；证书在点亮后才加到节点的，已点亮的不补；撤销后核实页 `REVOKED`、再点亮得新证书；颁发方：平台图 / 发起方的图 / 副本；发起方主体；既是学者又是发起方时互不可见）、`CertificateDocumentIT`（首次下载签发、再下载同一字节、别人 404、没有有效期一栏的 PDF 文字、旧 `qb.certificate` 的下载不变）、`CertificateShareIT`（停用 / 恢复、公开页不列）、`CertificateLookupIT`（平台按号、运营商只见自己的、别的运营商空）、`TreeCertificateRetryIT`（事件丢失后任务补发、`CERTIFICATE_MISSING`）、`TakerErasureIT` / `SponsorErasureIT` / `ErasureIT`（行与 PDF 清除、核实页查无）、`CertificateIT` 改写（旧证书核实、过期、撤销保持）、`TreeItSupport` 夹具、`ProcessAccessIT`、`SetupIT` / `QbRolesTest`（新权限）、`MessagesTest`（新代码与站内信）、`OpenApiSnapshotIT`、`PublicQueriesSnapshotIT`（`public-queries.json`）、`PlatformCheckIT`、`SealedColumnsHistory`。
- **断言只插入**：`qb_certificate` 是普通表，`CertificateIT` 的 `@AfterEach` 改为"只有 `shared` 一列会被更新"的检查（`pg_stat` 之外读审计）。
- **语句数**：`AttemptStatementBudgetIT` / `...WithoutHeldWritesIT` 的数不变（断言）；`QB_TREE_EVALUATE` 的点亮路径若有预算断言则只多事件一条写入，写进预算并说明原因；`QB_TREE_LIT_CERTIFICATE` 与签发流程各加预算断言。
- **前端**：Vitest + axe（亮暗）：`CertificatesPart`、`VerifyPage`（四种状态）、`TakerPage`、发起方的"证书"页、`TreeEditor` 的证书字段与 `tree-draft`、管理扩展的查询页。
- **e2e**：见 §3。

## 5. 不做

- 全网证书（规格"不做"）、"等下一场"的订阅与提醒。
- 二维码（Q77 已决：先印地址与编号）、有效期（A2-12）、证书的转让与重发新键。
- 点亮之后才加到节点上的证书不补发给已点亮的人（冻结于点亮时）。

## 6. 删除的功能

- **无**。Q77 的旧证书（`source = 'CERTIFICATION'`）保留它们的有效期显示、核实、过期与撤销（只读表 `qb_certification_record`、`qb_sponsor_certification` 与它们的读取保留）；"没有有效期"只对节点证书。唯一被替换的是 `QB_CERTIFICATE_ISSUE` 的"暂停"实现（改为节点证书的 PDF 签发）与 `QB_CERTIFICATE_PAUSED` 的提示。F-S2 说的"F-S5 迁移后再删"那两张只读表，按 §8 第 5 点的建议长期保留（旧证书的核实依赖它们），并在 F-S2.md 中改写那两处说明。

## 7. 风险

- **旧表扩成两种来源**：`qb_certificate`、两个公开模板与三个读取处要同时认两种形状。缓解：`source` 列加 `CHECK`，旧证书的所有现有测试照跑，模板内按 `source` 分支，读取的 Java 集中在 `CertificateProcesses` 一处。
- **PDF 单据只有签发人读得到**：若在点亮时由系统签发，持有人下载不到。缓解：首次下载由持有人签发（§2.2）；代价是 PDF 的"签发时刻"晚于点亮，单据上写点亮日期、不写签发时刻。
- **事件与每日补发的缝**：点亮提交了、事件没发出，证书最长晚到次日 03:41。缓解：补发任务、核对项、`JOB_RUN_REQUEST`；证书行不丢（锁内重读 + `lit_id` 唯一）。
- **冻结在点亮上的名称**：节点后来改名 / 撤销了证书设置，不影响已点亮的人；这是有意的（证书是点亮当时的承诺），要写进帮助与运维手册。
- **颁发方名取值**：平台图依赖 `qb.issuer.name`，空时用消息的缺省；上线检查清单要核对。运营商 / 发起方改名后，已签发的证书保持原名（冻结）。
- **公开页的隐私**：节点证书由系统自动产生，不是持有人"主动签发"；缺省 `shared = true` 只意味着知道键的人能核实，公开认证页仍须持有人开启并取公开名（Q77）。核实页继续 `noindex`。
- **与 F-S4c 的合并**：发起方中心的标签、`TreeEditor` 的字段、`tree-draft` 都与 F-S4c 同文件；F-S5b 在 F-S4c 之后开，迁移号合并前改。

## 8. 要需求方 / 总控定的点

1. **运营商的副本能不能改证书，颁发方写谁？** 建议：副本照抄模板、不能改（同赠送）；颁发方 = 图的主人，副本写运营商名。（Q90 §0-21 曾写运营商可改证书，规格只写"平台图写平台、发起方图写发起方名"。）
2. **PDF 何时签发？** 建议：证书（记录与核实页）点亮即有，PDF 在持有人首次下载时签发并存档（单据只有签发人能读）。
3. **撤销后再点亮**：建议签发新证书（新键），旧证书保持"已撤销"；赠送"终身一次"的规则不适用于证书。
4. **分享缺省**：建议核实链接缺省启用（键不可猜、不列在任何地方），持有人可停用 / 恢复；公开认证页的列出仍是持有人开启公开名之后的选择。
5. **两张只读旧表与旧证书**：建议长期保留（旧证书仍可核实，不减少已做好的功能；F-S2.md 的"迁移后再删"在 F-S5a 中改写为"保留"），不留"以后再删"的尾巴。另一选项：需求方确认任何环境都只有测试数据时，在 F-S5a 的迁移里一并清除旧证书行、两张表与读取代码（约 +0.5 天，属删除功能，须需求方明确同意）。
6. **点亮前已有的点亮**（F-S5 之前点亮、节点后来才设证书）不补发：建议如此（冻结于点亮时）。
7. **节点证书名称的语言**：建议三语至少填一种，签发时取所选语言、缺则英文再任一种；还是要求三语全填？
8. **发起方的证书是否要出现在其公开页**：规格只要发起方页面看自己的；建议本项不做公开列出（核实页可用）。

## 9. 粗估

F-S5a 1.5–2 天、F-S5b 1–1.5 天，合计 2.5–3.5 天（规格粗估 2–3 天；增量来自旧证书的两种形状、运营商查询与发起方一侧）。
