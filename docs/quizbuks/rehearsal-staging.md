# staging 重新初始化与彩排步骤

给需求方在测试系统（`staging.quizbuks.com`）彩排用：把 staging 清回空库，再从头走一遍首次上线要做的事。**只整理文档里已有的步骤**，每步写明依据（章节）；
文档里没有写清楚的标「**待确认**」，不猜。命令和细节以依据的文档为准，这里只给顺序和要看的结果。

依据文档（下文简称）：

- **DEPLOY** = `deploy/quizbuks/DEPLOY.md`；**运维** = `docs/quizbuks/operations.md`；**清单** = `docs/quizbuks/launch-checklist.md`；
  **手册** = `backend/quizbuks/src/main/resources/quizbuks/help/manual-app.zh.md`（学者使用 App）；**词表** = `docs/quizbuks/glossary.md`（界面里的叫法：学者、发起方、运营商、平台管理系统等）。

**这一轮彩排的取法**（总控 2026-11-10 答复；是 staging 的取法，不是正式环境的）：

- 奖励只有**到店兑换**与**电子奖品**（首次上线的范围；F-S4 还没做）。**学分（Kudos）与现金不在这一轮**，Stripe 测试模式的步骤不做；收费的平台费充值若需要另说。
- **收据与分录导出**（科目映射）这一轮不做，列为「以后」。**法律文本**用 `QB_SETUP` 建的占位草稿（`legal/README.md`），不贴正式文本。

## 0. 开始之前

- 只动 staging：名字都是 `quizbuks-staging`、`quizbuks_staging`、`quizbuks-staging_files`，生产的 `quizbuks`、`quizbuks_files` 一律不碰（DEPLOY「重置 staging」开头）。
- 要有：服务器的登录、staging 的 `.env`（`/opt/apps/quizbuks-staging/.env`）、能收信的几个邮箱（staging 发**真实邮件**，没有 Mailpit；Gmail 别名 `you+qb-sponsor-1@gmail.com` 这样的写法可以共用一个信箱）、
  每位管理员的验证器（TOTP）。
- 邮件必须是开着的：建管理员账号用邀请，要求 `JABIZ_MAIL_ENABLED=true`，否则 422 `MAIL_DISABLED`（运维 §3 第 4 步）。staging 现在**不确定**能否发信（DEPLOY「2. `.env`」「2b. 发信的服务器」、2026-11-08 的邮件故障），
  所以**首个管理员之后的第一步就是试一封邮件**（第 2 步第 3 点）；收不到就停下，把现象告诉总控。
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
7. **外部的旧测试数据**（第 7 步；这一轮不用 Stripe，曾经用过时才需要）：Stripe（测试模式）里旧的顾客与 Connect 账户可在 Developers 中「删除所有测试数据」（端点与签名密钥不受影响）；浏览器里清掉 `staging.quizbuks.com` 的站点数据，已安装的 App 的会话已失效。

**看到什么**：应用健康；用首个管理员能登录 `https://staging.quizbuks.com/admin/`。

## 2. 首个管理员（DEPLOY「5. 首次启动之后」第 1、3 步；运维 §3 第 2 步）

1. 以 `admin` 登录 `https://staging.quizbuks.com/admin/`（密码是上一步 `.env` 里新写的），右上角用户名 →「安全设置」**开启两步验证**，保存恢复码，**记下绑定时显示的密钥**（以后重新绑定或用脚本登录时要用）。
2. 这时先不删 `.env` 里的首个管理员变量：后面要用首个管理员建人。**建好第二位超级管理员之后**再删（第 4 步末尾）。
3. **试一封邮件（先做这一步，再往下）**：在 `https://staging.quizbuks.com/sponsor/` 的登录页，用一个能收信的测试邮箱注册（会寄验证邮件），再点「忘记密码 / 找回密码」，确认**收到重置邮件**。
   收到：继续（这个账号之后可作彩排的发起方，第 6 步）。收不到：**停下**，写下现象（哪个邮箱、什么时间、页面提示、`docker logs quizbuks-staging-app` 里与邮件有关的行），告诉总控；排查见 DEPLOY「2b. 发信的服务器」。

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
   - `qb.reward.default-methods`：**到店兑换**（`OFFLINE_REDEEM`）与**电子奖品**（`DIGITAL_PRIZE`）两种。运维 §3 写的缺省是 `OFFLINE_REDEEM`，值「逗号分隔」（§3 第 6 步）；电子奖品的方式码取自代码（`RewardMethod`）。
     也可以只对彩排的发起方单独开通：平台管理系统「发起方审核」页，`QB_SPONSOR_REWARD_METHODS_SET`（单个管理员，运维 §3 阶段一第一条）。**待确认**：两种写在缺省里是否被接受；F-S4 还没做，不被接受就改用单独开通。
   - `qb.reward.max-per-attempt` = **`0`（不限）**——这是 staging 的取法；**正式环境须等法务给值**（运维 §3 阶段一第二条）。
   - `qb.review.sponsor.auto` = `true`、`qb.review.publication.auto` = `false`（发布一律在上架前审核）；
   - `qb.transfer.enabled` 保持 `false`，`qb.ai.enabled` 保持 `false`（清单「AI」一节）。
4. **条款**（运维 §3 第 9 步）：`QB_SETUP` 建的四种法律文本是 `[TEST PLACEHOLDER]` 草稿（`legal/README.md`）；这一轮**不贴正式文本**，保持占位，页面上看到占位字样是预期的。
5. **以后再做（这一轮不做）**：收据开具方（`qb.issuer.*`）与科目映射（`qb.journal.account.*`，运维 §3 第 6 步）、分录导出；正式的法律文本；Stripe 测试模式全流程。

