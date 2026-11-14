# QuizBuks — 上线检查清单（Q10d）

正式开放（Stripe 正式模式、真实用户）之前逐项确认，每项写上确认人、日期与依据（文档、记录或链接）。操作细节见 `operations.md`，切换正式模式的技术检查在其 §4。
平台的各项（设置检查、告警试发、恢复演练与密钥轮换等）按顺序排在 `launch-day-platform-steps.md`。
任何一项不成立都不上线；确需带着未完成项上线的，由需求方书面决定并写在本页末尾。

每一项下面有一行"确认人 ／ 日期 ／ 依据"：勾选时填上确认的人（名字，不写账号编号）、日期（YYYY-MM-DD）与依据（文档的节、记录、命令输出或链接）；只勾不填的不算完成（F-F14）。
"首次上线不适用"一节中的项在首次上线时不检查，只确认其中写的替代条件（多半是开关保持 `false`）；以后开放对应的功能时把它移回原来的节。

## 开发完成与闭环验收（F-F14，规格"上线准备与闭环验收"）

- [ ] 规格 `first-launch-spec.md` 的 F-F1 至 F-F14、F-S1 至 F-S15、F-C1 至 F-C11、F-M1 至 F-M15 都已合并到 `1.3/quizbuks`，CI 绿；平台的 P0–P8 与之后的平台阶段已合入 `1.3/platform` 并同步过来（进度表 `first-launch-progress.md` 每行为"已合并"或"完成"）
      确认人： ／ 日期： ／ 依据：
- [ ] **F-F12 闭环 e2e** 在 CI 中连续通过；需求方在测试系统中亲手走通六步（`launch-loop-walkthrough.md`），发现的问题已修或已记入下一期
      确认人： ／ 日期： ／ 依据：
- [ ] **样板 5 家**已在测试系统完成，问题记录中的"高"全部关闭（F-F11；恢复步骤与用时见 `operations.md` §7.49，演练见 `rehearsal-staging.md`）
      确认人： ／ 日期： ／ 依据：

## 法务与合规

- [ ] **收据**：登记号（`qb.issuer.registration-no`，适格请求书发行事业者登记号）与税的表述（收据不声称是适格请求书；日元金额与 Kudos 的写法）经财务或法务确认
      确认人： ／ 日期： ／ 依据：
- [ ] 收据开具方的名称与地址（`qb.issuer.name`、`qb.issuer.address`）已以受控变更设好
      确认人： ／ 日期： ／ 依据：
- [ ] **节点证书的颁发方**（F-S5，`operations.md` §7.27）：平台图的证书上印的颁发方是 `qb.issuer.name`（与收据同一个值）；**上线前必须已设好**，否则证书上写缺省的平台名；已发出的证书保持当时的名字，事后改名不会改它们。核实页最长 60 秒的缓存（撤销与停用分享最多晚一分钟显示）已知并接受
      确认人： ／ 日期： ／ 依据：
- [ ] **平台费的账单**（Q87，版式 `qb.fee_invoice`）：是否收月费（`qb.fee.monthly`）、税率（`qb.fee.tax-rate`）与账单上登录番号、税的写法经财务确认；不收费时 `qb.fee.monthly` 保持 `0`（不出任何账单）
      确认人： ／ 日期： ／ 依据：
- [ ] **现金账户**（Q87，首次上线按 F-F10）：充值的目标（`qb.topup.targets` / `qb.topup.default-target`）为 `CASH`、现金换学分与换回（`qb.kudos.buy.enabled` / `qb.kudos.sell.enabled`）为 `false`，以财务页卡片"首次上线的资金开关"全部"一致"为准（`operations.md` §4"首次上线的开关"）；抽成 `qb.creator-fee-rate` 与 `qb.transfer.fee-rate` 为 `0`
      确认人： ／ 日期： ／ 依据：
- [ ] **隐私政策**（个人信息的取得与利用目的、第三方提供：Stripe、邮件服务商、Google、Facebook 与 LINE 登录；保存期间；海外传输）已发布，App 与发起方页面可达；草稿见 `legal/`（填好【】占位后发布）
      确认人： ／ 日期： ／ 依据：
- [ ] 隐私政策还写明：按 IP 地址判断所在地区以决定显示的内容（F-S11）；AdSense 与第三方 Cookie；第三方包括 Stripe、邮件服务商、Google、Meta、LINE、Anthropic 与地址库提供方（署名见"运维"的"IP 地址库"）
      确认人： ／ 日期： ／ 依据：
- [ ] **利用条款**（答题人、商家各自的条款；商家入驻时同意的版本）已发布
      确认人： ／ 日期： ／ 依据：
- [ ] 特定商取引法的表示（商家充值是有偿的）已发布
      确认人： ／ 日期： ／ 依据：
- [ ] 特定商取引法的表示列出首次上线收的费用（配送费、受众规则费、AI 额度、曝光费）与计价方式
      确认人： ／ 日期： ／ 依据：
