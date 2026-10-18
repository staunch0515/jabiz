# Q10b 安全审查的修正（计划，2026-10-17 确认）

来源：Q10b 独立安全审查（`origin/1.2/quizbuks` @ 6204a95，只读）。没有严重或高危问题；中危 M1–M6、低危 L1–L5、两项已登记的跟进（文件名、保留期）与依赖升级。
需求方已决定（2026-10-17）：

- M3：**7 天保留期 + 新商家的充值上限**，商家有未结争议时冻结其奖励；
- 依赖：**现在升级** Spring Boot 4.0.1 → 4.0.8（平台修订线）；
- 文件名：**平台选项 + 应用的临时措施**；
- 保留期：导出、凭证、收据、充值 10 年（自会计年度末，L-4）；入站 Stripe 事件**提高到 10 年**。

分两部分：平台阶段 16q（`1.2/phase-16q-security`，合入 `1.2/platform`）与应用阶段 Q10b（`1.2/quizbuks-10b-security`，平台合并进来之后完成依赖平台的部分）。

## 1. 平台 16q（2.5–3.5 天，一个 PR）

| # | 内容 | 要点 |
|---|---|---|
| P1 | Spring Boot 4.0.8 | `build-logic` 插件版本；随 BOM：reactor-netty 1.3.7（CVE-2026-47874）、netty 4.2.17、Spring Framework 7.0.9、Jackson 3.1.5、logback 1.5.38、PgJDBC 42.7.13、r2dbc-postgresql 1.1.2。全部 `check`、e2e、场景快照；有行为差异的逐条记录。前端依赖不变 |
| P2 | `FilePolicy.privateMetadata()` | 调用者既不是上传者、也不持有 `file.read`（平台管理）时：`GET /api/files/{id}` 的 `originalName`、`uploadedBy` 为 `null`，`uploadedTime` 截到日；`/content[/{变体}]` 的 `Content-Disposition` 用中性名 `<fileId>[-<变体>].<ext>`。公开文件（15）总是中性名。设计 14 增加一节；`FilePolicyExport` 带上该标志 |
| P3 | 登录类接口按地址限流（审查 M4） | `/api/auth/login`、二次验证、step-up、刷新、OIDC 回调按客户端地址（`ClientAddresses`）令牌桶：`jabiz.security.sign-in.rate-limit.*`（缺省每地址每分钟 20 次、突发 10），超出 429 `RATE_LIMITED` + `Retry-After`；与按账号的锁定并存 |
| P4 | 口令哈希的并发上限 | `PasswordHasher` 的 BCrypt 计算经信号量（`jabiz.security.password-hashing.max-concurrent`，缺省 = CPU 数），等待超过 2 秒即 503 `AUTH_BUSY` + `Retry-After`；不占数据库连接，答题等其他请求不受登录洪峰拖累 |

- 新决策 **D47**（取代无）：登录类接口按地址限流、口令哈希并发上限，二者缺省开启；文件元数据可按策略对非上传者隐藏。
- 测试：`SignInRateLimitIT`（同一地址超限 429、不同地址独立、可信代理后的真实地址、按账号锁定不变）、`PasswordHashingLimitTest`（并发上限与 503）、`FilePrivateMetadataIT`（上传者、管理员、其他持有读取权限者三种视角；变体与公开文件的文件名）、升级后的全量测试。
- 不做（建议推迟）：`sys_generated_file` / `sys_document_run` 可声明保留期。二者只追加、本来就不能删，只是不出现在到期报表中，不影响上线；登记为平台缺口。

## 2. 应用 Q10b（4–5 天）

### 2.1 资金与内容（M1–M3）

