# F-C1 发起方的资源库：实施计划（S3）

规格：`docs/quizbuks/first-launch-spec.md` F-C1（来源 Q110a）。计量是 F-C2，共享视频是 F-C3，不在本计划中。

## 分成四个 PR

| PR | 分支 | 内容 |
|---|---|---|
| **F-C1a** | `1.3/quizbuks-fc1a-assets` | 后端：资源本身（实体、规则、三个写入流程、数据视图、收集的候选模板、参数、运营商范围、抹除）与测试 |
| F-C1b | `1.3/quizbuks-fc1b-asset-uses` | 后端："用在哪里"：现有的 `QB_MATERIAL_SAVE`、`QB_QUESTION_SAVE`、`QB_QUIZ_SAVE`、`QB_PUBLICATION_SAVE` 多带资源编号，在同一事务中写 `QbAssetUse`；"旧内容"的判断模板 |
| F-C1c | `1.3/quizbuks-fc1c-asset-ui` | 发起方页面：资源库页、选择对话框、"存入资源库"、收集；商品与认证迁入，旧地址转向 |
| F-C1d | `1.3/quizbuks-fc1d-asset-ops` | 平台管理系统扩展的只读一栏（发起方详情）、帮助与 FAQ、`operations.md`、e2e（Q110 §7） |

**界面的位置**（需求方 2026-11-07 答复）：Q108 的新框架（P3）合入之前，资源库先放在发起方页面现有菜单的"内容"组；P3 合入后在 F-F3 中迁到"资源库"区域。规格 F-C1 中"§5 的过渡做法取消"一句随之改为与开发安排第 3 部分一致（本 PR 同时改规格）。

## F-C1a 的设计

### 数据（迁移 `V77__quizbuks_assets.sql`，合并前按 `1.3/quizbuks` 的最大号 + 1 重编）

**`QbAsset`**（普通表 `qb_asset`，主键 `asset_id` UUIDv7）。这是发起方账户自己的记录，按"发布方"的范围规则用 `sponsorId`，不带 `publisherKind` 那一对。

| 字段 | 说明 |
|---|---|
| `sponsorId` | 不可变，操作人 |
| `kind` | `IMAGE` `PDF` `AUDIO` `VIDEO_LINK` `LINK` `ARTICLE` `TEXT` `PRIZE`；不可变；字典 `urn:jabiz:dict:quizbuks:asset-kind` |
| `name` | 必填，≤ `ContentLimits.MATERIAL_TITLE_LENGTH` |
| `description` | 可选，≤ `MATERIAL_DESCRIPTION_LENGTH` |
| `language` | 基准语言，`ContentLanguages` |
| `translations` | jsonb 文本，同 Q73c 的 `TextTranslations`：`{语言: {字段: {text, hash}}}` |
| `tags` | jsonb 文本数组（平台没有多值字段） |
| `image` / `pdf` / `audio` | 文件引用分开三列，`FileKind.of(qb.content.image|pdf|audio)`，因此平台清扫不会删 |
| `url` | `VIDEO_LINK` 与 `LINK`，≤ `URL_LENGTH` |
| `durationSec` | 只给 `VIDEO_LINK` |
| `body` | `ARTICLE`（≤ `MATERIAL_BODY_LENGTH`）与 `TEXT`（按用途的上限） |
| `usage` | 只给 `TEXT`：`QUIZ_INTRO`（≤ `INTRO_LENGTH`）、`REDEEM_DESCRIPTION`、`DIGITAL_DESCRIPTION`、`REFERRAL_DESCRIPTION`（≤ `PublishingLimits.REDEEM_DESCRIPTION_LENGTH`） |
| `prizeMethod` | 只给 `PRIZE`：`OFFLINE_REDEEM` / `DIGITAL_PRIZE` / `SPONSOR_CASH` |
| `prizeDescription` | 只给 `PRIZE`，≤ `REDEEM_DESCRIPTION_LENGTH` |
| `digitalDelivery` | 只给 `DIGITAL_PRIZE`：`POOL` / `SHARED` / `SPONSOR_SENT` |
| `digitalSendDays` | 只给 `SPONSOR_SENT`，1 至 `MAX_DIGITAL_SEND_DAYS` |
| `contentHash` | 去重用：文字内容的 SHA-256（十六进制，UTF-8，不做规范化），SQL 中 `encode(sha256(convert_to(…, 'UTF8')), 'hex')` 与之相同 |
| `revision`、`createdAt`、`editedAt`、`version` | |

