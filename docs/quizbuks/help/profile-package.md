# 帮助文案：发起方资料包（F-C5、F-C11b）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。

- 对象：发起方（FAQ 的 `audience` = `SPONSOR`）
- 分类：`ACCOUNT`
- 页面：发起方页面"发起方中心 → 概况"页头的"导入与导出"菜单；商品在"资源库"的"商品"标签
- 参数（写进文案的数字是缺省值，改了参数时同步改这里）：`qb.products.max` = 50（商品个数上限）
- 模板：`docs/quizbuks/profile-package-prompts.md`（规则与示例由测试 `ProfilePackagePromptsTest` 与服务器的检查核对；页面上的提示词由 `aiPrompt.test.ts` 核对与它相同）
- 核对：`ProfilePackageHelpTest` 读本文件，核对上面的数字、页面与按钮的名字（与 `messages.ts` 一致）、三种语言的小节结构相同、文件格式的写法
- 规则：`docs/quizbuks/first-launch-spec.md` F-C5、F-C6、F-C11；设计 `plans/Q100-profile-package.md`
- 结构：每种语言九个小节，顺序相同（资料包是什么、导出、导入、商品、图片、邮件服务器、用 AI 根据官网起草、一定要校对、导入被拒绝）。原来的"想用 AI 根据官网生成发起方资料包"并入后三节，内容不丢；没有别处链接到旧小标题。

## 帮助页

### 中文 · 发起方资料包：导出、导入与用 AI 起草

**资料包是什么**：一个 `.quizbuks-profile.json` 文件，把你的基本信息、公开主页、商品和邮件服务器的设置存在一起（图片也在里面，邮件服务器的密码从不在里面）。可以用来备份、在测试环境和正式环境之间搬、交给运营商协助你，或者让 AI 工具根据你的官网先起草一份。

**导出**：在"发起方中心 → 概况"页头点"导入与导出"，在"发起方资料包"下点"导出"。保存下来的文件可以原样再导入。

**导入**：在同一个菜单里点"导入"，选择文件。确认页会逐项列出将新增、修改与删除的内容，你确认后才会写入；全部成功或全部不写。导入只写草稿，不会改变任何审核：公开主页照常自己提交审核，入驻申请还没通过时，基本信息只填进申请表，由你自己提交。

**商品**：商品在"资源库"的"商品"标签里。导入时，文件里的商品会**整体替换**现有的商品：有商品编号的按编号对上原来的商品，没有编号的按导出时的标识对上；对不上的新增，现有而文件里没有的删除。确认页会先列出将被删除的商品，被删除的商品如果关联着 Quiz，还会列出这些 Quiz，请你勾选确认。一个发起方最多 50 个商品，超过时整个文件都不能导入。运营商下架的商品照旧保持隐藏。

**图片**：导出的文件把图片直接放在里面。AI 工具写不出真实的图片，只能给出图片的链接：导入时服务器会把链接里的图片逐张下载一次（只收网上公开的 https 图片），之后与你自己上传的图片完全一样。下载不了的（对方网站拒绝、不是图片等），确认页会写明原因，你可以重试、改为自己上传图片，或让它保持现状，其余内容照常导入。

**邮件服务器**：资料包带着邮件服务器的设置，但不带密码；导入后到"邮件服务器"页补上密码，说明见那一页。它不由 AI 起草。

**用 AI 根据官网起草**：点"给 AI 工具的提示词"，填好官网地址和联系人姓名，点"复制提示词"，交给能读网页的 AI 工具（ChatGPT、Gemini、Claude 等）。AI 会回答一个 JSON，保存成 `.quizbuks-profile.json` 文件，再按上面的"导入"选择它。不要让 AI 在文件里放图片数据，只要链接。

**一定要校对**：导入前的确认页会列出将新增、修改与删除的内容。AI 可能读错或漏掉信息，请把公司名、简介、商品的价格与链接与官网逐项对照，确认无误再导入。

**导入被拒绝**：确认页会逐条列出问题（带位置，例如第几个商品）。点"复制问题和修正提示词"，连同文件一起交回 AI，让它只改报错的地方。

### 日本語 · 主催者資料パッケージ：書き出し、取り込み、AI での下書き

**資料パッケージとは**：`.quizbuks-profile.json` というファイルに、基本情報、公開ページ、商品、メールサーバーの設定をまとめたものです（画像も入りますが、メールサーバーのパスワードは入りません）。バックアップ、テスト環境と本番環境の間の移行、運営パートナーに手伝ってもらうとき、AI ツールに公式サイトから下書きを作らせるときに使います。

**書き出し**：「主催者センター → 概要」ページ上部の「インポートとエクスポート」を開き、「主催者資料パッケージ」の「書き出す」を押します。保存したファイルはそのまま取り込み直せます。

**取り込み**：同じメニューの「取り込む」を押してファイルを選びます。確認画面に追加・変更・削除される内容が一つずつ表示され、確認してから書き込まれます。すべて成功するか、何も書き込まれないかのどちらかです。取り込まれるのは下書きだけで、審査の状態は変わりません。公開ページは通常どおりご自身で審査に提出し、参加申請がまだ承認されていないときは、基本情報は申請フォームに入るだけで、ご自身で提出します。

