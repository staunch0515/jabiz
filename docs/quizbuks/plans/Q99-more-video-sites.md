# Q99 更多视频网站（2026-11-05 需求方确认）

需求方（2026-11-05）："是否要看完才能答题，是一个可选项。如果视频不支持读取进度，发起方就不要坚持看完答题了。立即完成支持更多的视频。"

## 1. 决定

| # | 决定 |
|---|---|
| D-Q99-1 | 视频资料除 YouTube、Vimeo 外，另认 **哔哩哔哩、niconico、Dailymotion、优酷、腾讯视频** 的单个视频，在 App（与商家后台的预览）中以该网站的播放器显示。识别只在 `content.VideoEmbeds`（后端）与 `qb/video.ts`（两个 SPA，同一组用例）：<br>`bilibili.com/video/BV…` → `player.bilibili.com/player.html?bvid=…&autoplay=0`；`nicovideo.jp/watch/sm…`、`nico.ms/sm…` → `embed.nicovideo.jp/watch/…`；`dailymotion.com/video/x…`、`dai.ly/x…` → `www.dailymotion.com/embed/video/…`；`v.youku.com/v_show/id_….html` → `player.youku.com/embed/…`；`v.qq.com/x/page/<vid>.html`、`v.qq.com/x/cover/<cover>/<vid>.html` → `v.qq.com/txp/iframe/player.html?vid=…`。~~短链接（`b23.tv`）不认~~（**Q99b 起认 `b23.tv`**，§5）；合集、频道、直播不认，照旧是链接 |
| D-Q99-2 | **"看完才能答题"是可选项，只对能读进度的播放器开放**：只有 YouTube 与 Vimeo 的播放器告诉页面播放到哪里（Q75），`VideoEmbeds.trackable`。其他网站的视频不能设为"看完才能答题"（`QB_MATERIAL_SAVE` 与导入照旧报 `QB_MATERIAL_VIDEO_NOT_EMBEDDABLE`，文案改为说明原因）；商家后台的勾选框对这类地址不可勾选并说明原因（已勾选的仍可取消） |
| D-Q99-3 | **卡片的视频**照旧是"看完才能答题"的那个，否则是第一个 App 能播放的视频（现在包括其他网站的），`watchRequired` 只可能对 YouTube / Vimeo 为真 |
| D-Q99-4 | ~~**其他网站的视频不自动播放**~~（**Q99b 取代**，§5：所有视频一视同仁）：首页卡片中间不播放（照旧显示封面与播放标记，点开进入详情页），详情页显示该网站的播放器，由答题人自己按播放；不经 Q65 的共用播放器（它靠 YouTube / Vimeo 的接口开声音与读进度） |
| D-Q99-5 | App 与商家后台的内容安全策略 `frame-src` 加 `player.bilibili.com`、`embed.nicovideo.jp`、`www.dailymotion.com`、`geo.dailymotion.com`、`player.youku.com`、`v.qq.com` |
| D-Q99-6 | Shopify 店面的视频（`QBS` 渠道，`ChannelPublishing`）是"看完才能答题"的视频，仍只认 YouTube / Vimeo |

## 2. 实施（2026-11-05）

- 后端：`VideoEmbeds.player` 认七个网站，`trackable` / `trackablePlayer`；`MaterialProcesses`（基准与语言版本）、`QuizFileChecks`、`PublicationEditing.cardVideo`、`ChannelPublishing` 改用 `trackablePlayer`；三语文案 `QB_MATERIAL_VIDEO_NOT_EMBEDDABLE`；`application.yml` 的两个 `frame-src`。不改表、不改快照、不加迁移；卡片的 `video` 列（80 字符）放得下最长的播放器地址。
- App：`qb/video.ts`（七个网站、`trackable`、`autoplaying` 只给能控制的播放器加自动播放）、`VideoPlayer`（其他网站不自动播放）、`FeedCard`（`data-video` 只给能在首页播放的）、`FeedPage` / `PublicationPage`（其他网站不经共用播放器）。
- 商家后台：资料对话框的视频地址说明列出七个网站；其他网站的地址"看完才能答题"不可勾选并说明原因，不读时长。
- 隐私政策草稿（三语）在第三方一节加五家公司（**须法务确认公司名称与向中国的提供**：APPI 第 28 条的外国第三方提供与 Cookie 的表述）。

## 3. 测试

