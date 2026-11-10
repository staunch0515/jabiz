# 帮助文案：知识结构图（F-S2）

给学者与发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。规则与运维见 `docs/quizbuks/plans/impl/F-S2.md`、`operations.md` §7.27。

- **可以发布**（F-S3e）：看图与编辑的页面都已上线。学者的页面：App 账户菜单"学习 → 知识结构图"（`/taker/maps`）与发起方页面上的图；发起方的页面："发布 → 知识结构图"（`/sponsor/publications/map`，编辑器与版本），发布向导第一步的"绑定节点"。
- **截图**：由 e2e `quizbuks-web/e2e/tests/tree-graph.spec.ts` 截取（亮色、英文界面），在 CI 的 e2e 报告（`quizbuks-e2e-report-*`）中该用例的附件：`knowledge-map-sponsor-editor.png`（编辑器）、`knowledge-map-sponsor-map.png`（生效的图与点亮人数）、`knowledge-map-taker-locked.png`（学者的图：前置未点亮的节点）、`knowledge-map-taker-lit.png`（合格后节点点亮）。页面改了，下一轮 CI 的截图随之更新；内容管理员发布条目时取最新一轮的。
- 参数（写进文案的是缺省值，改了参数时同步改这里）：一张图至多 200 个节点（`qb.tree.max-nodes`），一位发起方一张自己的图（`qb.tree.sponsor.max-trees`）。

## 一、给学者（FAQ 的 `audience` = `TAKER`，分类 `ANSWERING`）

### 中文 · 什么是知识结构图？

知识结构图把一个领域要学的东西排成一张图：每个节点是一项知识或能力，节点之间的线表示"先学什么、再学什么"。平台有自己的图，发起方也可以有自己的图。
每个节点写明怎样点亮：通常是在绑定这个节点的 Quiz 上合格，有的节点还要先点亮别的节点（"答题前须点亮"的节点没有点亮时，这个节点的 Quiz 不能开始）。有的节点不用答题，点亮了它需要的节点就会自动点亮。

### 中文 · 怎样点亮节点？

找到绑定这个节点的 Quiz 并合格，节点就会点亮（合格后几秒到几分钟内）；依赖它的节点满足条件时也会接着点亮。点亮是永久的：以后再答同一节点的其他 Quiz 照常得奖励，但节点不会再"点亮一次"。
只有你此刻能开始的 Quiz 才算"可答"：受众、邀请、地区、视频等条件不符合，或你已答过，都不算。
如果点亮有误（例如答案外流），运营商或平台可以经另一位成员审核后撤销，你会收到站内信说明原因。撤销后，原来那次合格不再算；以后在这个节点的 Quiz 上再次合格，会重新点亮。

### 中文 · 点亮节点能开放什么？

平台图上的一些节点点亮后会开放平台的功能，例如推荐朋友、在 App 中申请成为发起方；发起方点亮相应的节点后可以提供或赞助权益。功能没有开放时，相应的页面会说明要点亮哪个节点。撤销点亮后功能随之关闭。

### 中文 · 为什么我看不到某个 Quiz？

绑定知识结构图节点的 Quiz，要先点亮这个节点要求"答题前点亮"的节点才能看到：在那之前，首页不列出它，打开链接也显示找不到，收件箱里关于它的通知也不显示。点亮要求的节点后它就会出现，页面上写着它的节点"合格即点亮"；合格后写"已点亮"。已经答过的 Quiz，随时都能看自己的结果。

### 日本語 · 知識マップとは何ですか？

知識マップは、ある分野で学ぶことを一枚の図にまとめたものです。ノードはひとつの知識や力を表し、ノードをつなぐ線は「何を先に、何を後に学ぶか」を表します。プラットフォームのマップのほか、主催者が自分のマップを持つこともあります。
ノードごとに点灯のしかたが書かれています。多くはそのノードに結び付いたクイズに合格することで、ほかのノードを先に点灯する必要があるものもあります（「回答前に点灯が必要」なノードが点灯していないと、そのノードのクイズは始められません）。回答の要らないノードは、必要なノードが点灯すると自動で点灯します。

### 日本語 · ノードを点灯するには？