**商品**：商品は「リソースライブラリ」の「商品」タブにあります。取り込みでは、ファイルの商品が現在の商品と**丸ごと入れ替わります**。商品コードがあるものはコードで、ないものは書き出し時の識別子で元の商品に対応させ、対応しないものは追加、現在あってファイルにないものは削除されます。確認画面には削除される商品が先に表示され、Quiz に関連づけられているものは Quiz も表示されるので、チェックして確認してください。商品は主催者ごとに最大 50 件で、超えるとファイル全体が取り込めません。運営パートナーが非表示にした商品は、そのまま非表示です。

**画像**：書き出したファイルには画像がそのまま入っています。AI ツールは本物の画像を作れず、画像のリンクだけを出せます。取り込むとき、サーバーがリンク先の画像を一枚ずつ一度だけダウンロードします（インターネット上の公開された https の画像のみ）。ダウンロード後は、ご自身でアップロードした画像と同じです。ダウンロードできなかったもの（相手のサイトが拒否した、画像ではない、など）は確認画面に理由が表示され、やり直す、ご自身の画像をアップロードする、そのままにする、のいずれかを選べます。ほかの内容は通常どおり取り込まれます。

**メールサーバー**：資料パッケージにはメールサーバーの設定が入りますが、パスワードは入りません。取り込み後に「メールサーバー」ページでパスワードを入れてください。説明はそのページにあります。AI による下書きの対象ではありません。

**AI で公式サイトから下書きを作る**：「AI ツール用のプロンプト」を開き、公式サイトの URL と担当者名を入れて「プロンプトをコピー」し、ウェブページを読める AI ツール（ChatGPT、Gemini、Claude など）に渡します。AI が返した JSON を `.quizbuks-profile.json` として保存し、上の「取り込む」で選びます。AI に画像データを入れさせず、リンクだけにしてください。

**必ず確認してください**：取り込み前の確認画面に、追加・変更・削除される内容が表示されます。AI は読み違いや抜けをすることがあるため、会社名・紹介文・商品の価格とリンクを公式サイトと一つずつ照らし合わせ、間違いがなければ取り込んでください。

**取り込みが拒否されたとき**：確認画面に問題が一つずつ（何番目の商品かなどの位置つきで）表示されます。「問題と修正用プロンプトをコピー」を押し、ファイルと一緒に AI に渡して、エラーの箇所だけ直してもらいます。

### English · Profile package: export, import and drafting with an AI tool

**What a profile package is**: one `.quizbuks-profile.json` file that keeps your basics, public page, products and mail server settings together (with the images, and never the mail server's password). Use it to back up, to move between a test and the live environment, to let your operator help you, or to have an AI tool draft one from your website first.

**Export**: in the head of "Sponsor hub → Overview" open "Import and export", and under "Profile package" press "Export". The saved file can be imported again as it is.

**Import**: in the same menu press "Import" and choose the file. The confirmation screen lists each thing that will be added, changed and removed, and nothing is written until you confirm; everything is written or nothing is. An import writes drafts only and never changes a review: your public page is submitted for review as usual, and while your application is not approved the basics only fill in the application, which you send yourself.

**Products**: products are in the "Products" tab of the asset library. An import **replaces** your products with those in the file: one with a product code is matched by the code, one without by the identifier it had when exported; those that match nothing are added, and those you have that the file lacks are deleted. The confirmation screen lists the products to be deleted first, and for those linked to a Quiz it names the Quiz, so you tick to confirm. A Sponsor can have at most 50 products, and a file with more is refused as a whole. Products your operator took down stay hidden.

**Images**: an exported file holds its images inside. An AI tool cannot write a real image and can only give a link to one: when you import, the server downloads each linked image once (public https images only), and after that it is the same as an image you uploaded yourself. One it cannot download (the other site refuses, it is not an image and so on) is shown on the confirmation screen with the reason, and you can try again, upload an image of your own, or leave it as it is. Everything else is imported as usual.

**Mail server**: a package carries the mail server's settings but never its password; after an import, add the password on the "Mail server" page, where it is explained. An AI tool does not draft it.

**Drafting with an AI tool from your website**: press "Prompt for an AI tool", enter your website address and the contact name, press "Copy the prompt" and give it to an AI tool that can read web pages (ChatGPT, Gemini, Claude and others). The AI answers with one JSON; save it as a `.quizbuks-profile.json` file and choose it with "Import" as above. Do not let the AI put image data in the file, only links.

**Always check it**: the confirmation screen before the import lists what will be added, changed and removed. An AI can misread or miss things, so compare the company name, the introduction and each product's price and link with your website, and import only when they are right.

**If the import is refused**: the confirmation screen lists each problem with its place, such as which product. Press "Copy the problems with the fixing prompt" and give it back to the AI together with the file, so it changes only what was reported.