- `VideoEmbedsTest` 与两个 SPA 的 `video.test.ts`（同一组用例：每个网站的认与不认、`trackable`、`trustedPlayer`）。
- `WatchVideoIT`：哔哩哔哩的视频不能设为"看完才能答题"；只有哔哩哔哩视频的发布，卡片带它的播放器、`watchRequired` 为假，开始答题不要求观看。
- `QuizbuksAppIT`：两个前端的内容安全策略。
- Vitest：App 的首页卡片不播放、详情页显示其播放器且不自动播放；商家后台的勾选框（不可勾选、说明、axe）。
- e2e 未在本机运行（`watch-video.spec.ts` 用的是 YouTube，行为不变）。

## 4. 风险

- 各网站的嵌入地址与是否允许嵌入由各网站决定（如优酷、腾讯视频的部分视频禁止外站播放、或在中国以外受地区限制）：播放器自己显示原因，答题不受影响（不要求看完）。
- Dailymotion 的旧嵌入地址可能转到 `geo.dailymotion.com`，已一并放行。

## 5. Q99b：所有视频一视同仁、认 b23.tv 短链接（2026-11-05 需求方）

需求方 2026-11-05："我的要求是前台能播放各种视频，而且要能在首页播放。""我没有说过视频要区分对待。只是因为不知道是否播完，才让发起方选择一下是否强制看完。"
Q99 的 D-Q99-4 把其他网站的视频做成"首页不播放、只在开始页由答题人自己按播放"，与此不符；需求方填的哔哩哔哩分享短链接 `b23.tv` 也因 D-Q99-1 不认，前台只给一个链接。

| # | 决定 |
|---|---|
| D-Q99b-1 | **所有网站的视频一视同仁**：首页中间的卡片与开始页一样播放，静音自动开始（各网站以自己的参数：YouTube `mute=1`、Vimeo `muted=1`、哔哩哔哩 `autoplay=1&muted=1`、Dailymotion 与 niconico `autoplay=1&mute=1`；优酷、腾讯视频的播放器不接受这些参数，显示它自己的播放按钮，在卡片上点一下即在首页播放）。唯一的区别仍是 D-Q99-2：只有能读进度的（YouTube、Vimeo）可以设为"看完才能答题"；它们照旧经 Q65 的共用播放器（点一下开声音），其他网站的是卡片与页面自己的播放器（声音用它们自己的按钮） |
| D-Q99b-2 | **认哔哩哔哩的短链接 `b23.tv`**：商家后台在视频资料中输入或粘贴 `https://b23.tv/<码>` 时，以新的只读流程 `QB_VIDEO_LINK_RESOLVE` 问 b23.tv 它跳到哪里（外部服务 `video-short-links`，只许 `b23.tv`；平台从不跟随跳转，只读 `Location`），得到单个视频即把地址换成 `https://www.bilibili.com/video/<BV 号>`（去掉分享参数）后照常保存；跳到别处（空间、番剧）或找不到即 422 `QB_VIDEO_LINK_NOT_RESOLVED`，说明请复制视频页面的地址。资料保存的规则不变 |
| D-Q99b-3 | **商家后台说清能不能播**：视频地址下随输入显示"会在 App 中播放（首页与答题页）"，或"App 不能播放这个地址，只会作为链接在新标签页中打开"，或短链接的查找与结果 |

实施（2026-11-05）：

- 后端：`content.video.ShortVideoLinks`（`code`、`videoOf`、外部服务的声明）、`ResolveShortVideoLink`（阻塞步骤）、`VideoLinkProcesses`（`QB_VIDEO_LINK_RESOLVE`，`qb.content.write`，要求已验证邮箱、先查封禁）；`ContentConfig` 注册；三语文案；测试配置把服务指向本机。
- App 与商家后台（`qb/` 两份相同）：`qb/video.ts` 的 `autoplaying` 给每个网站各自的静音自动播放参数；`VideoPlayer` 不再只让 YouTube、Vimeo 自动播放；`FeedCard` 所有有视频的卡片都在首页中间播放（YouTube、Vimeo 经共用播放器，其他网站用卡片自己的播放器）；开始页其他网站的播放器同样自动播放。
- 商家后台：资料对话框的短链接查找与"能不能播"的说明（`content/api.ts` 的 `isShortVideoLink`、`resolveVideoLink`）。
- 测试：`ShortVideoLinksTest`、`VideoLinkResolveIT`（b23.tv 的桩：单个视频、空间、找不到、不是短链接、答题者不能用）；App 的 `video.test.ts`（每个网站的自动播放参数）、`Play.test.tsx`（哔哩哔哩在首页卡片与开始页播放）；商家后台 `content.test.tsx`（短链接换成完整地址、失败的说明、不能播的地址的说明、axe）。后端在本机因 Maven Central 限流（429）未能编译，以 PR 的 CI 为准。