そのノードに結び付いたクイズに合格すると、ノードが点灯します（合格から数秒〜数分）。それを必要とするノードも、条件を満たせば続けて点灯します。点灯はずっと続きます。同じノードのほかのクイズに回答しても報酬はいつもどおりですが、もう一度「点灯」することはありません。
「回答できる」のは、いま始められるクイズだけです。対象、招待、地域、動画などの条件が合わないもの、すでに回答したものは含まれません。
点灯に誤りがあった場合（答えの流出など）、運営パートナーまたはプラットフォームが、別のメンバーの審査を経て取り消すことがあります。その場合は理由をメッセージでお知らせします。取り消し後は元の合格は数えられません。そのノードのクイズにあらためて合格すると、もう一度点灯します。

### 日本語 · ノードを点灯すると何が使えるようになりますか？

プラットフォームのマップには、点灯すると機能が使えるようになるノードがあります。たとえば友だち紹介や、アプリからの主催者への申請です。主催者は該当するノードを点灯すると、特典の提供や協賛ができるようになります。使えないときは、その画面にどのノードを点灯すればよいかが表示されます。点灯が取り消されると、その機能も使えなくなります。

### 日本語 · 見えないクイズがあるのはなぜですか？

知識マップのノードに結び付いたクイズは、そのノードが「回答前に点灯が必要」とするノードを先に点灯すると見えるようになります。それまではホームに出ず、リンクを開いても見つからないと表示され、そのクイズについての受信箱のお知らせも表示されません。必要なノードを点灯すると現れ、ページにはそのノードが「合格で点灯」と書かれます。合格すると「点灯済み」になります。回答したクイズは、いつでも自分の結果を見られます。

### English · What is a knowledge map?

A knowledge map lays out what there is to learn in a field as one picture: each node is a piece of knowledge or a skill, and the lines between nodes show what to learn first and what comes next. The platform has its own maps, and a Sponsor may have one too.
Each node says how it is lit: usually by passing a quiz bound to it, sometimes only after other nodes are lit (while a node marked "to be lit before answering" is not lit, the quiz of this node cannot be started). A node that asks for no quiz lights by itself once the nodes it needs are lit.

### English · How do I light a node?

Pass a quiz bound to the node and it lights (within seconds to minutes of passing); the nodes that depend on it light in turn when their conditions are met. A node stays lit: answering other quizzes of the same node gives their rewards as usual, but the node is not "lit again".
Only the quizzes you can start right now count as open to you: not those whose audience, invitation, region or video rules leave you out, and not those you have answered.
If a lighting was wrong (for example, answers were shared), the operator or the platform may revoke it after another member reviews the request; you will get a message with the reason. After that, the pass it rested on no longer counts; passing a quiz of the node again lights it anew.

### English · What does lighting a node unlock?

Some nodes of the platform's maps unlock a feature once lit, such as referring friends or applying in the App to become a Sponsor; a Sponsor who lights the matching node may provide or sponsor benefits. While a feature is not open, its page says which node to light. If a lighting is revoked, the feature closes again.

### English · Why can't I see a quiz?

A quiz bound to a knowledge map node shows once you have lit the nodes it needs lit before answering: until then it is not on your home, its link says it cannot be found, and inbox notices about it do not show. Once those nodes are lit it appears, and its page says passing lights its node; after you pass, the node shows as lit. A quiz you have answered always shows your own result.

## 二、给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `PUBLISHING`）

### 中文 · 怎样建立与发布自己的知识结构图？

每位发起方可以有一张自己的图（点亮的是学者），在"发布 → 知识结构图"中点"创建我的图"，再点"编辑"。保存草稿时会列出全部问题（例如条件绕成一个圈、引用了不存在的节点），有问题照样能保存，但提交审核前要改好。提交后由你的运营商的另一位成员审核，批准后生效；驳回时会附理由，改好后再提交。
节点的条件只能是"全部""任一"或"至少 N 个"，对象是其他节点，或"在绑定本节点的 Quiz 上合格"。"答题前须点亮"的节点只能放在最外层的"全部"中。你的图可以引用平台中由学者点亮的图的节点：在"添加节点"中，它们按平台的图名列在本图的节点之后。
新版本不能去掉仍有进行中发布绑定的节点；停用整张图时，绑定它的进行中发布会结束，你会收到通知。

### 中文 · 怎样让发布点亮节点？

在发布向导中选"绑定节点"（可不选），选你的图中要求合格的节点。绑定节点的发布可以不设奖励档位。学者在这个发布上合格即点亮该节点；节点要求"答题前须点亮"的节点时，没有点亮的学者看不到、也不能开始这个发布。

### 日本語 · 自分の知識マップを作って公開するには？

