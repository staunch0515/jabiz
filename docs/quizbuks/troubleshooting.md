# QuizBuks 使用中的问题与解决办法

这里集中记录**使用者（学者、发起方、店员、运营商、管理员）实际遇到的问题**：现象、原因、使用者自己能怎么解决、我们修了没有。

- **这是总账**：遇到新问题，先在这里加一条，再决定要不要修代码。修好后不删条目，把"状态"改成已修复并写明版本或日期；只有现象再也不会出现、也不会有人再问时，才把条目移到文末的"已归档"。
- **给使用者看的地方是各前端的"帮助"页**（App `/taker/help`、发起方后台 `/sponsor/help`）。帮助页读的是 FAQ（`QbFaqEntry`），由内容管理员在管理后台"FAQ"中编辑与发布，改了不用重新部署。每条下面的"帮助页文案"可以直接复制成一条 FAQ（三种语言，Markdown）；"对象"和"分类"就是 FAQ 的 `audience` 和 `category`。
- 只对内部（测试、运维）有用的条目标"内部"，不放进帮助页；运维的故障处理照旧写在 `operations.md`。

| # | 现象 | 对象 | 状态 |
|---|---|---|---|
| T1 | 发起方后台添加通行密钥时，点邮件中的确认链接，提示"请先在本设备上登录" | 发起方、运营商、管理员 | 有绕过办法，待修复 |
| T2 | 在微信或 QQ 里打开页面，通行密钥（指纹、刷脸登录）用不了 | 全部 | 设计如此 |
| T3 | 在测试环境添加的通行密钥，到正式站点不能用 | 内部 | 设计如此 |
| T4 | 视频资料的"看完这个视频即可答题"勾不上 | 发起方 | 设计如此（Q99） |
| T5 | 首页卡片上的优酷、腾讯视频不自动播放；其他网站的视频没有声音 | 学者、发起方 | 设计如此（Q99b） |
| T6 | 在中国扫不出兑换奖品的轮换码 | 学者、店员 | 已修复（2026-11-04）；与 T12 合并，待真机确认后一起归档 |
| T7 | 导入 Quiz 文件失败，只说"出错了，请重试" | 发起方 | 已修复（2026-11-04） |
| T8 | 部署失败：`application not healthy after 3m0s`，回滚也失败 | 内部（运维） | 服务器磁盘满，待清理 |
| T9 | 管理后台找不到 AI 获客的平台开关（`qb.prospect.*`），设了"每天获客"也不执行 | 内部（管理员） | 有办法，待改进（现在不做） |
| T10 | 公开主页的"链接名"填了官网地址，提示"须为 3 至 30 个小写字母、数字或连字符" | 发起方 | 已改进（F-F9）：输入网址时提示并建议链接名；问答发布后归档 |

---

## T1 发起方后台添加通行密钥：邮件链接提示"请先在本设备上登录"

- **对象**：发起方（同样适用于运营商后台、管理后台）。FAQ：`SPONSOR` / `ONBOARDING`
- **状态**：有绕过办法；彻底修复需要改平台的确认页（待需求方决定，见"修复"）
- **现象**：账号没有密码（用邮件链接或 Google 等登录）的人，在"账户 → 通行密钥"点"添加通行密钥"，系统发确认邮件；点邮件里的链接后，页面显示"请先在本设备上登录，再重新打开邮件中的链接"。按提示登录、再点链接，还是一样。
- **原因**：为了安全，发起方后台、运营商后台、管理后台的登录**只在当前标签页有效**（`jabiz.security.entries.<入口>.session.persistence: tab`）。点邮件链接时浏览器会开一个新标签页，新标签页没有登录。学者 App 的登录在整台设备上有效（`device`），所以 App 不会出现这个问题。
- **使用者的解决办法**：
  1. 回到已经登录发起方后台的那个标签页；
  2. 在邮件里右键点链接，选"复制链接地址"（手机上长按链接）；
  3. 粘贴到这个标签页的地址栏，打开；
  4. 点"添加通行密钥"，按指纹或刷脸。
  链接在点"添加"之前不会用掉；如果提示失效，在账户页重新发一封。
- **修复**：建议在平台 `1.3/platform` 上让确认页在未登录的新标签页中，把确认交给同一浏览器里已登录的标签页（同源的 `BroadcastChannel`），由那个标签页显示"继续添加通行密钥"。不建议把发起方后台改成整台设备保持登录（发起方后台有充值与资金操作）。