1. **M1 选项编号按答题实例**：开始答题返回的选项编号改为该次显示的位置 1..k（经已存的 `optionOrder` 映射），`Answers.check/merge` 与结果输出按同一映射换回作者顺序；提交与保存只接受 1..k。答题人前端只把收到的编号送回，不需要改。场景快照随之变化。
2. **M2 管理员下架**：`QB_PUBLICATION_ADMIN_PAUSE`、`QB_PUBLICATION_ADMIN_RESUME`、`QB_PUBLICATION_ADMIN_END`（`qb.admin.content.write`，`requiresMfa(ALWAYS)`，必填理由，按现有锁顺序：发布锁 → 开始锁），`actsOn(QbPublication, …)` 使后台扩展显示行操作；商家看到"已被管理员暂停"且不能自己恢复。
   封禁商家时同一事务内暂停其进行中的发布（经子流程逐个；封禁取账号锁在发布锁之前，顺序不变）。
3. **M3 奖励保留期与充值上限**：
   - 每日转账只转 `max(0, 2120 余额 − 保留中的奖励)`；保留中 = 最近 `qb.transfer.hold-days`（受控参数，缺省 7）天入账的奖励，加上来自"有未结争议的商家"的发布、在被争议充值的 `paidAt` 之后入账的奖励（争议结案后解除）。实现为模板 `qb.wallet.taker-transferable`（2120 的贷方行按 `publication` 维度关联发布与商家），`TransferRules.Facts` 增加 `transferable`。
   - 新商家充值上限：首次充值成功后 `qb.topup.new-sponsor.days`（缺省 30）天内，已付累计 + 本次不超过 `qb.topup.new-sponsor.max-total`（缺省 ¥100 000），超出 422 `TOPUP_NEW_SPONSOR_LIMIT`（参数 `remaining`、`until`）。两个参数都是受控参数（四眼）。
   - 答题人钱包页显示"可转账"与"保留中（预计 X 日起可转）"；商家充值页显示上限与剩余额度。消息三种语言。

### 2.2 部署与认证（M5、M6、L1–L5）

4. **M5 入口脚本**：`deploy/quizbuks/entrypoint.sh` 在 `JABIZ_JWT_SECRET`、`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY`、`JABIZ_BOOTSTRAP_ADMIN_PASSWORD`（首次启动时）任一缺少时拒绝启动，除非 `QB_LOCAL_SECRETS=true`（只由本地 compose 与 `local-demo` 设置）；正式模式（`sk_live_`）下一律拒绝生成。不再把生成的密码打印到标准输出（写入卷内只读文件并提示路径）。
5. **M6 可信代理**：启动检查 `QUIZBUKS`：`trusted-proxies` 为空时测试模式警告、正式模式报错，除非显式声明 `quizbuks.deploy.direct-exposure=true`（没有反向代理）；包含 `0.0.0.0/0` 或 `::/0` 时一律报错。运维手册补一节。
6. **L1**：正式模式下 `jabiz.security.mfa.administration=false` 启动失败（`TransferScheduleCheck`）。
7. **L2 入口限流**：`jabiz.process.rate-limits` 增加 `QB_PAYOUT_ONBOARD`（每用户每小时 5）、`QB_TOPUP_START`（每用户每小时 20）、`QB_JOURNAL_PREVIEW`（每用户每分钟 10）、`QB_ATTEMPT_SAVE`（每用户每分钟 120）、`QB_VIEW`（每用户每分钟 300）、`QB_PUBLICATION_SAVE`（每用户每分钟 60）。
8. **L3 职责分离**：`QB_SETUP` 提议 SoD 规则（{`control.propose`} 对 {`control.publish`}；{`security.role.write`, `security.user-role.write`} 对 {`control.publish`, `approval.decide`}），经四眼发布；上线清单加"停用引导管理员"与访问审查周期。
9. **L4**：`local-demo.sh` 设置 `SERVER_ADDRESS=127.0.0.1`。
10. **L5**：`dev` profile 只在监听回环地址且邮件基础地址为 localhost 时允许启动（`QUIZBUKS` 检查）。
11. 数据库密码不再回退到 `quizbuks`（`application.yml`），缺少即启动失败；compose 照常显式设置。

### 2.3 文件与保留期