主催者はそれぞれ自分のマップを一枚持てます（点灯するのはテイカーです）。「公開 → 知識マップ」で「マップを作成」を押し、「編集」を押します。下書きを保存すると問題点（条件が輪になっている、存在しないノードを参照しているなど）がすべて表示されます。問題があっても保存はできますが、審査に出す前に直してください。提出すると、あなたの運営パートナーの別のメンバーが審査し、承認されると有効になります。差し戻しのときは理由が付きます。直してから再提出してください。
ノードの条件は「すべて」「いずれか」「少なくとも N 個」だけで、対象はほかのノード、または「このノードに結び付いたクイズに合格」です。「回答前に点灯が必要」なノードは、いちばん外側の「すべて」の中にだけ置けます。プラットフォームの、テイカーが点灯するマップのノードを参照することもできます。「ノードを追加」で、このマップのノードのあとにプラットフォームのマップ名ごとに並びます。
実施中の公開が結び付いているノードは、新しい版で削除できません。マップ全体の使用を停止すると、結び付いた実施中の公開は終了し、お知らせが届きます。

### 日本語 · 公開でノードを点灯させるには？

公開の作成で「ノードに結び付ける」を選び（選ばなくてもかまいません）、ご自身のマップの、合格を条件とするノードを選んでください。ノードに結び付けた公開は報酬の段階を設けなくてもかまいません。テイカーがその公開に合格するとノードが点灯します。ノードが「回答前に点灯が必要」なノードを求めている場合、それを点灯していないテイカーにはその公開が表示されず、始めることもできません。

### English · How do I make and publish my own knowledge map?

Each Sponsor may have one map of their own (lit by Takers): in "Publications → Knowledge maps" choose "Create my map", then "Edit". Saving a draft lists every problem (a condition going round in a circle, a node that does not exist, and so on); a draft with problems is still saved, but fix them before submitting it for review. Another member of your operator reviews it; once approved it is in effect. A map sent back comes with the reason: fix it and submit it again.
A node's condition is "all", "any" or "at least N", over other nodes or "passing a quiz bound to this node". A node "to be lit before answering" can only be in the outermost "all". Your map may name nodes of the platform's maps that Takers light: "Add a node" lists them under each platform map's name, after your map's own nodes.
A new version cannot remove a node that running publications are bound to; retiring the whole map ends the running publications bound to it, and you are told.

### English · How does a publication light a node?

In the publication wizard choose "Bind to a node" (optional) and pick a node of your map that asks for a pass. A publication bound to a node may have no reward tiers. A Taker who passes it lights the node; when the node needs other nodes lit before answering, Takers who have not lit them neither see nor start the publication.

### 中文 · 什么是挑战？合格后得到什么？

挑战是你所属运营商在平台图副本的节点上出的 Quiz，只给发起方答，在发起方中心的"挑战"标签里。点节点可看它的状态、点亮后开放的功能（如电子奖品、到店领取的奖品）与赠送额度，以及可以开始的挑战。挑战没有奖励，每个只能答一次。合格后节点点亮：开放的奖励方式即可在发布中使用，赠送额度进入平台费账户（每个节点只赠送一次，有到期日）。不合格时请等运营商的下一场挑战。入驻申请通过后才能答。

### 日本語 · チャレンジとは？ 合格すると何が得られますか？

チャレンジは、所属する運営パートナーがプラットフォームマップのコピーのノードに出すクイズで、主催者だけが回答します。主催者センターの「チャレンジ」タブにあります。ノードを押すと、状態、点灯で使えるようになる機能（デジタル賞品、店頭で受け取る賞品など）と付与クレジット、始められるチャレンジがわかります。チャレンジに報酬はなく、各チャレンジに回答できるのは 1 回だけです。合格するとノードが点灯し、使えるようになった報酬の方法を公開で選べるようになります。付与クレジットはプラットフォーム利用料アカウントに入ります（ノードごとに 1 回だけ、有効期限あり）。不合格のときは次のチャレンジをお待ちください。主催者の申し込みが承認されると回答できます。

### English · What is a challenge, and what does passing it give?

A challenge is a quiz your operator sets on a node of its copy of a platform map, answered only by Sponsors, on the "Challenges" tab of the Sponsor hub. Choose a node to see its state, what lighting it opens (such as digital prizes or prizes collected in person) and its granted credit, and the challenges you can start. A challenge has no reward, and each is answered once. Passing lights the node: the reward methods it opens can be chosen for your publications, and the granted credit goes to your platform fee account (once per node; it expires). If you did not pass, wait for your operator's next challenge. You can answer once your Sponsor application is approved.