**帮助页文案**

- 中文 · 问：添加通行密钥时，点了邮件里的链接却提示"请先在本设备上登录"，怎么办？
  答：发起方后台的登录只在当前浏览器标签页有效，而点邮件链接会打开一个新标签页。请回到已登录的那个标签页，在邮件中复制链接地址（右键"复制链接地址"，手机上长按），粘贴到这个标签页的地址栏打开，再点"添加通行密钥"。
- 日本語 · 問：パスキーを追加するとき、メールのリンクを開くと「まずこの端末でログインしてから」と表示されます。
  答：主催者管理画面のログインは、ログインしたブラウザのタブの中だけで有効です。メールのリンクは新しいタブで開くため、ログインしていない状態になります。ログイン済みのタブに戻り、メールのリンクのアドレスをコピーして（右クリックで「リンクのアドレスをコピー」、スマートフォンでは長押し）、そのタブのアドレスバーに貼り付けて開き、「パスキーを追加」を押してください。
- English · Q: When I add a passkey, the link in the mail says "Sign in on this device first". What do I do?
  A: In the sponsor console you stay signed in only in the browser tab where you signed in, and a link in a mail opens a new tab. Go back to the tab where you are signed in, copy the link's address from the mail (right-click "Copy link address", or long-press on a phone), paste it into that tab's address bar, open it and press "Add a passkey".

---

## T2 在微信或 QQ 里通行密钥用不了

- **对象**：全部。FAQ：`ALL` / `ACCOUNT`
- **状态**：设计如此（微信、QQ 等应用的内置浏览器不支持通行密钥）
- **现象**：在微信、QQ 里打开的页面，没有"用通行密钥登录"，或者提示"请在浏览器中打开本页"。
- **使用者的解决办法**：点右上角"···"，选"在浏览器打开"，在手机自带的浏览器（Safari、Chrome 等）里登录或添加。

**帮助页文案**

- 中文 · 问：在微信里为什么不能用指纹或刷脸登录？
  答：微信、QQ 等应用里的浏览器不支持通行密钥。请点右上角的"···"，选择"在浏览器打开"，在 Safari、Chrome 等浏览器里使用。
- 日本語 · 問：WeChat の中で指紋や顔認証のログインが使えません。
  答：WeChat や QQ などのアプリ内ブラウザはパスキーに対応していません。右上の「···」から「ブラウザで開く」を選び、Safari や Chrome などで使ってください。
- English · Q: Why can't I sign in with my fingerprint or face inside WeChat?
  A: The browsers inside apps such as WeChat and QQ do not support passkeys. Tap "···" at the top right, choose "Open in browser", and use it in Safari, Chrome or another browser.

---

## T3 测试环境的通行密钥到正式站点不能用（内部）

- **状态**：设计如此（Q97，D-Q97-2）
- 通行密钥绑定网站的地址：staging（`staging.<域名>`）与正式站点各是一个依赖方，互不通用。在哪个环境用，就在哪个环境添加。正式站点的地址一旦有人添加了通行密钥就不能再改（改了全部失效），换域名前先通知用户。
- 同一个人在 App、发起方后台、运营商后台、管理后台各自添加：一个入口的通行密钥不能登录另一个入口。

---

## T4 视频资料的"看完这个视频即可答题"勾不上

- **对象**：发起方。FAQ：`SPONSOR` / `PUBLISHING`
- **状态**：设计如此（Q99，`plans/Q99-more-video-sites.md`）
- **现象**：视频地址是哔哩哔哩、niconico、Dailymotion、优酷、腾讯视频的，勾选框是灰的，下面写着"这个网站的播放器不能告诉 App 看了多少"。导入的文件里对这类视频写了 `watchToAnswer: true` 时，导入报 `QB_MATERIAL_VIDEO_NOT_EMBEDDABLE`。
- **原因**："看完才能答题"要知道学者看了多少，只有 YouTube 和 Vimeo 的播放器会告诉页面。
- **使用者的解决办法**：要求看完，就把视频放到 YouTube 或 Vimeo 并填那个地址；不要求看完，直接用其他网站的地址，视频照样在 App 中播放。"看完才能答题"本来就是可选项。

**帮助页文案**

