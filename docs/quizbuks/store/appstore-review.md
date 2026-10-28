# App Store 的年龄分级与审核说明（Q49d-2）

由需求方在 App Store Connect 中填写。本页给出每项的答案与理由；表单的措辞以 App Store Connect 显示的为准，意思不同时按理由作答。

## 1. 提交审核的前提（Apple 审核指南）

| 要求 | 条款 | 由谁 | 状态 |
|---|---|---|---|
| App 内删除账号 | 5.1.1(v) | 平台 17i + Q49a | 17i-a 已合入平台，Q49a 未完成 |
| 有第三方登录就要有 Sign in with Apple；第三方登录不在 App 内的网页中进行 | 4.8；Google 的政策 | 平台 17j、17l + Q49c-2 | 未开始 |
| 不只是网站的外壳：原生的推送、Universal Links、系统浏览器登录 | 4.2 | 平台 17k、17l + Q49c-2 | Universal Links 已做（Q49c-1），其余未开始 |
| 有奖活动的正式规则，写明 Apple 不是赞助方 | 5.3.3 | Q49a（规则页）；商店说明末尾已写 | 规则页未完成 |
| 用户生成内容：发布前审核、举报、联系方式 | 1.2 | 发布前审核已有（D-Q49d-7）；举报入口 Q49a | 举报未完成 |

**以上都完成之前只做 TestFlight 的内部测试**，不提交审核（被拒的记录会留在 App 的审核历史中）。

## 2. 年龄分级

App Store Connect →（本 App）→ App 信息 → 年龄分级。所有内容类问题都答**无**：

| 问题 | 答 | 理由 |
|---|---|---|
| 暴力、恐怖、成人内容、粗俗或低俗幽默、医疗信息 | 无 | 问卷由商家撰写，发布前一律审核（`operations.md`"阶段一"） |
| 酒精、烟草或药物的使用或提及 | 无 | 不允许这类问卷（D-Q49d-7） |
| 赌博、模拟赌博 | 无 | 答题不付费，奖励按分数给出，不是抽签或押注 |
| 竞赛（若表单问到） | 有 | 按分数给奖品的问卷（规则见 Q49a 的规则页） |
| 不受限制的网页访问 | 否 | 外壳只停在 `/taker/`，其他地址交给 Safari（Q49c） |
| 用户生成内容 | 是 | 商家撰写的问卷；答题者之间不能交流 |
| 消息与聊天 | 否 | 收件箱只有运营方与商家的通知，不能回复 |
| 广告 | 否 | |

按内容算出的级别多半是 4+ 或 9+。**目标是 18 岁以上**（D-Q49d-1）：在年龄分级页选"覆盖为更高的年龄分级"并选 **18+**，与 Google Play 的目标年龄一致。

## 3. App 审核信息（每个版本）

- **登录信息**：审核专用的答题者账号（需求方在 production 注册，邮箱已验证、资料已填）。**只填在 App Store Connect 中，不进仓库。** 审核期间至少有一份这个账号还没答过的问卷（`play-review.md` §1）。
- **备注**（照下面粘贴；Q49c-2 之后按实际补全）：

```
QuizBuks is for people who answer quizzes published by shops and win a prize at the shop's counter.
Sign in with the account above, answer a quiz from Home, see the result and the prize, and the past quizzes in History.

Native features: Universal Links open the app from our emails; sign-in with Google, Facebook, LINE and Apple runs in
the system browser (ASWebAuthenticationSession); push notifications announce new quizzes and prizes. Only the
takers' pages open inside the app; every other page opens in Safari.

Shops publish quizzes on a separate website. Nothing can be bought in the app. Every quiz is reviewed before it is
published; users can report a quiz from its page. Apple is not a sponsor of the prizes; the official rules are
linked from every quiz. Accounts can be deleted from Account → Delete account.
```

- **联系信息**：需求方的姓名、电话与邮箱（Apple 只用于审核联系）。

## 4. 其他设置

| 项目 | 选 | 理由 |
|---|---|---|
| 价格 | 免费 | |
| 销售范围 | 全部国家或地区，**去掉中国大陆** | 需求方 2026-10-28（D-Q49c-5） |
| 出口合规 | 只用标准加密（HTTPS），已在 Info.plist 中声明 `ITSAppUsesNonExemptEncryption = false`，上传时不再询问 | Q49c-1 |
| 内容版权 | 不含第三方内容的权利问题：问卷由商家提供并按商家条款负责 | 商家条款 |
| 类别 | 主要：购物；次要：可不选 | D-Q49d-3 |
| 主要语言 | 日文；本地化：简体中文、英文 | |
| 截图 | 6.9 英寸 iPhone（1320 × 2868），每种语言 6 张：workflow `QuizBuks store` 的产物 `<语言>/iphone/` | |

## 5. 提交前的核对

- [ ] §1 的前提都已完成并部署到 production
- [ ] App 隐私按 `appstore-privacy.md` 填完
- [ ] 年龄分级按 §2 填完（覆盖为 18+）
- [ ] 三种语言的商店信息照 `appstore-listing.*.md` 粘贴，截图来自最新的 `QuizBuks store`
- [ ] `apple-app-site-association` 在 production 上可以取到（`deploy/quizbuks/DEPLOY.md`），TestFlight 版中邮件链接打开 App
- [ ] 审核账号可以登录，并有可答的问卷
