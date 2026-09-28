# CLAUDE.md — culture 应用规则

应用 Culture, Unfiltered 的后端（模块 `backend/culture`）。根目录 `CLAUDE.md` 的全部规则照常适用；本文件只写本应用额外的约定。
设计：`docs/culture/00-design.md`；需求：`docs/culture/brief.md`；阶段：`docs/culture/ROADMAP.md`；编辑指南：`docs/culture/editor-guide.md`。
公开网站（`site/`）的规则在 `site/CLAUDE.md`。

## 1. 边界

- 本分支只改 `.jabiz-app-paths` 中的目录。平台缺能力或有缺陷时，先在 `platform` 的工作分支上补（带平台自己的测试与示范，不提 culture），
  合入 `platform` 后再合并到 `culture`。C1 中这样补过：显式初始状态 `st.initial`、`CallProcess.forEach`、违规只报告一次（平台 13e）。
- 包 `com.jabiz.culture`；启动类 `CultureApp`；表前缀 `cu_`；迁移 `db/migration/V*__culture_*.sql`；文案 `messages_{zh,ja,en}.properties`。

## 2. 个人数据（参与者多为未成年人）

- 全部实体是**普通实体**（非时态）：删除权要求能真正删除。不要把任何 culture 实体改成时态实体。
- 操作记录（`op_process`）只追加、不可清除，因此**流程输入只含主键、枚举与布尔**；唯一的自由文本（退回意见）标 `@Sensitive` 并在 `toString()` 中遮蔽。
  新流程照此办理；违规的参数与文案只放主键与代码，**不放名字或正文**（失败的操作会记录错误信息）。
- 登录账号与 `accountActorId` 用化名（如 `cu-jp-01`）；`displayName` 只写名或化名，不写姓；不存学校、街区或精确位置。
- 同意书文件（`culture.consent-doc`）只有 `culture.consent.read` 可读，任何公开视图都不得引用它。

## 3. 数据与工作流

- 主键为 UUIDv7 文本（`varchar(36)`），这是平台对普通实体主键的规范类型；文件字段为 `uuid`，不加外键。
- 业务唯一性用 `eb.unique(名, …)` 声明，并在迁移中建**同名**唯一约束（启动检查核对）。
- 状态、`visibility`、`editable`、发布时间、审核意见都是 `processOnly`，只由流程改变；状态机回到起点，初始状态用 `st.initial(...)` 显式声明。
- 通讯员的"只能改自己的、提交后不能改"完全由数据视图范围实现（`own:` 视图：操作人 + 可编辑状态）。不要为此另写检查代码。
- 同一流程中同一行只登记一次更新（第二次会与第一次冲突，409）：用 `Workflow.setAll(ctx, rows, Map)` 合并字段。
- 发布检查（`PublishCheck`）与同意规则（`ConsentRule`）是纯函数，所有规则都有单元测试；新增规则时同时加错误码的三种语言文案与测试。
- 数据库字典（媒体类型、活动类型、年龄段）的初始值是迁移中的基础数据（`jabiz_dict_put`），启动检查要求它们存在；编辑可在后台增加。
- 开关（`culture.review.required`、`culture.consent.guardian.required`）是平台业务参数，由 `CULTURE_SETUP` 创建，流程以 `LoadParams` 按操作时间读取。

## 4. 公开接口（设计 §7）

- 公开视图 `urn:jabiz:dataset:public:<实体>`（`Culture.publicDataset`）只含设计 §3 中标 ✓ 的字段；新增公开字段时同时改设计、视图白名单与模板。
  `Consent` 永远没有公开视图；任何公开视图都不得含 `culture.consent-doc` 文件字段。
- 公开模板在 `queries/culture/public/*.sql`（`access: public`、`cacheSeconds: 60`），只经公开视图读取。视角与媒体必须与公开的
  `Participant` 联接（隐藏的参与者不出现），故事的子项必须与公开的 `Story` 联接（设计 §7.1 的连带）。
- `CAST(:p AS varchar)` 而不是 `AS text`：`text` 是 `Contribution` 的列名，平台检查会报"裸物理名"。
- 搜索用 `cu_i18n_text(...)` 与 `cu_like_pattern(:q) ESCAPE '\'`，表达式与 `V4__culture_search.sql` 的索引逐字相同。
- 把内容下线的流程以 `.afterCommit(…, FileAccess.invalidate(…))` 立即结束文件的公开判定（`Workflow.files` 收集文件 id）。
- 模板变化后：`./gradlew :culture:test --tests '*PublicQueriesSnapshotIT' -Dpublic-queries.update-snapshot=true`（写 `site/src/api/public-queries.json`）
  → `site/` 下 `pnpm gen:api`，一起提交。新模板要在属性测试 `PublicVisibilityPropertyIT` 的 `checkTemplates` 中调用（它断言全部公开模板都被调用过）。

## 5. 测试

- 集成测试继承 `CultureItSupport`（真实 PostgreSQL、真实令牌、BlockHound）；每个测试前执行 `CULTURE_SETUP`（幂等）。
  公开接口的测试继承 `PublicItSupport`（匿名 `publicItems` / `publicFile`）；测试配置打开公开访问并放宽限流。
- 场景回放：`src/test/resources/scenarios/culture/*.yml`，快照随变更提交；更新快照：
  `./gradlew :culture:test --tests '*ScenarioTest' -Dscenario.update-snapshots=true`。
- 常用命令（在 `backend/` 下）：`./gradlew :culture:check`（测试 + `platformCheck`）、`./gradlew :culture:bootJar`（含 `/` 与 `/admin/` 两个 SPA）。
- 路径检查：仓库根目录 `tools/check-app-paths.sh`。

## 6. 运行

- 本地：`docker compose -f deploy/culture/docker-compose.yml up -d --build`（数据库端口 5437、应用 8080）；
  或只起数据库后在 `backend/` 下 `./gradlew :culture:bootRun --args='--spring.profiles.active=dev'`。
- 首次部署后由管理员在后台执行一次 `CULTURE_SETUP`。
