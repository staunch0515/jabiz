# staging 重新初始化与彩排步骤

给需求方在测试系统（`staging.quizbuks.com`）彩排用：把 staging 清回空库，再从头走一遍首次上线要做的事。**只整理文档里已有的步骤**，每步写明依据（章节）；
文档里没有写清楚的标「**待确认**」，不猜。命令和细节以依据的文档为准，这里只给顺序和要看的结果。

依据文档（下文简称）：

- **DEPLOY** = `deploy/quizbuks/DEPLOY.md`；**运维** = `docs/quizbuks/operations.md`；**清单** = `docs/quizbuks/launch-checklist.md`；
  **Stripe** = `docs/quizbuks/stripe-test-mode.md`；**词表** = `docs/quizbuks/glossary.md`（界面里的叫法：学者、发起方、运营商、平台管理系统等）。

## 0. 开始之前

- 只动 staging：名字都是 `quizbuks-staging`、`quizbuks_staging`、`quizbuks-staging_files`，生产的 `quizbuks`、`quizbuks_files` 一律不碰（DEPLOY「重置 staging」开头）。
- 要有：服务器的登录、staging 的 `.env`（`/opt/apps/quizbuks-staging/.env`）、能收信的几个邮箱（staging 发**真实邮件**，没有 Mailpit，Stripe「4.2」第 6 条；Gmail 别名 `you+qb-sponsor-1@gmail.com` 这样的写法可以共用一个信箱）、
  每位管理员的验证器（TOTP）。
- 邮件必须是开着的：建管理员账号用邀请，要求 `JABIZ_MAIL_ENABLED=true`，否则 422 `MAIL_DISABLED`（运维 §3 第 4 步）。**待确认**：staging 的 `.env` 现在是否已开邮件、发件设置是否可用（DEPLOY「2. `.env`」「2b. 发信的服务器」、2026-11-08 的邮件故障）——彩排前先发一封测试邮件。
- 需要的人（每人一个邮箱，一人一号，不共用账号）：首个管理员 `admin`（不用邮箱，由 `.env` 的变量创建）、财务管理员 1 位、内容管理员 1 位、超级管理员 2 位、运营商负责人 1 位（加第二位能审核的成员，见第 6 步）、发起方 1 位、学者 1 位。

## 1. 清空数据库并重新部署（DEPLOY「重置 staging（演练前）」第 0–6 步）

在服务器上，按顺序：

0. **确认对象是 staging**：运行 DEPLOY 第 0 步的检查，输出 `OK: quizbuks-staging / quizbuks_staging / quizbuks-staging_files`；不是就停下。
1. （可选）`pg_dump` 备份旧数据（第 1 步）。
2. **记下当前镜像并停掉应用**（第 2 步）：记下 `$SHA`，`docker stop quizbuks-staging-app`。
3. **删库重建**（第 3 步）：`DROP DATABASE … WITH (FORCE)`，再 `CREATE DATABASE … OWNER …`；角色与密码不变，`.env` 的数据库变量不改。
4. **清空上传的文件**（第 4 步）：清 `quizbuks-staging_files` 卷；`quizbuks-staging_secrets` 不动。
5. **让首个管理员重新创建**（第 5 步）：在 `.env` 写回 `JABIZ_BOOTSTRAP_ADMIN_USER` 与**新的** `JABIZ_BOOTSTRAP_ADMIN_PASSWORD`，删去 `QB_BOOTSTRAP_ADMIN_DONE=true`。
   平台密钥可以不变，也可以重新生成（顺便演练换密钥，「`.env`」一节）；换 `JABIZ_SEAL_KEY` 之前先看清单「测试环境（staging）」两条（旧的 Shopify 令牌与数字产品码在换密钥时读不出）——**库已经清空时不受影响**。
6. **重新部署同一个版本**（第 6 步）：`/opt/infra/scripts/deploy-app.sh quizbuks-staging "$SHA"`（或 GitHub Actions 的 QuizBuks deploy，分支 `1.3/quizbuks`，`image-tag` 填 `$SHA`）；`docker logs -f quizbuks-staging-app` 直到健康，且**没有** `Platform check failed`（DEPLOY「4. 第一次部署」、运维 §3 第 1 步）。
   第一次启动在空库上迁移，要一两分钟。
