# F-F9 链接名中粘贴网址的提示：实施计划（S3）

规格：`docs/quizbuks/first-launch-spec.md` F-F9。来源：troubleshooting T10。

## 要改的

| 位置 | 改动 |
|---|---|
| `quizbuks-web/sponsor/src/page/handle.ts`（新） | 纯函数 `addressInHandle(输入)`：输入像网址（有 `http(s)://`、以 `www.` 开头，或是带点、顶级域名为字母、没有空格的主机）而又不是合规的链接名时，返回 `{ handle, website }`。`handle` 取注册域名的那一段：去掉 `www.`、顶级域名，以及 `co.jp` / `com.cn` / `co.uk` 这类第二级；子域名不取。结果须满足 `HANDLE_PATTERN`，否则为 null。中文域名（非 ASCII 或 punycode `xn--`）不给建议。`website` 是该网址，没有协议时补 `https://`。其余输入返回 null，交给原来的校验 |
| `SponsorPageEditor.tsx` | 链接名下方：是网址时把原来的说明换成提示"这是本平台上的地址名"，并给一个按钮："使用 X"；"网站"为空时按钮为"使用 X，并把网址移到'网站'一栏"；没有建议但"网站"为空时为"把网址移到'网站'一栏"。只在用户点击时填入，不自动改写。粘贴的网址整段保留，不按 30 个字符截断（`onPaste`），以便读出域名 |
| `messages.ts` | 三语：`handleIsAddress`、`handleSuggest`、`handleSuggestMove`、`handleMove` |
| `handle-cases.json`（新） | 链接名规则的一组合规 / 不合规用例；前端 `HANDLE_PATTERN`（`page/api.ts`）与服务端 `QbSponsorPages.HANDLE_PATTERN` 都要通过 |
| `QbSponsorPagesHandleTest`（新，Java） | 服务端规则（整段匹配，同平台的 `PATTERN` 规则）通过同一组用例；最长的合规用例等于 `HANDLE_LENGTH` |
| troubleshooting T10、进度 | 状态 |

前后端的链接名校验不变；不加表、迁移、流程。

## 测试

- `handle.test.ts`：`https://`、`http://`、`www.`、只有域名、带路径与查询、子域名、`co.jp` / `com.cn` / `co.uk`、中文域名与 punycode（不给建议）、不合规的建议（太短、太长、下划线、首尾连字符），以及普通名字（不提示）；共用用例。
- `page.test.tsx`：粘贴一个长于 30 个字符的网址后整段保留、显示提示、axe 通过；点击后链接名与网站都填好，且没有保存；"网站"已填时不覆盖；中文域名有提示、没有按钮。
- `QbSponsorPagesHandleTest`。

## 与依赖的关系

- 规格的依赖写的是 F-F3（新的"公开主页"标签）。F-F3 要等 P3，所以这次先在现有的公开主页编辑中做；F-F3 迁移页面时把这一栏原样带过去（`handle.ts` 与测试不变）。

## 风险

- 第二级域名的列表只是常见的几个；遇到少见的后缀，建议可能取到第二级（如 `co`），这时它太短而不合规，就不会给出建议，最坏只是没有建议。
