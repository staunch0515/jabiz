# 帮助文案：发起方包（F-C4）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-C4.md`、`operations.md` §7.33。

- 对象：发起方（FAQ 的 `audience` = `SPONSOR`）
- 分类：`PUBLISHING`
- 页面：发起方资料的"概况"页（`/sponsor/profile`）页头的"导入与导出"菜单；发布列表的"提交全部草稿"
- 参数（写进文案的是缺省值，改了参数时同步改这里）：超过 100 MB 的导出先确认（`qb.package.confirm-bytes`）；导入记录保留 90 天（`qb.package.import-retention-days`）

## 帮助页

### 中文 · 发起方包：把资料、Quiz 与发布搬到另一个账户

**是什么**：发起方包是一个文件，里面有你的资料（基本信息、主页、商品、邮件服务器的设置，不含密码）、全部 Quiz（连同正在用的发布所用的版本）、没有结束的发布的设置，以及你的知识结构图。用它可以把内容导入另一个账户或另一个环境。只带内容与设置：不带审核结果、答题与奖励的记录、钱包与交易、店员、码与邀请名单，也不带资源库。

**导出**：在"概况"页头点"导入与导出"，选"发起方包"下的"导出发起方包"。文件超过 100 MB 时会先问你是否保存。

**导入**：点"导入发起方包"并选择文件。页面先检查整个包，一次列出全部问题与将要生成的内容（知识结构图、Quiz 与版本、发布草稿）；有问题时先解决再导入。确认之前什么也不写入。
- 从未提交过发布的账户可以导入整个包；已有的 Quiz 草稿不影响，标题相同的会标出来（导入的另外新建）。
- 提交过发布的账户只能"只导入资料部分"。
- 资料部分中以链接给出的图片，检查页会列出；导入时由 QuizBuks 下载，下载不了的保留原来的图片，请导入后在页面上设置。
- 你的账户还没有提交过知识结构图时，包里的图导入为草稿；已经有的，不导入图，发布按节点对应到你的图中相同的节点，对应不上的会列出来，导入后请自己选节点。
- 共享视频只在这里也有同一个已共享的视频时才挂上，否则会列出来，导入后请重新选择。
- 发布一律导入为草稿，开始时间是导入时的下一个整点，天数与原来相同。

**导入以后要逐个审核**：导入以后一切都照常审核，与手填的一样：入驻申请、主页、知识结构图和每个发布都要逐个审核，没有免审。导入完成后点"提交全部草稿"（发布列表上也有这个按钮）：先提交知识结构图，再逐个提交发布；不能提交的会跳过并说明原因（例如账户余额不足、发布绑定的图还没生效）。图审核通过后再点一次即可提交其余的发布。

**中断后继续或放弃**：导入一步一步进行，页面显示进行到第几步。中途停了（网络断开、某一步被拒），已完成的步骤保留；排除原因后再选同一个文件点"继续"，从停下的地方接着导入，不会生成两份。不想继续时点"放弃导入"：这次导入生成的知识结构图、Quiz 和发布草稿会被删除；资料部分保持导入后的样子。

### 日本語 · 主催者パッケージ：プロフィール、クイズ、公開を別のアカウントへ

**とは**：主催者パッケージは 1 つのファイルで、プロフィール（基本情報、ページ、商品、メールサーバーの設定。パスワードは含みません）、すべてのクイズ（使用中の公開が使っているバージョンを含む）、終了していない公開の設定、知識マップが入っています。別のアカウントや別の環境に内容をインポートできます。内容と設定だけを持ち運び、審査結果、回答と報酬の記録、ウォレットと取引、スタッフ、コードと招待リスト、リソースライブラリは含みません。

**エクスポート**：「概要」ページ上部の「インポートとエクスポート」から「主催者パッケージ」の「主催者パッケージをエクスポート」を押します。ファイルが 100 MB を超えるときは、保存の前に確認があります。