7. **外部的旧测试数据**（第 7 步）：Stripe（测试模式）里旧的顾客与 Connect 账户可在 Developers 中「删除所有测试数据」（端点与签名密钥不受影响）；浏览器里清掉 `staging.quizbuks.com` 的站点数据，已安装的 App 的会话已失效。

**看到什么**：应用健康；用首个管理员能登录 `https://staging.quizbuks.com/admin/`。

## 2. 首个管理员（DEPLOY「5. 首次启动之后」第 1、3 步；运维 §3 第 2 步）

1. 以 `admin` 登录 `https://staging.quizbuks.com/admin/`（密码是上一步 `.env` 里新写的），右上角用户名 →「安全设置」**开启两步验证**，保存恢复码，**记下绑定时显示的 Base32 密钥**（Stripe 的脚本要用它，Stripe「4.2」第 5 条）。
2. 这时先不删 `.env` 里的首个管理员变量：后面要用首个管理员建人。**建好第二位超级管理员之后**再删（第 4 步末尾）。

## 3. QB_SETUP 与两位日常管理员（运维 §3 第 3–5 步前半）

按这个顺序（顺序有讲究，见运维 §3 第 5 步）：

1. **执行 `QB_SETUP`**（平台管理系统的「流程」页，要求二次验证）：建角色、账本科目、国家、业务参数；受控参数与审批规则作为**提案**提出（运维 §3 第 3 步）。
   以后每次部署新版本都要再执行一次（运维 §3 第 8 步）；彩排只做一次。
2. **建财务管理员**（`QB_ADMIN_FINANCE`，持有 `control.publish`）与**内容管理员**（`QB_ADMIN_CONTENT`）：首个管理员用 `SEC_USER_CREATE` 填用户名与**本人的邮箱**、**勾选 `invite`、不填密码**，再用 `SEC_USER_ROLE_ASSIGN` 分配角色；
   本人按邀请邮件里的链接（72 小时内有效）设密码，用邮箱登录，**绑定二次验证**。邮件没收到：在用户列表对该行执行「发送邀请」（`SEC_USER_INVITE`）。
3. **财务管理员发布 `QB_SETUP` 的提案**（运维 §3 第 5 步）：平台管理系统「数据 → SysControlChange」里状态为 `PROPOSED` 的行，行操作 `CONTROL_CHANGE_PUBLISH`（提出人不能发布自己提出的）。**包括两条职责分离规则**，且必须在第二位超级管理员出现之前发布。
   发布之前，发起方入驻和提交发布一律被拒。
   - 如果发布前财务管理员看不到这个视图：运维 §3 第 5 步说明了原因（`approval.read`）和补法。

**看到什么**：参数和审批规则生效；启动日志里没有 `QB_SETUP has not run yet` 一类的警告（运维 §3 第 8 步）。

## 4. 两位超级管理员（运维 §3 第 5 步后半、「受保护的授权」）

> 需求方说的「第二位管理员」在文档里是**两位超级管理员**（`QB_ADMIN_SUPER`），日常的财务与内容管理员是上一步。

1. **首个管理员建第一位超级管理员**（同样用邀请，本人设密码并绑定二次验证）。此后首个管理员退役，不能再做受保护的变更（403 `BOOTSTRAP_ADMIN_RETIRED`）。
2. **第一位超级管理员建第二位**（仍在建立期，当即生效）。第二位出现后**建立期结束**（永不回退）：此后受保护的变更要一位申请、另一位审批。
3. 试一次「一位申请、另一位审批」的角色分配（清单「至少两位超级管理员」一项的要求）。
4. 现在才把首个管理员的变量从 `.env` 删去：删 `JABIZ_BOOTSTRAP_ADMIN_USER` 与 `JABIZ_BOOTSTRAP_ADMIN_PASSWORD`，加 `QB_BOOTSTRAP_ADMIN_DONE=true`，
   再以 `deploy-app.sh quizbuks-staging <当前 SHA>` 重新部署（DEPLOY「5. 首次启动之后」第 3 步；这一步本身也是演练的一部分，DEPLOY「重置 staging」末段）。
   - 当前 SHA 用 `docker inspect quizbuks-staging-app -f '{{.Config.Image}}'` 冒号后的部分。