12. **临时措施**：商家控制台上传内容文件时用中性名 `${kind}.${ext}`（`quizbuks-web/sponsor/src/content/editing.tsx`）。平台 16q 合并后，内容文件策略声明 `privateMetadata()`，`FileNamesIT` 断言答题人读不到原始名。
13. **保留期**（`RetentionPolicy`，10 年，`afterFiscalYearEnd()`）：`QbJournalExport`（`periodTo`）、`QbJournalExportPart` 与 `QbJournalExportConfirmation`（创建时间）、`QbTopUpReceipt`（迁移 **V11** 增加 `issued_at`，`TopUpReceipts.record` 写入；已有行无需回填，尚无生产数据）、`QbTopUp`（`paidAt`）；入站 Stripe 事件 7 → 10 年。

### 2.4 测试

- `AttemptOptionIdsIT`（编号与作者顺序无关；旧编号、越界编号 400；结果页映射正确）；jqwik：任意排列下映射往返不变。
- `AdminPublicationIT`（权限、二次验证、理由、锁顺序下与提交并发、商家不能恢复、封禁即暂停）。
- `TransferHoldIT`（7 天内的奖励不转、第 8 天转；争议开启冻结该商家来源的奖励、结案解除；其他商家来源不受影响）、`TransferRules` 单元测试、`TopUpLimitIT`（新商家累计上限与期满解除）。
- `DeploySafetyCheckTest`（M6、L1、L5 的各组合）、`tools/quizbuks/test/entrypoint.test.sh`（M5）。
- `RetentionIT`（各实体出现在到期报表、到期日 = 会计年度末 + 10 年）、`QbSetupIT` 断言 SoD 规则提议。
- 场景快照更新；`:quizbuks:check`、e2e（含门户、商家、后台）全部通过；独立评审 + `/security-review`（涉及认证、权限与资金）。

## 3. 顺序

1. 平台 16q → PR → 合并后合入 `1.2/quizbuks`；
2. 应用 Q10b 中不依赖平台的 1–11、12 的临时措施、13 先做，同时进行；
3. 平台合并后完成 12 的 `privateMetadata()` 与全量测试 → PR。

## 4. 风险

- 升级 Boot 4.0.8 跨 7 个修订版：Jackson 3.1 的序列化细节可能改动 OpenAPI 快照或 `ProblemDetail` 的字段顺序；按快照比对确认，不悄悄更新。
- M1 改变答题接口中编号的含义：App 与 e2e 若在别处缓存了作者顺序的编号会出错；审查过只经 `OptionView.no` 送回。
- M3 的保留期让早期答题人更晚收到转账，体验文字需要需求方确认（钱包页的说明）。

## 5. 已确认（2026-10-17，全部按建议）

| 编号 | 问题 | 结论 |
|---|---|---|
| S-1 | 平台 16q 按 P1–P4 做、D47 如上，`sys_generated_file` / `sys_document_run` 的保留期推迟 | **是** |
| S-2 | 登录限流的缺省值（每地址每分钟 20、突发 10）与哈希并发 = CPU 数、等待 2 秒 | **是** |
| S-3 | 新商家充值上限：首充后 30 天内累计 ¥100 000；保留期 7 天；都可经四眼修改 | **是** |
| S-4 | 封禁商家时自动暂停其进行中的发布 | **是** |
| S-5 | L2 的限流数值如 §2.2 第 7 项 | **是** |

## 6. 实施记录（应用 Q10b，2026-10-17，`1.2/quizbuks-10b-security`）

§2 的 1–11、第 12 项的临时措施与第 13 项已完成；`privateMetadata()` 的声明与 `FileNamesIT` 等平台 16q 合并后补上。迁移 **V11**（`V11__quizbuks_security.sql`）：
发布的 `paused_by`、`admin_reason`，收据的 `issued_at`，`qb_topup` 上未结争议的部分索引。