`revision`、`editedAt`、`contentHash` 都是 `processOnly`。另建两个索引：`(sponsor_id, kind)` 与 `(sponsor_id, content_hash)`。

**`QbAssetUse`**（普通表 `qb_asset_use`）在本 PR 只建表与视图，F-C1b 起写入。

| 字段 | 说明 |
|---|---|
| `useId` | 主键 |
| `assetId`、`sponsorId` | |
| `targetKind` | `MATERIAL` `QUESTION_IMAGE` `OPTION_IMAGE` `QUIZ_COVER` `QUIZ_INTRO` `PUBLICATION_COVER` `PUBLICATION_REDEEM` `PUBLICATION_DIGITAL` `PUBLICATION_REFERRAL` `PUBLICATION_PRIZE` |
| `targetId` | |
| `assetRevision` | 复制时资源的 `revision`，用于"旧内容"提示 |
| `usedAt` | |

唯一约束 `(target_kind, target_id)`：一处只来自一个资源。

### 规则 `asset.AssetRules`（纯 Java）

- 输入记录为全部字段，输出为全部问题（一次列出）。
- 每种的内容字段只有自己的（同 `MaterialKind.contentField` 的做法），其余必须为空。
- 视频链接必须是 `VideoEmbeds.player` 认得的网站，时长在 `MIN_VIDEO_SECONDS`–`MAX_VIDEO_SECONDS`。
- 标签：去掉首尾空白后不为空、不重复、个数 ≤ `qb.assets.max-tags`、每个 ≤ `qb.assets.tag-length`。
- 译文按 `TextTranslations.problems` 检查。
- `contentHash(…)` 也在这里。
- 错误码在 `asset.AssetCodes`（`ALL`，三语文案）。

### 流程 `asset.AssetProcesses`

共同的部分：

- 权限 `qb.content.write`（发起方已有，不改 `QbRoles`），`requiresVerifiedEmail()`，`CallerChecks.refuseBanned`。
- 锁 `HoldLock.exclusive("qb.assets:" + 发起方)`，锁内读本人的全部资源，数量上限在锁内检查。
- 带 `baseRevision`：读到的修订号不是当前的即 422 `QB_ASSET_CHANGED`（同商品的做法）；版本冲突照平台给 409。
- 不是本人的资源即 404。
- 新放入的文件须由本人上传（读平台文件视图的 `uploadedBy`，同 `QuizEditing.requireOwnFile`），否则 `QB_ASSET_FILE_NOT_OWN`。

三个流程：

- `QB_ASSET_SAVE`：新建或修改一个资源（`kind` 不可改）。
- `QB_ASSET_DELETE`：删除资源与它的 `QbAssetUse` 行。已复制到使用处的内容不受影响，文件由平台清扫。
- `QB_ASSET_COLLECT`：把勾选的候选收进库（每项同 `QB_ASSET_SAVE` 的输入，不带编号）。
  - 再次去重：文件按编号、文字按 `contentHash`，与库中已有的与同一批之间都去重；重复的跳过并在输出中列出。
  - 超过数量上限即整体拒绝。
  - 输出为新建的编号与跳过的项。

### 候选模板 `qb.sponsor.asset-candidates`

