# 帮助文案：用其他 AI 从官网生成发起方资料包（F-C5）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。

- 对象：发起方（FAQ 的 `audience` = `SPONSOR`）
- 分类：`ACCOUNT`
- 页面：发起方页面"发起方中心 → 概况"页头的"导入与导出"菜单
- 模板：`docs/quizbuks/profile-package-prompts.md`（规则与示例由测试 `ProfilePackagePromptsTest` 与服务器的检查核对；页面上的提示词由 `aiPrompt.test.ts` 核对与它相同）
- 规则：`docs/quizbuks/first-launch-spec.md` F-C5

## 帮助页

### 中文 · 想用 AI 根据官网生成发起方资料包

**怎么做**：在"概况"页头点"导入与导出"，选"发起方资料包"下的"给 AI 工具的提示词"，填好官网地址和联系人姓名，点"复制提示词"，交给能读网页的 AI 工具（ChatGPT、Gemini、Claude 等）。AI 会回答一个 JSON，保存成 `.quizbuks-profile.json` 文件，在"导入与导出 → 发起方资料包 → 导入"中选择它。

**一定要校对**：导入前的确认页会列出将新增、修改与删除的内容。AI 可能读错或漏掉信息，请把公司名、简介、商品的价格与链接与官网逐项对照。导入只写草稿，主页照常提交审核。

**导入被拒绝**：确认页会逐条列出问题。点"复制问题和修正提示词"，连同文件一起交回 AI，让它只改报错的地方。

**图片**：AI 只能给出图片的链接。导入时服务器会把链接里的图片逐张下载一次；下载不了的（对方网站拒绝、不是图片等），确认页会写明原因，你可以重试、改为自己上传图片，或让它保持现状，其余内容照常导入。

### 日本語 · AI で公式サイトから主催者資料パッケージを作る

**手順**：「概要」ページ上部の「インポートとエクスポート」から「主催者資料パッケージ」の「AI ツール用のプロンプト」を開き、公式サイトの URL と担当者名を入れて「プロンプトをコピー」し、ウェブページを読める AI ツール（ChatGPT、Gemini、Claude など）に渡します。AI が返した JSON を `.quizbuks-profile.json` として保存し、「インポートとエクスポート → 主催者資料パッケージ → 取り込む」で選びます。

**必ず確認してください**：取り込み前の確認画面に、追加・変更・削除される内容が表示されます。AI は読み違いや抜けをすることがあるため、会社名・紹介文・商品の価格とリンクを公式サイトと一つずつ照らし合わせてください。取り込まれるのは下書きだけで、ページは通常どおり審査に提出します。

**取り込みが拒否されたとき**：確認画面に問題が一つずつ表示されます。「問題と修正用プロンプトをコピー」を押し、ファイルと一緒に AI に渡して、エラーの箇所だけ直してもらいます。

**画像**：AI が出せるのは画像のリンクだけです。取り込むときにサーバーが一枚ずつダウンロードします。ダウンロードできなかったもの（相手のサイトが拒否した、画像ではない、など）は確認画面に理由が表示され、やり直す、ご自身の画像をアップロードする、そのままにする、のいずれかを選べます。ほかの内容は通常どおり取り込まれます。

### English · Create a profile package from your website with an AI tool

**How**: In the head of "Overview" open "Import and export", and under "Profile package" choose "Prompt for an AI tool", enter your website address and the contact name, press "Copy the prompt" and give it to an AI tool that can read web pages (ChatGPT, Gemini, Claude and others). The AI answers with one JSON; save it as a `.quizbuks-profile.json` file and choose it in "Import and export → Profile package → Import".

**Always check it**: the confirmation screen before the import lists what will be added, changed and removed. An AI can misread or miss things, so compare the company name, the introduction and each product's price and link with your website. The import writes drafts only, and your page is submitted for review as usual.

**If the import is refused**: the confirmation screen lists each problem. Press "Copy the problems with the fixing prompt" and give it back to the AI together with the file, so it changes only what was reported.

**Images**: an AI can only give links to images. When you import, the server downloads each linked image once. One it cannot download (the other site refuses, it is not an image and so on) is shown on the confirmation screen with the reason, and you can try again, upload an image of your own, or leave it as it is. Everything else is imported as usual.