**インポート**：「主催者パッケージをインポート」を押してファイルを選びます。まずパッケージ全体を確認し、すべての問題と作成するもの（知識マップ、クイズとバージョン、公開の下書き）を一覧で表示します。問題があるときは先に解決してください。確定するまで何も書き込みません。
- 公開を申請したことのないアカウントはパッケージ全体をインポートできます。既存のクイズの下書きは影響しません。同じタイトルのものは表示されます（インポートでは別に新しく作成します）。
- 公開を申請したことのあるアカウントは「プロフィール部分だけをインポート」できます。
- プロフィール部分でリンクとして指定された画像は確認画面に一覧表示され、インポート時に QuizBuks がダウンロードします。ダウンロードできなかった画像は元のままなので、インポート後にページで設定してください。
- 知識マップを申請したことがまだないときは、パッケージの知識マップを下書きとしてインポートします。すでにあるときは知識マップをインポートせず、公開をあなたのマップの同じノードに結び付けます。結び付けられないものは一覧で表示されるので、インポート後にノードを選んでください。
- 共有動画は、ここにも同じ共有動画が共有されているときだけ付けます。そうでないものは一覧で表示されるので、インポート後に選び直してください。
- 公開はすべて下書きとしてインポートします。開始日時はインポート時の次の正時、日数は元と同じです。

**インポートした後はひとつずつ審査されます**：インポートした後は、手で入力したときと同じように、主催者申請、ページ、知識マップ、各公開がひとつずつ審査されます。審査なしになるものはありません。インポートが終わったら「すべての下書きを申請」を押します（公開一覧にも同じボタンがあります）。まず知識マップ、次に公開をひとつずつ申請し、申請できないものは理由を示して飛ばします（残高不足、公開が結び付く知識マップがまだ有効でない、など）。知識マップが承認されたら、もう一度押すと残りの公開を申請できます。

**中断したときの再開と中止**：インポートは 1 ステップずつ進み、何ステップ目かが表示されます。途中で止まったとき（通信が切れた、あるステップが拒否された）は、完了したステップはそのまま残ります。原因を取り除いて同じファイルを選び直し「続ける」を押すと、止まったところから続けます。二重に作成されることはありません。続けないときは「インポートを中止」を押します。このインポートで作成した知識マップ、クイズ、公開の下書きは削除され、プロフィール部分はインポートしたままです。

### English · The sponsor package: bring your profile, quizzes and publications to another account

**What it is**: a sponsor package is one file holding your profile (basics, page, products and the mail server's settings, without its password), all your quizzes (with the versions your running publications use), the settings of your publications not ended, and your knowledge maps. With it you bring the content into another account or environment. Only content and settings travel: no review results, no records of answers or rewards, no wallet or transactions, no staff, codes or invitation lists, and not your asset library.

**Exporting**: on the "Overview" page, open "Import and export" in its head and under "Sponsor package" choose "Export sponsor package". A file over 100 MB asks before it is saved.

**Importing**: choose "Import sponsor package" and pick the file. The page first checks the whole package and lists every problem and what it will make (knowledge maps, quizzes and versions, publication drafts); fix the problems first. Nothing is written before you confirm.
- An account that never submitted a publication imports the whole package. Quiz drafts you have do not matter; those with the same title are pointed out (the import adds new ones).
- An account that submitted a publication imports only the profile part.
- Images given as links in the profile part are listed on the check page and downloaded by QuizBuks during the import; one that cannot be downloaded keeps the image there is, so set it on your page afterwards.
- When your account has not submitted a knowledge map yet, the package's maps are imported as drafts. When it has, no map is imported: the publications are bound to the same nodes of your map, and those that cannot be are listed: choose their nodes after the import.
- A shared video is attached only when the same video is shared here too; otherwise it is listed: choose one again after the import.
- Every publication is imported as a draft, starting at the next full hour after the import and running as many days as before.

**After an import, everything is reviewed one by one**: just as if you had entered it by hand, your application, your page, your knowledge map and each publication are reviewed one by one; nothing skips the review. When the import is done, choose "Submit all drafts" (the publication list has the same button): first the knowledge maps, then each publication; one that cannot be submitted is skipped with the reason (for example, not enough Kudos in your wallet, or the map a publication is bound to is not in effect yet). Once the map is approved, choose it again to submit the rest.

**Going on or giving up**: the import goes step by step, and the page shows which step it is on. If it stops half-way (the connection drops, a step is refused), the steps done stay. Remove the cause, pick the same file again and choose "Continue": it goes on where it stopped and never makes anything twice. If you do not want to go on, choose "Give up the import": the knowledge maps, quizzes and publication drafts this import made are deleted; the profile part stays as imported.
