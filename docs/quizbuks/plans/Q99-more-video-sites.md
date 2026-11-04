# Q99 更多视频网站（2026-11-05 需求方确认）

需求方（2026-11-05）："是否要看完才能答题，是一个可选项。如果视频不支持读取进度，发起方就不要坚持看完答题了。立即完成支持更多的视频。"

## 1. 决定

| # | 决定 |
|---|---|
| D-Q99-1 | 视频资料除 YouTube、Vimeo 外，另认 **哔哩哔哩、niconico、Dailymotion、优酷、腾讯视频** 的单个视频，在 App（与商家后台的预览）中以该网站的播放器显示。识别只在 `content.VideoEmbeds`（后端）与 `qb/video.ts`（两个 SPA，同一组用例）：<br>`bilibili.com/video/BV…` → `player.bilibili.com/player.html?bvid=…&autoplay=0`；`nicovideo.jp/watch/sm…`、`nico.ms/sm…` → `embed.nicovideo.jp/watch/…`；`dailymotion.com/video/x…`、`dai.ly/x…` → `www.dailymotion.com/embed/video/…`；`v.youku.com/v_show/id_….html` → `player.youku.com/embed/…`；`v.qq.com/x/page/<vid>.html`、`v.qq.com/x/cover/<cover>/<vid>.html` → `v.qq.com/txp/iframe/player.html?vid=…`。短链接（`b23.tv`）、合集、频道、直播不认，照旧是链接 |
| D-Q99-2 | **"看完才能答题"是可选项，只对能读进度的播放器开放**：只有 YouTube 与 Vimeo 的播放器告诉页面播放到哪里（Q75），`VideoEmbeds.trackable`。其他网站的视频不能设为"看完才能答题"（`QB_MATERIAL_SAVE` 与导入照旧报 `QB_MATERIAL_VIDEO_NOT_EMBEDDABLE`，文案改为说明原因）；商家后台的勾选框对这类地址不可勾选并说明原因（已勾选的仍可取消） |
| D-Q99-3 | **卡片的视频**照旧是"看完才能答题"的那个，否则是第一个 App 能播放的视频（现在包括其他网站的），`watchRequired` 只可能对 YouTube / Vimeo 为真 |
| D-Q99-4 | **其他网站的视频不自动播放**：首页卡片中间不播放（照旧显示封面与播放标记，点开进入详情页），详情页显示该网站的播放器，由答题人自己按播放；不经 Q65 的共用播放器（它靠 YouTube / Vimeo 的接口开声音与读进度） |
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