- 权限 `qb.content.write`。只读本人：未移除的 Quiz、自己的发布草稿与已提交的发布。
- 列出这些内容：资料（基准，不含语言版本）的图片、PDF、音频、视频链接、网页链接、文章；Quiz 的封面与简介；题目与选项的图片；发布的封面、到店兑换说明、电子奖品说明、推荐说明。
- 去掉库中已有的（文件按编号，文字按哈希）与重复的。
- 只在本人打开"收集"时读，不定时同步。

### 数据视图

- 默认：`asDefault`，读权限 `qb.admin.publication.read`（客服），`processOnlyWrites`，`OperatorScopes`（两个实体都登记 `sponsorId`）。
- `me:`：`SPONSOR_ME`，只读、限本人。

### 参数（`QbParams.ALL`，不受控）

- `qb.assets.max-count`：缺省 1000；
- `qb.assets.max-tags`：缺省 10；
- `qb.assets.tag-length`：缺省 30。

### 抹除

`SponsorErasure` 登记两个实体：`ErasedEntity.delete`，用户字段为 `sponsorId`。

### 文案

三语：实体与字段、流程、参数、错误码、模板与列名、两个字典。

## 测试（F-C1a）

- `AssetRulesTest`：每种的字段、长度、标签、视频网站与时长、奖品的组合、译文、哈希与 SQL 的一致（固定的例子）。
- `AssetIT` 覆盖：
  - 保存、修改（`baseRevision`）、删除；
  - 数量上限（锁内）；
  - 只看本人（`me:` 视图）；
  - 别人的文件被拒；
  - 别人的资源 404；
  - 运营商只读并只看到自己的发起方；
  - 收集：模板列出候选并去重、流程再去重；
  - 删除账号清除资源（`SponsorErasureIT`）。
- `MessagesTest`（新码类的 `ALL`）、`OperatorScopesIT`、`platformCheck`、场景快照（`QB_SETUP` 多建三个参数）。
- 答题路径不变（`AttemptStatementBudgetIT` 不跑也不改：本 PR 不碰答题流程）。

## 风险

- 候选模板连接的表多，第一次写容易漏列；以 `AssetIT` 中造好的各种内容逐项断言。
- 规格要求"选了就复制"，F-C1a 只有资源本身，复制与引用在 F-C1b；在 F-C1b 合入之前，资源库只是目录。

## F-C1b 的设计

"选了就复制"：前端把资源的内容复制进使用处，照常经现有的保存流程保存，同时说明"这一处来自资源 X"。服务器只核对与登记：
资源是本人的、种类适合这一处，然后在同一事务中写 `QbAssetUse`。存下的内容与手填的完全相同（只多一行使用记录）。

### 输入（都可不给；不给即不动这一处已有的使用记录）

| 流程 | 新的输入 | 使用处（`targetKind`） | `targetId` |
|---|---|---|---|
| `QB_MATERIAL_SAVE` | `MaterialInput.assetId` | `MATERIAL` | 资料编号（新资料为保存时得到的） |
| `QB_QUESTION_SAVE` | `QuestionInput.imageAssetId`；`OptionInput.imageAssetId` | `QUESTION_IMAGE`；`OPTION_IMAGE` | 题目编号；选项编号（新选项为保存时得到的） |
| `QB_QUIZ_SAVE` | `QuizInput.coverAssetId`、`introAssetId` | `QUIZ_COVER`、`QUIZ_INTRO` | Quiz 编号（新 Quiz 为保存时得到的） |
| `QB_PUBLICATION_SAVE` | `SaveInput.assets`（`PublicationAssets`：`coverAssetId`、`redeemAssetId`、`digitalAssetId`、`referralAssetId`、`prizeAssetId`） | `PUBLICATION_COVER`、`PUBLICATION_REDEEM`、`PUBLICATION_DIGITAL`、`PUBLICATION_REFERRAL`、`PUBLICATION_PRIZE` | 发布编号（新草稿为保存时得到的） |

各记录原有的规范构造器保留为"F-C1b 之前"的构造器（不带资源编号），现有的调用不变。

