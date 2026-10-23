# Q19 看视频答题：视频嵌入页面

需求方 2026-10-23："现在视频是链接，但是需要把视频嵌入在页面中，而不是链接到其他地方看视频。特别注意，在视频上传的地方如果选择仅看视频就可以答题的话，
就按下面的要求来做。我在答题人的 Home 中显示调查问卷的地方，直接就是嵌入的视频，但不是不自动播放。在视频中的播放按钮，用户点击可以直接观看。
旁边或者下面，有一个回答正确的到多少 kudos 的提示和一个开始答题按钮就够了。"
确认（2026-10-23）：视频来源为 YouTube / Vimeo 链接（不上传视频文件）；顺序在 Q17、平台 16zc 之后、Q18 之前。取代页面阶段的决定 Q-3（视频不内嵌）。

## 1. 做了什么

| 部分 | 内容 |
|---|---|
| 视频地址 | `content.VideoEmbeds`（App 的 `play/video.ts` 同样读法，测试用例相同）：YouTube（`watch?v=`、`youtu.be/`、`shorts/`、`embed/`、`live/`）与 Vimeo（`vimeo.com/<数字>`、`player.vimeo.com/video/<数字>`）的单个视频 → 播放器地址；YouTube 用无 Cookie 的 `www.youtube-nocookie.com/embed/<id>`。其他地址不是播放器 |
| "看视频即可答题" | 资料的新字段 `watchToAnswer`（`QbMaterial`，迁移 V22）：只有视频链接可勾选（否则 422 `QB_MATERIAL_FIELD_NOT_ALLOWED`），地址须是 YouTube / Vimeo 的单个视频（422 `QB_MATERIAL_VIDEO_NOT_EMBEDDABLE`），每个模版一个（422 `QB_QUIZ_ONE_WATCH_VIDEO`）|
| 版本 | 快照中只有该视频写 `"watchToAnswer":true`：其他资料与已有版本的文本与哈希不变；复制与导入照样带上 |
| 发布卡片 | 发布上线时，卡片（`QbPublicationCard.video`，迁移 V22）冻结该视频的播放器地址；模板 `qb.app.feed` 返回 `video` |
| App 首页 | 有 `video` 的卡片显示视频本身（`VideoPlayer`：`iframe`，靠近屏幕时才加载，不自动播放，用户点播放器的按钮观看；`sandbox` 只给播放器需要的权限），下方是"答对最多可得 N Kudos"（问卷："作答即可得 N Kudos"）与"开始答题"按钮（与详情页同一个开始，`QB_ATTEMPT_START`）；名称仍链到详情页。App 只信任两种播放器地址，服务器给的其他地址按普通卡片显示 |
| 详情页的资料 | 任何 YouTube / Vimeo 视频链接都在页面中播放；其他视频照旧以新标签页打开 |
| 内容安全策略 | App（`/`）的策略为平台缺省加 `frame-src https://www.youtube-nocookie.com https://player.vimeo.com`；`application.yml` 因此写出完整的 `jabiz.web.spa`（与 jar 中的三个前端一致，启动检查 `WEB`）。商家后台与管理后台保持缺省 |
| 商家后台 | 视频资料的对话框有"看完这个视频即可答题"复选框与说明；资料列表显示"显示在首页"；导出文件带上（版本 1 的可选字段，缺省为否） |

## 2. 测试

- `VideoEmbedsTest`（与 App 的 `video.test.ts` 用例相同）：各种 YouTube / Vimeo 地址，及不是单个视频、别的网站、带用户名或端口、`javascript:` 等。
- `QuizSnapshotsTest`：只有该视频写出字段，其他快照的文本与哈希不变。
- `WatchVideoIT`：只能是视频、只能是 YouTube / Vimeo、每个模版一个、取消后可换另一个；版本内容带字段；上线后卡片与 `qb.app.feed` 有播放器地址，普通发布没有。
- `QuizbuksAppIT`：App 的策略只多 `frame-src` 两个播放器，商家后台与管理后台是缺省。
- App Vitest：首页的视频卡片（播放器地址、不自动播放、懒加载、奖励文字、开始即 `QB_ATTEMPT_START` 并进入答题），问卷的文字，服务器给的其他地址不嵌入；详情页的 YouTube 视频嵌入。
- 商家 Vitest：只有视频资料有复选框，保存发送 `watchToAnswer`。
- e2e `watch-video.spec.ts`：商家在后台勾选；上线后答题人首页的卡片有播放器（播放器以桩页面回答，不访问 YouTube），axe，"开始答题"进入第一题。

## 3. 风险

- 视频网站的 Cookie 与跟踪：YouTube 用无 Cookie 域名；Vimeo 本身不设广告 Cookie，播放后仍可能设置分析 Cookie（隐私政策需要提到嵌入的视频播放器，交法务确认）。
- 视频被删除或设为不可嵌入：播放器自己显示错误；答题不受影响。
- "看视频即可答题"只是显示方式：平台不检查答题人是否看完视频。
