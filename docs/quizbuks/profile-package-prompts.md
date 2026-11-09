# 用其他 AI 工具从企业官网生成资料包：提示词模版

发起方页面「概况 → 发起方资料包 → 导入」读的是 `.quizbuks-profile.json` 文件（格式见 `plans/Q100-profile-package.md`，设计见 `plans/Q104-site-to-profile.md` §3.1）。
本文给出可以直接交给 ChatGPT、Gemini、Claude 等能读网页的工具的提示词，从一个企业官网生成能被导入的资料包。规则取自服务器的检查
（`page/ProfilePackage.java`、`page/QbSponsorPages.java`、`page/QbSponsorProducts.java`、`identity/AccountLimits.java`）；
本文中的两个 JSON 示例由测试 `ProfilePackagePromptsTest` 按服务器的同一套检查读取，文中的长度与格式也由它与代码核对，规则变了测试即失败，请同时改本文。

## 1. 用法

1. 在发起方页面「概况 → 发起方资料包」点「给 AI 工具的提示词」，填好官网地址、联系人姓名等，一键复制（页面上的提示词就是第 2 节的，参数已替你填好；`aiPrompt.test.ts` 核对两者相同）。也可以手动复制第 2 节的提示词，只改最前面【参数】中的值。
2. 交给 AI 工具。它必须能打开网页；只会凭记忆回答的工具会编造内容，请换一个能读网页的。它只应回答一个 JSON 代码块，之后另有一张"官网上没找到的项目"清单。
3. 把代码块的内容保存为 `<名字>.quizbuks-profile.json`（UTF-8），在发起方页面「概况 → 发起方资料包 → 导入」中选择它。
4. 导入的确认页会列出新增、修改与删除的内容。**逐项校对**：价格、商品说明、公司名都要与官网对照；确认无误再导入。导入只写草稿，主页照常提交审核。
5. 导入时报错：确认页的问题列表下有「复制问题」与「复制问题和修正提示词」两个按钮；把复制的内容连同文件交回 AI（逐行，含 `products[3].price: …` 这样的位置，修正提示词见第 4 节）。

**不要让 AI 在文件里放图片数据。** 语言模型写不出真实的图片，只会编一段假的 Base64，导入会失败。图片一律写成链接 `{"url": "https://…"}`
（链接图片目前在确认页列为"请下载后自己上传"，导入时不会写入，其余内容照常导入），或不写，导入后在发起方页面上传。

## 2. 主提示词

````text
你是 QuizBuks 的资料整理助手。请阅读下面【参数】中的企业官网，按【格式规则】生成一个 QuizBuks 发起方资料包（JSON）。

【参数】（使用者只改这里）
- 官网地址：<例：https://www.aini-helmet.com/>
- 联系人姓名（由使用者填写；你只照抄，不要从网站上找）：<例：山田 花子>
- 基准语言：zh（ja / zh / en 之一；缺省取网站的语言，先用它写）
- 其他语言：ja, en（0–2 种，从 ja / zh / en 中选，不含基准语言；不要就写"无"，译文由你翻译）
- 要不要商品：要（要 / 不要）
- 商品个数上限：50（按当前设置，缺省 50；超过时只取网站上靠前的）

