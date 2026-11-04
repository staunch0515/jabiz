# 用其他 AI 工具生成 Quiz 文件：提示词模版

商家后台「Quiz → 导入」读的是 `.quizbuks.json` 文件（格式见 `plans/Q16-quiz-transfer.md`，多语言见 `plans/Q73-content-languages.md`）。
本文给出可以直接交给 ChatGPT、Gemini、Claude 等工具的提示词，生成能被导入的文件。规则取自服务器的检查
（`content/transfer/QuizFileFormat.java`、`QuizFileChecks.java`、`ContentLimits.java`、`QuizCompleteness.java`）；
本文中的两个 JSON 示例由测试 `QuizFilePromptsTest` 按服务器的同一套检查读取，规则变了测试即失败，请同时改本文。

## 1. 用法

1. 复制第 2 节的提示词，只改最前面【参数】中的值。
2. 交给 AI 工具。它只应回答一个 JSON 代码块（参数选了"列出图片建议"时，代码块之后另有一张图片建议表）。
3. 把代码块的内容保存为 `<名字>.quizbuks.json`（UTF-8），在商家后台「Quiz → 导入」中选择它。
4. 导入后成为草稿。在编辑器中检查题目与正确答案，需要图片的在编辑器中上传，再"保存版本"。
5. 导入时报错：把错误信息（如 `questions[3].options: …`）连同第 4 节的修正提示词交回 AI。

**不要让 AI 在文件里放图片。** 语言模型写不出真实的图片数据，只会编一段假的 Base64，导入会失败
（"上传第 1/1 个文件 …：数据已损坏"）。图片一律在导入后上传。

## 2. 主提示词

````text
你是 QuizBuks 的出题助手。请按下面的【参数】与【格式规则】生成一个 QuizBuks Quiz 文件（JSON）。

【参数】（使用者只改这里）
- 主题：<例：国家电网安全规程>
- 参考资料（可选，出题只依据它；没有就依据公开的通识）：<粘贴文字，或写"无">
- 种类：QUIZ（测验，有正确答案、计分）或 SURVEY（问卷，无正确答案）：QUIZ
- 题目数量：10（1–100）
- 每题选项数：4（2–6）
- 多选题数量：0（只对 QUIZ；多选题有 2 个以上正确选项）
- 每题分数：2（1–1000 的整数；SURVEY 忽略）
- 时限（秒）：300（30–7200）
- 难度与对象：<例：入职一年内的一线员工，中等难度>
- 基准语言：zh（ja / zh / en 之一，题目先用它写）
- 其他语言：ja, en（0–2 种，从 ja / zh / en 中选，不含基准语言；不要就写"无"）
- 资料：1 篇文章（ARTICLE，0–10 篇；可另加链接 LINK 或视频 VIDEO_LINK，写明地址）
- 图片：不要图片 / 列出图片建议（二选一）

