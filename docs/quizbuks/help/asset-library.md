# 帮助文案：发起方的资源库（F-C1）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
Q109 的使用手册（F-C10）建立时，把下面的"帮助页"一节原样放进发起方的手册（`zh` / `ja` / `en` 各一份）。用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。

- 对象：发起方（FAQ 的 `audience` = `SPONSOR`）
- 分类：`PUBLISHING`
- 页面：发起方页面的菜单"资源库"（`/sponsor/assets`，标签：全部 / 素材 / 文字 / 奖品 / 商品，F-F3）
- 参数（写进文案的数字是缺省值，改了参数时同步改这里）：`qb.assets.max-count` = 1000、`qb.assets.max-tags` = 10、`qb.assets.tag-length` = 30；一次收集至多 200 项（`AssetRules.MAX_COLLECT`）

## 帮助页

### 中文 · 资源库

**资源库是什么**：你自己的图片、PDF、音频、视频链接、网页链接、文章、常用文字（Quiz 简介、到店兑换说明、电子奖品说明、推荐奖励说明）和奖品卡片，存一次，以后在 Quiz 和发布里反复用。可以搜索、加标签、按种类查看。菜单"资源库"。

**选了就复制**：在 Quiz 编辑器（封面、简介、资料、题目与选项的图片）和发布向导（封面、奖品、各说明）里点"从库里选"，资源的内容就复制到这一处，保存后生效。图片等文件不会多存一份，只是引用同一个文件。复制以后两边各管各的：改这一处不会改资源，改资源也不会改已经用了它的地方，已经提交的发布更不会变。复制的是资源的基准语言，其他语言的译文在这一处照常另做。

**"用的是旧的内容"**：在资源库里点某个资源的"用在哪里"，可以看到它被复制到了哪些地方。资源在复制之后又改过的，那一处会标"用的是旧的内容"。这只是提醒：要用新的内容，就到那一处重新"从库里选"并保存。

**存入资源库**：在上面这些地方也可以点"存入资源库"，把这一处现在的内容存成一个新资源，起个名字即可。

**从我的内容收集**：资源库页上的"从我的内容收集"会列出你的 Quiz 和发布里已经有、但还没在资源库里的内容。同一个文件、同样的文字只列一次，已经收进来的不再列出。勾选要保留的，点"收集"。只收你勾选的，以后也不会自动同步；可以随时再收集一次（一次至多列出 200 项）。

**删除资源**：删除只是从资源库里拿掉，已经复制到 Quiz 和发布里的内容不受影响。删除后不能恢复，需要时重新存入。

**商品**：原来在"发起方资料"中的"商品"搬到了资源库的"商品"标签，页面和用法都没有变；原来的"认证"已经停用。旧的地址会自动转到资源库。

**上限**：每个发起方至多 1000 个资源；每个资源至多 10 个标签，每个标签至多 30 个字。名称、说明、文字的长度和它们要复制到的地方相同（例如到店兑换说明的长度上限就是发布里那一栏的上限）。到了上限，先删除不用的资源再新建。

### 日本語 · リソースライブラリ

**リソースライブラリとは**：自分の画像、PDF、音声、動画リンク、Web リンク、記事、よく使うテキスト（クイズの紹介、店頭引き換えの説明、デジタル賞品の説明、紹介特典の説明）、賞品カードを一度保存し、クイズや公開で何度でも使えます。検索、タグ付け、種類ごとの表示ができます。メニューの「コンテンツ → リソースライブラリ」から開きます。

**選ぶとコピーされます**：クイズの編集画面（カバー、紹介、資料、問題と選択肢の画像）や公開の作成画面（カバー、賞品、各説明）で「ライブラリから選ぶ」を押すと、リソースの内容がその場所にコピーされ、保存すると反映されます。画像などのファイルは複製されず、同じファイルを参照します。コピーした後は別々に管理されます。その場所を変えてもリソースは変わらず、リソースを変えても使っている場所は変わりません。申請済みの公開も変わりません。コピーされるのはリソースの基本の言語の内容で、ほかの言語の翻訳はその場所で通常どおり作成します。

**「古い内容を使用中」**：リソースの「使用箇所」で、どこにコピーされたかを確認できます。コピーした後にリソースを変更した場合、その場所に「古い内容を使用中」と表示されます。お知らせだけなので、新しい内容にしたいときは、その場所でもう一度「ライブラリから選ぶ」で選び、保存してください。

**ライブラリに保存**：同じ場所で「ライブラリに保存」を押すと、その場所の今の内容を新しいリソースとして保存できます。名前を付けるだけです。

**自分のコンテンツから集める**：リソースライブラリの「自分のコンテンツから集める」は、クイズや公開にすでにあって、まだライブラリにない内容を一覧にします。同じファイル、同じテキストは一度だけ表示され、ライブラリに入れたものは表示されません。残したいものにチェックを入れて「集める」を押してください。チェックしたものだけが入り、自動で同期されることはありません。何度でも集め直せます（一度に表示されるのは 200 件まで）。