| 项 | 做法 | 与计划的出入 |
|---|---|---|
| 1 M1 | 开始与继续给出的选项编号是本次显示的位置 1..k（`AttemptProcesses.questions`）；保存与提交只接受位置（`Answers.check`），`answers` 与 `result` 都按位置存放，只在评分时经 `optionOrder` 换回版本的编号（`Scoring.scoreShown`、`Answers.inVersion` / `positions`）。`optionOrder` 改为敏感字段（平台 10 §6：任何读接口都不返回，含答题人本人的数据视图） | 计划写"check/merge 换回作者顺序"：改为存位置、只在评分时换回，并把 `optionOrder` 设为敏感——否则答题人本人读会话即可从 `optionOrder` 或按作者编号保存的 `answers` 反推作者顺序，M1 并未关上。"旧编号 400"做不到：旧编号同样在 1..k 之内，按位置解释（`AttemptOptionIdsIT` 断言作者的 1 号在这里是错答）；越界与重复照旧 422 `QB_ANSWER_INVALID`（不是 400） |
| 2 M2 | `publishing.PublicationAdmin`：`QB_PUBLICATION_ADMIN_PAUSE` / `_RESUME` / `_END`（`qb.admin.content.write`、`requiresMfa(ALWAYS)`、已验证邮箱、必填理由 ≤ 500、`actsOn(QbPublication)`），发布锁 → 开始锁 → 经缺省数据视图读取。暂停记 `pausedBy`（`SPONSOR` / `ADMIN`）与 `adminReason`；商家恢复管理员暂停的发布 422 `QB_PUBLICATION_ADMIN_PAUSED`，管理员恢复商家暂停的 422 `QB_PUBLICATION_NOT_ADMIN_PAUSED`；结束的 `endReason = ADMIN`。封禁商家：`QB_USER_BAN` 在账户锁内读该商家的发布，按编号升序以子流程逐个管理员暂停其 `SCHEDULED` / `LIVE` / `FUNDS_SHORT` / 自己暂停的发布（同一事务），解封不恢复。商家页显示"已被管理员暂停 / 结束"与理由、不给恢复；后台扩展的发布页给持有 `qb.admin.content.write` 的人链到平台的流程页（预填 `publicationId`），平台的列表按 `actsOn` 显示行操作 | 另补：审核中的发布在商家被封禁后获批，不上架而回到草稿（`rejectCode = QB_USER_BANNED`，`QB_PUBLICATION_APPROVAL_RESULT` 读商家的封禁）——否则封禁后仍可能有发布上架 |
| 3 M3 | 模板 `qb.wallet.taker-transferable`（转账流程在用户钱包锁内读，`qb.payout.system`）：2120 余额与保留中的奖励 = 来源为 `QbAttempt` 的 2120 贷方行中，`bookingTime` 在 `at − hold-days` 之后的，或其发布（维度 2 → 卡片 → 商家）的商家有 `disputeStatus = OPEN` 的充值、且入账不早于该充值 `paidAt` 的；`TransferRules.Facts.rewardsHeld`、`transferable() = max(0, 余额 − 保留)`，余额够门槛而可转额不够时 `REWARDS_HELD`；审核通过后发出前同样按可转额再查。新商家上限 `wallet.NewSponsorLimit`：首次付款前与之后 30 天内，已付（含退款的）+ 未结（一小时内的 `OPENING`、未过期的 `OPEN`）+ 本次 ≤ 100 000，超出 422 `QB_TOPUP_NEW_SPONSOR_LIMIT`；充值开始取 `qb.topup:<商家>`（只有它持有、之后不取别的锁），未结的充值才算得准。三个受控参数由 `QB_SETUP` 提出；未发布时转账 `INVALID_PARAMS`、充值 `QB_TOPUP_UNAVAILABLE`（不放行）。App 钱包页（`qb.app.wallet` 加可选的 `at`）显示可转账、保留中与预计可转日期、争议冻结的说明；商家充值对话框（`qb.sponsor.topup-limit`）显示每笔范围与新商家的累计上限、剩余与期限 | `Facts` 增加的是 `rewardsHeld`（`transferable()` 由它算出），不是现成的 `transferable`。商家经发布维度与卡片找到（计划写法）。保留规则在两个模板中各写一遍（转账用的与 App 用的；数据视图不同），`TransferHoldIT` 同时检查两者。争议冻结按"该商家在被争议付款之后入账的全部奖励"计，不扣除已经转出的部分：可能多冻结同一用户其他来源的余额，偏保守 |
| 4 M5 | 入口脚本：`JABIZ_JWT_SECRET`、`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY` 或 `JABIZ_BOOTSTRAP_ADMIN_PASSWORD` 缺少即拒绝启动，一次列出全部；`QB_LOCAL_SECRETS=true`（compose 缺省、`local-demo.sh`）时才交给平台脚本生成；正式模式（两个 livemode 变量或 `sk_live_` / `rk_live_`）一律不生成，Stripe 占位也不填。本机缺少管理员密码时脚本自己生成到卷内只读文件（400）并只打印路径，平台脚本因此不再打印密码 | 首次启动无从在脚本中判断，改为：首个管理员已存在后设 `QB_BOOTSTRAP_ADMIN_DONE=true` 免给密码（脚本给一个谁也看不到的随机值，防止平台脚本生成并打印） |
| 5–6 M6、L1 | 新启动检查 `wallet.DeploySafetyCheck`（类别 `QUIZBUKS`）：可信代理为空时测试模式告警、正式模式报错，除非 `quizbuks.deploy.direct-exposure=true`（`QB_DIRECT_EXPOSURE`）；任何前缀长度为 0 的网段一律报错；正式模式下 `jabiz.security.mfa.administration=false` 报错 | L1 放在新检查里，没有放进 `TransferScheduleCheck` |
| 7 L2 | `application.yml` 的 `jabiz.process.rate-limits` 照 §2.2 第 7 项 | — |
| 8 L3 | `setup.QbSodRules` 两条规则由 `QB_SETUP` 以受控变更提出（`rulesProposed` 中排在审批规则之后）；`QbRoles` 的超级管理员不再含 `approval.decide`（否则违反第二条），`QbRolesTest` 断言没有角色违反任何一条 | 已有安装须在发布之前先从 `QB_ADMIN_SUPER` 收回 `approval.decide`（`QB_SETUP` 不收回），写在部署说明中 |
| 9 L4 | `local-demo.sh` 设 `SERVER_ADDRESS=127.0.0.1`、`QB_LOCAL_SECRETS=true` | — |
| 10 L5 | 同一检查：`dev` profile 只在 `server.address` 为回环地址（`application-dev.yml` 缺省 `127.0.0.1`）且 `jabiz.mail.base-url` 为空或指向本机时启动 | — |
| 11 | `application.yml` 的数据库密码 `${DB_PASSWORD}` 没有缺省值；`dev` profile 保留本机 compose 库的密码 | — |
| 12 | 商家控制台以 `<种类>.<扩展名>` 上传内容文件（`content/api.ts` 的 `neutralName`） | `privateMetadata()` 待 16q |
| 13 | `RetentionPolicy`（10 年，`afterFiscalYearEnd()`）：`QbJournalExport`（`periodTo`）、`QbJournalExportPart` 与 `QbJournalExportConfirmation`（平台的 `createdTime`）、`QbTopUp`（`paidAt`）、`QbTopUpReceipt`（`issuedAt`，V11，`TopUpReceipts.record` 写入）；入站事件 7 → 10 年 | 入站事件同样改为自会计年度末起算（与其他会计凭证一致） |