- [ ] **发起方条款的自动付款一句**（F-M13）：发起方条款写明"到账后自动支付未付账单"（平台在充值、赠送额度或运营商转入到账后，替发起方支付最早的未付账单，不需要本人操作），并随上线前那一版一起同意（是否另发新版、已有发起方是否重新同意，由法务定）；自动付款开关（`qb.fee.auto-pay.enabled`）**发布即打开**（没有发布时为关），所以发布前已通知有未付账单的发起方（且：①平台费账户的邮件文字 `qb.fee.auto-paid`、`qb.fee.invoice-voided` 已由另一位管理员发布；②夜间任务 `qb.fee-auto-pay` 与 `qb.mail-fee-paid-due` 在运行、`qb.reconcile` 的持有人知道 `AUTO_PAY_MISSED`；③`jabiz.mail.enabled` 已开（否则付款通知与作废通知两封邮件都不发）；④`qb.fee.auto-pay.missed-after-hours` 的值已确认（缺省 48），并知道打开后第一夜 `AUTO_PAY_MISSED` 可能报出（见 `operations.md` §7.58，付清后自行消失）；⑤同一次受控变更之外不另开别的钱的开关；⑥先在 staging 上以真实的未付账单走过一次付款、作废与邮件）
      确认人： ／ 日期： ／ 依据：
- [ ] 上面四种文本都是需求方或律师提供的**正式文本**（日文为准据文本、英文一并提供），经 `QB_LEGAL_PUBLISH` 发布且已生效：页面上不再出现 `[TEST PLACEHOLDER]`；
      正式模式启动日志中没有 `legal:TERMS_APP` / `legal:TERMS_SPONSOR` / `legal:PRIVACY` 的告警（`operations.md` §3 第 9 步）
      确认人： ／ 日期： ／ 依据：
- [ ] 上面的正式文本都用新词（学者 / テイカー / Taker，发起方 / 主催者 / Sponsor，运营商 / 運営パートナー，`glossary.md`，F-F5），三语
      确认人： ／ 日期： ／ 依据：
- [ ] **法务意见**：平台费账户中的充值余额、预付的 AI 额度、有有效期的赠送额度，是否构成前払式支払手段（自家型），需要的表示与申报；结论写在本项的"依据"
      确认人： ／ 日期： ／ 依据：
- [ ] **财务确认**：赠送额度付款的记账（借 平台费 Kudos ／ 贷 1130）与消费税的处理；账单的部分付款与多个付款来源；收据与平台费账单的税的写法；**运营商结算的记账取舍**（F-M12）：赠送的"不分成"扣除、作废账单的扣除与贷项凭证的扣减，都是从运营商分成里扣回，照记在 4110（贷，退还时借），所以月报的损益表上 5130 与 4110 各自多（或少）同一数额，净额正确；首次上线接受这样，或要求拆开（拆开要把结转分成两列，由财务以后决定）
      确认人： ／ 日期： ／ 依据：
- [ ] **AI 说明**（法律文本种类 `AI_NOTICE`：发送给 AI 提供方的内容、提供方的数据使用与保留、处理地域）由需求方或律师提供正式文本并发布，Anthropic 的商业条款（是否用于训练、保留期）已由法务确认（`plans/Q8-ai.md` A-11）；未完成时 `qb.ai.enabled` 保持 false 上线
      确认人： ／ 日期： ／ 依据：
- [ ] AI 说明覆盖发起方的 AI 额度与学者的问题解答（F-C10：发送给提供方的内容、保留、地域）
      确认人： ／ 日期： ／ 依据：
- [ ] 帮助中心的问答（账号、答题规则、奖励与转账；商家的入驻、发布、充值与费用）已发布，支持邮箱 `qb.support.email` 已设为 `quizbuks@gmail.com`；法律文本（隐私政策、特定商取引法表记）中的联系邮箱、Stripe 与 Google、Meta、LINE 控制台中的联系与支持邮箱都已改为它（Q41）；`JABIZ_PUBLIC_ENABLED` 未设为 `false`
      确认人： ／ 日期： ／ 依据：
- [ ] **上线的内容**（Q36，`tools/quizbuks/content/README.md`）：需求方的商户账号（与管理员不同的邮箱）在正式系统自助注册、通过入驻审核并充值；测试系统中整理好的内容包（私有仓库）以 `apply` 写入、管理员审核发布；至少一个长期的公开发布（欢迎问卷），`renew` 的运行日期已记入日程；不从测试系统复制数据库
      确认人： ／ 日期： ／ 依据：
- [ ] 保留期：导出文件、汇总凭证与收据的保留（L-4：10 年）已以 `RetentionPolicy` 声明（`backend/quizbuks/CLAUDE.md`"保留期"一条：分录导出、明细段、充值、收据、平台费与账单、单据存档等，自会计年度末起算）；确认人核对声明的年数与范围符合法务与财务的要求
      确认人： ／ 日期： ／ 依据：

- [ ] **Facebook 与 LINE 登录（Q40，开 `social` 时）**：Facebook 应用已切换为正式模式、LINE 渠道已获准取得邮箱；用真实账号在 App 与发起方页面各走一遍：首次登录开户、收到验证邮件、在登录着的浏览器中打开链接后能保存资料或申请入驻、退出后再用同一方式登录（平台 D61 的端点与签名只以存根测试过）
      确认人： ／ 日期： ／ 依据：
- [ ] **安装到主屏幕（Q38）**：iPhone Safari 与 Android Chrome 各装一次（"我的 → 设置 → App 设置"中的入口、答完测验后的提示）；LINE 与微信中打开分享链接看到"在浏览器中打开"；iPhone 桌面 App 中邮件链接（验证、邮件登录）与 Google 登录的表现可以接受（`plans/Q38-install.md` §4）
      确认人： ／ 日期： ／ 依据：
