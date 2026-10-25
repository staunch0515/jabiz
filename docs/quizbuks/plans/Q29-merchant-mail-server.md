# Q29 商家自己的邮件服务器（计划，2026-10-25 确认）

需求方（2026-10-25）：商家用自己的 SMTP 服务器发邀请邮件，平台的服务器只发平台自己的邮件。
确认（2026-10-25，"全部同意"）：分两部分——平台先做"一封消息可以指定发件服务器"（16zg，平台决策 D56，PR #236），应用再做本阶段。
保存时发测试邮件、页面显示"已验证"或服务器的错误；邀请只经商家验证过的服务器发出，没有验证过的服务器即不能发邀请；
平台自己的邮件（验证、重置、欢迎、给商家的通知）照旧从平台的服务器发出。服务器拒收时是否另外邮件通知商家：先只在页面上显示（本阶段）。

## 1. 已确认的决定

| # | 决定 |
|---|---|
| D-Q29-1 | **实体 `QbSponsorMailServer`**（普通表 `qb_sponsor_mail_server`，主键 `sponsorId`：每个商家一台），字段 `host`、`port`、`tls`（`SMTPS` / `STARTTLS`）、`username`、`sealedPassword`（`sensitive()`，只存平台 `SealedSecrets` 密封的值）、`fromAddress`、`fromName`、`verifiedAt`、`testedAt`、`lastError`（后三者 `processOnly`）。只经 `QB_MAIL_SERVER_SET` / `QB_MAIL_SERVER_REMOVE` 写入（缺省视图 `processOnlyWrites`，管理员 `qb.admin.users.read` 可读；商家视图 `sponsor:` 只读本人）。迁移 V27 |
| D-Q29-2 | **保存即测试**：`QB_MAIL_SERVER_SET`（`qb.publication.write`、已验证邮箱、未被封禁；限流每人每小时 10 次；商家锁 `qb.mail-server:<商家>` 只串行化保存与删除，邀请流程在自己的事务里读行）先检查 TLS 方式与首次保存必须给密码（422 `QB_MAIL_SERVER_INVALID` / `QB_MAIL_SERVER_PASSWORD`），用平台步骤 `SealSecret` 密封密码（留空即保留已存的；已存的密码因密钥更换打不开时 422 `QB_MAIL_SERVER_PASSWORD`，商家重新输入），再在阻塞步骤 `TestMailServer` 中按平台的 `MailSenders.problems` 检查设置（主机须公开域名、端口 25/465/587/2525、必须 TLS……；每个问题是其字段上的 422 `QB_MAIL_SERVER_INVALID`，`detail` 是平台的原话）、经 `MailSenders.send` 给**商家自己的地址**（账号的已验证邮箱）发一封测试邮件（文案 `qb.mail-server.test.subject|body`，按请求语言）。寄出即 `verifiedAt = 现在`、`lastError = null`；服务器或连接的回答即 `verifiedAt = null`、`lastError = 回答原文（截断 2000）`，**行照样保存**，商家在页面上看到原因。输出 `verified`、`error`、`testedAt`、`sentTo`。测试邮件重复无害，所以在事务内发（平台 18 §5.6 允许的用法） |
| D-Q29-3 | **平台的来源**：`MailSenderSource.of("QbSponsorMailServer", "sponsorId", …)`，只把 `verifiedAt` 不为空的行换成设置；发件键是商家的用户编号 |
| D-Q29-4 | **邀请只经商家的服务器**：`QB_INVITATION_SEND` 多读商家的服务器行，没有或未验证即 422 `QB_INVITATION_NO_MAIL_SERVER`（配额不扣）；`QB_INVITATION_MAIL` 与卡片、商家一起读服务器行，`SendMail.via(商家编号).when(排队中 ∧ LIVE ∧ 服务器可用)`，服务器在排队后被删除或失效即不寄、行退回 `ADDED`（同发布不再 LIVE；排队时扣的当日配额不退，同已有的不再 LIVE 的情况）。投递时服务器拒收的，是平台邮件尝试的 `FAILED`（`sys_mail_attempt`，Outbox 照常重试；绝不改经平台的服务器）——本阶段商家在页面上看不到投递时的失败，只能重新保存再测（已知限制）。平台自己的邮件不变 |
| D-Q29-5 | **商家后台**：导航项与页面「邮件服务器」（`/mail-server`，`publishing/MailServerPage.tsx`，模板 `qb.sponsor.mail-server`：从不返回密码）：表单（服务器、端口、加密方式、用户名、密码（已有服务器时留空保留）、发件地址与名称）、状态（未设置 / 已验证于 / 最近一封测试邮件没有发出 + 服务器的回答）、"保存并发送测试邮件"、删除（确认）。发布页的邀请区块：发布 LIVE 而没有验证过的服务器时发送按钮不可用，提示并链接到该页 |
| D-Q29-6 | **密钥** `JABIZ_SEAL_KEY`（`jabiz.security.seal.key`）成为部署必需：`application.yml` 映射，入口脚本缺少即拒绝启动（本机演示 `QB_LOCAL_SECRETS=true` 时由应用的入口脚本生成到 secrets 卷：平台的入口脚本不认识它），`operations.md` §1.1、`DEPLOY.md`、README、上线清单、workflow 与本机脚本都补上。测试配置给固定密钥并开 `jabiz.mail.senders.trust-all-for-tests`（只对本机的服务器放宽端口与证书，平台 D56 第 5 条） |
| D-Q29-7 | **e2e**：CI 的 e2e 作业以步骤启动第二个 Mailpit（自签证书的 STARTTLS、接受任何登录；服务容器在证书生成之前就启动，所以不能用服务），应用以 `JABIZ_MAIL_SENDERS_TRUST_ALL_FOR_TESTS=true` 运行；`invitation.spec.ts` 先经接口设置服务器、从商家的 Mailpit 读邀请并断言平台的 Mailpit 没有收到；新 `mail-server.spec.ts` 走页面（错端口 → 未验证并显示原因；对端口 → 已验证、测试邮件到达） |