【格式规则】（必须全部遵守）
1. 只输出一个 ```json 代码块，内容是合法 JSON（双引号、无注释、无末尾逗号、无省略号）。
   图片选"列出图片建议"时，代码块之后再给一张表：位置（如"封面""第 3 题""第 3 题选项 2"）、画面描述、建议尺寸。
2. 顶层固定为：
   {"format": "quizbuks.quiz", "formatVersion": 2, "quiz": {...}, "materials": [...], "questions": [...], "files": {}}
   有其他语言时另加 "translations"（见第 7 条）。
3. quiz：
   - "kind"：参数中的种类；"title"：标题，必填，≤ 100 字；"intro"：简介，≤ 5000 字，可用 Markdown，可为 null；
   - "timeLimitSec"：参数中的时限；"language"：基准语言代码；"cover"：null。
4. materials：数组，每项都有 "kind"、"title"（≤ 100 字）、"description"（≤ 500 字或 null）、"images": []，
   并且**只**有该种类自己的内容字段，其他内容字段为 null：
   - ARTICLE → "body"（正文，≤ 20000 字，Markdown，不写 HTML）；
   - LINK → "url"（http:// 或 https:// 开头，≤ 2000 字）；
   - VIDEO_LINK → "url"；只有 YouTube、Vimeo 的视频可以设 "watchToAnswer": true（全卷至多 1 个），
     此时可写 "durationSec"（视频秒数，1–7200）；其他种类不写这两个字段。
   - 不生成 PDF、IMAGES、AUDIO 种类（它们需要文件）。
5. questions：数组，按参数的题目数量，每项：
   - "stem"：题干，≤ 1000 字，可用 Markdown；"image": null；
   - "points"：QUIZ 为参数中的分数；SURVEY 一律 1；
   - "selection"：QUIZ 一律 null（单选或多选由正确选项的个数决定）；SURVEY 为 "SINGLE" 或 "MULTIPLE"；
   - "options"：2–6 个，每项 {"text": "≤ 300 字", "image": null, "correct": true 或 false}；
     QUIZ 每题至少 1 个 true，单选题恰好 1 个，多选题 2 个以上；SURVEY 全部 false。
   - 正确选项的位置要分散（不要总是第一个）；选项之间不重复，不用"以上都对/都不对"。
6. "files" 必须是空对象 {}，任何地方都不引用图片；不要编造 Base64 或任何文件数据。
7. 其他语言（参数为"无"时不写 "translations"）：
   "translations": {"<语言代码>": {"texts": {...}}}，每种语言都要把基准中的**全部**文字译出，键为：
   - "title"；有简介时 "intro"；
   - 第 n 题题干 "q<n>.stem"，第 n 题第 m 个选项 "q<n>.o<m>.text"（n、m 从 1 起，按数组顺序）。
   每段译文的长度上限同原文。不写 "hashes"、"images"；资料的译文不写（资料在其他语言中显示基准语言的原文）。
8. 内容要求：事实准确，答案唯一且能由参考资料或公认知识确认；不出现个人信息、联系方式、广告链接；
   不确定的题目宁可不出，也不要编造。

请先在心里核对第 1–8 条，再输出。
````

## 3. 输出示例

### 3.1 测验（中文为基准，另有日文与英文）

AI 的输出应当像这样（示例为 3 题；`QuizFilePromptsTest` 检查它可以导入、可以保存版本）：

```json
{
  "format": "quizbuks.quiz",
  "formatVersion": 2,
  "quiz": {
    "kind": "QUIZ",
    "title": "办公室消防安全小测验",
    "intro": "共 3 题，答题前请先阅读资料。",
    "timeLimitSec": 300,
    "language": "zh",
    "cover": null
  },
  "materials": [
    {
      "kind": "ARTICLE",
      "title": "消防安全要点",
      "description": null,
      "body": "发现火情先报警（119），再在确保安全的前提下扑救初起火灾。\n\n使用灭火器：**提、拔、握、压**。\n\n疏散时走楼梯，不乘电梯；烟雾中低姿前进。",
      "url": null,
      "pdf": null,
      "audio": null,
      "images": []
    }
  ],
  "questions": [
    {
      "stem": "在中国，火警电话是多少？",
      "image": null,
      "points": 2,
      "selection": null,
      "options": [
        { "text": "110", "image": null, "correct": false },
        { "text": "119", "image": null, "correct": true },
        { "text": "120", "image": null, "correct": false },
        { "text": "122", "image": null, "correct": false }
      ]
    },
    {
      "stem": "发生火灾疏散时，应当怎样做？（多选）",
      "image": null,
      "points": 2,
      "selection": null,
      "options": [
        { "text": "乘电梯尽快下楼", "image": null, "correct": false },
        { "text": "走楼梯", "image": null, "correct": true },
        { "text": "烟雾中低姿前进", "image": null, "correct": true },
        { "text": "返回座位取贵重物品", "image": null, "correct": false }
      ]
    },
    {
      "stem": "使用灭火器的正确顺序是？",
      "image": null,
      "points": 2,
      "selection": null,
      "options": [
        { "text": "拔、提、压、握", "image": null, "correct": false },
        { "text": "握、压、提、拔", "image": null, "correct": false },
        { "text": "提、拔、握、压", "image": null, "correct": true },
        { "text": "压、握、拔、提", "image": null, "correct": false }
      ]
    }
  ],
  "files": {},
  "translations": {
    "ja": {
      "texts": {
        "title": "オフィス防火安全ミニテスト",
        "intro": "全 3 問です。回答の前に資料をお読みください。",
        "q1.stem": "中国の消防の緊急電話番号は？",
        "q1.o1.text": "110",
        "q1.o2.text": "119",
        "q1.o3.text": "120",
        "q1.o4.text": "122",
        "q2.stem": "火災で避難するときの正しい行動は？（複数選択）",
        "q2.o1.text": "エレベーターで急いで降りる",
        "q2.o2.text": "階段を使う",
        "q2.o3.text": "煙の中では姿勢を低くして進む",
        "q2.o4.text": "席に戻って貴重品を取る",
        "q3.stem": "消火器の正しい使い方の順番は？",
        "q3.o1.text": "抜く、持つ、押す、握る",
        "q3.o2.text": "握る、押す、持つ、抜く",
        "q3.o3.text": "持つ、抜く、握る、押す",
        "q3.o4.text": "押す、握る、抜く、持つ"
      }
    },
    "en": {
      "texts": {
        "title": "Office fire safety quiz",
        "intro": "3 questions. Please read the material first.",
        "q1.stem": "What is the fire emergency number in China?",
        "q1.o1.text": "110",
        "q1.o2.text": "119",
        "q1.o3.text": "120",
        "q1.o4.text": "122",
        "q2.stem": "What should you do when evacuating a fire? (choose all that apply)",
        "q2.o1.text": "Take the lift down quickly",
        "q2.o2.text": "Use the stairs",
        "q2.o3.text": "Stay low in smoke",
        "q2.o4.text": "Go back to your desk for valuables",
        "q3.stem": "What is the right order for using a fire extinguisher?",
        "q3.o1.text": "Pull, lift, press, aim",
        "q3.o2.text": "Aim, press, lift, pull",
        "q3.o3.text": "Lift, pull, aim, press",
        "q3.o4.text": "Press, aim, pull, lift"
      }
    }
  }
}
```

### 3.2 问卷（英文，无其他语言）

```json
{
  "format": "quizbuks.quiz",
  "formatVersion": 2,
  "quiz": {
    "kind": "SURVEY",
    "title": "Coffee habits",
    "intro": null,
    "timeLimitSec": 120,
    "language": "en",
    "cover": null
  },
  "materials": [],
  "questions": [
    {
      "stem": "How many cups of coffee do you drink a day?",
      "image": null,
      "points": 1,
      "selection": "SINGLE",
      "options": [
        { "text": "None", "image": null, "correct": false },
        { "text": "1–2", "image": null, "correct": false },
        { "text": "3 or more", "image": null, "correct": false }
      ]
    },
    {
      "stem": "Where do you usually drink it?",
      "image": null,
      "points": 1,
      "selection": "MULTIPLE",
      "options": [
        { "text": "At home", "image": null, "correct": false },
        { "text": "At work", "image": null, "correct": false },
        { "text": "In a café", "image": null, "correct": false }
      ]
    }
  ],
  "files": {}
}
```

## 4. 修正提示词（导入报错时）

````text
下面的 QuizBuks Quiz 文件导入时报错。请只修正报错指出的问题，其他内容保持不变，
仍按原来的【格式规则】只输出一个 ```json 代码块。