### 种类是否适合（`AssetRules.suits`，纯 Java；不适合即 422 `QB_ASSET_TARGET_KIND`，字段为该输入）

| 使用处 | 资源的种类 |
|---|---|
| `MATERIAL` | 与资料的种类对应：`IMAGES`↔`IMAGE`、`PDF`、`AUDIO`、`VIDEO_LINK`、`LINK`、`ARTICLE`；只限基准语言的资料（其他语言的资料随基准，不是"从库里选"的地方，候选模板也只列基准的） |
| `QUESTION_IMAGE`、`OPTION_IMAGE`、`QUIZ_COVER`、`PUBLICATION_COVER` | `IMAGE` |
| `QUIZ_INTRO` | `TEXT`，用途 `QUIZ_INTRO` |
| `PUBLICATION_REDEEM` / `_DIGITAL` / `_REFERRAL` | `TEXT`，用途 `REDEEM_DESCRIPTION` / `DIGITAL_DESCRIPTION` / `REFERRAL_DESCRIPTION` |
| `PUBLICATION_PRIZE` | `PRIZE` |

只核对种类与用途，不核对复制进来的内容是否与资源相同（使用处与资源之后各管各的）。

### 共用的 `asset.AssetUses`

- `load(pb, 资源编号, 使用处)`：先在流程自己的锁之后取 `HoldLock.shared(QbAssets.lockName(操作人))`，再以一条 `QueryEntities.all` 读出所给资源（`assetId` 在所给之中且 `sponsorId` = 操作人）与所给使用处已有的使用记录（`targetKind` 与 `targetId` 都在所给之中，用到唯一约束的索引）。
- 不选资源、也不删使用处时，锁名为空（`HoldLock` 不取锁）、两条查询都是空的 `IN`（平台不发语句）：现有的保存一条语句也不多。
- `check(ctx, 所选)`：资源不是本人的或不存在即 404（同 `AssetProcesses`，不说是否存在）；种类不适合即 `ctx.reject` 累积。放在流程的主计算之前单独一步，主计算见到违规即不写。
- `record(ctx, 种类, 编号, 资源)`：在主计算得到使用处的编号以后登记：删掉这一处原有的记录（若有），插入新的（`assetRevision` = 资源当前的 `revision`，`usedAt` = 操作时间）。`assetId`、`targetKind`、`targetId` 不可变，所以"再选一次"是删旧插新，不是更新。内容没变（`unchanged`）时也登记。
- `forget(ctx, 种类, 编号…)`：删掉这些使用处的记录。

### 锁的顺序

流程自己的锁（`qb.quiz:<quizId>` 或 `qb.pub:<publicationId>`）→ `qb.assets:<发起方>`（共享）。资源的三个流程只独占地取 `qb.assets:<发起方>`、从不取内容或发布的锁，所以不会互等。
资源的读取在共享锁之内：同时进行的 `QB_ASSET_SAVE` 先完成或后开始，读到的 `revision` 就是提交时的；同时进行的 `QB_ASSET_DELETE` 不会在我们写入以后又留下一个指向已删资源的记录（它独占锁，删的是它读到的全部使用）。
同一发起方的几次内容保存共享这把锁，互不等待。

### 删除使用处

- `QB_MATERIAL_DELETE`：删掉该资料（与它的其他语言版本）的 `MATERIAL` 记录。
- `QB_QUESTION_DELETE`：删掉题目的 `QUESTION_IMAGE` 与其选项的 `OPTION_IMAGE`。
- `QB_QUESTION_SAVE` 给出选项列表而去掉了某些选项：删掉这些选项的 `OPTION_IMAGE`。
- `QB_QUIZ_DELETE`（移除 Quiz，删掉它的资料、题目与选项）：删掉该 Quiz 全部使用处的记录（封面、简介、资料、题目、选项）。
- `QB_PUBLICATION_DELETE`（删除草稿）：删掉该发布的 `PUBLICATION_*` 记录。
- 这些流程总要取共享锁并读一次（事先不知道有没有记录）；删除不是热门路径。
- `QB_ASSET_DELETE` 照旧删掉资源的全部使用记录；复制、提交、版本切换不带使用记录（复制出的草稿的内容来自原发布，不是来自资源库），已提交的发布不再保存，记录随之不变，"旧内容"照常提示。