- [ ] **国产安卓手机的"添加到桌面"（Q50）**：找两三台（最好有华为、小米与一台 vivo 或 OPPO），各用系统自带的浏览器与 QQ 浏览器或 UC 打开 `/taker/`，按账户页的"查看步骤"添加；确认步骤的文字与手机上的菜单一致（不一致时把实际的写法告诉开发，改平台的文案），没有图标时按提示开启"桌面快捷方式"权限后能添加；微信与 QQ 中打开分享链接，第一屏就看到"点右上角的'···'"的提示
      确认人： ／ 日期： ／ 依据：

- [ ] **招商网站的"试试看"（Q95）**：平台图第一个节点的挑战（官方账号）三种语言都已发布，链接与二维码已填入 `quizbuks-web/biz/src/content/try-quizzes.json`，首页不再显示"即将推出"
      确认人： ／ 日期： ／ 依据：
- [ ] **Google AdSense（Q95，首次上线即开）**：隐私政策（含 AdSense 与第三方 Cookie）的新版本已经 `QB_LEGAL_PUBLISH` 发布；AdSense 账户已审核通过、站点 `quizbuks.com` 已登记；欧洲经济区、英国、瑞士访客的同意（Google 认证的同意管理平台）已在 AdSense 中设好；正式系统上三处（`/biz/`、`/taker/`、`/sponsor/`）都能看到广告，平台管理系统、运营商管理系统与登录页没有
      确认人： ／ 日期： ／ 依据：

## 资金开关（F-F10）

- [ ] 正式环境 `QB_SETUP` 的提案已由另一位管理员按 `operations.md` §4"首次上线的开关"的顺序发布；财务页"首次上线的资金开关"可比较的行全部"一致"，启动日志没有 `FirstLaunchSwitchesCheck` 的警告
      确认人： ／ 日期： ／ 依据：
- [ ] **核对的两个阈值**（F-M14a，`operations.md` §7.52）：`qb.prepayment.release.overdue-days` 与 `qb.audience.refund.missing-after-hours` 已是受控参数。F-M7 之后还没跑过 `QB_SETUP` 的安装，`qb.audience.refund.missing-after-hours` 会成为提案：发布之前 `AUDIENCE_REFUND_MISSING` 不执行，所以要与其他提案一起发布；已有值的安装保留现值
      确认人： ／ 日期： ／ 依据：
- [ ] Stripe 正式模式的各项（本页"Stripe"）完成；小额实测充值进入现金子账户
      确认人： ／ 日期： ／ 依据：
- [ ] 各运营商的价目表（配送费、受众规则费、曝光费）与 AI 额度的价格、倍数、汇率已以受控变更设好；赠送额度的金额与有效期已设
      确认人： ／ 日期： ／ 依据：
- [ ] 打开曝光费的开关（`qb.exposure.billing.enabled`）之前，各运营商都已设好曝光价格：没设价格的运营商下面，用别人视频的发布完全免费且不限次数（F-M8 问题 6）
      确认人： ／ 日期： ／ 依据：
- [ ] **曝光的最少购买次数**（F-M8 问题 3）：提交发布时最少购买的曝光次数（`qb.exposure.min-purchase`）现为占位的 100 次；上线前由需求方定数并以受控变更设好（追加购买不设最少）
- [ ] **曝光价格范围与单次追加上限**（F-M8）：运营商可设的单价范围（`qb.exposure.unit.min` 现为占位 1、`qb.exposure.unit.max` 现为占位 100，日元 / 次）与一次追加购买的上限（`qb.exposure.top-up.max` 现为占位 1,000,000）上线前由需求方定数并以受控变更设好；**开关 `qb.exposure.billing.enabled` 在曝光费的结算（F-M8c）合并之前不要打开**（它在首次上线登记表里为“等待”，F-M8c 才改为生效）
      确认人： ／ 日期： ／ 依据：
- [ ] **法务：注销时现金余额不退**（F-M15 问题 6）：发起方注销后，平台费账户里剩下的现金留在已关闭的账户、不退银行（注销前的提示写明，`operations.md` §7.59）；请法务确认这一做法与预付性质余额的退款规定（前払式支払手段）是否相容，不相容时由财务定线下退款的流程并改提示
      确认人： ／ 日期： ／ 依据：
- [ ] **财务与法务：注销后开出的账单**（F-M15 问题 12）：注销之后才开出的账单没有收件方名称（个人信息已抹除），编号、日期、金额完整并存档；请确认能否作为凭证（适格请求书的收件方名称），不能时另定替代办法
      确认人： ／ 日期： ／ 依据：
- [ ] **注销收尾的开关 `qb.erasure.close.enabled`**（F-M15 b，受控，首次上线登记为开）：F-M13 的自动付款已合并（#696），收尾以它的单项付款、不看 `qb.fee.auto-pay.enabled`；发布后注销的发起方会先用账户里的钱付欠费、再作废剩余的赠送额度，由财务确认发布时点
      确认人： ／ 日期： ／ 依据：
