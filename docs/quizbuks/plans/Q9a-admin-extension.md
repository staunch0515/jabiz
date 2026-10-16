# Q9a 管理员后台扩展：审核与用户汇总 — 实施计划与记录

依据：`02-design.md` §7.3、`plans/QP-pages.md`（Q-5）、平台 12 §9、决策 D22 / D34、指南 `docs/guide/admin-extension.md`；示范扩展 `backend/app/admin-extension/`。
基准：`origin/1.2/quizbuks` @ 51acdfc（已含平台 #152：图片比策略宽度窄时变体请求返回原件）。

## 1. 目标与范围

- 审核人在做决定之前**完整看到**被审核的对象：商家入驻申请（审批绑定的名字、公司、简介、头像、同意条款的时间，账户邮箱与是否已验证），
  或已提交的发布（冻结的版本快照：题目、选项与正确答案、资料、图片；全部设置与档位；商家资料；商家资金只给财务）。
- 决定仍是平台的 `ApprovalPanel`（`APPROVAL_DECIDE`）：扩展不写审批逻辑，只决定在哪里显示它。
- P-01 用户汇总：资料、国家、注册时间、封禁、商家状态、答题历史（不含金额）；资金部分（答题的奖励与抽成、转账、收款账户（状态与后 4 位）、充值）只给财务（`qb.admin.finance.read`）。
- **资金只给财务**（`backend/quizbuks/CLAUDE.md` §5）：页面只在持有 `qb.admin.finance.read` 时显示与请求；内容管理员读的模板本身也不带金额：
  商家资金（`fundsShort`、`pendingCount`、`pendingAmount`）从 `qb.admin.publications` 移到 `qb.admin.publication-funds`，答题的 `reward`、`fee`、`ledgerTransactionId`
  从 `qb.admin.attempts` 移到 `qb.admin.attempt-rewards`（两者都只要 `qb.admin.finance.read`）。发布本身的设置（预算、最高奖励、最多支出、档位的奖励）是审核的内容，照常显示。
- **不在本阶段**：转账监控、对账结果页（以后的财务 PR）。
- 不改平台、不加迁移。

## 2. 设计

| 项 | 做法 |
|---|---|
| 目录 | `backend/quizbuks/admin-extension/`（无 `package.json`；`tsconfig.json` 继承 `../../../frontend/tsconfig.extension.json`）；`build.gradle.kts`：`spa("/admin", "../../frontend", extension = "admin-extension", entry = "admin")` |
| 文件 | `src/index.tsx`（`defineExtension`）、`ids.ts`（数据视图、模板、字典、权限、路径的名字）、`api.ts`（手写类型与读取）、`snapshot.ts`（快照 schema 1 的类型与解析，抄自商家后台 `content/api.ts`）、`hooks.ts`、`components.tsx`、四个页面、`VersionPreview.tsx`、`messages.ts`（命名空间 `app`，三语） |
| 菜单 | 分组 "QuizBuks"：审核（`qb.review.publication`；商家入驻的标签页另需 `qb.review.sponsor`）、用户（`qb.admin.users.read`）。只决定显示 |
| 路由（`/qb`，不占平台路径） | `/qb/reviews`（两个标签页：`qb.admin.publications` 中 `PENDING_REVIEW` 的、`qb.admin.sponsors` 中 `PENDING` 的，等待最久的在前）；`/qb/reviews/publications/:publicationId`；`/qb/reviews/sponsors/:userId`；`/qb/users`（搜索）；`/qb/users/:userId`（P-01） |
| 发布的读取 | 默认数据视图：`QbPublication` 实体（按键）、`QbQuizVersion` 实体（按 `versionId`，只有 `qb.admin.quiz.read` 可读，含正确答案）、`QbRewardTier`（按 `publicationId`）、`QbSponsor`（按 `userId`）；档位与版本按 `[publicationId, approvalRequestId]` 读取；题目名的后备取 `qb.admin.publications`（按新加的筛选 `publicationId`）；资金只给财务，取 `qb.admin.publication-funds` |
| 决定 | `ApprovalPanel requestId={approvalRequestId}` 只在 `PENDING_REVIEW`（入驻为 `PENDING`）且有审核权限时显示。审批绑定版本哈希、设置与档位三者，所以发布的面板只在发布、档位与版本三项读取都成功、都不在重读、且都是为当前 `approvalRequestId` 读的时候给出（任何一项出错即不给，并在该处显示错误）；这些读取不随窗口聚焦重读（否则会收起正在填写的面板）。决定后：请求已结束（`APPROVED` / `REJECTED`）即有界轮询（每秒一次、至多 30 秒）直到状态离开审核，期间提示"状态稍后更新"，超时后提示"还没有更新"并给"重新读取"；只批准了本级（`PENDING`，等下一级）即直接说明，不等待。并刷新队列 |
| 显示 | 图片经 `FilePreview`（策略的变体，小图由服务端返回原件）；PDF 与音频为下载；外链只放 http(s)、新标签页、`noopener noreferrer`；Markdown 只经 `MarkdownView`（不渲染原始 HTML、不放 `javascript:`）；题目为默认展开的折叠区块，文章正文默认折叠；正确选项以文字"正确答案"标记。金额以 `formatAmount` 按 JPY 显示（管理员看真实币种），用户编号以 `UserName` 显示 |
| 权限 | 页面上的隐藏只是导航：内容管理员读不到资金模板（403），财务管理员读不到版本与内容文件（403），商家读不到任何默认数据视图。资金部分没有财务权限时既不显示也不请求 |
| 列表 | 队列（带服务端计数，标签页显示总数）、答题、奖励、转账、充值都按页读取（平台 `DataTable` 的分页，每页 20 行），不在 100 行处截断 |
| 地址 | 用户汇总的地址不是 UUID 即"没有这个用户"，不向服务端询问；用户编号只经 `UserName` 显示 |

