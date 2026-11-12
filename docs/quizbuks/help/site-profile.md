# 帮助文案：从官网生成资料与“认识我们”的 Quiz（F-C7）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。

- 对象：发起方（FAQ 的 `audience` = `SPONSOR`）
- 分类：`ACCOUNT`
- 页面：发起方页面"发起方中心 → 概况"页头"导入与导出"菜单里的"从官网生成"（只在部署开启这项功能时出现）
- 参数（写进文案的数字是缺省值，改了参数时同步改这里）：`qb.ai.site-profile.daily` = 3（每天次数）、`qb.ai.site-profile.monthly` = 10（每月次数）、`qb.ai.site-profile.max-pages` = 10（最多读几页）
- 核对：`SiteProfileHelpTest` 读本文件，核对上面的数字、菜单与按钮的名字（与 `profile/site/messages.ts` 一致）、三种语言的小节结构相同
- 规则：`docs/quizbuks/first-launch-spec.md` F-C7；计划 `plans/impl/F-C7.md`；与 `profile-package.md`（资料包本身）互相指向
- 结构：每种语言八个小节，顺序相同（它做什么、开始、读什么和发什么、一定要校对、链接名与图片、“认识我们”的 Quiz、读不了时、次数与费用）

## 帮助页

### 中文 · 从官网生成资料与“认识我们”的 Quiz

**它做什么**：AI 读你自己的官网，替你起草公司资料、公开主页、商品，还可以起草一份“认识我们”的 Quiz（官网上的文章和几道题）。它只起草：你校对并确认之前，什么都不会保存；你的公开主页照常自己提交审核，Quiz 是草稿，发布时照常审核。

**开始**：在“发起方中心 → 概况”页头点“导入与导出”，再点“从官网生成”。填官网地址（你自己的网站，也可以是其下的某个页面）、网站的语言、要另外翻译成的语言和要起草的内容，勾选“这是我的网站，或我有权为此使用的网站。”（必须勾选），第一次使用还要同意 AI 说明，然后点“读取我的官网”。读取要一到三分钟，你可以关闭窗口或离开页面，结果会保留到过期；想停下就点“取消”。

**读什么和发什么**：AI 只读你填的那个网站的页面，最多 10 页。发给 AI 服务商的只有官网地址和语言选项，绝不发送你的账户信息或学者的任何信息。联系人姓名不从官网取，用你账户里的名字；官网上的个人邮箱、电话和社交账号会被去掉，“联系方式”只写公司公开的地址。

**一定要校对**：草稿做好后点“校对草稿”。确认页会在每个字段旁写出取自官网的哪一页，AI 翻译的文字标有“机器翻译”，并列出“官网上没找到”的项和“被去掉或改动的内容及原因”。AI 可能读错或漏掉信息，请把公司名、简介、商品的价格与链接与官网逐项对照。下面这些情况**不会**被自动处理，请自己看：
- 不以 0 或 + 开头的境外电话号码（例如 `(415) 555-2671`）不会被去掉；
- “LINE ID xxx”“微信号：xxx”这类写法不会被去掉；
- 订单号、品番、坐标之类的长数字可能被误当作电话而去掉，但每一处改动都会列在“被去掉或改动的内容”里；
- 乐天、Amazon、BASE 等站外的店铺链接会被去掉（不在你官网自己的地址下），请自己填写。

**链接名与图片**：主页的链接名由官网域名得出；被其他发起方用了时，确认页给出几个可选的，选好才能导入。图片只给链接，你点导入时服务器才把它们逐张下载（同资料包的“图片”一节）。

**“认识我们”的 Quiz**：确认页下方点“建立 Quiz 草稿”，官网上的文章成为 Quiz 的资料，AI 起草的题目留在 Quiz 里等你审核：每一道题要你逐题勾选“我已校对此题”才会加入。一份草稿只能建一个 Quiz。

**读不了时**：打不开、网站不允许这类读取（robots.txt）、几乎没有文字（页面靠脚本绘制）、用时太长或没有找到可用的内容时，页面会写明原因。可以改用“给 AI 工具的提示词”，或者手工填写。

**次数与费用**：每个发起方一次只能有一个在读取；每天最多 3 次、每月最多 10 次（失败的也算），并计入你的 AI 额度。

### 日本語 · 公式サイトからの下書きと「私たちを知る」クイズ

**できること**：AI があなた自身の公式サイトを読み、会社情報・公開ページ・商品の下書きと、「私たちを知る」クイズ（サイトの記事といくつかの問題）の下書きを作ります。下書きだけで、確認して承認するまで何も保存されません。公開ページは通常どおりご自身で審査に提出し、クイズは下書きで、公開時に通常どおり審査されます。

**始め方**：「主催者センター → 概要」ページ上部の「インポートとエクスポート」を開き、「公式サイトから下書き」を押します。公式サイトのアドレス（ご自身のサイト。その下のページでも可）、サイトの言語、追加で翻訳する言語、下書きする内容を選び、「これは自分のサイト、またはこの目的で使用する権利のあるサイトです。」にチェックします（必須）。初めてのときは AI 説明への同意も必要です。そして「公式サイトを読む」を押します。読み取りには 1〜3 分かかります。ウィンドウを閉じても、ページを離れても結果は保管されます。止めたいときは「キャンセル」を押します。