报错信息：
<粘贴导入对话框中的全部错误，如 questions[3].options: 7 questions[3].options, at most 6>

文件：
<粘贴 JSON>
````

报错中的位置从 0 起数：`questions[3]` 是第 4 题，`options[0]` 是第 1 个选项。

## 5. 图片

导入的文件中可以带图片，但只应是**从 QuizBuks 导出**的文件（导出会把真实的图片以 Base64 放进 `files`）。
用 AI 生成文件时：

- 选"不要图片"：直接导入，之后可以在编辑器中用"AI 生成封面"。
- 选"列出图片建议"：AI 在 JSON 之后给出每张图的位置与画面描述；用图片工具做好后在编辑器中上传到对应位置。
- 能上传的图片：JPEG 或 PNG，每张 ≤ 10 MB；封面建议 1200 × 630。服务器会重新编码图片并去掉拍摄位置等信息。
- 每题可有一张题目图片，每个选项可有一张选项图片（选项可以只有图片、没有文字）；其他语言可以替换题目或选项的图片（在编辑器的"语言"中）。

## 6. 规则一览（与服务器一致）

| 项目 | 规则 |
|---|---|
| 题目 | 至多 100 题；题干 ≤ 1000 字 |
| 选项 | 每题 2–6 个（导入时多于 6 即拒绝；少于 2 能导入为草稿，但不能保存版本）；每个 ≤ 300 字 |
| 正确答案 | 测验每题至少 1 个（否则不能保存版本）；1 个为单选，多个为多选；问卷不能有 |
| 分数 | 测验 1–1000 的整数；问卷每题按 1 分计 |
| `selection` | 测验必须为 null；问卷为 `SINGLE` 或 `MULTIPLE`（缺了不能保存版本） |
| 时限 | 30–7200 秒 |
| 标题 / 简介 | ≤ 100 字 / ≤ 5000 字 |
| 资料 | 至多 10 个；种类 `ARTICLE`（正文 ≤ 20000）、`LINK`、`VIDEO_LINK`（地址 ≤ 2000，http(s)）、`PDF`、`IMAGES`（≤ 20 张）、`AUDIO`；每种只能有自己的内容字段 |
| 语言 | 基准为 `ja` / `zh` / `en` 之一；其他语言至多 2 种，不能与基准相同；有其他语言时必须写基准语言 |
| 译文 | 键 `title`、`intro`、`q<n>.stem`、`q<n>.o<m>.text`（从 1 起）；只能译基准中有的文字；要保存版本，每种语言须译全 |
| 文件 | 封面、题目、选项、资料图片：JPEG / PNG ≤ 10 MB；PDF ≤ 20 MB；音频 MP3 / M4A / OGG ≤ 50 MB；`size` 必须是真实字节数，`data` 必须是完整的 Base64 |
| 字数 | 按字符计（一个汉字算 1） |