**运维手册**：手册还在 `1.2/quizbuks-10cd-stripe-ops` 的 PR 中，部署说明先写在 `deploy/quizbuks/README.md` 的新节"密钥与部署"，手册合并后移过去。

**e2e**：财务的测试把 `qb.transfer.hold-days` 设为 0（测试等不了 7 天）。

**测试**（§2.4）：`AttemptOptionIdsIT`（位置编号、越界拒绝、结果按位置、答题人读不到 `optionOrder`）、`ScoringTest` 的 jqwik 属性（任意打乱下位置与版本编号互相映射、得分不变）与 `AnswersTest`；
`AdminPublicationIT`（权限、二次验证、已验证邮箱、理由；暂停 / 恢复 / 结束与商家不能恢复；封禁即暂停、解封不恢复；审核中的发布在封禁后获批回到草稿；与 6 个提交并发时全部完成、计数一致）；
`TransferHoldIT`（7 天内不转、第 8 天只转未冻结来源的；争议开启冻结该商家被争议付款之后的奖励、结案解除；更早的奖励不受新争议影响；审核中来了争议则批准后不发出；App 钱包模板的保留与预计日期）、
`TransferRulesTest`（含保留的属性测试）、`NewSponsorLimitTest`、`TopUpLimitIT`（首充前即受限、未结的计入、期满解除、三个同时开始只成一个）；`DeploySafetyCheckTest`；`tools/quizbuks/test/entrypoint.test.sh`（M5 的各组合）；
`RetentionIT`（六个策略、到期日 = 会计年度末 + 10 年的前一天与当天）；`SetupIT` / `QbRolesTest`（职责分离规则的提出与发布，角色不违反）；前端各自的测试（商家页的管理员暂停提示、充值上限，App 钱包页的保留，后台扩展的下架链接，中性文件名）。
场景：`QB_SETUP` 的提案由 22 个增加到 27 个，各场景保存并发布全部；答案按位置；`payout.yml` 先断言保留期内 `REWARDS_HELD`，再过 7 天转账。快照随之更新（编号顺移、超级管理员的权限、`optionOrder` 不再出现、`payout` 的时间晚 7 天），逐项看过。

