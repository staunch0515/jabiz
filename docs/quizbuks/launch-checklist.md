# QuizBuks — 上线检查清单（Q10d）

正式开放（Stripe 正式模式、真实用户）之前逐项确认，每项写上确认人、日期与依据（文档、记录或链接）。操作细节见 `operations.md`，切换正式模式的技术检查在其 §4。
任何一项不成立都不上线；确需带着未完成项上线的，由需求方书面决定并写在本页末尾。

## 法务与合规

- [ ] **Q6b 转账的法务意见**（资金決済法：Kudos 与转账是否构成资金移动、前払式支付手段等；`03-plan.md` 第 4 节）已取得，结论允许按现设计开放转账；否则 `qb.transfer.enabled` 保持 false 上线
- [ ] **收据**：登记号（`qb.issuer.registration-no`，适格请求书发行事业者登记号）与税的表述（收据不声称是适格请求书；日元金额与 Kudos 的写法）经财务或法务确认
- [ ] 收据开具方的名称与地址（`qb.issuer.name`、`qb.issuer.address`）已以受控变更设好
- [ ] **隐私政策**（个人信息的取得与利用目的、第三方提供：Stripe、邮件服务商、Google 登录；保存期间；海外传输）已发布，App 与商家后台可达
- [ ] **利用条款**（答题人、商家各自的条款；商家入驻时同意的版本）已发布
- [ ] 特定商取引法的表示（商家充值是有偿的）已发布
- [ ] 上面四种文本都是需求方或律师提供的**正式文本**（日文为准据文本、英文一并提供），经 `QB_LEGAL_PUBLISH` 发布且已生效：页面上不再出现 `[TEST PLACEHOLDER]`；
      正式模式启动日志中没有 `legal:TERMS_APP` / `legal:TERMS_SPONSOR` / `legal:PRIVACY` 的告警（`operations.md` §3 第 9 步）
- [ ] **AI 说明**（法律文本种类 `AI_NOTICE`：发送给 AI 提供方的内容、提供方的数据使用与保留、处理地域）由需求方或律师提供正式文本并发布，Anthropic 的商业条款（是否用于训练、保留期）已由法务确认（`plans/Q8-ai.md` A-11）；未完成时 `qb.ai.enabled` 保持 false 上线
- [ ] 帮助中心的问答（账号、答题规则、奖励与转账；商家的入驻、发布、充值与费用）已发布，支持邮箱 `qb.support.email` 已设；`JABIZ_PUBLIC_ENABLED` 未设为 `false`
- [ ] 保留期：导出文件、汇总凭证与收据的保留（L-4：10 年）已实施或已有书面的替代做法（`backend/quizbuks/CLAUDE.md`"导出文件与收据还没有 `RetentionPolicy`"）

## 性能

- [ ] 在**上线所用的硬件**上重测（压测程序在另一台主机，读者按预期读取量限速；`./gradlew :quizbuks:loadTest`，`operations.md` §6）：预期峰值低于饱和点并有约 1.3 倍余量，应用与数据库 CPU < 60–70 %，结果写进 `perf/`
- [ ] 同一次压测中让分析任务 `qb.analytics` 照常每 10 分钟运行（Q9e，`perf/Q9e-analytics.md`）：提交 p95 与吞吐与不运行时相同，任务每次运行 < 1 分钟
- [ ] 提交过载（503、以 EXPIRED 结束）的风险已向需求方说明并接受，或已做缓解（`operations.md` §7.6）
- [ ] `max-active` 与连接池按"同时热门的发布数 × 4 + 1"确认

## AI（Q8a）

- [ ] **Opus 与 Sonnet 的比较**（A-2）：以 20 份真实测验分别用 `claude-opus-5-5` 与 `claude-sonnet-5-5`（effort `medium`）生成候选，比较可用题数、需要改动的题数与错误的正确选项，需求方据此决定 `qb.ai.model`（Sonnet 约为一半成本）；记录写在本项
- [ ] 正式的 `ANTHROPIC_API_KEY` 只在密钥库，Anthropic Console 的工作区月支出上限已设；`qb.ai.model`、`qb.ai.effort` 与限额按预算以受控变更确认
- [ ] `qb.ai.enabled` 在上面三项完成后才开启（四眼）；开启后用一个测试商家生成、校对、加入一次，`/qb/ai` 显示用量

## Stripe

- [ ] **Stripe 测试模式全流程已完成**（`stripe-test-mode.md`，真实测试密钥，不是桩），记录写在该手册的"记录"中，结论为可以
- [ ] Stripe 正式账户（日本）审核通过；Connect 平台资料、Express 的品牌与设置完成；打款银行与打款计划已设
- [ ] 正式的受限密钥与两个正式 Webhook 端点（API 版本 `2025-03-31.basil`）已建，密钥只在密钥库
- [ ] 资金调度已定（充值几天后才可用；转账从可用余额转出）
- [ ] 小额实测（真实卡、最小额充值与退款、次日核对 `OK`）完成（`operations.md` §4）

## 安全

- [ ] **安全审查（Q10b）**的发现全部关闭（或逐条由需求方接受并记录），含已登记的跟进（题目图片原始文件名、保留期）
- [ ] 依赖与镜像的已知漏洞已评估（`gradle dependencies`、`pnpm audit`、镜像扫描）
- [ ] **全部管理员都有二次验证**；管理员角色设了"要求二次验证"；没有共用账号；首个管理员的 `JABIZ_BOOTSTRAP_ADMIN_*` 已从环境中删去
- [ ] **密钥已从任何演示或测试的值轮换**：`JABIZ_JWT_SECRET`、`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY`、数据库密码、Stripe 密钥与签名密钥、SMTP、Google 客户端密钥都是为生产新生成的，没有在本机演示、e2e、压测或测试服务器上用过；`JABIZ_INTEGRITY_KEY` 与 `JABIZ_MFA_KEY` 的离线副本已保存
- [ ] `operations.md` §1.4 的测试开关一个都没设；`SPRING_PROFILES_ACTIVE` 不含 `dev`；`JABIZ_SECURITY_MFA_ADMINISTRATION` 未设；`QB_STRIPE_PLACEHOLDERS=false`
- [ ] 只经 HTTPS 访问（HSTS），`JABIZ_TRUSTED_PROXIES` 已设；数据库与管理端口不对公网开放；指标不在应用端口上暴露
- [ ] 正式域名已写进 `tools/quizbuks/stripe-test-mode/production-hosts`

## 运维

- [ ] **备份已测试**：WAL 归档与每日基础备份在运行，恢复演练（`operations.md` §2.4）做过一次并记录了 RTO 与 RPO；文件卷有备份
- [ ] **监控与告警已上线**（`operations.md` §5）：OTLP 已接入，健康、准入与提交 503、核对差异、转账失败、Webhook 被拒、分录导出被拒、备份与磁盘的告警都已配置并试发过一次；告警送达值班的人
- [ ] 首次启动（`operations.md` §3）完成：`QB_SETUP` 的提案已发布，科目映射已设，审批规则已发布
- [ ] 值班与联系方式：Stripe 支持、邮件服务商、数据库托管方
- [ ] 本清单与 `operations.md` 中的步骤由将要值班的人读过

## 带着未完成项上线的决定

（无）