- 中文 · 问：为什么哔哩哔哩等网站的视频不能设为"看完才能答题"？
  答：只有 YouTube 和 Vimeo 的播放器能告诉我们学者看了多少，所以只有它们能要求看完再答题。其他网站（哔哩哔哩、niconico、Dailymotion、优酷、腾讯视频）的视频照样能在 App 中播放，只是不能要求看完。需要看完的视频请上传到 YouTube 或 Vimeo。
- 日本語 · 問：ニコニコ動画などの動画を「見終わってから回答」にできないのはなぜですか。
  答：どこまで見たかを伝えるのは YouTube と Vimeo のプレーヤーだけなので、見終わってからの回答にできるのはこの 2 つだけです。ほかのサイト（ニコニコ動画、bilibili、Dailymotion、Youku、Tencent Video）の動画も App で再生できますが、見終わることを条件にはできません。必要な動画は YouTube か Vimeo にアップロードしてください。
- English · Q: Why can't a Bilibili or Niconico video be one takers must watch before answering?
  A: Only YouTube's and Vimeo's players tell us how much was watched, so only their videos can be required. Videos of the other sites (Bilibili, Niconico, Dailymotion, Youku, Tencent Video) still play in the App; they just cannot be required. Put a video that must be watched on YouTube or Vimeo.

---

## T5 首页卡片上的优酷、腾讯视频不自动播放；其他网站的视频没有声音

- **对象**：学者、发起方。FAQ：`ALL` / `OTHER`
- **状态**：设计如此（Q99b，D-Q99b-1，2026-11-05 起；Q99 时其他网站的视频在首页都不播放，已改）
- **现象**：首页中间的卡片与开始页上，各网站的视频都静音自动播放；只有优酷、腾讯视频的播放器不接受自动播放的设置，显示它自己的播放按钮。
  YouTube、Vimeo 点一下卡片就开声音；哔哩哔哩、niconico、Dailymotion 等要按播放器自己的声音按钮。
- **原因**：浏览器只允许静音的视频自动播放；点一下开声音要靠播放器的接口，只有 YouTube 和 Vimeo 提供。优酷、腾讯视频的嵌入播放器不支持静音自动播放的参数。
- **使用者的解决办法**：优酷、腾讯视频在卡片上点一下播放按钮即在首页播放；没有声音时按播放器上的声音按钮。

**帮助页文案**

- 中文 · 问：为什么有的视频没有声音，或者不自动播放？
  答：视频都是静音开始播放的，请按播放器上的声音按钮打开声音（YouTube、Vimeo 的视频点一下即有声音）。优酷、腾讯视频的视频不会自动播放，请点一下视频上的播放按钮。
- 日本語 · 問：音が出ない動画や、自動再生されない動画があるのはなぜですか。
  答：動画はミュートで再生が始まります。プレーヤーの音量ボタンで音を出してください（YouTube と Vimeo の動画はタップすると音が出ます）。Youku と Tencent Video の動画は自動再生されないので、動画の再生ボタンを押してください。
- English · Q: Why does a video play without sound, or not start by itself?
  A: Videos start muted: turn the sound on with the player's own button (YouTube and Vimeo videos get their sound with a tap). Videos from Youku and Tencent Video do not start by themselves: press the play button on the video.

---

## T6 在中国扫不出兑换奖品的轮换码

- **对象**：学者、店员。FAQ：`APP` / `REWARDS`
- **状态**：已修复（2026-11-04，提交 877171c1）
- **原因**：App 的"扫一扫"先用浏览器的条码识别（Android 上由 Google Play 服务提供）。在中国销售的手机大多没有它，或下载不了识别模型，但浏览器仍说自己能识别，于是一直识别失败。现在识别失败或连续认不出时，改用 App 自带的识别。
- **使用者的解决办法**（修复后仍不行时）：刷新页面或重新打开 App，确保是新版本；光线充足、码完整在取景框里；也可以直接用手机相机扫码，打开链接。

**帮助页文案**：与 T12 合成一条，见 T12（F-F7）。

---

## T7 导入 Quiz 文件失败，只说"出错了，请重试"

