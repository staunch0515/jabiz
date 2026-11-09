# 帮助文案：大题库与抽题（F-S6）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
Q109 的使用手册（F-C10）建立时，把下面的"帮助页"一节原样放进发起方的手册（`zh` / `ja` / `en` 各一份）。用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。

- 对象：发起方（FAQ 的 `audience` = `SPONSOR`）
- 分类：`PUBLISHING`
- 页面：Quiz 编辑器的"题目"标签（`/sponsor/quizzes/<Quiz>`），发布向导的"每人抽题数"
- 参数（写进文案的数字是缺省值，改了参数时同步改这里；页面显示的是当时的值，读模板 `qb.sponsor.quiz-limits`）：`qb.content.max-questions` = 1000、`qb.play.max-questions-per-attempt` = 100、`qb.content.import.batch-size` = 200；一次批量操作至多 200 道题（`QuestionBatchProcesses.MAX_BATCH`）
- 计划：`docs/quizbuks/plans/impl/F-S6.md`

## 帮助页

### 中文 · 大题库与抽题

**上限**：一个 Quiz 最多 1000 道题，这是题库的上限。每次答题最多考 100 道题。题目的"题目"标签上方会显示现在有多少道题、上限是多少，以及每次答题的上限。上限可能由平台调整，以页面显示的为准。到了题库上限，就不能再加题或导入；已有的题目照常修改和删除。

**题库比每次答题的上限大时要抽题**：题目超过每次答题的上限（100 道）时，发布时必须在"每人抽题数"中填写每人抽多少题（不超过这个上限）。每个学者从版本的全部题目中随机抽到这么多题，满分 = 抽题数 × 每题分数，所以抽题的版本每道题分数要相同。题目不多时，留空就是全部都考。

**分批导入**：在"题目"标签点"导入题目"，选择一个 Quiz 文件（Quiz 列表的"导出"得到的文件）。文件中的题目加在最后一题之后，每批最多 200 道，一批一批地导入，页面显示进行到第几批。每批单独保存：某一批不能导入时（例如超过题库上限），导入停在这一批，页面说明是第几批、为什么；之前的批次已经导入，保持不变。只导入题目：文件中的资料和商品不会导入；文件的语言要和 Quiz 的语言相同；译文只导入 Quiz 已有的语言，其他语言会列出来。

**撤销一批**：导入后，"已导入的批次"列出每一批的时间和题数。点"撤销"会删除这一批中导入后没有改过的题目；你改过的题目保留，页面会说明删除了几道、保留了几道。移动位置和翻译不算修改。每一批只能撤销一次。

**搜索**：题目每页显示 50 道。在搜索框输入题干中的文字可以找到含有这些文字的题目（不分大小写）；输入一个数字就是找这个题号的题。找到的题目保留原来的题号。点"显示全部"回到全部题目。

**移动**：每道题可以"上移""下移"，或用"移到…"换到任意位置（题目很多时直接输入新位置）。其他题目依次让开。

**批量操作**：勾选题目前面的方框（"选择本页的题目"一次选中一页），可以一次删除，或一次把它们设为同一个分数（问卷没有分数，不显示）。一次最多 200 道。删除前会再问一次；已保存的版本不受影响。

### 日本語 · 大きな問題集と出題

**上限**：1 つのクイズに入れられる問題は 1000 問までです（問題集の上限）。1 回の回答で出題されるのは 100 問までです。「問題」タブの上に、今の問題数と上限、1 回の回答の上限が表示されます。上限はプラットフォームが変更することがあるので、画面の表示を確認してください。問題集の上限に達すると、問題の追加やインポートはできません。今ある問題の変更や削除はこれまでどおりできます。

**1 回の上限より多いときは出題数を決めます**：問題が 1 回の回答の上限（100 問）より多いときは、公開するときに「一人あたりの出題数」（上限以下）を必ず入力してください。各テイカーには、バージョンの全問題からこの数だけ無作為に出題されます。満点は出題数 × 1 問の配点なので、出題数を決めるバージョンはすべての問題の配点が同じである必要があります。問題が少ないときは、空のままにすると全問出題です。