### 模板 `qb.sponsor.asset-uses`

- 权限 `qb.content.write`，数据视图都是本人的（`me:QbAssetUse`、`me:QbAsset`、`sponsor:` 的 Quiz、资料、题目、选项与发布）。
- 列：`useId`、`assetId`、`assetName`、`assetKind`、`targetKind`、`targetId`、`quizId`、`publicationId`、`targetName`（Quiz 的标题、资料的标题或 Quiz 的标题、发布的名称；已移除的 Quiz 为空）、`assetRevision`、`currentRevision`、`stale`（资源当前的 `revision` > 记录的 `assetRevision`："用的是旧的内容"）、`usedAt`。
- 筛选 `assetId`、`targetKind`、`stale`。三语的标题与列名。

### 测试

- `AssetRulesTest`：`suits` 的每一行（适合与不适合，资料的每种）。
- `AssetUseIT`：
  - 四个流程各带资源编号保存：写下记录，`assetRevision` 是资源当前的；新建的使用处（新资料、新选项、新 Quiz、新草稿）取保存得到的编号；
  - 别人的资源 404，种类或用途不适合 422 `QB_ASSET_TARGET_KIND`（一次列出），都不写任何东西；
  - 再选一次替换记录（同一处只有一行）；不给资源编号不动已有的记录；
  - 资源改过（`QB_ASSET_SAVE`）以后，模板标出 `stale`，已提交的发布的内容不变；
  - 删除资料、题目、选项、Quiz、草稿都删掉相应的记录；删除资源删掉它的记录；
  - 平台清扫（`FILE_PURGE_ORPHANS`）不删只被资源引用的文件；
  - 带资源编号保存与手填保存存下相同的内容（只多一行记录）。
- 照旧通过：`MessagesTest`、`BrandTest`、`AssetIT`、四个流程现有的 IT、`ScenarioTest`、`AttemptStatementBudgetIT`（答题路径不变）、`OperatorScopesIT`、`SponsorErasureIT`、`:quizbuks:platformCheck`。

### 风险

- "不给即不动"：发起方之后手工改了这一处（换了封面），记录仍说它来自资源；前端只在这一处仍是资源的副本时带上编号，"用在哪里"只是提示。要准确就得每次保存都读一次记录，不值得。
- 删除使用处的流程多了一个锁与一次读取；内容保存在不选资源时语句数不变（锁名为空、空的 `IN` 不发语句）。
- 共享锁在流程自己的锁之后：若以后有流程先取 `qb.assets` 再取内容的锁，就会互等；`CLAUDE.md` 的"资源库"一条写明顺序。

## F-C1c 的设计

发起方页面（`quizbuks-web/sponsor`）的资源库：资源库页、编辑、收集、"用在哪里"、"从库里选"与"存入资源库"，以及商品与认证迁入（见下面"商品与认证"）（需求方 2026-11-07 确认：迁入资源库的"商品""认证"标签，页面与操作原样、旧地址转向、概览小计保留）。

### 位置与地址

- 菜单"内容"组加"资源库"（需求方 2026-11-07：P3 合入之前放在这里，F-F3 迁到"资源库"区域），地址 `/assets`。
- 页内标签：全部 / 素材（图片、PDF、音频、视频链接、网页链接、文章）/ 文字 / 奖品 / 商品 / 认证；当前标签写在地址中（`?tab=`），刷新与后退保持。"全部"只列资源（商品与认证不是资源）。

### 商品与认证（需求方 2026-11-07 确认：迁入资源库的"商品""认证"标签，页面与操作原样、旧地址转向、概览小计保留）