- [ ] **AI 额度与注销收尾**（F-M15 b，复核 M2）：有 AI 额度（用途 `AI` 的预付款）的发起方注销后，收尾停在 `DEPOSIT_OPEN`，直到 F-M15c 关闭那笔预付款，属预期；F-M15c 合并之前不打开 `qb.ai.fee.enabled`（首次上线本来就关着）
      确认人： ／ 日期： ／ 依据：

- [ ] **运营商分成的 8 个设置**（F-M12 a）：部署后，`QB_SETUP` 提出的 `qb.operator.share.kinds`、`qb.operator.share.ai-cost-in-base`、`qb.operator.credit-recovery.rate`、`qb.operator.gift.treatment.*`（4 个）、`qb.operator.gift.recover-outside-base` 已由另一位管理员发布（四眼）；未发布时结算任务对每个运营商都 422 `QB_OPERATOR_NO_SHARE_SETTING`。**必须在部署后的第一次结算任务之前完成**（staging：12 月的第一次任务之前）
      确认人： ／ 日期： ／ 依据：
- [ ] **数据库连接与结算的索引**（F-M12 d，#701）：部署后确认应用的连接上 PostgreSQL 的 JIT 为关（`operations.md` §7.60"数据库的 JIT 关闭"：环境变量 `SPRING_R2DBC_PROPERTIES_OPTIONS` 没有覆盖 `application.yml` 的 `jit=off`；开着 `pg_stat_statements` 时应用语句的 `jit_functions` 为 0），以及迁移 V129 并发建的两个索引都有效：`SELECT c.relname, x.indisvalid FROM pg_index x JOIN pg_class c ON c.oid = x.indexrelid WHERE c.relname IN ('qb_fee_charge_version_operator_period_idx', 'qb_fee_payment_version_credit_paid_idx')` 两行都为 `t`（为 `f` 照 V129 的注释处理）。
      确认人： ／ 日期： ／ 依据：

## 内容与运营

- [ ] 总部的运营商至少有两位能审核的成员（直营发起方的发布由另一位成员审核，F-S1）；每个运营商恰好一个直营发起方，每日核对 `qb.operator-check` 没有 `NO_DEFAULT_OPERATOR`、`DIRECT_SPONSOR`、`FEW_REVIEWERS`（`operations.md` §7.13）
      确认人： ／ 日期： ／ 依据：
- [ ] 平台图第一个节点的发布（官方账号，即平台运营商的直营发起方）三语已发布（与"法务与合规"中招商网站"试试看"一项同时确认）
      确认人： ／ 日期： ／ 依据：
- [ ] 第一批样板间以发起方包导入正式系统并经审核（规格"未决"第 4 条定为导入时；`operations.md` §7.33、§7.49）
      确认人： ／ 日期： ／ 依据：
- [ ] 帮助中心三语问答覆盖首次上线的全部新功能（知识结构图、平台费账户与赠送额度、AI 额度、资源库、境外视频的说明、屏蔽与主题、扫一扫与通行码），用新词
      确认人： ／ 日期： ／ 依据：
- [ ] 邮件模版：改名后的缺省文字三语抽查过；有覆盖的已对照"缺省已更新"（`operations.md` §7.45）
      确认人： ／ 日期： ／ 依据：
- [ ] `troubleshooting.md` 中 T6、T8、T9、T10、T12、T13 已归档
      确认人： ／ 日期： ／ 依据：

## 性能

- [ ] 在**上线所用的硬件**上重测（压测程序在另一台主机，读者按预期读取量限速；`./gradlew :quizbuks:loadTest`，`operations.md` §6；以千题题库与抽题的发布：`LOAD_QUESTIONS`、`LOAD_CONTINUE=yes`）：预期峰值低于饱和点并有约 1.3 倍余量，应用与数据库 CPU < 60–70 %，结果写进 `perf/`
      确认人： ／ 日期： ／ 依据：
- [ ] 同一硬件上以带千题题库与抽题的发布重测版本快照变大后的开始、继续、提交（`LOAD_QUESTIONS`、`LOAD_CONTINUE=yes`，与上一项同一次压测即可）
      确认人： ／ 日期： ／ 依据：
- [ ] **观察项（F-F11c）**：同一硬件上对 1000 题三语的题库各计时一次：单个文件以 `intoQuizId` 替换（`QB_QUIZ_IMPORT`，换成 200 题三语）与删除 Quiz（`QB_QUIZ_DELETE`），**目标 10 秒以内**。本地实测（2026-11-12，平台批量删除之后）：替换 8–11 秒，删除约 7 秒。超过 10 秒时按 `plans/impl/F-F11.md` §10"F-F11c"的分步办法处理，不阻挡上线
      确认人： ／ 日期： ／ 依据：
- [ ] 同一次压测中让分析任务 `qb.analytics` 照常每 10 分钟运行（Q9e，`perf/Q9e-analytics.md`）：提交 p95 与吞吐与不运行时相同，任务每次运行 < 1 分钟
      确认人： ／ 日期： ／ 依据：
- [ ] 同一次压测之后补跑每日核对 `qb.reconcile`，记下各核对模板的耗时（`rehearsal-staging.md` §12，F-M14b）：每个模板的 max 低于数据视图时限 5 秒（建议 2.5 秒以下），整个任务 1 分钟以内
      确认人： ／ 日期： ／ 依据：