**分けてインポート**：「問題」タブの「問題をインポート」でクイズファイル（クイズ一覧の「エクスポート」で作ったファイル）を選びます。ファイルの問題は最後の問題の後に、一度に 200 問ずつ順にインポートされ、何回目まで進んだかが表示されます。各回は個別に保存されます。ある回がインポートできないとき（たとえば問題集の上限を超えるとき）は、その回で止まり、何回目か、なぜかが表示されます。それより前の回はインポート済みのままです。インポートされるのは問題だけで、ファイルの資料と商品は入りません。ファイルの言語はクイズの言語と同じである必要があります。翻訳はクイズにすでにある言語だけがインポートされ、ほかの言語は一覧で表示されます。

**1 回分の取り消し**：インポートした後は、「インポートした回」に各回の日時と問題数が表示されます。「取り消す」を押すと、その回の問題のうちインポート後に変更していないものが削除されます。変更した問題は残り、削除した数と残した数が表示されます。位置の移動と翻訳は変更に数えません。各回は一度だけ取り消せます。

**検索**：問題は 1 ページに 50 問表示されます。検索欄に問題文の文字を入力すると、その文字を含む問題が見つかります（大文字と小文字は区別しません）。数字を入力すると、その番号の問題を探します。見つかった問題は元の番号のままです。「すべて表示」で全問題に戻ります。

**移動**：各問題は「上へ」「下へ」で動かすか、「移動…」で好きな位置に移せます（問題が多いときは新しい位置を入力します）。ほかの問題は順にずれます。

**まとめて操作**：問題の前のチェックボックス（「このページの問題を選択」で 1 ページ分をまとめて選択）で選ぶと、まとめて削除したり、同じ配点にしたりできます（アンケートには配点がないので表示されません）。一度に 200 問までです。削除の前に確認があります。保存済みのバージョンは変わりません。

### English · Large question banks and drawing

**Limits**: a quiz holds up to 1000 questions: that is the bank's limit. One attempt asks at most 100 questions. Above the list on the "Questions" tab you see how many questions the quiz has, its limit, and one attempt's limit. The platform may change the limits; the page always shows the current ones. When the bank is full, you cannot add or import questions; you can still change and delete the ones it has.

**A bank larger than one attempt must be drawn from**: when a quiz has more questions than one attempt asks (100), fill in "Questions per Taker" when you publish (at most that limit). Each Taker gets that many of the version's questions, drawn at random, and the full score is that many times the points of a question, so every question of a version drawn from has the same points. With fewer questions, leave it empty to ask all of them.

**Importing in batches**: on the "Questions" tab, "Import questions" takes a quiz file (as "Export" on the quiz list makes it). Its questions are added after the last question, up to 200 at a time, batch after batch; the page shows which batch it is on. Each batch is saved on its own: when one cannot be imported (for example, the bank would be over its limit), the import stops there and says which batch and why; the batches before it stay imported. Only questions are imported: the file's materials and products are not. The file must be in the quiz's language; translations are imported only for languages the quiz has, and the others are listed.

**Undoing a batch**: after an import, "Imported batches" lists each batch with its time and number of questions. "Undo" deletes the batch's questions that were not changed since the import; the questions you changed stay, and the page says how many were removed and how many stayed. Moving and translating do not count as changes. Each batch can be undone once.

**Searching**: the list shows 50 questions a page. Type words of a question text in the search box to find the questions that contain them (upper and lower case alike); type a number to find the question with that number. Questions found keep their numbers. "Show all" brings back every question.

**Moving**: move a question up or down, or to any place with "Move to…" (in a large quiz, type the new place). The other questions make room.

**Batch actions**: tick the boxes in front of questions ("Select the questions on this page" ticks a whole page) to delete them at once or give them all the same points (not shown for a survey, which has no points). Up to 200 at once. Deleting asks first; saved versions keep their questions.