【格式规则】（必须全部遵守）
1. 只输出一个 ```json 代码块，内容是合法 JSON（双引号、无注释、无末尾逗号、无省略号）。
   代码块之后再给一张"官网上没找到"清单：列出留空的项目，以及每个主要内容取自哪一页的地址。
2. 顶层固定为：
   {"format": "quizbuks-profile", "schema": 1, "languages": [基准语言, 其他语言…],
    "basics": {...}, "page": {...}, "products": [...]}
   不写 "files"、"mailServer"（资料包从不带邮件服务器的密码，也不由你生成）。
3. 所有文字写成按语言的对象 {"zh": "…", "ja": "…", "en": "…"}，键只能是 "languages" 中列出的语言；
   基准语言必填。只有基准语言时，也可以直接写成字符串。其他语言是你对基准文字的翻译，语义不变，不添加原文没有的内容。
4. basics：
   - "contactName"：照抄参数中的联系人姓名，≤ 100 字；**不要从网站取任何人的姓名**；
   - "company"：公司的正式名称（取自页眉、页脚、"公司概要 / 会社概要 / About"），≤ 100 字，必填；
   - "intro"：简介，2–5 句，取自"关于我们"或首页的介绍，不照搬整页，≤ 5000 字，可用 Markdown，不写 HTML；
   - "avatar"：网站的 logo 图片地址 {"url": "https://…"}，找不到就不写这个键。
5. page：
   - "handle"：主页的地址名，由官网的域名得出（www.aini-helmet.com → aini-helmet），只用小写字母、数字与连字符，
     3–30 个字符，不以连字符开头或结尾；
   - "headline"：一句话标语，取自首页的主标题或网站的说明文字，每种语言 ≤ 120 字，必填；
   - "website"：官网的地址，以 https:// 开头，不含空格，≤ 300 字；
   - "cover"：首页主视觉图片地址 {"url": "https://…"}，找不到就不写这个键。
6. products（参数为"不要商品"时写 []）：
   - 取自"产品与服务"页或在线商店的商品页，按网站上的顺序，个数不超过参数的上限；
   - 每项：
     "name"：商品名，每种语言 ≤ 100 字，必填；
     "description"：说明，2–3 句，每种语言 ≤ 500 字；
     "price"：价格，数字，必填；日元（JPY）不带小数，其他币种至多 2 位小数；**网站上没有明码标价的商品不要放进来，不要猜价格**；
     "currency"：币种，只能是 USD、EUR、GBP、JPY、CNY、INR 之一，必填；
     "shopUrl"：购买链接，以 https:// 开头，不含空格，≤ 300 字，必填（没有购买链接的商品不放进来）；
     "code"：商品的货号，有才写，只用字母、数字与 . _ -，以字母或数字开头，≤ 40 字；
     "image"：商品图片地址 {"url": "https://…"}，找不到就不写这个键。
7. 图片只能写成 {"url": "https://…"}（https 开头的真实地址，取自网页）；不要写 Base64 或任何图片数据；不要编造地址。
8. 内容要求：
   - **不编造**：只用官网上有的内容；没有的项目（简介、标语、封面、商品说明……）留空或不写这个键，并列入"官网上没找到"清单；
   - **不取个人信息**：不取网站上任何个人的姓名、邮箱、电话、社交账号；"联系方式"只取公司公开的地址或公开表单的链接，放进 "intro" 中；
   - 公司名与商品名保持原文，不翻译成不存在的名称；价格与币种必须与网页一致；
   - 不确定的内容宁可留空，也不要编造。

请先在心里核对第 1–8 条，再输出。
````

## 3. 输出示例

### 3.1 只有基准语言（日文），没有商品

```json
{
  "format": "quizbuks-profile",
  "schema": 1,
  "languages": ["ja"],
  "basics": {
    "contactName": "山田 花子",
    "company": "株式会社みどり茶房",
    "intro": "京都で創業した日本茶の専門店です。農家から直接仕入れた茶葉を、店内で焙煎して販売しています。"
  },
  "page": {
    "handle": "midori-saboh",
    "headline": "農家から届く、焙煎したての日本茶",
    "website": "https://www.midori-saboh.example/"
  },
  "products": []
}
```

### 3.2 三語、商品つき（中文为基准）

```json
{
  "format": "quizbuks-profile",
  "schema": 1,
  "languages": ["zh", "ja", "en"],
  "basics": {
    "contactName": "山田 花子",
    "company": "艾尼头盔",
    "intro": {
      "zh": "艾尼头盔专注摩托车与自行车头盔，所有产品通过安全认证。官网提供在线商店与门店查询。",
      "ja": "アイニヘルメットはバイクと自転車用ヘルメットの専門ブランドです。全製品が安全認証を取得しています。公式サイトにオンラインストアと店舗検索があります。",
      "en": "Aini Helmet specializes in motorcycle and bicycle helmets, and every product is safety certified. The website offers an online store and a store locator."
    },
    "avatar": {"url": "https://www.aini-helmet.example/images/logo.png"}
  },
  "page": {
    "handle": "aini-helmet",
    "headline": {
      "zh": "每一次出行，都戴得安心",
      "ja": "毎日の移動を、安心して",
      "en": "Ride with confidence, every time"
    },
    "website": "https://www.aini-helmet.example/",
    "cover": {"url": "https://www.aini-helmet.example/images/hero.jpg"}
  },
  "products": [
    {
      "code": "AH-100",
      "name": {"zh": "城市通勤头盔", "ja": "シティコミューターヘルメット", "en": "City Commuter Helmet"},
      "description": {
        "zh": "轻量化设计，内置可拆洗衬垫，适合每天通勤。",
        "ja": "軽量設計で、洗えるインナーパッド付き。毎日の通勤に向いています。",
        "en": "Lightweight with a washable inner pad, made for the daily commute."
      },
      "price": 12800,
      "currency": "JPY",
      "shopUrl": "https://www.aini-helmet.example/shop/ah-100",
      "image": {"url": "https://www.aini-helmet.example/images/ah-100.jpg"}
    },
    {
      "name": {"zh": "儿童骑行头盔", "ja": "キッズサイクルヘルメット", "en": "Kids Cycling Helmet"},
      "price": 49.9,
      "currency": "USD",
      "shopUrl": "https://www.aini-helmet.example/shop/kids"
    }
  ]
}
```

## 4. 修正提示词（导入报错时）

````text
下面的 QuizBuks 发起方资料包导入时报错。请只修正报错指出的问题，其他内容保持不变，
仍按原来的【格式规则】只输出一个 ```json 代码块。不要为了通过检查而编造内容：
报错的项目在官网上确实没有时，删掉这一项（或留空），并在清单里说明。