**验证**：`./gradlew :quizbuks:check`（509 个测试、场景、`platformCheck`、两个 SPA 的版本检查）通过；`quizbuks-web/app`、`sponsor` 的 lint、typecheck、test 通过，`qb/` 两份相同；
`JABIZ_ADMIN_EXTENSION=../backend/quizbuks/admin-extension pnpm ext:check` 通过；`tools/quizbuks/test/entrypoint.test.sh` 通过；e2e（打包的 jar、Stripe 桩、Mailpit）41 个全部通过。

**待办与已知问题**（2026-10-17 评审修正后更新，见 §7）：
- ~~16q 合并后补 `privateMetadata()`~~：已完成（§7 第 12 项）。
- ~~争议冻结不扣除已转出的部分~~：已修正（§7 M-b）。
- 钱包页的保留说明（"新获得的奖励会先保留几天再转账……""预计 X 起可转"）需求方尚未确认措辞；预计日期是最后一笔按时间保留的奖励到期之时，实际转账在其后的每日运行。
- ~~审核中被退回草稿的原因 `QB_USER_BANNED` 显示为代码~~：已修正（§7 L9）。
- 既有安装上线前须先从 `QB_ADMIN_SUPER` 收回 `approval.decide` 再发布职责分离规则（部署说明）。

## 7. 独立评审的修正（2026-10-17，`1.2/quizbuks-10b-security`）

先合并了 `origin/1.2/quizbuks`（#169、#170，再 #172 Q10a5）与 `origin/1.2/platform`（16q，#171），均为合并提交。