**注意**：给管理员账号的受保护权限，其密码必须由**本人**设定（用邀请），管理员知道过的密码不行（运维 §3 第 4 步，`TARGET_CREDENTIALS_NOT_OWN`）。

## 5. 参数与运营商（运维 §3 第 5 步末、第 6 步、§7.13）

1. **默认运营商**（必做，否则发起方入驻被拒 `QB_OPERATOR_DEFAULT_MISSING`，平台管理系统首页有红色提示）：超级管理员先为负责人建账户（`invite`），确认邮箱已验证，
   再在「运营商」新建运营商（名称、联系人、联系邮箱、负责人），第一个自动成为默认运营商；负责人的账号同时成为该运营商的**直营发起方**（运维 §7.13「第一个运营商」）。
2. **第二位能审核的成员**：直营发起方的发布由同一运营商的**另一位**成员审核（准备人不能审批）；只有负责人一人时每日核对报 `FEW_REVIEWERS`。
   做法在运维 §7.13「签下一个运营商」第 2–5 步：建账户、在运营商页面添加成员、给成员分配角色 `QB_OPERATOR`（**受保护的授权，要另一位超级管理员审批**）、成员登录 `/operator/` 并绑定二次验证。
3. **阶段一（推广期）的设置**（运维 §3「阶段一」清单，由超级管理员提出、财务管理员发布，都是受控参数）：
   - `qb.reward.default-methods` = `OFFLINE_REDEEM`；`qb.reward.max-per-attempt` = 法务给的上限（**待确认**彩排用什么值）；
   - `qb.review.sponsor.auto` = `true`、`qb.review.publication.auto` = `false`（发布一律在上架前审核）；
   - `qb.transfer.enabled` 保持 `false`（Stripe 全流程里会临时改，见第 8 步）；`qb.ai.enabled` 保持 `false`（清单「AI」一节）。
4. **收据开具方**与**科目映射**（运维 §3 第 6 步）：`qb.issuer.name` / `.address` / `.registration-no`，`qb.journal.account.*`。彩排如果要走发起方充值的收据与分录导出才需要；**待确认**彩排是否包含。
5. **条款与帮助**（运维 §3 第 9 步）：`QB_SETUP` 建的四种法律文本是 `[TEST PLACEHOLDER]` 草稿；正式文本由需求方或律师提供后，内容管理员在平台管理系统「法律文本（草稿）」换入，再在 `/qb/legal` 发布（要求二次验证）。**待确认**彩排用占位文本还是先发布正式文本。

**看到什么**：平台管理系统首页没有「请先建默认运营商」的提示；运营商页面显示两位可审核的成员。

## 6. 发起方入驻

1. 发起方在 `https://staging.quizbuks.com/sponsor/` 用邮箱注册，点邮件里的链接验证邮箱（也可用 Google 等登录，视 `.env` 的 `SPRING_PROFILES_ACTIVE`，DEPLOY「staging 环境」第 2 条）。
2. 在发起方页面「发起方资料」填基本信息，申请入驻并同意发起方条款（依据：运维 §3 第 9 步、§7.13「新发起方归谁」；发起方一侧的叫法见词表）。
3. 审核：阶段一设置了 `qb.review.sponsor.auto=true`（第 5 步第 3 点），入驻不逐个人工审核；否则由其运营商的成员在 `/operator/` 的审核队列里决定。
   - **待确认**：`deploy/quizbuks/README.md` 里写入驻由「内容管理员在 `/tasks` 审核」，这是 F-S1 之前的写法；运维 §7.13 写「审核即终审，由运营商的成员在运营商管理系统做」。彩排以运维 §7.13 为准，请需求方确认。
4. 新发起方首次充值前后 30 天内累计充值有上限（`qb.topup.new-sponsor.max-total`，缺省 100 000，Stripe「4.2」第 6 条）——彩排时每次用新的发起方。

**看到什么**：发起方页面的状态显示入驻已通过（`APPROVED`）；发起方页面「概况」的「你的运营商」卡片显示默认运营商的名称与联系方式（`help/your-operator.md`）。

## 7. 出题、发布、审核