- **对象**：发起方。FAQ：`SPONSOR` / `PUBLISHING`
- **状态**：已修复（2026-11-04，提交 0297a1af）：现在每个问题都写明是哪一步（第几个文件、哪个键，或建立草稿）和原因。
- **常见原因**：文件中图片的数据不是有效的 base64；单个文件过大；用其他 AI 生成的文件格式不对。让其他 AI 生成文件时，用 `quiz-file-prompts.md` 中的提示词模板。
- **使用者的解决办法**：按提示改文件后重新导入；看不懂提示时，把文件与截图交给客服。

**帮助页文案**

- 中文 · 问：导入 Quiz 文件失败怎么办？
  答：导入页会列出每个问题出在哪一步、是哪个文件或哪一项，以及原因（例如图片数据损坏、文件过大）。请按提示修改文件后重新导入。如果文件是用其他 AI 生成的，可以向客服索取生成 Quiz 文件的提示词模板。仍不明白时，请把文件和画面截图发给客服。
- 日本語 · 問：クイズファイルの取り込みに失敗しました。
  答：取り込み画面に、どの手順のどのファイル・項目で、何が原因か（画像データの破損、ファイルが大きすぎるなど）が表示されます。表示に従ってファイルを直し、もう一度取り込んでください。ほかの AI でファイルを作るときのプロンプトのひな形は、サポートにお問い合わせください。わからないときは、ファイルと画面のスクリーンショットをサポートに送ってください。
- English · Q: Importing a quiz file failed. What now?
  A: The import page lists, for each problem, the step, the file or item, and the reason (for example broken image data or a file too large). Fix the file as it says and import it again. To have another AI write the file, ask support for our prompt templates. If it is still unclear, send the file and a screenshot to support.

---

## T8 部署失败：健康检查一直 503，回滚也失败（内部）

- **状态**：2026-11-05 staging 出现；原因是服务器磁盘满，不是代码问题。
- **现象**：`deploy-app.sh` 报 `application not healthy after 3m0s`，自动回滚到上一个版本也一样失败；容器日志反复出现
  `DiskSpaceHealthIndicator … Free disk space at path '/app/.' below threshold. Available: 0 bytes`，`/actuator/health` 一直 503。应用本身已经启动（定时任务照常 `SUCCEEDED`）。
- **原因**：Spring 的磁盘检查在可用空间低于 10 MB 时把健康状态判为不健康。容器的文件系统就是服务器的磁盘，每次部署都拉一个新镜像，旧镜像不会自动删除，磁盘会慢慢被占满。磁盘满时上传文件、数据库写入也会失败，所以这个检查应当保留。
- **处理**：在服务器上查看占用（`df -h /`、`docker system df`），删除不用的旧镜像（只删没有容器在用的），再重新部署（Actions → QuizBuks deploy → Run workflow，分支 `1.3/quizbuks`，`image-tag` 留空）。同一台服务器上的数据库也在这块盘上的话，先确认它还能写入。
- **预防（待决定）**：部署成功后自动只保留本应用最近几个镜像；监控中加磁盘告警（`operations.md` §5 已有数据库磁盘 > 80 % 的告警，应用服务器还没有）。

## T9 管理后台找不到 AI 获客的平台开关，设好了也不执行（内部）

- **状态**：2026-11-05 staging 出现；需求方已按下面的办法设好。界面的两项改进记为待做，**现在不做**（需求方 2026-11-05）。
- **现象**：「AI 获客 → 设置」里发件人、邮件服务器、搜索方向、"每天获客"都设好了，却一直没有搜索；去"数据 → SysControlChange"找开关，列表每行都只写"First value of the QuizBuks parameter, proposed by QB_SETUP"，看不出是哪个参数。
- **原因**：
  - 获客另有三个平台的受控参数 `qb.prospect.enabled`（缺省 `false`）、`qb.prospect.model`、`qb.prospect.effort`，`enabled` 不是 `true` 时搜索与研究的作业都不运行（逐项流程 `QB_PROSPECT_FIND_START` 输出 `switched-off`）；受控参数只经四眼的受控变更修改。
  - 「AI 获客 → 设置」页不显示也不能提出这三个参数（AI 出题的 `/qb/ai` 页有"提出开启 / 关闭"，获客页没有）；平台的 `SysControlChange` 列表不显示参数名。
  - 另外：搜索每天只在 UTC 23:17（日本 08:17）运行一次，错过的时刻不补跑；"自动批准的评分"留空时草稿全部等人工批准。