- [ ] 提交过载（503、以 EXPIRED 结束）的风险已向需求方说明并接受，或已做缓解（`operations.md` §7.6）
      确认人： ／ 日期： ／ 依据：
- [ ] `max-active` 与连接池按"同时热门的发布数 × 4 + 1"确认
      确认人： ／ 日期： ／ 依据：
- [ ] **从中国大陆访问（Q50）**：在中国大陆的网络（手机流量与家庭宽带各一）打开首页、登录、答一份问卷，记下首屏与答题页的加载时间与有没有打不开的资源；**平台自己的视频服务器（正式 `videos.quizbuks.com`，测试 `videos.staging.quizbuks.com`）上的视频文件能播放，以及开始播放的等待时间**（F-S15，`operations.md` §7.29；能播放后由总部经受控变更（四眼）把该环境自己的主机加进 `qb.video.mainland-accessible-sites`：正式环境只登记 `videos.quizbuks.com`，测试环境只登记 `videos.staging.quizbuks.com`；不写进 `QB_SETUP`）；以后偶尔复测（尤其是改了 CDN、字体、第三方脚本或视频服务器之后）
      确认人： ／ 日期： ／ 依据：
- [ ] 同一次中国大陆的访问中：含境外视频的发布看不到，设了例外的能答（F-S11，`operations.md` §7.24）
      确认人： ／ 日期： ／ 依据：

## AI（Q8a、Q8b）

- [ ] **Opus 与 Sonnet 的比较**（A-2）：以 20 份真实测验分别用 `claude-opus-5-5` 与 `claude-sonnet-5-5`（effort `medium`）生成候选，比较可用题数、需要改动的题数与错误的正确选项，并**记录每次的响应时间**（p50、p95、最长，与超过 90 秒的比例：请求在 2 分钟时被放弃并按预估计费，接近它就降低题数上限，或等平台的流式读取），需求方据此决定 `qb.ai.model`（Sonnet 约为一半成本）；记录写在本项
      确认人： ／ 日期： ／ 依据：
- [ ] 正式的 `ANTHROPIC_API_KEY` 只在密钥库，Anthropic Console 的工作区月支出上限已设；`qb.ai.model`、`qb.ai.effort` 与限额按预算以受控变更确认
      确认人： ／ 日期： ／ 依据：
- [ ] `qb.ai.enabled` 在上面三项完成后才开启（四眼）；开启后用一个测试商家生成、校对、加入一次，`/qb/ai` 显示用量
      确认人： ／ 日期： ／ 依据：
- [ ] **AI 费的分成**（F-M9、F-M12）：打开 `qb.ai.fee.enabled`（AI 收费开关）之前，F-M12 已完成（AI 费中属于成本的部分不进运营商分成基数），或财务已书面确认运营商对 AI 费的分成比例（AI 费是成本的 150%，其中 100% 是平台付给 AI 提供方的成本，整笔分给运营商，分成超过费用的三分之一时平台就亏；F-M9 先把 AI 种类排除在分成基数之外，所以这一项没有完成时运营商分不到 AI 费）
      确认人： ／ 日期： ／ 依据：
- [ ] **官网生成的 AI 费预留**（F-M9，#692 复核）：打开 AI 收费开关（`qb.ai.fee.enabled`）之前，用 staging 或生产上真实 `SITE_PROFILE` 作业的用量（`operations.md` §7.56 的 SQL）定出 P95，经受控变更发布 `qb.ai.fee.site-profile.calls` / `.pages` / `.output-tokens`。未设置时预留按作业自身的上限（最坏情况），请求会因为预留过大而常被拒；作业超出预留的部分由平台承担，每日核对以提示 `AI_RESERVATION_SHORT` 列出

- [ ] **暂停的演练（平台 16v-2）**：`QB_SETUP` 的 `qb.ai.enabled` 提案（`false`）已发布，启动日志中没有 `EXTERNAL` 对 `qb.ai.questions` / `qb.ai.cover` 的 `enabledBy` 警告；以测试商家请求一次后以四眼关闭开关，确认作业留在排队（商家页面注明 AI 暂停）、`jabiz.external.job.held` 为 1、提供方没有收到调用，重新打开后作业完成；部署环境中没有残留的 `QB_AI_HALTED`
      确认人： ／ 日期： ／ 依据：
- [ ] 值班手册中的 AI 紧急停止按 `operations.md` §7.8 的顺序：吊销密钥 → 四眼关闭 `qb.ai.enabled`（排队与重试中的作业随之暂停）→ 逐个取消；两位能发布受控变更的管理员的联系方式已写明
      确认人： ／ 日期： ／ 依据：
- [ ] **PDF 的计数（Q8b，复审 L-g）**：开启 PDF 资料之前，以正式的密钥手动调用一次真实的 `POST /v1/messages/count_tokens`，请求与作业发送的相同（带 `thinking`、`output_config` 的 effort 与结构化输出的 Schema、一个文档块），确认它接受这些字段并返回 `input_tokens`；再以一个加密的 PDF 与一个超过页数的请求各试一次，把提供方 400 的原文与 `ClaudeErrors` 的匹配对照（对不上即改匹配或记下）
      确认人： ／ 日期： ／ 依据：
