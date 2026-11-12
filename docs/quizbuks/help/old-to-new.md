# 帮助文案：旧功能 → 新做法对照（F-F11）

给发起方看的帮助文案（三语）。帮助中心的内容存在数据库里（FAQ 为 `QbFaqEntry`），**由内容管理员在平台管理系统的"FAQ"中发布**，改了不用重新部署；这里是文案的底稿，功能改了先改这里再请内容管理员照改。
用词照 `docs/quizbuks/glossary.md`；`tools/quizbuks/terms/terms.test.mjs` 扫描本目录。来源：Q90 T1b（样板指南的"旧功能 → 新做法"一节），旧功能的删除清单与替代在 `plans/impl/F-S2.md` §7（D1–D8），节点证书在 `plans/impl/F-S5.md`，规则与运维在 `operations.md` §7.27；新功能本身的帮助在 `knowledge-map.md`。

- **可以发布**：对照里的每个新做法都已上线（F-S2、F-S3、F-S5 已合并）。
- **对象**：发起方，FAQ 的 `audience` = `SPONSOR`，分类 `PUBLISHING`；一条三语的条目，内容就是下面的表。
- **页面名称**：照现在的界面写——发起方页面的"发布 → 知识结构图"，发布向导第一步的"绑定节点"，学者 App 的"我的 → 你拥有的东西"里的知识结构图与证书（日文、英文的叫法见下表）。界面改名时同步改这里。
- **措辞已对过实现**：每一行都对过 `F-S2.md` §7、`F-S5.md` 与 `operations.md` §7.27；"照旧"一行是没有被删的功能（见 `F-S2.md` §7 之外的 F-C4、F-S3）。
- 截图：不需要；对照表不配图。

## 一、给发起方（FAQ 的 `audience` = `SPONSOR`，分类 `PUBLISHING`）

### 中文 · 以前的认证、轮、前置条件和科技树，现在怎么做？

以前用来决定"谁能答什么、谁能得到什么"的几项功能，现在都由知识结构图承担：一张图把要学的东西排成节点，学者在绑定节点的发布上合格就点亮节点，点亮是永久的。下表按"以前 → 现在 → 在哪里"对照；新功能本身的说明见"什么是知识结构图""怎样建立与发布自己的知识结构图"。

| 以前 | 现在 | 在哪里 |
|---|---|---|
| 发布的"前置条件"（先完成另一个发布，或持有某个认证，才能答这个发布） | 在知识结构图的节点上设条件。节点要求"答题前须点亮"另一个节点时，没有点亮它的学者看不到、也不能开始绑定这个节点的发布 | 发布向导第一步的"绑定节点"；节点的条件在"发布 → 知识结构图"里编辑 |
| 平台的"认证"与"轮"（人工授予、认领合格、认证概况） | 平台的知识结构图。学者在绑定节点的发布上合格，节点就点亮，不再有人工授予、认领和"轮" | 学者的"我的 → 你拥有的东西 → 知识结构图" |
| 发起方自己的认证与轮（含奖励为 0 的"轮"） | 发起方自己的知识结构图：每位发起方可以有一张，提交后由你的运营商的另一位成员审核。绑定节点的发布可以不设奖励档位 | "发布 → 知识结构图"，发布向导的"绑定节点" |
| "持有认证的人不能再答这一轮" | 去掉了。节点点亮是永久的，学者再答同一节点上的其他 Quiz 照常得到奖励，只是节点不会再"点亮一次" | — |
| 协议理解答题卡（首页与概况里的"理解条款"卡） | 去掉了。需要类似的答题时，由总部在平台的图上建节点 | — |
| 科技树 | 知识结构图。发起方的图上显示每个节点的点亮人数和"答过未点亮"的人数（人数很少时不显示） | "发布 → 知识结构图" |
| 申请成为发起方、生成推荐链接、提供或赞助合作权益所需的认证 | 点亮平台指定的节点。哪个节点由总部设定；没有指定时，这些功能不开放，页面会说明要点亮哪个节点 | 学者 App 或发起方页面上相应功能的页面 |
| 证书（按认证签发） | 节点证书：节点设了证书名称，学者点亮节点就有证书，没有有效期；第一次下载时签发 PDF；持有人可以停用和恢复核实页的分享。以前签发的旧证书照常核实和下载，到有效期为止 | 学者的"我的 → 证书"；发起方自己的图在节点上设"证书名称" |
| **照旧**：复制发布、发布设置的导出与导入、版本切换、Quiz 文件、资料包、发起方包 | 同前 | 概况页的"导入与导出"，发布详情页 |

### 日本語 · 以前の認証・ラウンド・前提条件・テックツリーは、いまどうなりますか？

以前は「だれが何に回答できるか、だれが何を受け取れるか」を決めていた機能が、いまは知識マップに集約されています。マップは学ぶことをノードとして並べた図で、テイカーがノードに結び付いた公開に合格するとノードが点灯し、点灯はずっと続きます。下の表は「以前 → いま → どこで」の対応です。新しい機能そのものの説明は「知識マップとは何ですか？」「自分の知識マップを作って公開するには？」をご覧ください。

