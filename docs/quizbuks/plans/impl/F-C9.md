# F-C9 统一信纸：QuizBuks 一侧的接入（S4）

规格：`docs/quizbuks/first-launch-spec.md` F-C9（来源 `Q107-mail-templates.md` §4、§8 第 2 条）。平台能力是 D72 第 6 条（平台 17zh-b：`MailLayout` 实体、`MAIL_LAYOUT_*`、受控变更 `mail-layout`、`MailLayoutRules`、信纸页 `/mail/layout`），已在 `1.3/platform` 做完并同步过来。本项只做 QuizBuks 一侧：缺省信纸、授权、运维文档、测试。
规格写"依赖 F-C8"：平台部分两者已都在，QuizBuks 一侧的授权（`mail.text.write`）由 F-C8 的计划一并定，本项与 F-C8 同一套授权决定（见 §问题 1），所以两项的角色改动合并到先开的那个 PR。

## 做什么

1. **缺省信纸**写进 `backend/quizbuks/src/main/resources/application.yml` 的 `jabiz.mail.layout.<语言>.*`（三种语言各一份），由品牌档案在打包时写入，代码里不写品牌：
   - `display-name`：`@QB_BRAND_NAME_zh@` / `_en` / `_ja`（`build.gradle.kts` 的 `qbBrandTokens` 已有每种语言的名字；应用文件已经过这套替换，照 `rp-name: @QB_BRAND_NAME@` 的做法）；
   - `footer`（Markdown）：品牌名、客服邮箱（`mailto:@QB_BRAND_SUPPORT_EMAIL@`）、官网（`@QB_BRAND_SITE_URL@`，主机须在 `jabiz.mail.base-url` 的主机或 `allowed-link-hosts` 中，否则启动失败；见风险）。页脚适用 F-C8 的同一套链接规则：不含令牌、登录链接与 `{unsubscribeUrl}`，退订行由平台自动加；
   - `logo-url`、`logo-alt`：见 §问题 2。
   - 用词照 `glossary.md`，页脚与显示名中不出现"后台"。
2. **授权**：同 F-C8 的 §问题 1（`MailLayoutControlTarget` 提出与发布都要 `mail.text.write`，所以发布人也要持有它）。`QbRolesTest` 写明。
3. **运维文档**：`operations.md` 新增一节（号取开 PR 时的最大号 +1，先向总控核号）：谁能改信纸、怎么提出与发布（另一位管理员）、恢复默认即时生效、存的信纸不合格时按缺省发出并告警（观测 `jabiz.mail.layout.fallback`）、缺省值（配置）不合格即启动失败、怎么试发。与 F-C8 的邮件文字同在一节或相邻两节（定稿时对齐）。
4. **帮助**：管理员读 `operations.md`，不另写帮助页（发起方的帮助底稿不涉及信纸）。

## 不做

- 颜色与对比度（规格定：首次上线不做）。
- 新页面：平台管理系统的「邮件信纸」页（`/mail/layout`，平台 17zh-c）按权限显示，QuizBuks 不另写。
- 迁移、新参数（缺省信纸用平台的配置键）。

## 测试

- 后端 `MailLayoutDefaultsTest`（QuizBuks 单元）：读 `application.yml` 的缺省信纸，三种语言都有显示名与页脚；照平台的 `MailLayoutRules` 检查无问题；页脚不含令牌、登录链接、`{unsubscribeUrl}`。
- `QbRolesTest`：内容管理员与财务管理员的 `mail.text.*`，超级管理员没有（若问题 1 照建议）。
- `MailLayoutIT`（QuizBuks 一侧，真实 PostgreSQL）：内容管理员提出、财务管理员发布后信纸生效，发出的邮件 HTML 与纯文本带页眉页脚；本人发布被拒（`CONTROL_SAME_PERSON`）；恢复默认即时生效；时态表上没有 UPDATE / DELETE。平台已有同类 IT，这里只验 QuizBuks 的角色与缺省值接得上。
- 启动检查：`./gradlew :quizbuks:platformCheck`（缺省信纸不合格即失败）。
- e2e `mail-layout.spec.ts`：内容管理员在平台管理系统「邮件信纸」页提出修改 → 待审批显示 → 第二位管理员（测试用的 `QB_E2E_PUBLISHER`，需加上 `mail.text.write`，仅测试）发布 → 触发一封通知，Mailpit 收到的邮件带新页眉与页脚；亮暗两种外观过 axe。
- 语句数：`StatementBudgetIT` 不变（取信纸在投递事务中）。