后端（只在应用分支）：`qb.admin.users` 加参数 `userId`（`like: SecUser.userId`，按主键查一个用户）；`qb.admin.publications` 的筛选加 `publicationId`、去掉资金列；
新模板 `qb.admin.publication-funds` 与 `qb.admin.attempt-rewards`（`qb.admin.finance.read`），`qb.admin.attempts` 去掉金额列（列的文案随之移到新模板）。

## 3. 测试

- **集成** `it/AdminReviewAccessIT`（以 `QbRoles` 的真实权限签发令牌）：内容管理员读到他人待审发布的发布、版本（含正确答案，内容哈希与版本一致）、档位、入驻与全部内容文件和头像；
  审核人读到的发布、版本与档位重新算出的审批内容哈希等于请求绑定的 `content_hash`；财务管理员读发布但版本与内容文件 403、不能决定；商家（本人与他人）读默认数据视图与 `qb.admin.users` 都 403；
  `qb.admin.users` 按 `userId` 只返回该用户、未知编号为空、非 UUID 为 400；资金模板（转账、充值、收款账户、答题奖励）对内容管理员 403、对财务 200；
  内容管理员两条路都拿不到钱：`qb.admin.publications`、`qb.admin.attempts` 的列（目录 `/api/meta/queries`）与行都不含金额，`qb.admin.publication-funds` 与 `qb.admin.attempt-rewards` 对其 403。
- **场景** `scenarios/qb/accounts.yml`：加一步 `query: qb.admin.users` + `userId`（快照只含实体，查询步骤不改快照）。
- **Vitest**（`vi.mock('@jabiz/admin')`）：队列（模板、筛选、排序、链接、按权限的标签页）；预览显示每道题、正确标记、全部资料与文件、外链与不安全的链接、Markdown 不渲染脚本；
  非待审不显示面板并显示驳回理由；决定后轮询在状态变化后停止、超时后提示并可重读；只批准本级时直接说明；档位读取出错或版本 403 时不显示面板；
  资金只给财务；队列分页与计数；入驻页；用户汇总无财务权限时不显示也不请求资金模板（含奖励）、有权限时显示后 4 位与奖励；非 UUID 地址不请求；
  三语键与占位符一致；真实快照样例的解析。`JABIZ_ADMIN_EXTENSION=../backend/quizbuks/admin-extension pnpm ext:check`（CI `web` 作业）。