- **处理**：
  1. 看现值：后台"数据 → SysParam"（`urn:jabiz:dataset:platform:SysParam`），按 `paramKey` 筛 `qb.prospect`。
  2. 提出：后台流程 `CONTROL_CHANGE_PROPOSE`（`/admin/processes/CONTROL_CHANGE_PROPOSE/1`），`targetEntity` = `SysParam`，`values` = `{"paramKey": "qb.prospect.enabled", "value": "true"}`，填理由；要换模型或强度时同样各提一次。
  3. 发布：另一位持有 `control.publish` 的管理员（不是提出人）在"数据 → SysControlChange"中把 status 筛为 `PROPOSED`，对该行执行 `CONTROL_CHANGE_PUBLISH`。
- **待做（现在不做）**：
  1. **应用（`1.3/quizbuks`，约半天）**：「AI 获客 → 设置」（后台扩展 `ProspectSettingsPage.tsx`）照 `/qb/ai`（`AiPage.tsx` 的 `proposeSwitch`）加"平台开关"：显示三个参数的现行值与待发布的提案，持有 `control.propose` 的人可提出开启 / 关闭与模型、强度（理由必填），发布仍由另一位管理员；附 `prospect.test.tsx` 的用例与 axe。
  2. **平台（`1.3/platform`）**：通用后台的 `SysControlChange` 列表显示目标（参数的 `paramKey`），并可按它筛选。

## T10 公开主页的"链接名"填了官网地址，提示格式不对

- **状态**：2026-11-05 staging 出现（填了 `https://www.aini-helmet.com/`）。不是故障，是两个栏目容易混淆。Q104（从官网生成）会把官网地址放进"网站"、由域名得出链接名，从根本上避开。**已改进（F-F9）**：在链接名中输入或粘贴网址时，页面提示"这是本平台上的地址名"，并给出由域名得出的建议（如 `aini-helmet`），一键填入，"网站"为空时同时把网址移过去；中文域名不给建议。帮助中心发布下面的问答后归档。
- **现象**：主页编辑的"链接名"填了官网的网址，下面提示"链接名须为 3 至 30 个小写字母、数字或连字符，不能以连字符开头或结尾"。
- **原因**："链接名"是主页在本平台**里面**的短名字（主页地址为 `/taker/s/<链接名>`），不是自己的网站；自己网站的地址填在同一页下面的"网站"一栏。
- **处理**：链接名填 `aini-helmet` 这样的短名字（可以取域名中的那一段），"网站"填 `https://www.aini-helmet.com/`。
- **帮助页文案**（对象：发起方；分类：公开主页）：
  - 中文 · 问：链接名填网址为什么不行？
    答："链接名"是你的主页在本平台上的地址名，只能用 3 至 30 个小写字母、数字或连字符，例如 `aini-helmet`，主页地址就是 `…/taker/s/aini-helmet`。你自己的网站地址请填在同一页的"网站"一栏，主页上会显示它的链接。
  - 日本語 · 問：リンク名に URL を入れるとエラーになります。
    答：「リンク名」は本サービス上のページの名前で、英小文字・数字・ハイフンの 3〜30 文字です（例：`aini-helmet`、ページは `…/taker/s/aini-helmet`）。自社サイトの URL は同じページの「ウェブサイト」欄に入れてください。ページにリンクが表示されます。
  - English · Q: Why can't I put my website's address as the link name?
    A: The link name is your page's name on this service: 3 to 30 lowercase letters, digits or hyphens, for example `aini-helmet` (your page is then `…/taker/s/aini-helmet`). Put your own website's address in the "Website" field on the same page; your page links to it.

## T11 手机上哔哩哔哩的视频是空白（首页与开始页）

- **状态**：2026-11-05 staging 出现（iPhone）；同日修复：App 与发起方后台的内容安全策略允许哔哩哔哩自己的主机 `www.bilibili.com`、`m.bilibili.com`（Q99 §5）。
- **现象**：电脑上的发起方后台预览能播放，手机 App 的首页卡片与开始答题页的哔哩哔哩视频只有一块空白。
- **原因**：哔哩哔哩的嵌入播放器在手机上会跳到 `www.bilibili.com/blackboard/…` 的移动播放器，而页面只允许 `player.bilibili.com` 嵌入，跳转被浏览器拦下。
- **使用者的解决办法**：无需操作；更新后重新打开 App 即可。若仍空白，在手机上直接打开视频页面的地址确认视频本身可以公开播放。

