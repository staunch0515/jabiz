# F-C5 给其他 AI 的资料包提示词模板：实施计划（S4）

规格：`docs/quizbuks/first-launch-spec.md` F-C5（来源 `plans/Q104-site-to-profile.md` §3.1、§7）。依赖：无。总控 2026-11-09 指派并批准计划。

## 做什么

1. `docs/quizbuks/profile-package-prompts.md`：主提示词（参数：官网地址、联系人姓名、基准语言、其他语言、要不要商品、商品个数上限）、格式规则、两个完整示例（只有基准语言；三语带商品）、文件被拒时的修正提示词、规则一览。
2. `docs/quizbuks/quiz-file-prompts.md` 新增第 7 节「从企业官网生成『认识我们』的 Quiz」（Q104 §4 的规则）。
3. 帮助文案底稿 `docs/quizbuks/help/profile-package.md`（三语，由内容管理员发布为 FAQ；说明模板的用法、必须校对、被拒时把确认页的问题原样复制给 AI）。
4. 测试 `ProfilePackagePromptsTest`。

## 决定

- **联系人姓名**：`ProfilePackage` 要求 `basics.contactName` 必填，而 Q104 P5 规定不从官网取个人信息。模板让使用者在参数里自填，AI 只照抄（总控同意）。
- **商品上限**写「按当前设置（缺省 50，`qb.products.max`）」，没有新参数。
- **图片**只写 `{"url": …}`；F-C6 合入前确认页把链接图片列为「请下载后自己上传」，模板与帮助都如实写出。
- **前端不改**：确认页的问题本来就是页面上的文字列表，可选取复制；帮助页在应用内的入口随 F-C11b / F-C7（从官网生成）一并考虑，本项只给 FAQ 底稿。如需在「发起方资料包」卡片上加链接或「复制问题」按钮，改 `quizbuks-web/sponsor/src/profile/ProfileTransfer.tsx`，须先经总控同意。
- 文档与页面不出现「后台」（`glossary.md`）；发起方一侧用「发起方页面」。

## 测试

- `ProfilePackagePromptsTest.everyExampleIsReadAsAPackage`：两个示例经 `ProfilePackage.read` 无问题；无 `files`、无邮件服务器、图片都是链接；地址名、网址、币种与小数位、商品编号、各长度都在代码的限制内。
- `theNumbersTheDocumentStatesAreTheCodes`：文档中写明的长度、模式、币种、商品上限缺省值、`schema` 与代码常数一致；文档不出现「后台」。
- `tools/quizbuks/terms/terms.test.mjs`（用词）通过。

## 风险

- 文档里的数字写死在正文中，靠上面的测试与代码核对，不另设常量。
- AI 生成的内容仍可能有误：模板与帮助都要求逐项校对，导入只写草稿，主页照常审核。