**読むものと送るもの**：AI は、入力したサイトのページだけを最大 10 ページ読みます。AI 提供元に送るのは、サイトのアドレスと言語の選択だけです。アカウント情報やテイカーに関する情報は送りません。担当者名はサイトからは取らず、アカウントの名前を使います。サイトにある個人のメールアドレス・電話番号・SNS アカウントは取り除き、「連絡先」には会社の公開アドレスだけを書きます。

**必ず確認してください**：下書きができたら「下書きを確認する」を押します。確認画面には、各項目の横に取得元のページが表示され、AI が翻訳した文には「機械翻訳」と付き、「サイトに見つからなかった項目」と「除外または変更した内容と理由」も表示されます。AI は読み違いや抜けをすることがあるので、会社名・紹介文・商品の価格とリンクを公式サイトと一つずつ見比べてください。次のものは自動では処理されないので、ご自身で確認してください。
- 0 や + で始まらない海外の電話番号（例：`(415) 555-2671`）は取り除かれません。
- 「LINE ID xxx」「微信号：xxx」のような書き方は取り除かれません。
- 注文番号・品番・座標のような長い数字は、電話番号と間違えて取り除かれることがあります。ただし変更はすべて「除外または変更した内容」に表示されます。
- 楽天・Amazon・BASE など、公式サイト自身のアドレスにない店舗リンクは除外されます。ご自身で入力してください。

**リンク名と画像**：ページのリンク名はサイトのドメインから作られます。他の主催者が使っているときは確認画面に代わりの候補が出るので、選ぶまで取り込めません。画像はリンクだけで、取り込みを押したときにサーバーが一枚ずつダウンロードします（資料パッケージの「画像」と同じです）。

**「私たちを知る」クイズ**：確認画面の下の「クイズの下書きを作る」を押すと、サイトの記事がクイズの教材になり、AI が下書きした問題はクイズの中で確認待ちになります。1 問ずつ「この問題を確認しました」にチェックしたものだけが追加されます。1 つの下書きから作れるクイズは 1 つです。

**読み取れないとき**：開けない、サイトがこの種の読み取りを許可していない（robots.txt）、文章がほとんどない（スクリプトで描画している）、時間がかかりすぎた、使える内容がなかった場合は、理由が表示されます。「AI ツール用のプロンプト」を使うか、手入力に切り替えてください。

**回数と費用**：主催者ごとに同時に読み取れるのは 1 件、1 日最大 3 回、1 か月最大 10 回です（失敗した分も数えます）。AI の利用枠にも数えられます。

### English · Drafting from your website, and the "get to know us" quiz

**What it does**: an AI reads your own website and drafts your company details, public page and products for you, and can also draft a "get to know us" quiz (articles from the website and a few questions). It only drafts: nothing is saved until you check it and confirm; your public page is still sent for review by you as always, and the quiz is a draft that is reviewed when you publish it.

**How to start**: in the head of "Sponsor hub → Overview" open "Import and export", then "Draft from your website". Enter your website's address (your own site; a page below it is fine), its language, the languages to translate into and what to draft, tick "This is my website, or one I may use for this." (required), accept the AI notice the first time, and press "Read my website". Reading takes one to three minutes; you can close the window or leave the page and the result is kept for you; press "Cancel" to stop it.

**What is read and what is sent**: the AI reads the pages of that website only, up to 10 pages. What goes to the AI provider is the website's address and the language choices; never your account details or anything about Takers. The contact name is not taken from the website: your account's name is used. A person's e-mail address, phone number and social account on the website are removed, and "contact" is only the company's public address.

**Always check it**: when the draft is ready press "Review the draft". The confirmation screen shows next to each field the page of the website it was taken from, marks the texts the AI translated as "machine-translated", and lists what the website did not show and what was left out or changed, with the reason. An AI can misread or miss things, so compare the company name, the introduction and each product's price and link with your website. These are **not** handled automatically; look at them yourself:
- phone numbers from abroad that do not start with 0 or + (for example `(415) 555-2671`) are not removed;
- writings like "LINE ID xxx" or a WeChat ID are not removed;
- long numbers such as order numbers, part numbers or coordinates can be taken for a phone number and removed, but every change is listed under what was left out or changed;
- links to shops that are not on your website's own address (Rakuten, Amazon, BASE and the like) are left out; enter them yourself.

**Link name and images**: your page's link name is made from your website's domain; when another Sponsor has it, the confirmation screen offers free ones to choose from, and you cannot import before you choose. Images are only links: the server downloads each one when you press import (the same as the "Images" part of the profile package).

**The "get to know us" quiz**: press "Make the quiz draft" below the confirmation screen's list: the website's articles become the quiz's materials, and the questions the AI drafted wait in the quiz for your review: only the questions you tick "I have reviewed this question" one by one are added. One quiz can be made from a draft.

**When it cannot be read**: if the website did not open, does not allow this kind of reading (robots.txt), shows almost no text (it draws its pages with scripts), took too long or gave nothing usable, the page says why. Use the prompt for an AI tool instead, or fill it in by hand.

**Times and cost**: a Sponsor can have one reading at a time, at most 3 a day and 10 a month (those that failed count too), and each counts toward your AI allowance.