## 风险

- 官网主机与 `jabiz.mail.base-url` 的主机不同时，页脚的官网链接会让启动失败（平台规则：绝对链接只许本站）。上线前须把官网主机列入 `jabiz.mail.texts.allowed-link-hosts`，或页脚只写文字不加链接；写进 `deploy/quizbuks/README.md` 与 `operations.md`。
- 页脚没有"公司住所"：规格写"公司名、住所、客服邮箱"，但品牌档案没有住所，不能编造（见问题 3）。

## 要总控决定的问题

1. **谁改谁发布**。平台的 `MailLayoutControlTarget` / `MailTextControlTarget` 规定，提出与发布都须持有 `mail.text.write`，再加受控变更自己的 `control.propose` / `control.publish`。QuizBuks 现状：`control.publish` 只在财务管理员；超级管理员有 `control.propose`；内容管理员两者都没有。规格写"授予内容管理员，发布的人须持有发布权限"。建议：内容管理员得 `mail.text.read|write`，**加上 `control.propose`**（否则它只能改普通通知，安全类与信纸提不出）；财务管理员得 `mail.text.read|write`（用来发布）；超级管理员不授予（职责分离）。这样"内容管理员提出、财务管理员发布"。请确认，或指定别的分配。
2. **标志图片**：邮件客户端大多不显示 SVG，品牌档案里只有 `mark.svg`；规格要求标志只许 `{baseUrl}` 下的地址。建议首次上线信纸只有显示名、没有标志（`logo-url` 留空），以后要加标志时放一张 PNG 到品牌档案的 `public` 下。请确认。
3. **页脚的住所**：品牌档案没有住所字段。建议首次上线页脚只写品牌名、客服邮箱、官网，不写住所；若需求方要求法律上的发件人住所，请给出文字（再加品牌档案的字段）。
4. 官网链接的主机（风险一）：官网 `quizbuks.com` 与邮件的 `base-url` 主机相同吗？不同的话放进 `allowed-link-hosts`，还是页脚不放官网链接？

## 总控的决定（2026-11-11 批准）

1. 授权照建议：内容管理员 `mail.text.read|write` + `control.propose`；财务管理员 `mail.text.read|write`；超级管理员不授予。授权改动放在本项（先开的 PR），`QbRolesTest` 写明；测试角色 `QB_E2E_PUBLISHER` 加 `mail.text.write`（只在测试里）。
2. 标志首次上线不放（`logo-url` 留空）。页脚只写品牌名、客服邮箱、官网，不写住所、不编造（住所由总控问需求方；页脚以后可在线改）。
3. 官网主机：`JABIZ_MAIL_BASE_URL` 按部署而定（staging 为 `staging.quizbuks.com`），与官网主机不同。做法：`build.gradle.kts` 从品牌档案的 `siteUrl` 算出只含主机名的 token `QB_BRAND_SITE_HOST`，`application.yml` 把它放进 `jabiz.mail.texts.allowed-link-hosts`；不写死 `quizbuks.com`。`siteUrl` 为空时页脚不放官网链接。`MailLayoutDefaultsTest` 覆盖这两种情况，并把 base-url 设成与官网不同的主机跑一次 `platformCheck`。
4. `operations.md` 的节号：总控定 F-C9 用 7.45、F-C8 用 7.46（F-M5 占 7.44）。F-M5 尚未合入时节号不连续，`ops-sections` 检查会失败，所以本项现用 7.44，F-M5 合入后改为 7.45（F-C8 的下一号），与 F-C8 的一节互相引用；同号先合者占，后合者改号。
5. 顺序：F-C9 先，F-C8 后；F-C8 合并前经 Fable 审（总控安排）。draft PR，CI 全绿后报 PR 号与 40 位 SHA。