- "商品"标签就是原来的商品页（`products/ProductsPage`，Q60b：新建、修改、上下架、排序、删除，原样）；"认证"标签就是原来的认证页（`certifications/CertificationsPage`，Q88a，原样；只在标签中把标题换成 h2，页内只有一个 h1）。数据、流程、组件都不改。
- 发起方中心去掉"商品"一节（`PROFILE_SECTIONS`），菜单去掉"认证"一项；"内容"组有"资源库"。
- 旧地址转向（`profile/paths.ts` 的 `MOVED_PATHS`）：`/profile/products` 与更早的 `/products` → `/assets?tab=products`，`/certifications` → `/assets?tab=certifications`。
- 概览的清单照旧数已上架的商品，链接改到新的标签。
- 测试：原有测试只改地址；另测旧地址转向（`products.test.tsx`、`certifications.test.tsx`）、发起方中心只剩五节、菜单的项目；e2e 的 `a11y.spec.ts` 经资源库进入商品，`sponsor-certification.spec.ts` 打开新地址并检查旧地址转向。
- 功能目录 `src/assets/`：名字在 `ids.ts`（流程、模板、数据视图、字典），手写类型与调用在 `api.ts`，文案在 `messages.ts`（命名空间 `sponsor` 的 `assets`，三语，同 `certifications/messages.ts` 的做法）；
  只有发起方页面用到，不进 `qb/`。

### 读取

- 资源：`me:QbAsset`（`POST /api/datasets/{id}/query`，每页 200，按 `offset` 读完；上限 `qb.assets.max-count` 缺省 1000）。`tags` 与 `translations` 是 JSON 文本，在 `api.ts` 中解析。
- 搜索（名称、说明、标签）、种类、标签的筛选都在页面上对已读到的行做：标签存为 JSON 文本，服务端筛不了；行数有上限。
- 用在哪里：模板 `qb.sponsor.asset-uses`（筛选 `assetId`），打开某个资源的"用在哪里"时才读；`stale` 的行标出"用的是旧的内容"，Quiz 的链到编辑器、发布的链到发布页。

### 页面与组件

| 组件 | 内容 |
|---|---|
| `AssetsPage` | 页头（新建、收集）、标签、搜索框、种类与标签的筛选、`DataTable`（`meta.card`：名称 `title`、种类与用途 `subtitle`、标签 `badge`、操作 `actions`；`storageKey="qb.sponsor.assets"`；行的 testid 经 `rowProps`）、空状态（`EmptyState`） |
| `AssetDialog` | 新建与修改：先选种类（修改时不可改），按种类只显示自己的内容字段（同 `AssetKind.contentFields`）；名称、说明、基准语言、标签；译文用现有的 `TextTranslationsFields`（另外两种语言，同商品，Q73c）；文件经 `FileField`（`qb.content.image|pdf|audio`，中性文件名）；带 `baseRevision`；服务端的问题一次列出、能对上字段的放在字段下（`tags[i]` 放在标签下，`translations.*` 放在译文框下），其余列在表单末尾 |
| `AssetUses` | 一个资源的"用在哪里"对话框 |
| `CollectDialog` | 读 `qb.sponsor.asset-candidates`（每页 200，至多 `MAX_COLLECT` = 200 项一次），勾选（全选），运行 `QB_ASSET_COLLECT`，显示新建与跳过的个数；候选的名称为空时以使用处的种类作名称（服务端要求名称） |
| `AssetPicker` | "从库里选"对话框：只列适合这一处的资源（`suits.ts`，照抄 `AssetRules.suits`，同一组用例），可搜索；方向键在列表中移动、回车选定（`@jabiz/ui` 的 `Command`） |
| `SaveToLibrary` | "存入资源库"按钮：以这一处现在的内容建一个资源（`QB_ASSET_SAVE`），名称取这一处的标题或种类名，可在弹出的小对话框中改名 |