## T12 App 的"扫一扫"扫不到发起方的轮换码（中国的安卓手机与 iPhone）

- **对象**：学者、店员。FAQ：`APP` / `REWARDS`（与 T6 合成一条）
- **状态**：2026-11-04 首次报告，当日按"没有 Google Play 服务"修复（Q52 §5），未解决；2026-11-05 再次出现，找到真正的原因并修复（Q52 §6，Q52b，PR 458 已合入 `1.3/quizbuks`）。**待在中国的安卓手机与 iPhone 上确认**（F-F7），确认后 T6、T12 一起移入"已归档"。
- **现象**：用 App 的"扫一扫"对着发起方屏幕上的码，一直在取景，没有任何反应。
- **原因**：相机只以 640 × 480 拍摄，码在画面中太小，这些手机所用的解码程序读不出。现在以 1920 × 1080 拍摄，画面中间按原尺寸识别（`ScanPage.tsx` 的 `CAMERA`、`decoder.ts` 的 `SCRIPT_EDGE` / `MIDDLE_SHARE`），码的四周留出标准的空白。
- **使用者的解决办法**：更新后重新打开 App 再扫。仍不行时把手机拿近一些（码占满取景框）、让发起方把屏幕调亮，或用手机自带的相机扫码。
- **真机确认记录**（F-F7 验收：一台国产安卓、一台 iPhone，扫 staging 发起方后台"兑换"页的轮换码）：

  | 日期 | 机型 | 系统与浏览器 / App | 结果 | 确认人 |
  |---|---|---|---|---|
  | | | | | |

**帮助页文案**（T6 与 T12 合成一条；对象：学者、店员；分类：`REWARDS`）

- 中文 · 问：扫不出兑换奖品的二维码怎么办？
  答：请先刷新页面或重新打开 App，确保是最新版本。扫码时让二维码完整地出现在取景框里，手机离屏幕近一些，光线充足；屏幕太暗时请发起方调亮屏幕。仍然不行时，可以用手机自带的相机扫码，打开出现的链接。
- 日本語 · 問：特典の引き換えコードが読み取れません。
  答：まずページを再読み込みするか、App を開き直して最新の状態にしてください。コード全体が枠に入るように、画面に少し近づけて、明るい場所で読み取ってください。画面が暗いときは主催者に明るくしてもらってください。それでも読み取れないときは、スマートフォンのカメラで読み取り、表示されたリンクを開いてください。
- English · Q: The reward's QR code does not scan. What can I do?
  A: Reload the page or reopen the App first, so that it is up to date. Keep the whole code in the frame, a little closer to the screen, in good light; if the screen is dark, ask the Sponsor to make it brighter. If it still does not scan, scan it with your phone's camera and open the link it shows.

## T13 刷新页面时偶尔被登出（e2e 的报名费用例偶发失败即此）

- **状态**：2026-11-06 由 e2e 的失败找到原因（PR #458 的 CI，`entry-fee.spec.ts`），**未修复**；修复在平台（刷新令牌的重用宽限），待需求方确认后在 `1.3/platform` 上做。
- **现象**：发起方后台或 App 中刷新页面（或在页面正在续期登录时关闭、刷新）之后，偶尔回到登录页，要重新登录。e2e 中：测试每 5 秒刷新一次钱包页等报名费入账，中途被登出，停在登录页直到超时（不是入账任务的问题：任务每分钟按时运行）。
- **原因**：登录的续期每次换一个新的刷新令牌（轮换），旧的作废；同一个旧令牌再次出现即视为被盗、整个会话吊销（服务器日志 `Refresh token of user … presented twice; revoking its session`）。页面在续期请求发出后、收到新令牌前被刷新或关闭，新令牌没存下，下一页拿旧令牌续期，就被当成重用。多个标签页之间已经用 Web Locks 排队（`@jabiz/client` 的 `withRefreshLock`），但页面卸载时锁随之释放，管不到这种情况。
- **修复方向**（平台，需决策）：刷新令牌的重用宽限：被轮换掉的令牌在很短的时间内（如 30 秒，可配置）再出现时，不吊销会话，而是给出同一个后继（或作废后继、再发一个），超过宽限才按被盗处理；同时 e2e 的轮询改为等页面稳定后再刷新。
- **使用者的解决办法**：重新登录即可，数据不受影响。

## 已归档

（暂无）