**看到什么**：平台管理系统首页没有「请先建默认运营商」的提示；运营商页面显示两位可审核的成员。

## 6. 发起方入驻

1. 发起方在 `https://staging.quizbuks.com/sponsor/` 用邮箱注册，点邮件里的链接验证邮箱（也可用 Google 等登录，视 `.env` 的 `SPRING_PROFILES_ACTIVE`，DEPLOY「staging 环境」第 2 条）。
2. 在发起方页面「发起方资料」填基本信息，申请入驻并同意发起方条款（依据：运维 §3 第 9 步、§7.13「新发起方归谁」；发起方一侧的叫法见词表）。
3. 审核：阶段一设置了 `qb.review.sponsor.auto=true`（第 5 步第 3 点），入驻不逐个人工审核；否则由其运营商的成员在 `/operator/` 的审核队列里决定。
   - 以运维 §7.13 为准：审核即终审，由运营商的成员在运营商管理系统做。（`deploy/quizbuks/README.md` 里「内容管理员在 `/tasks` 审核」是 F-S1 之前的旧写法，已在本 PR 中改为指向 §7.13。）

**看到什么**：发起方页面的状态显示入驻已通过（`APPROVED`）；发起方页面「概况」的「你的运营商」卡片显示默认运营商的名称与联系方式（`help/your-operator.md`）。

## 7. 出题、发布、审核

1. 发起方在发起方页面建 Quiz（题目、选项、正确答案、时限），保存版本；需要的话也可以导入 `.quizbuks.json` 文件（`quiz-file-prompts.md`）或用内容包工具（`tools/quizbuks/content/README.md`，`apply` 可以反复运行）。
2. **奖励方式**：到店兑换（`OFFLINE_REDEEM`）或电子奖品（`DIGITAL_PRIZE`），按第 5 步第 3 点开通；发起方页面此时不显示钱包与充值（运维 §3 阶段一第一条）。
   - 电子奖品要发起方先装入码（流程 `QB_DIGITAL_CODES_LOAD`：粘贴码、一行一个；代码里的说明，设计见 `plans/Q33-digital-prize.md`）。**待确认**：发起方页面上的具体入口和操作文档里没有逐屏写。
3. 发起方走「发布」向导（共三步，保存时一次报告全部问题），选版本、时间、地区、及格线与奖励档，**提交**；提交后内容冻结（`plans/Q4-publishing.md` D-Q4-2、D-Q4-6）。
4. **审核**：发布一律在上架前审核（第 5 步第 3 点）。由该发起方所属运营商的成员在 `/operator/` 审核（运维 §7.13）；直营发起方的发布要由**另一位**成员审核。通过后上架；审核员拒绝以酒类、烟草或毒品为题材或奖品的问卷（运维 §3 阶段一第三条）。

**看到什么**：发布在发起方页面显示已上架；学者的 App 首页能看到它。

## 8. 学者答题领奖（手册 `manual-app.zh.md`，逐节照着走）

学者一侧不另写步骤，照手册的相应小节：

1. **开始使用**：用邮箱注册并打开我们发的链接（答题前须验证邮箱）；在 `/taker/welcome` 填名字和所在国家或地区（Quiz 按国家和地区提供）。
2. **找 Quiz**（首页 `/taker/`）：找到上架的那个发布（可搜索、筛选；已回答的默认不显示）。
3. **答题**（`/taker/p/…`）：看题目数、时间限制、最高奖励，点「开始答题」开始计时（每人一次机会），答完提交；结果页显示得分、是否通过和奖励。
4. **奖励**（手册「奖励」一节），按这个发布的奖励方式：
   - **到店领取的奖品**：在发起方的店里，打开右上角的菜单，点「扫码」（`/taker/scan`），扫描对方屏幕或海报上的码，再点要领取的奖品，在期限内领取。
   - **电子奖品**：码、券或链接出现在 App 中，点「查看奖品」，确认可以使用，或反馈问题。
   - 「历史」（`/taker/history`）列出回答过的每个 Quiz 及其结果和奖励。

**待确认**：发起方一侧的兑换处理（到店兑换、电子奖品的争议）在运营商管理系统里怎么操作，文档里只有运维 §7.13 一句概述，没有逐屏的步骤。

## 9. 彩排之后

- 把发现的问题写下来（现象、页面、时间、发起方或学者的账号、浏览器）；已知的常见问题先查 `docs/quizbuks/troubleshooting.md`。
- 要再来一遍：回到第 1 步（DEPLOY「重置 staging」）。
- 清单里「测试环境（staging）」两条在换 `JABIZ_SEAL_KEY` 之前要看（第 1 步第 5 点）。

## 待确认的汇总

已由总控答复（2026-11-10）并写进上文：邮件（先试一封）、收据与分录导出（以后）、法律文本（占位）、`qb.reward.max-per-attempt`（staging 填 `0`）、入驻由运营商成员审核、奖励方式（到店兑换与电子奖品）、学者一侧（照手册）。

仍要在彩排中确认的：

1. staging 的邮件能不能发（第 2 步第 3 点的结果）。
2. `qb.reward.default-methods` 写两种（`OFFLINE_REDEEM,DIGITAL_PRIZE`）是否被接受；不被接受就对发起方单独开通。（第 5 步第 3 点）
3. 电子奖品：发起方装码的页面入口与操作；到店兑换、电子奖品争议在运营商管理系统里的处理步骤——文档里没有逐屏写。（第 7、8 步）