- **e2e** `review.spec.ts`：商家申请 → 管理员在 `/admin/qb/reviews?tab=sponsors` 打开申请并批准 → 商家提交发布 → 管理员打开发布，看到题目、正确答案与资料 → 带理由驳回 →
  商家在商家后台看到理由 → 再次提交 → 批准 → `SCHEDULED` / `LIVE` → 用户汇总。`a11y.spec.ts` 加扩展的六个页面（亮、暗）。

## 4. 验收标准

1. `./gradlew :quizbuks:check` 通过；jar 的 `/admin/` 含扩展，启动不报扩展问题。
2. 内容管理员看到每个待审的入驻与发布（完整快照与设置），在该页批准或驳回（驳回须写理由）。
3. 资金只给财务：内容管理员看不到也读不到；预览对财务管理员拒绝。
4. `ext:check`、e2e、axe 通过；`tools/check-app-paths.sh origin/1.2/platform` 通过（不改平台文件）。

## 确认：2026-10-15，按缺省

## 实施记录（Q9a，2026-10-15）

分支 `1.2/quizbuks-9a-admin-extension`（从 `origin/1.2/quizbuks` @ 51acdfc 拉出）。只改应用路径，`tools/check-app-paths.sh origin/1.2/platform` 通过。

- 按第 2 节实现；`:quizbuks:check` 433 个测试通过（含 `AdminReviewAccessIT` 5 个）与 `platformCheck`；扩展 Vitest 23 个测试；e2e 全部 33 个通过（含 `review.spec.ts` 与 `a11y.spec.ts` 中扩展的六个页面，亮、暗）。
- **偏离计划**：
  - `qb.admin.users` 的 `userId` 是**参数**而不是列表筛选：场景回放的查询步骤只接受参数（`Scenario` 的 `query` 只有 `params`），而且参数是 `SecUser` 主键上的条件。
  - `qb.admin.publications` 另加筛选 `publicationId`：发布页按键取一行计数与资金，而不是按商家取一页再找。
  - 加了用户列表页 `/qb/users`（菜单"用户"需要一个落点），按 `q` 搜索并分页。
  - 发布页在版本内容显示出来之前不给决定面板（版本读不到、读不出时只说明原因）；平台的待办页照常可决定。
  - 题目以默认展开的折叠区块显示（"折叠显示"理解为可收起），文章正文默认折叠。
- **已知**：内容文件的读取权限 `qb.content.file.read` 也授予答题人与商家（答题需要图片），所以知道文件编号的人可读任何内容文件（编号是 UUIDv7，不可猜），
  并经 `GET /api/files/{id}` 读到文件的元数据（含原始文件名 `originalName`）；这是既有情况，另作后续处理，本阶段不改，财务管理员没有该权限。
  e2e `content.spec.ts` 的"changed elsewhere"偶发失败（焦点断言，商家后台，与本阶段无关）。
  `QbAttempt` 的默认数据视图（`qb.admin.users.read`，通用后台的生成页面）仍含奖励与抽成：改它的读权限会影响 Q5 的生成页面，本阶段只把模板分开，另作后续。

### 评审后的修正（2026-10-16）

- 发布页的决定面板只在发布、档位、版本三项都已读好（同一 `approvalRequestId`、无错误、不在重读）时给出；档位读取出错时显示错误且不给面板。
- 资金只给财务：新模板 `qb.admin.publication-funds`、`qb.admin.attempt-rewards`；`qb.admin.publications` 与 `qb.admin.attempts` 不再带金额；发布页的资金块与用户汇总的奖励只对财务显示与请求。
- 决定后的等待有界：超时后"还没有更新"与"重新读取"；只批准本级时直接说明。
- 队列与汇总中的列表分页（队列带服务端计数）；用户编号的后备一律 `UserName`；用户汇总先查地址是不是 UUID。
- **后续**：平台的 `APPROVAL_DECIDE` 可以接受调用方看到的内容哈希（`expectedContentHash`），与请求绑定的不同即拒绝：页面就不必靠"读取都已就绪"来保证审核人看到的就是被批准的。
  这是平台改动（`1.2/platform`），本阶段不做。