1. 发起方在发起方页面建 Quiz（题目、选项、正确答案、时限），保存版本；需要的话也可以导入 `.quizbuks.json` 文件（`quiz-file-prompts.md`）或用内容包工具（`tools/quizbuks/content/README.md`，`apply` 可以反复运行）。
2. **奖励方式**：缺省只能线下奖品（`OFFLINE_REDEEM`，发起方页面不显示钱包与充值）。要彩排付 Kudos 或现金，在平台管理系统「发起方审核」页对该发起方单独允许（`QB_SPONSOR_REWARD_METHODS_SET`，运维 §3 阶段一第一条）；
   这时发起方要先充值（第 8 步）。**待确认**：彩排用线下奖品还是 Kudos？
3. 发起方走「发布」向导（共三步，保存时一次报告全部问题），选版本、时间、地区、及格线与奖励档，**提交**；提交后内容冻结（`plans/Q4-publishing.md` D-Q4-2、D-Q4-6）。
4. **审核**：发布一律在上架前审核（第 5 步第 3 点）。由该发起方所属运营商的成员在 `/operator/` 审核（运维 §7.13）；直营发起方的发布要由**另一位**成员审核。通过后上架；审核员拒绝以酒类、烟草或毒品为题材或奖品的问卷（运维 §3 阶段一第三条）。

**看到什么**：发布在发起方页面显示已上架；学者的 App 首页能看到它。

## 8. 学者答题领奖

1. 学者在 `https://staging.quizbuks.com/taker/`（App）用邮箱注册并验证邮箱，找到上架的发布，答题并提交。
   - **待确认**：文档里没有逐屏的学者操作说明；可对照 `quizbuks-web/e2e/tests/play.spec.ts` 一类的端到端用例，或需求方彩排时现场补。
2. **线下奖品**：兑换与数字奖品的争议由发起方所属的运营商在运营商管理系统处理；现金奖励的争议平台不裁决，由发起方再次「标记已支付」或学者直接确认（运维 §7.13）。**待确认**：学者兑换线下奖品的具体界面步骤文档里没有逐屏写。
3. **Kudos 与现金**（选做）：发起方先充值（Stripe 测试卡 `4242 4242 4242 4242`），答对后学者的钱包收到奖励；学者开通收款账户后转账任务把钱转出（门槛、大额审核等见 Stripe「5」）。
   前提是 `.env` 里 Stripe 的测试密钥、两个端点及各自的签名密钥、`QB_TRANSFER_RUN_CRON='0 * * * * *'` 都已设好，Cloudflare 放行 `/api/inbound/`（Stripe「4.2」一次性准备 1–3）；`qb.transfer.enabled` 为 `true` 要提出并发布（四眼）。
   整套已有脚本：`tools/quizbuks/stripe-test-mode/run.sh run --base-url https://staging.quizbuks.com --allow-remote --fund-balance`（Stripe「4.2」运行）。
4. 检查每日核对：最近一次应为 `OK`（走 Stripe 脚本时看汇总的最后一行，Stripe「5」；有差异时按运维 §7.2「核对差异」处理）。

## 9. 彩排之后

- 把发现的问题写下来（现象、页面、时间、发起方或学者的账号、浏览器）；已知的常见问题先查 `docs/quizbuks/troubleshooting.md`。
- 要再来一遍：回到第 1 步（DEPLOY「重置 staging」）。
- 清单里「测试环境（staging）」两条在换 `JABIZ_SEAL_KEY` 之前要看（第 1 步第 5 点）。

## 待确认的汇总（需求方或总控回答）

1. staging 的邮件现在能发吗（邀请、验证、找回密码都要）？（第 0 步）
2. 彩排是否包含：收据与分录导出（科目映射）？法律文本用占位还是正式文本？（第 5 步第 4、5 点）
3. `qb.reward.max-per-attempt` 彩排取什么值？（第 5 步第 3 点）
4. 发起方入驻由谁审核：运维 §7.13（运营商的成员，或阶段一免审）还是 README 的旧写法（内容管理员）？（第 6 步）
5. 奖励方式：线下奖品，还是 Kudos / 现金（要充值与 Stripe 测试模式）？（第 7、8 步）
6. 学者一侧没有逐屏的步骤文档，要不要在彩排前补一页？（第 8 步）