| 以前 | いま | どこで |
|---|---|---|
| 公開の「前提条件」（別の公開を先に終える、または認証を持っていないと回答できない） | 知識マップのノードに条件を設定します。ノードが別のノードの「回答前に点灯」を求めるとき、それを点灯していないテイカーには、そのノードに結び付いた公開は表示されず、始めることもできません | 公開の作成の最初の画面の「ノードに結び付ける」。ノードの条件は「公開 → 知識マップ」で編集します |
| プラットフォームの「認証」と「ラウンド」（手動の付与、合格の申請、認証の概況） | プラットフォームの知識マップ。テイカーがノードに結び付いた公開に合格するとノードが点灯します。手動の付与、申請、「ラウンド」はなくなりました | テイカーの「マイページ → 持っているもの → 知識マップ」 |
| 主催者自身の認証とラウンド（報酬が 0 の「ラウンド」を含む） | 主催者自身の知識マップ。主催者ごとに一枚持て、提出すると運営パートナーの別のメンバーが審査します。ノードに結び付けた公開は報酬の段階を設けなくてもかまいません | 「公開 → 知識マップ」、公開の作成の「ノードに結び付ける」 |
| 「認証を持つ人は、そのラウンドにもう回答できない」 | なくなりました。ノードの点灯はずっと続き、同じノードのほかのクイズに回答しても報酬はいつもどおりです。ノードがもう一度「点灯」することはありません | — |
| 規約理解クイズのカード（ホームや概況の「規約を理解する」カード） | なくなりました。同様のクイズが必要なときは、本部がプラットフォームのマップにノードを作ります | — |
| テックツリー | 知識マップ。主催者のマップには、ノードごとの点灯人数と「回答したが未点灯」の人数が表示されます（人数が少ないときは表示されません） | 「公開 → 知識マップ」 |
| 主催者への申請、紹介リンクの作成、提携特典の提供・協賛に必要だった認証 | プラットフォームが指定したノードを点灯すること。どのノードかは本部が設定します。指定がないときはこれらの機能は使えず、その画面にどのノードを点灯すればよいかが表示されます | テイカーのアプリまたは主催者ページの、該当する機能の画面 |
| 証明書（認証に基づく発行） | ノード証明書：ノードに証明書の名称が設定されていると、テイカーがノードを点灯した時点で証明書が付与されます。有効期限はなく、PDF は最初にダウンロードしたときに発行されます。持ち主は確認ページの共有を止めたり再開したりできます。以前に発行された証明書は、有効期限までこれまでどおり確認・ダウンロードできます | テイカーの「マイページ → 証明書」。主催者自身のマップはノードに「証明書の名称」を設定します |
| **これまでどおり**：公開のコピー、公開設定のエクスポート・インポート、版の切り替え、Quiz ファイル、資料パッケージ、主催者パッケージ | 変わりません | 概況ページの「インポートとエクスポート」、公開の詳細ページ |

### English · How do the old certifications, rounds, prerequisites and tech tree work now?

The features that used to decide who may answer what, and who gets what, are now carried by knowledge maps. A map lays out what there is to learn as nodes; a Taker lights a node by passing a publication bound to it, and a lit node stays lit. The table below goes "before → now → where". For the new feature itself, see "What is a knowledge map?" and "How do I make and publish my own knowledge map?".

| Before | Now | Where |
|---|---|---|
| A publication's "prerequisites" (finish another publication, or hold a certification, before answering this one) | Set a condition on a node of the knowledge map. When a node asks for another node to be lit before answering, Takers who have not lit it neither see nor start a publication bound to this node | "Bind to a node" on the first step of the publication wizard; the node's conditions are edited in "Publications → Knowledge maps" |
| The platform's "certifications" and "rounds" (granting by hand, claiming a pass, the overview) | The platform's knowledge maps. A Taker who passes a publication bound to a node lights it; there is no granting by hand, no claiming and no "round" | A Taker's "Me → What I have → Knowledge maps" |
| A Sponsor's own certifications and rounds (including "rounds" with a reward of 0) | A Sponsor's own knowledge map: each Sponsor may have one, and another member of your operator reviews it once submitted. A publication bound to a node may have no reward tiers | "Publications → Knowledge maps"; "Bind to a node" in the wizard |
| "Whoever holds the certification can no longer answer that round" | Gone. A lit node stays lit, and answering other quizzes of the same node still gives their rewards as usual; the node is just not "lit again" | — |
| The agreement-understanding quiz card (the "understand the terms" card on the home and overview) | Gone. When a similar quiz is needed, headquarters makes a node on the platform's map | — |
| The tech tree | The knowledge map. A Sponsor's map shows, for each node, how many Takers lit it and how many answered without lighting it (not shown when the number is small) | "Publications → Knowledge maps" |
| The certification needed to apply as a Sponsor, make a referral link, or provide or sponsor partner benefits | Lighting the node the platform names. Headquarters sets which node; when none is set, these features are not open, and their page says which node to light | The page of the feature in the Taker App or the Sponsor app |
| Certificates (issued by certification) | Node certificates: when a node has a certificate name, a Taker who lights it has the certificate at once, with no validity period; the PDF is issued the first time it is downloaded; the holder may stop and resume sharing its verify page. Certificates issued before can still be verified and downloaded until their validity ends | A Taker's "Me → Certificates"; a Sponsor's own map sets the "certificate name" on a node |
| **Unchanged**: copying a publication, exporting and importing publication settings, switching versions, Quiz files, profile packages, Sponsor packages | As before | "Import and export" on the overview page; a publication's detail page |
