# Google Play 的审核说明（Q49d-1）

## 1. 应用访问权限（Play Console → 应用内容 → 应用访问权限）

选"部分或全部功能受限"，添加一组说明：

- **名称**：答题者账号
- **用户名 / 密码**：需求方在 production 注册的审核专用答题者账号（邮箱已验证、资料已填）。**只填在 Console 中，不进仓库，不发给开发者。**
- **其他信息**（照下面粘贴，按需改语言）：

```
QuizBuks is the takers' app: sign in with the account above, answer a quiz from the Home tab and see the result,
the prize won and the past quizzes in History. At least one quiz is always live for this account.
Sponsors publish quizzes on a separate website; nothing can be bought in the app.
```

需求方要保证审核期间至少有一份能答的公开问卷（`launch-checklist.md`"上线的内容"中的长期欢迎问卷），并且这个账号**还没有答过**它；
审核员可能答很多次，账号答完后在 Console 中换一个新账号，或者准备一份每人可答多次的问卷（如果发布规则允许）。

## 2. 有奖活动（Google 的"真钱赌博、游戏和竞赛"政策）

- 本 App 的奖励（推广期只有门店领取的奖品）按答题分数给出，不付费参加，不是抽奖。政策仍要求：**写明 Google 不是主办方**，并有**正式规则**。
- Google 不是主办方：已写入三种语言的商店说明末尾（`play-listing.*.md`）。
- 正式规则：Q49a 的规则页（三语，由另一个 session 提供）。合入后在 App 的问卷页与帮助中可达，我核对后在本页记下地址；在那之前，封闭测试照样可以提交。

## 3. 用户生成内容（UGC 政策）

问卷是商家（企业用户）写的，发布前一律经过审核（推广期同样，`operations.md`"阶段一"）；答题者不能发布任何内容。Google 的 UGC 政策要求有举报入口：Q49a 在发布页加"举报"。合入后核对它在 App 中可达（`Q49-mobile-apps.md` §1）。

## 4. 发布前的核对

- [ ] 隐私政策已填好【】占位并在后台发布，`https://quizbuks.com/taker/legal/privacy` 可以打开（`legal/README.md`）
- [ ] 数据安全表按 `play-data-safety.md` 填完
- [ ] 内容分级与目标受众按 `play-content-rating.md` 填完
- [ ] 商店信息按 `play-listing.*.md` 填好三种语言，截图与宣传图来自 workflow `QuizBuks store` 的最新产物
- [ ] 审核账号可以登录，并有可答的问卷
- [ ] `assetlinks.json` 在 production 上可以取到（`deploy/quizbuks/DEPLOY.md`"Android App 的 assetlinks"），否则 App 顶部显示地址栏，可能被认为是网页外壳
