# QuizBuks — 法律文本的草稿

这里是隐私政策的正式草稿（日文 `privacy-policy.ja.md` 为准据文本，中文、英文供参考），按代码中实际收集与使用的数据写成（2026-10-27 盘点；1.3 另加店员 Q39 与"关于我"Q47，2026-10-28；运营商与 AI 获客 Q48，2026-10-29：第2条第4项、第3条第8–9项、第5条两行、第8条一行；App 内扫码 Q52，2026-10-30：第2条第3项末尾一句；须由需求方或律师确认）。
适用于答题 App、商家后台与招商网站；QuizBuks for Shopify 有自己的隐私政策页：`/shopify-app/privacy/`，正文在 `quizbuks-web/shopify-site/src/content/`（S4a，`shopify/S4-launch.md` §1），随应用构建，不经 `QB_LEGAL_PUBLISH`；两份政策描述的数据有改动时各自同步。
它们不进入代码：`QB_SETUP` 只建测试占位的草稿（`LegalDrafts`），正式文本由内容管理员在管理后台粘贴、发布（`operations.md`，`QB_LEGAL_PUBLISH`）。

## 发布前

1. 填写【】中的占位：运营者名称、住所、代表者、邮件发送事业者（`.env` 的 `SPRING_MAIL_HOST` 所属，用 Gmail 时为 Google LLC）、服务器事业者与所在国、运营商的名称与所在国（Q48：签约后填写；没有对外运营商时删去该行）、制定日。
2. 管理后台"法律文本（草稿）"中打开 `PRIVACY`：标题填 `プライバシーポリシー` / `隐私政策` / `Privacy policy`，正文按语言粘贴对应文件（不含本页）；
   在 `/qb/legal` 发布（需要二次验证）。
3. 发布后的地址：`https://<域名>/taker/legal/privacy`。Meta（Facebook 登录）与 LINE 控制台的"隐私政策 URL"填它；Meta 的"用户数据删除"选"数据删除说明 URL"，同样填它（第10条写明了删除的请求方式）。

4. 第10条的"删除账号"写明了 App 与商户后台的入口：发布含这一节的版本之前，先在生产开启 `QB_APP_ACCOUNT_DELETION` 与 `QB_SPONSOR_ACCOUNT_DELETION`（`deploy/quizbuks/DEPLOY.md` §1a，需要维护连接）。

## 代码改动时

政策描述的是当前的实现。以下改动要同时修改政策并发布新版本（`requires re-consent` 视变更是否重要而定）：

- 收集新的个人信息（例如资料增加生年、性别：1.3 的 Q47，已写入）
- 增加外部服务（支付、邮件、登录方式、分析、嵌入）或改变送出的内容
- 商家能看到的答题人信息、分析的最小人数（`qb.stats.min-group`）
- 保存期间（`RetentionPolicy`）、本人能操作的功能（例如将来的导出；删除账号见第10条，Q72 / Q71，2026-11-01）

政策改了，商店的数据申报（Google Play 数据安全表 `docs/quizbuks/store/play-data-safety.md`，以后的 Apple 隐私标签）一起核对并在 Console 中更新（Q49d）。