- [ ] **PDF 资料（Q8b）**：以一份真实的多页 PDF 资料生成一次题目，记录实际的输入 Token 与每页 Token，确认 `QB_AI_PDF_MAX_PAGES` 与 `qb.ai.sponsor.monthly-tokens` 的取值（带 PDF 的请求按页数上限预留月 Token，`operations.md` §7.8）；AI 说明的正式文本写明 PDF 资料的原文件会发送给 AI 提供方
      确认人： ／ 日期： ／ 依据：

## Stripe

- [ ] **Stripe 测试模式全流程已完成**（`stripe-test-mode.md`，真实测试密钥，不是桩），记录写在该手册的"记录"中，结论为可以
      确认人： ／ 日期： ／ 依据：
- [ ] Stripe 正式账户（日本）审核通过；Connect 平台资料、Express 的品牌与设置完成；打款银行与打款计划已设
      确认人： ／ 日期： ／ 依据：
- [ ] 正式的受限密钥与两个正式 Webhook 端点（API 版本 `2025-03-31.basil`）已建，密钥只在密钥库
      确认人： ／ 日期： ／ 依据：
- [ ] 小额实测（真实卡、最小额充值与退款、次日核对 `OK`）完成（`operations.md` §4）
      确认人： ／ 日期： ／ 依据：

## 安全

- [ ] **安全审查（Q10b）**的发现全部关闭（或逐条由需求方接受并记录），含已登记的跟进（题目图片原始文件名、保留期）
      确认人： ／ 日期： ／ 依据：
- [x] 依赖与镜像的已知漏洞已评估（`gradle dependencies`、`pnpm audit`、镜像扫描）——S1，2026-11-08；依据 `security/dependency-review.md`：高、严重的条目或已升级（平台 #523、#524，QuizBuks #522、#527），或逐条说明够不到；#527 的 CI 镜像报告中应用镜像为 0
      确认人：S1 ／ 日期：2026-11-08 ／ 依据：`security/dependency-review.md`；平台 #523、#524，QuizBuks #522、#527
- [ ] **全部管理员都有二次验证**；管理员角色设了"要求二次验证"；没有共用账号；首个管理员的 `JABIZ_BOOTSTRAP_ADMIN_*` 已从环境中删去
      确认人： ／ 日期： ／ 依据：
- [ ] **至少两位超级管理员**（`QB_ADMIN_SUPER`，平台 16u，D51）在职、各自二次验证，按 `operations.md` §3 第 4–5 步的顺序建立（`QB_SETUP` 与职责分离规则的发布在第二位之前）；建立期已结束（`sec_protected_grants_armed` 有一行），试过一次由一位申请、另一位审批的角色分配；`JABIZ_SECURITY_PROTECTEDGRANTS_ENABLED` 未设
      确认人： ／ 日期： ／ 依据：
- [ ] **密钥已从任何演示或测试的值轮换**：`JABIZ_JWT_SECRET`、`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY`、`JABIZ_SEAL_KEY`、数据库密码、Stripe 密钥与签名密钥、SMTP、Google、Facebook 与 LINE 的客户端密钥都是为生产新生成的，没有在本机演示、e2e、压测或测试服务器上用过；`JABIZ_INTEGRITY_KEY`、`JABIZ_MFA_KEY` 与 `JABIZ_SEAL_KEY` 的离线副本已保存
      确认人： ／ 日期： ／ 依据：
- [ ] `operations.md` §1.4 的测试开关一个都没设；`SPRING_PROFILES_ACTIVE` 不含 `dev`；`JABIZ_SECURITY_MFA_ADMINISTRATION` 未设；`QB_STRIPE_PLACEHOLDERS=false`
      确认人： ／ 日期： ／ 依据：
- [ ] **设置检查**：在服务器上对正式环境的 `.env` 运行平台的检查与 QuizBuks 的规则，`0 error(s)`（只读，只输出变量名，不输出值）：
      `tools/ops/env-check.sh /opt/apps/quizbuks/.env deploy/quizbuks/server/env-check.rules`。平台检查密钥（设了、够长、互不相同、不是仓库中的测试值）、
      没有 `dev`、首个管理员的变量已删、管理的二次验证没有关、`JABIZ_TRUSTED_PROXIES`、`JABIZ_WEB_ENVIRONMENT=production`、告警收件人与 Grafana 的 SMTP；
      QuizBuks 的规则检查 §1.4 的测试开关、本地 `.env` 的占位开关、QuizBuks 测试用的封存密钥、监控已开启（`COMPOSE_PROFILES=observability`、指标与日志的 OTLP）。
      警告（如"轮换进行中"）逐条看过
      确认人： ／ 日期： ／ 依据：
- [ ] 只经 HTTPS 访问（HSTS），`JABIZ_TRUSTED_PROXIES` 已设；数据库与管理端口不对公网开放；指标不在应用端口上暴露
      确认人： ／ 日期： ／ 依据：
- [ ] 正式环境的 `.env` 有 `JABIZ_WEB_ENVIRONMENT=production`（平台 16zd）：打开 App、发起方页面与平台管理系统的登录页，顶部**没有**"测试环境"的横条、标题没有 `[TEST]`；
  staging 打开同样的页面**有**横条（两边各看一次，确认标志指向正确的环境）
      确认人： ／ 日期： ／ 依据：
