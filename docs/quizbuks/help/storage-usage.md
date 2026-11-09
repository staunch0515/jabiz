# 帮助文案：资源占用与免费额度（F-C2）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
Q109 的使用手册（F-C10）建立时，把下面的"帮助页"一节原样放进发起方的手册（`zh` / `ja` / `en` 各一份）。用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。

- 对象：发起方（FAQ 的 `audience` = `SPONSOR`）
- 分类：`PUBLISHING`
- 页面：发起方页面的"资源库"顶部的用量卡（`/sponsor/assets`）
- 参数（写进文案的数字是缺省值，改了参数时同步改这里）：`qb.usage.storage.free-bytes` = 524288000（500 MB，受控参数）；快照每天一次（部署配置 `quizbuks.usage.snapshot-time`，缺省东京时间 03:17）

## 帮助页

### 中文 · 存储用量与免费额度

**在哪里看**：资源库页面的顶部显示"已用 X / 免费 Y"、文件数和资源数，以及统计的日期。用量每天统计一次，今天新上传的文件明天才计入。

**怎样计**：按你上传的原文件计，不含缩略图等变体。同一个文件不论用在几处（资源、Quiz 的封面与资料、发布）只算一次，算在上传它的账号上。YouTube 等外部视频链接不占存储，只计个数。

**免费额度**：每个发起方有 500 MB 的免费存储。**首次上线不收费**：超过免费额度只会提示，上传和已有的内容照常可用，不会被拒绝。

**超过以后**：资源库顶部会显示一条提示；第一次超过时你还会在站内信中收到一条通知（同一次超过只通知一次；降到额度以下后再次超过会再通知）。

**怎样腾出空间**：在资源库的列表中按"大小"排序，找出占用大的资源；勾选"只看未使用的资源"，找出没有复制到任何 Quiz 或发布的资源，删掉不需要的。删除资源不影响已经复制到 Quiz 和发布中的内容；文件不再被任何地方使用时由系统清理，次日的用量随之变小。

### 日本語 · ストレージの使用量と無料枠

**どこで見るか**：リソースライブラリの上部に「使用中 X / 無料枠 Y」、ファイル数、リソース数と集計日が表示されます。使用量は 1 日 1 回集計され、今日アップロードしたファイルは翌日から反映されます。

**数え方**：アップロードした元のファイルで数え、サムネイルなどの派生ファイルは含みません。同じファイルは何か所で使っても（リソース、クイズのカバーや資料、公開）1 回だけ数え、アップロードしたアカウントの分になります。YouTube などの外部の動画リンクはストレージを使わず、件数だけを数えます。

**無料枠**：主催者ごとに 500 MB の無料ストレージがあります。**初回リリースでは料金はかかりません**。無料枠を超えてもお知らせが表示されるだけで、アップロードや既存の内容はこれまでどおり使え、拒否されることはありません。

**超えたとき**：リソースライブラリの上部にお知らせが表示されます。初めて超えたときは、メッセージでもお知らせが届きます（同じ超過では 1 回だけ。無料枠を下回ってから再び超えたときは、もう一度届きます）。

**容量を空けるには**：リソースライブラリの一覧を「サイズ」で並べ替えて、大きなリソースを探します。「未使用のリソースだけ」にチェックを入れると、どのクイズや公開にもコピーされていないリソースが表示されるので、不要なものを削除してください。リソースを削除しても、クイズや公開にコピー済みの内容はそのままです。どこからも使われなくなったファイルはシステムが整理し、翌日の使用量に反映されます。

### English · Storage usage and the free amount

**Where to see it**: the top of the asset library shows "Used X of Y free", the number of files and of assets, and the day they were counted. Usage is counted once a day; a file uploaded today counts from tomorrow.

**How it is counted**: files count as you uploaded them, without thumbnails or other variants. A file counts once however many places use it (assets, quiz covers and materials, publications), for the account that uploaded it. Links to outside videos such as YouTube take no storage; only their number is counted.

**The free amount**: each Sponsor has 500 MB of storage free of charge. **Nothing is charged in the first launch**: above the free amount you only see a notice; uploads and your content keep working and nothing is refused.

**When you are above it**: the top of the asset library shows a notice, and the first time you go above it you also get a message in your inbox (once for the same crossing; going below and above again sends another).

**Freeing space**: sort the library's list by "Size" to find the largest assets, and tick "Only the unused assets" to find those copied into no quiz or publication; delete the ones you no longer need. Deleting an asset leaves what was copied into your quizzes and publications as it is; a file nothing uses any more is cleaned up by the system, and the next day's usage goes down.

## FAQ（一条）

- 中文 · 问：超过了 500 MB 的免费存储会怎样？要付费吗？
  答：首次上线不收费。超过免费额度只会在资源库顶部提示，第一次超过时另有一条站内信；上传和已有的内容照常可用。用量按上传的原文件计（不含缩略图等变体），同一个文件不论用在几处只算一次，外部视频链接不占存储，每天统计一次。要腾出空间，可在资源库中按"大小"排序，或勾选"只看未使用的资源"，删掉不需要的资源。
- 日本語 · 問：500 MB の無料ストレージを超えるとどうなりますか？料金はかかりますか？
  答：初回リリースでは料金はかかりません。無料枠を超えるとリソースライブラリの上部にお知らせが表示され、初めて超えたときはメッセージも届きますが、アップロードや既存の内容はこれまでどおり使えます。使用量はアップロードした元のファイルで数え（サムネイルなどの派生ファイルは含みません）、同じファイルは何か所で使っても 1 回だけ、外部の動画リンクはストレージを使いません。集計は 1 日 1 回です。容量を空けるには、リソースライブラリで「サイズ」順に並べるか「未使用のリソースだけ」にチェックを入れ、不要なリソースを削除してください。
- English · Q: What happens above the 500 MB of free storage? Do I have to pay?
  A: Nothing is charged in the first launch. Above the free amount the asset library shows a notice, and the first time you also get a message in your inbox; uploads and your content keep working. Usage counts the files as uploaded (without thumbnails or other variants), each file once however many places use it, and links to outside videos take no storage; it is counted once a day. To free space, sort the asset library by "Size" or tick "Only the unused assets", and delete what you no longer need.