## 2. 改动

| 部分 | 改动 |
|---|---|
| 迁移 V27 | 表 `qb_sponsor_mail_server` |
| 后端 | 包 `mailserver`：`QbMailServers`（实体、视图编号、锁名、`settingsOf`）、`MailServerCodes`、`MailServerProcesses`（`QB_MAIL_SERVER_SET` / `_REMOVE`）、`TestMailServer`（阻塞步骤）、`MailServerConfig`（实体、两个视图、`MailSenderSource`、流程）；`InvitationProcesses`（发送前查服务器、`SendMail.via`、退回 `ADDED`）、`InvitationCodes.NO_MAIL_SERVER` |
| 模板 | `queries/qb/sponsor/mail-server.sql` |
| 消息 | `entity.QbSponsorMailServer.*`、`process.QB_MAIL_SERVER_*`、`QB_MAIL_SERVER_*`、`QB_INVITATION_NO_MAIL_SERVER`、`query.qb.sponsor.mail-server.*`、`qb.mail-server.test.*`（三语） |
| 配置 | `jabiz.security.seal.key: ${JABIZ_SEAL_KEY:}`、`jabiz.mail.senders.trust-all-for-tests: ${JABIZ_MAIL_SENDERS_TRUST_ALL_FOR_TESTS:false}` |
| 前端 | 商家后台：`publishing/MailServerPage.tsx`、`Invitations.tsx` 的提示、`App.tsx` 的路由与导航、`publishing/api.ts` / `ids.ts` / `paths.ts` / `messages.ts` |
| 部署 | `entrypoint.sh`（必需、本机生成）、`tools/quizbuks/test/entrypoint.test.sh`、`local-demo.sh`、`stripe-test-mode/run.sh`、workflow（密钥、商家 Mailpit、开关）、`DEPLOY.md`、README、`operations.md`、`launch-checklist.md` |
| 测试 | `MailServerIT`（验证、密封、模板不含密码、邀请经商家服务器以其名义到达而平台服务器没有、留空密码保留、平台拒绝的设置按字段 422、错密码保存为未验证并带原因、没有或未验证的服务器拒绝发送、删除后排队的邀请退回 `ADDED`、再删 404）；`InvitationIT` 与 `InvestorMetricsIT` 的商家先设服务器；`MessagesTest`；Vitest（页面保存 / 失败 / 删除、邀请区块的提示）；e2e `mail-server.spec.ts`、`invitation.spec.ts` |

## 3. 风险与已知限制

- 投递时（而不是保存时）服务器拒收的邀请：邀请行已标 `SENT`（同以前经平台服务器时），失败只在平台的 `sys_mail_attempt` 中；商家页面不显示。下一步可在页面上显示最近的尝试或在多次失败后把服务器标回未验证。
- 商家服务器的发送配额、SPF/DKIM、退信都是商家的事；平台只提供按地址退订（链接仍是平台的）。
- `JABIZ_SEAL_KEY` 丢失或更换：已存的密码打不开，投递记 `FAILED`，所有商家要重新输入密码并保存（页面照常）。密钥与数据库备份分开保管。
- 平台只按名字检查主机（D56 的剩余风险）：解析到内网地址的公开域名会得到一次 SMTP 连接尝试；TLS 与认证之前不发凭据。
