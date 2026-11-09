# 帮助文案：用其他 AI 从官网生成发起方资料包（F-C5）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。

- 对象：发起方（FAQ 的 `audience` = `SPONSOR`）
- 分类：`ACCOUNT`
- 页面：发起方页面"概况"的"发起方资料包"卡片
- 模板：`docs/quizbuks/profile-package-prompts.md`（规则与示例由测试 `ProfilePackagePromptsTest` 与服务器的检查核对）
- 规则：`docs/quizbuks/first-launch-spec.md` F-C5

## 帮助页

### 中文 · 想用 AI 根据官网生成发起方资料包

**怎么做**：向客服索取"资料包提示词模板"，把里面的提示词交给能读网页的 AI 工具（ChatGPT、Gemini、Claude 等），只改最前面的参数（官网地址、联系人姓名等）。AI 会回答一个 JSON，保存成 `.quizbuks-profile.json` 文件，在"概况 → 发起方资料包 → 导入"中选择它。

**一定要校对**：导入前的确认页会列出将新增、修改与删除的内容。AI 可能读错或漏掉信息，请把公司名、简介、商品的价格与链接与官网逐项对照。导入只写草稿，主页照常提交审核。

**导入被拒绝**：确认页会逐条列出问题。把这些问题原样复制，连同模板里的"修正提示词"一起交回 AI，让它只改报错的地方。

**图片**：AI 只能给出图片的链接，目前不会自动下载；确认页会提示"请下载后自己上传"，其余内容照常导入。

### 日本語 · AI で公式サイトから主催者資料パッケージを作る

**手順**：サポートに「主催者資料パッケージ用プロンプトのテンプレート」を依頼し、その中のプロンプトを、ウェブページを読める AI ツール（ChatGPT、Gemini、Claude など）に渡します。変更するのは冒頭のパラメーター（公式サイトの URL、担当者名など）だけです。AI が返した JSON を `.quizbuks-profile.json` として保存し、「概要 → 主催者資料パッケージ → 取り込む」で選びます。

**必ず確認してください**：取り込み前の確認画面に、追加・変更・削除される内容が表示されます。AI は読み違いや抜けをすることがあるため、会社名・紹介文・商品の価格とリンクを公式サイトと一つずつ照らし合わせてください。取り込まれるのは下書きだけで、ページは通常どおり審査に提出します。

**取り込みが拒否されたとき**：確認画面に問題が一つずつ表示されます。それをそのままコピーし、テンプレートの「修正用プロンプト」と一緒に AI に渡して、エラーの箇所だけ直してもらいます。

**画像**：AI が出せるのは画像のリンクだけで、現在は自動ではダウンロードされません。確認画面に「ダウンロードしてご自身でアップロードしてください」と表示され、ほかの内容は通常どおり取り込まれます。

### English · Create a profile package from your website with an AI tool

**How**: Ask support for the "profile package prompt template", and give its prompt to an AI tool that can read web pages (ChatGPT, Gemini, Claude and others), changing only the parameters at the top (your website address, the contact name and so on). The AI answers with one JSON; save it as a `.quizbuks-profile.json` file and choose it in "Overview → Profile package → Import".

**Always check it**: the confirmation screen before the import lists what will be added, changed and removed. An AI can misread or miss things, so compare the company name, the introduction and each product's price and link with your website. The import writes drafts only, and your page is submitted for review as usual.

**If the import is refused**: the confirmation screen lists each problem. Copy them as they are and give them back to the AI together with the template's "fixing prompt", so it changes only what was reported.

**Images**: an AI can only give links to images, and they are not downloaded automatically for now; the confirmation screen says "download it and upload it yourself", and everything else is imported as usual.
