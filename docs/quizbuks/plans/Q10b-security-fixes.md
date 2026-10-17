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