| 项 | 做法 | 测试 |
|---|---|---|
| M-a 打乱可被答题人重算 | `OptionShuffle` 改为实例：种子 = HMAC-SHA256(密钥, `用户\|发布\|题号`) 的前 64 位，密钥 = HMAC(`JABIZ_INTEGRITY_KEY`, `qb.option-shuffle`)（`OptionShuffle.of(IntegrityKey)`，平台已有的 `IntegrityKey` Bean，不加新的环境变量）；顺序照旧随会话保存。测试配置的固定密钥使场景确定 | `OptionShuffleTest`（不同密钥的顺序不同、派生密钥不是完整性密钥本身、短密钥拒绝）；场景的答案按新位置改写，快照只有位置变化 |
| M-b 争议冻结计入已转出的奖励、冻结其他商家的奖励 | 模型 `wallet.RewardHolds`（设计 4.4）：2120 分录按时刻与流程先后排序，分为争议冻结、按时间保留、自由与借方；每笔借方只取此前入账的，先取自由、再取按时间保留、最后取争议冻结；保留 = 保留类贷方 − 借方取走的部分，限在 0 与余额之间。两个模板以窗口函数算同一个闭式；7 天的时间保留同样只计尚在余额中的部分；`TransferRules.transferable` 本身也把保留与余额限在 0 以上 | `RewardHoldsTest`（jqwik：闭式与逐笔分配一致；可转额在 0 与余额之间）、`TransferHoldIT`（先转出再争议：其他商家的奖励照转；时间保留与争议并存；页面拒绝 `asOf`） |
| M-c 经用户与角色管理绕过四眼 | 需求方决定由平台 16r（D48）修正；部署说明写入残留风险："平台 16r 修正；在此之前只有检查性控制"（季度访问审查、审计中逐条复核角色变更、最少授予） | — |
| M-d 下架之后复制或重新提交 | (1) `QB_PUBLICATION_COPY` 拒绝管理员暂停中或管理员结束的源（422 `QB_PUBLICATION_TAKEN_DOWN`，三语），商家页不给"复制"；(2) 提交时读商家的发布，同一 Quiz 有被下架的发布即事实 `autoApprove = false`、`takenDown = true`（主体新增事实 `takenDown`）；规则仍不要求审核（豁免）或会沿用以前的批准时拒绝（422 `QB_PUBLICATION_REVIEW_REQUIRED`）。后台扩展的审核页经新模板 `qb.admin.quiz-takedowns` 显示被下架的发布、时间与理由，读好之前（或读取失败时）不给决定面板 | `TakedownIT`（两种下架都不能复制、商家自己暂停的照常；下架后的发布进入待审核且事实记 `takenDown`、批准后上架、另一 Quiz 不受影响；豁免规则下拒绝）；后台扩展与商家页的组件测试 |
| L1 封禁与获批竞争 | `QB_PUBLICATION_APPROVAL_RESULT` 先在锁外读发布（商家不可变），取 `qb.account:<商家>` 再取发布锁；锁序（设计 4.3、应用规则）随之更新 | `TakedownIT.aBanAndAnApprovalTakeTurns`（并发的封禁与获批：要么退回草稿 `QB_USER_BANNED`，要么上架后被暂停） |
| L2 封禁期间发布结束或回到草稿使封禁失败 | 封禁的暂停改为子流程 `QB_PUBLICATION_BAN_PAUSE`（`subProcessOnly`），取锁读到的发布不在运行中即什么也不做 | `TakedownIT`（经测试用的根流程对已结束与草稿的发布运行：不变、不报错；直接调用 403） |
| L3 内部的封禁理由给了商家 | 暂停记固定代码 `QB_ACCOUNT_SUSPENDED`，商家页与后台扩展按语言显示（"账户已被停用"，三语；后端消息也有） | `TakedownIT`、`AdminPublicationIT`、前端测试 |
| L4 创建中的充值只计一小时 | 创建中（`OPENING`）一直计入新商家上限与未结数，直到作业的完成或失败流程把它改为开立或失败；页面模板同样 | `TopUpLimitIT`、`TopUpOwnerIT`（两小时后仍计入、失败后不再计入） |
| L5 compose 缺省生成密钥 | `docker-compose.yml` 缺省 `QB_LOCAL_SECRETS=false`；入口脚本生成（或读回生成的）密钥或密码时导出 `QB_SECRETS_GENERATED=true`，`DeploySafetyCheck` 在正式模式下见到即报错；CI 的 `deploy` 作业设 `QB_LOCAL_SECRETS=true`（`local-demo.sh` 已设，`package` / `e2e` 直接运行 jar、给出全部密钥，不经入口脚本） | `entrypoint.test.sh`（标记的三种情形）、`DeploySafetyCheckTest` |
| L6 网段解析 | `DeploySafetyCheck.tooWide` 照平台 `ClientAddresses` 的读法（`Integer.parseInt`：`+0`、`00` 是 0）解析，拒绝不是网段的、IPv4 短于 /8、IPv6 短于 /32、整个 IPv4 的映射（`::ffff:0:0/96` 及其 IPv4 写法、`::/96`、`64:ff9b::/96`、`64:ff9b:1::/48`） | `DeploySafetyCheckTest` |
| L7、L8 | 平台 16r 修正，部署说明中记为残留风险 | — |
| L9 | `qb.app.wallet` 与 `qb.sponsor.topup-limit` 经新的只读参数视图 `urn:jabiz:dataset:now:SysParam`（不允许时间旅行）读参数：请求不能指定 `asOf` / `knownAt`，参数按服务端的时钟（平台的缺省时点），与流程的 `LoadParams` 一致；规则中的"现在"仍是页面给出的 `at`（只影响本人页面的显示）。封禁期间管理员恢复 422 `QB_PUBLICATION_SPONSOR_BANNED`；子流程的二次验证照旧。商家页把 `QB_USER_BANNED` 显示为文字（三语） | `TopUpLimitIT`、`TransferHoldIT`（`asOf` / `knownAt` 400）、`TakedownIT`、商家页测试 |
| 第 12 项 | 平台 16q 合并后，三个内容文件策略声明 `privateMetadata()` | `FileNamesIT`（答题人读不到原始名与上传者、下载名为中性名，变体同样；上传的商家与持 `file.read` 的管理员照常可见） |