### "选了就复制"与资源编号（F-C1b 的约定）

- 选定后把资源的内容复制进表单（文件引用同一个编号），同时记下"这一处来自资源 X"及复制进来的值（`copy.ts` 的 `AssetCopy`）。
- 下一次保存时，只有这一处的值仍与复制进来的相同才带上资源编号；改过即不带（"不给即不动"，记录仍在，`F-C1b` 风险一节已写明）。保存成功后忘掉这个标记：再保存不再带编号，否则会把记录的 `assetRevision` 刷成当前的，掩盖"旧内容"。
- 复制的是基准语言的内容；译文不复制（使用处有自己的译文流程，Q73）。

### 接上"从库里选"与"存入资源库"的地方

| 地方 | 使用处 | 资源 | 输入 |
|---|---|---|---|
| Quiz 编辑器的基本信息：封面 | `QUIZ_COVER` | 图片 | `QB_QUIZ_SAVE.coverAssetId` |
| 同上：简介 | `QUIZ_INTRO` | 文字（Quiz 简介） | `introAssetId` |
| 新建资料（对话框）| `MATERIAL` | 与资料种类对应的素材 | `QB_MATERIAL_SAVE.assetId`；选定后资料的种类随资源 |
| 发布向导第一步：封面 | `PUBLICATION_COVER` | 图片 | `QB_PUBLICATION_SAVE.assets.coverAssetId` |
| 发布向导：到店兑换说明、电子奖品说明 | `PUBLICATION_REDEEM` / `_DIGITAL` | 文字（对应用途） | `assets.redeemAssetId` / `digitalAssetId` |
| 发布向导：奖品（领取方式一组字段） | `PUBLICATION_PRIZE` | 奖品卡片 | `assets.prizeAssetId`；复制方式、说明、交付方式与发送天数；方式不在本发起方允许的方式中时不可选（列表中标出） |

题目与选项的图片、推荐说明本部分不接（题目对话框的选项是动态列表，要另做按选项的标记），列为余下的工作；"存入资源库"只放在上表中有"从库里选"的地方。

### 文案

`assets.*` 三语（zh/ja/en），用词照 `glossary.md`；不写"后台"。种类、用途、使用处的名字取服务端字典（`asset-kind`、`asset-text-usage`、`asset-use-target`），未答复前显示代码。

### 测试（Vitest + Testing Library，`src/assets/assets.test.tsx`、`suits.test.ts`、`copy.test.ts`）

- 文案三语键一致且非空。
- `suits` 与后端 `AssetRulesTest` 的每一行一致；`AssetCopy` 只在值未改时给出编号。
- 资源库页：按标签、搜索、种类与标签筛选；空状态；删除先确认并带 `baseRevision`；"用在哪里"标出旧内容；`expectAccessible`（亮、暗）。
- 编辑：每种只发自己的字段；修改带 `baseRevision`；服务端的多个问题同时显示在各自字段下；标签的增删。
- 收集：全选、发送所选、显示新建与跳过的个数。
- 选择对话框：只列适合的种类；键盘（方向键、回车）选定；`expectAccessible`。
- 接入处：Quiz 封面与简介、新资料、发布封面与奖品：选定后保存带编号，改过后不带；"存入资源库"发出 `QB_ASSET_SAVE`。
- e2e（`quizbuks-web/e2e/tests/assets.spec.ts`）：存入一张图片、在新 Quiz 中从库里选、改资源后"用在哪里"标出旧内容；需要打包的应用，本部分不运行。

### 风险

- 发布向导很大（`PublicationWizard.tsx`），接入只加按钮与一个 `assets` 输入，不改原有字段的保存方式；`publishing.test.tsx` 照旧通过。
- 页面上筛选全部资源：1000 个以内可行；以后上限调大时改为服务端筛选（标签需要另建表或 jsonb 列）。
- `suits.ts` 是后端规则的抄本，两处一起改（同 `video.ts` 的做法）。