**リソースの削除**：削除はライブラリから外すだけで、クイズや公開にコピー済みの内容には影響しません。削除したリソースは元に戻せないので、必要なときはもう一度保存してください。

**商品**：以前「主催者情報」にあった「商品」は、リソースライブラリの「商品」タブに移りました。画面と使い方は変わりません。以前の「認定」は廃止されました。以前のアドレスはリソースライブラリに自動で移動します。

**上限**：リソースは 1 人の主催者につき 1000 件まで、タグは 1 件につき 10 個まで、タグ 1 つは 30 文字までです。名前、説明、テキストの長さの上限は、コピー先の欄と同じです（たとえば店頭引き換えの説明は、公開のその欄と同じ上限）。上限に達したら、使わないリソースを削除してから作成してください。

### English · Asset library

**What it is**: your own images, PDFs, audio, video links, web links, articles, texts you use again and again (a quiz's introduction, the in-store, digital and referral prize descriptions) and prize cards, kept once and used in your quizzes and publications as often as you like. You can search them, tag them and list them by kind. Open it from the menu: "Content → Asset library".

**Choosing copies**: in the quiz editor (cover, introduction, materials, question and option images) and the publication wizard (cover, prize, descriptions), "Choose from the library" copies the asset's content into that place; saving keeps it. Files such as images are not stored twice: the place refers to the same file. From then on each is on its own: changing the place does not change the asset, changing the asset does not change the places that use it, and a submitted publication never changes. What is copied is the asset's base language; translations are made in the place as usual.

**"Uses older content"**: "Where used" on an asset shows the places it was copied to. A place copied before the asset last changed is marked "Uses older content". It is only a reminder: to use the new content, choose the asset again in that place and save.

**Save to the library**: in the same places, "Save to the library" keeps what the place has now as a new asset; just give it a name.

**Collect from my content**: on the library page, "Collect from my content" lists what your quizzes and publications already have that is not in the library yet. Each file and each text is listed once, and what is in the library already is left out. Tick what to keep and press "Collect". Only what you tick is taken, and nothing is kept in sync by itself; you can collect again at any time (up to 200 are listed at once).

**Deleting an asset**: deleting only takes it out of the library; what was copied into your quizzes and publications stays as it is. A deleted asset cannot be brought back: save it again if you need it.

**Products**: "Products" (formerly in the Sponsor profile) is now the library's "Products" tab and works as before; the former "Certifications" were retired. The old addresses lead to the library.

**Limits**: up to 1,000 assets per Sponsor; up to 10 tags per asset, each up to 30 characters. Names, descriptions and texts may be as long as the field they are copied into (an in-store prize description, for example, as long as the publication's field). At the limit, delete assets you no longer use before adding new ones.

## FAQ（一条）

- 中文 · 问：资源库里的资源改了或删了，已经用了它的 Quiz 和发布会变吗？
  答：不会。"从库里选"是把内容复制过去，之后两边各管各的，已经提交的发布更不会变。资源改过以后，在资源的"用在哪里"中，之前复制的地方会标"用的是旧的内容"；要用新的内容，就到那一处重新"从库里选"并保存。删除资源只是从资源库中拿掉，已复制的内容照旧。资源库在菜单"内容 → 资源库"，每个发起方至多 1000 个资源；"从我的内容收集"可以把已有的内容一次收进来（同样的只收一次）。
- 日本語 · 問：リソースライブラリのリソースを変更・削除すると、それを使ったクイズや公開も変わりますか？
  答：変わりません。「ライブラリから選ぶ」は内容をコピーするので、その後は別々に管理されます。申請済みの公開も変わりません。リソースを変更すると、そのリソースの「使用箇所」で、以前コピーした場所に「古い内容を使用中」と表示されます。新しい内容にしたいときは、その場所でもう一度「ライブラリから選ぶ」で選んで保存してください。リソースを削除してもライブラリから外れるだけで、コピー済みの内容はそのままです。リソースライブラリはメニューの「コンテンツ → リソースライブラリ」にあり、主催者 1 人につき 1000 件まで保存できます。「自分のコンテンツから集める」で、既存の内容をまとめて取り込めます（同じものは一度だけ）。
- English · Q: If I change or delete an asset in my library, do the quizzes and publications that used it change?
  A: No. "Choose from the library" copies the content, and from then on each is on its own; a submitted publication never changes. Once you change an asset, its "Where used" marks the places copied before as "Uses older content"; to use the new content, choose the asset again in that place and save. Deleting an asset only takes it out of the library; the copies stay as they are. The library is under "Content → Asset library" in the menu and holds up to 1,000 assets per Sponsor; "Collect from my content" brings in what you already have in one go (each file and text once).