**语句预算**：`AttemptStatementBudgetIT` 与写缓冲关闭的变体在合并 Q10a5 后照旧通过：打乱的 HMAC 是内存计算，审批结果多出的账户锁与一次读取不在答题的锁内，预算不变。

**新的界面文字（待需求方确认）**：商家页"您的账户已被停用。"（暂停理由）与"审核期间您的账户被停用，因此未能上架。"（退回原因）；
后台扩展"曾被下架"一节的说明与"账户已停用（商家被封禁）"；后端消息 `QB_PUBLICATION_TAKEN_DOWN`、`QB_PUBLICATION_REVIEW_REQUIRED`、`QB_PUBLICATION_SPONSOR_BANNED`、`QB_ACCOUNT_SUSPENDED`（各三语）。

**残留与说明**：M-d 的拒绝也覆盖"同一发布沿用以前对同样内容的批准"（只在封禁后退回草稿再提交时出现）：商家复制它再提交即进入人工审核。
被封禁暂停过的发布也算"被下架"，所以解封后同一 Quiz 的新发布同样要人工审核。

**e2e 与登录限流**：16q 合并后 e2e 撞上平台的登录限流（每地址 + 名字每分钟 20 次、突发 10：共用的管理员从本机登录数十次，429 `RATE_LIMITED`），
所以 CI 的 `e2e` 作业与本机说明（应用规则"端到端"）照平台 e2e 设 `JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_PER_USER_ADDRESS=6000`、`JABIZ_SECURITY_SIGN_IN_RATE_LIMIT_USER_BURST=1000`；
生产缺省不变（运维手册列为生产不得设置的变量）。

**验证**（合并 Q10a5 之后）：`./gradlew :quizbuks:check`（537 个测试、场景、`platformCheck`、两个 SPA 的版本检查；真实 PostgreSQL）通过；
`quizbuks-web/app`、`sponsor` 的 lint、typecheck、test 通过（104、115 个），`e2e` 的 typecheck 通过，`qb/` 两份相同；
`JABIZ_ADMIN_EXTENSION=../backend/quizbuks/admin-extension pnpm ext:check` 通过（65 个）；`tools/quizbuks/test/entrypoint.test.sh` 与 `stripe-test-mode.test.sh` 通过；actionlint 通过；
e2e（打包的 jar、Stripe 桩、Mailpit，放宽登录限流）41 个中 40 个通过，`content.spec.ts` 的"他处改过的 Quiz 要求先重新加载"一次焦点超时，单独重跑通过（未改动内容页，判断为时序不稳）。