- [x] 正式域名已写进 `tools/quizbuks/stripe-test-mode/production-hosts`（`quizbuks.com`、`www.quizbuks.com`；正式域名变了要同步改）
      确认人：S3 ／ 日期：2026-11-12 ／ 依据：`tools/quizbuks/stripe-test-mode/production-hosts` 中有 `quizbuks.com` 与 `www.quizbuks.com`（2026-11-03 写入）

## 运维

- [ ] **备份已测试**：WAL 归档与每日基础备份在运行，恢复演练（`operations.md` §2.4，`tools/ops/restore-drill.sh`，**加 `DRILL_ROTATE=true`**）做过一次，结果 `PASSED`；
      演练记录即脚本写出的 Markdown（RTO、RPO、每项核对，含 QuizBuks 的三个模板与密钥轮换），附在本项；文件卷有备份
      确认人： ／ 日期： ／ 依据：
- [ ] **监控与告警已上线**（`operations.md` §5）：本环境的 LGTM 在运行（`COMPOSE_PROFILES=observability`），OTLP 已接入；平台的规则（`docker/grafana/alerting/jabiz-alerts.yaml`）与 QuizBuks 的规则（`deploy/quizbuks/alerting/quizbuks-alerts.yaml`）都已载入；
      在 staging 上以 `tools/ops/alert-fire-test.sh` 试过：`rules`（全部求值无误；QuizBuks 的文件夹 `quizbuks` 在 Grafana 中看）、`notify`（值班的人收到了测试告警）、`fire`、
      `disk <上传文件的目录>`（F-F13 的验收"把磁盘填到阈值"：磁盘告警进入 pending 后脚本删去临时文件）；数据库主机的 WAL 归档、备份与磁盘告警由托管方或主机监控配置并试过
      确认人： ／ 日期： ／ 依据：
- [ ] 首次上线的新告警已在 staging 试发（`operations.md` §5 的表）：刷新补发次数异常（`jabiz-refresh-reuse-*`，F-F6）、磁盘用量（`jabiz-disk-usage`，F-F13）、地址库缺失或过旧（`jabiz-geoip-*`，P5）、费用的每日核对差异（`qb-reconcile-diff`，收费组）
      确认人： ／ 日期： ／ 依据：
- [ ] 首次启动（`operations.md` §3）完成：`QB_SETUP` 的提案已发布，科目映射已设（含 Q87 的 `qb.journal.account.2160 / 1130 / 4120 / 2150`），审批规则已发布（含 `qb.fee-request.review`）
      确认人： ／ 日期： ／ 依据：
- [ ] **IP 地址库**（F-S11，`DEPLOY.md`"3c"、`operations.md` §7.24）：DB-IP Lite 的 CSV 已放进卷 `geoip`、`.env` 设了 `JABIZ_GEOIP_DATABASE`（正式模式下不设即启动失败），每月更新排进值班表；从中国大陆与日本的网络核对过 `/api/client/region`；`qb.video.mainland-accessible-sites` 已发布；隐私政策含"按 IP 判断所在地区"与 DB-IP 署名的版本已**勾选"须重新同意"**发布（需求方 2026-11-07，§7.24 第 6 步），已同意旧版的测试账号登录时被要求同意新版
      确认人： ／ 日期： ／ 依据：
- [ ] **短视频**（F-S10，`operations.md` §7.25）：`QB_SETUP` 已建 `qb.feed.shorts.positions` / `.per-shelf`；帮助"短视频"（`docs/quizbuks/help/shorts.md`）已由内容管理员发布为学者与发起方的 FAQ
      确认人： ／ 日期： ／ 依据：
- [ ] **主题词**（F-S9，`operations.md` §7.26）：`QB_SETUP` 已建 `qb.topic.max-per-quiz` / `.custom-max-length`；总部已在平台管理系统的"主题词"页建好首批词（三语名称齐全，启动检查无 `topic:` 错误）；帮助"主题词与只看喜欢的主题"（`docs/quizbuks/help/topics.md`）已由内容管理员发布为学者与发起方的 FAQ（自定义词的搜索一句等 F-S6）
      确认人： ／ 日期： ／ 依据：
- [ ] **屏蔽**（F-S8，`operations.md` §7.30）：`QB_SETUP` 已建 `qb.block.max-per-user` / `.count-min-group`，内容管理员有 `qb.admin.block.read`（平台管理系统的"屏蔽"页只有汇总）；帮助"屏蔽与解除"（`docs/quizbuks/help/blocks.md`）已由内容管理员发布为学者与发起方的 FAQ
      确认人： ／ 日期： ／ 依据：
- [ ] **曝光计数**（F-S13，`operations.md` §7.31）：`QB_SETUP` 已建五个 `qb.exposure.*` 参数，内容管理员有 `qb.admin.exposure.read` / `.monitor`、运营商有 `qb.admin.exposure.read`；"定时任务"页中 `qb.exposure-ends`（每分钟）与 `qb.exposure-check`（每天）在跑；帮助"共享视频的曝光"（`docs/quizbuks/help/exposure.md`）已由内容管理员发布为学者与发起方的 FAQ
      确认人： ／ 日期： ／ 依据：
- [ ] 值班与联系方式：Stripe 支持、邮件服务商、数据库托管方
      确认人： ／ 日期： ／ 依据：
- [ ] 本清单与 `operations.md` 中的步骤由将要值班的人读过
      确认人： ／ 日期： ／ 依据：