报错信息（确认页列出的问题，原样粘贴）：
<例：products[3].price: A price>

文件：
<粘贴 JSON>
````

报错中的位置从 0 起数：`products[3]` 是第 4 个商品。

## 5. 规则一览（与服务器一致）

| 项目 | 规则 |
|---|---|
| 格式 | `format` 为 `quizbuks-profile`，`schema` 为 1；`languages` 非空，基准在前，只能是 `ja` / `zh` / `en`，不重复 |
| 文字 | 按语言的对象，键须在 `languages` 中；字符串视为基准语言；必填的文字须有基准语言 |
| `basics.contactName` | 必填，≤ 100 字；**由使用者在参数中给出，不从官网取** |
| `basics.company` | 必填，≤ 100 字 |
| `basics.intro` | ≤ 5000 字，Markdown，可不写 |
| `page.handle` | 必填，`[a-z0-9][a-z0-9-]{1,28}[a-z0-9]`（3–30 个字符）；被别人占用时确认页提示另选 |
| `page.headline` | 必填，每种语言 ≤ 120 字 |
| `page.website` | 可不写；`https://` 开头，不含空白，≤ 300 字 |
| 商品个数 | 至多 `qb.products.max` 个（缺省 50），超过则整包拒绝；按数组顺序 |
| `products[].name` | 必填，每种语言 ≤ 100 字 |
| `products[].description` | 每种语言 ≤ 500 字 |
| `products[].price` / `currency` | 都必填；币种为 `USD` `EUR` `GBP` `JPY` `CNY` `INR`；日元不带小数，其他至多 2 位小数 |
| `products[].shopUrl` | 必填；`https://` 开头，不含空白，≤ 300 字 |
| `products[].code` | 可不写；字母、数字与 `. _ -`，以字母或数字开头，≤ 40 字；再次导入时按它找到原来的商品 |
| 图片 | `{"url": "https://…"}`、`null`（去掉现有的）或不写这个键（保留现有的）；不能放 Base64。链接图片暂不下载，确认页列为"请下载后自己上传" |
| 邮件服务器 | 资料包不由 AI 生成；`mailServer` 里写了 `password` 即整包拒绝 |