- [ ] 每个首次上线的功能在 `operations.md` §7 开头的"首次上线的功能与小节"中都有处理步骤（"随功能交付"的一行都已换成小节号）
      确认人： ／ 日期： ／ 依据：

## 测试环境（staging）

- [ ] **换 `JABIZ_SEAL_KEY` 之前重置测试库，或请数字产品的提供方重新装入码**（平台 D78，V81）：V81 之前由提供方装入码池的码没有记下装码人（`QbDigitalCode.fulfillerId`），换密钥后平台的 `jabiz.key-rotation` 以发布方为上下文算不出、打不开，记为 `unreadable` 并原样保留；正式环境从 V81 之后才有数据，不受影响
      确认人： ／ 日期： ／ 依据：

## 上线

- [ ] **配送费价目都在总部的范围内**（核对项 `DISTRIBUTION_PRICE_RANGE` 第一次真正运行）：部署之前在 staging 库与正式库上各执行一次 `psql "$DATABASE_URL" -f tools/quizbuks/sql/distribution-price-range.sql`，结果为空；有行时先改价目或范围（`operations.md` §4 检查清单、§7.48）
      确认人： ／ 日期： ／ 依据：
- [ ] 正式系统上线；第一批真实用户走通闭环（学者答题、发起方发布、运营商审核、兑换）；结果记在本项的"依据"
      确认人： ／ 日期： ／ 依据：

## 首次上线不适用


以下各项与首次上线不开放的功能有关（规格"首次上线不做"：商店 App、Apple 登录、Shopify、学分的使用、提现与转账），原文保留，首次上线不检查；只确认每项前面写的替代条件。

- **替代条件**：`qb.transfer.enabled` 与 `qb.withdrawal.bank.enabled` 为 `false`（`operations.md` §4 的卡片"一致"）。转账与学者的提现不开放，下面的法务意见以后开放时再取得：
  - [ ] **Q6b 转账的法务意见**（资金決済法：Kudos 与转账是否构成资金移动、前払式支付手段等；`03-plan.md` 第 4 节）已取得，结论允许按现设计开放转账；否则 `qb.transfer.enabled` 保持 false 上线
      确认人： ／ 日期： ／ 依据：
- **替代条件**：同上（转账不开放）：
  - [ ] 资金调度已定（充值几天后才可用；转账从可用余额转出）
      确认人： ／ 日期： ／ 依据：
- **替代条件**：不提交 App Store 审核，`SPRING_PROFILES_ACTIVE` 不含 `apple`，Apple 的变量不设：
  - [ ] **Apple 登录（平台 17j）**：提交 App Store 审核前，production 的 `SPRING_PROFILES_ACTIVE` 由 `social` 改为 `apple`，设好 Apple 的五个变量（`deploy/quizbuks/README.md`"前端"），以真实的 Services ID 在网页与 App 中各登录一次
      确认人： ／ 日期： ／ 依据：
- **替代条件**：不发布 iOS App（PWA 照常，"安装到主屏幕"一项照常检查）：
  - [ ] **iOS App（Q49c）**：TestFlight 版装在 iPhone 上：打开即 App 的首页、没有安装提示；邮件中的验证与邮件登录链接打开 App；`/sponsor/` 与 `/biz/` 的链接在 Safari 打开；问卷中嵌入的视频能播放；Q49c-2 之后 Google、Facebook、LINE 与 Apple 登录回到 App、推送能收到（`plans/Q49c-ios.md` §3）
      确认人： ／ 日期： ／ 依据：
- **替代条件**：不发布 Android App（同上）：
  - [ ] **Android App（Q49b）**：封闭测试版装在一台 Android 手机上：打开后没有浏览器地址栏（`assetlinks.json` 验证通过）；首页、结果页、"App 设置"页都没有安装提示；验证与邮件登录的邮件链接在 App 中打开，`/sponsor/` 与 `/biz/` 的链接在浏览器打开；Google、Facebook、LINE 登录回到 App；返回键与断网时的表现可以接受（`plans/Q49b-android.md` §4）
      确认人： ／ 日期： ／ 依据：
- **替代条件**：Shopify 不在首次上线；只在测试库中存在的旧行，换测试环境的封存密钥前照下面处理：
  - [ ] **更换 `JABIZ_SEAL_KEY` 之前**（含恢复演练的 `DRILL_ROTATE=true` 用的是测试库时）：迁移 V84 之前保存的 Shopify 访问令牌行没有店铺地址（`qbs_shop_token.shop_domain` 为空），
        密钥轮换解不开它们（计为 `unreadable`，留在旧密钥下）。Shopify 不在首次上线中，这样的行只在测试库里：先重置测试库，或在内嵌页中重新连接店铺（`QBS_SHOP_CONNECT` 写入地址），再换密钥
        （`plans/impl/F-F14-sealed-fields.md`）
      确认人： ／ 日期： ／ 依据：
- 现金换学分与换回（`qb.kudos.buy.enabled` / `qb.kudos.sell.enabled`）、学分奖励与报名费：首次上线学分只显示、不使用，开关为 `false`，由"资金开关"一节确认，这里没有另外的检查项。
- AI 生成封面（Q8b）已撤销（Q98a），不检查。

## 带着未完成项上线的决定

（无）
