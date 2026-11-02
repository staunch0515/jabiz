import { addMessages } from '@jabiz/client'
import { SPONSOR } from '../messages'

/**
 * The tree page's texts (Q85), part of the console's namespace `sponsor` (added here, next to the page). The states'
 * names and the picture's own texts are the shared `qb` namespace's (`tree.*`).
 */
const en = {
  tree: {
    nav: 'Tree',
    title: 'Tree',
    intro: 'Your quizzes as a tree: each one that requires another first is a step up. On each node, how many of your customers lit it (passed), answered without passing, or could start it now. Pick a node for the people on it; paste a customer\'s key from the transactions page to light the tree the way they see it.',
    hideEnded: 'Hide ended quizzes',
    hideIsolated: 'Hide quizzes that lead nowhere',
    empty: 'No quiz submitted yet.',
    noEdges: 'Your quizzes do not lead to one another yet. In a publication\'s wizard, step "Customer matching", you can require a quiz first: that draws the path.',
    toPublications: 'Your publications',
    label: 'Your quizzes and your customers on them',
    customer: { label: 'Light the tree for one customer', placeholder: 'Customer key (from the transactions page)',
      show: 'Show', clear: 'Back to everyone', bad: 'A key is 32 letters and digits, as the transactions page shows it.',
      none: 'No customer of yours has this key.', shown: 'Showing {{name}}', someone: 'this customer' },
    node: { pick: 'Pick a node to see who is on it.', counts: '{{lit}} lit · {{answered}} answered without passing · {{inProgress}} in progress · {{unlocked}} could start · {{locked}} locked out',
      status: 'Status', open: 'Open now', closed: 'Not open', publication: 'Open the publication' },
    people: { none: 'Nobody here.', more: 'More', when: 'Finished {{when}}', transactions: 'Transactions',
      conversation: 'Conversation', light: 'Their tree', someone: 'A taker' },
    onTree: 'See on the tree',
  },
}

const zh = {
  tree: {
    nav: '科技树',
    title: '科技树',
    intro: '把你的答卷画成一棵树：要求先完成另一份的，就在它上面一层。每个节点上是你的客户中已点亮（合格）、答过未合格、现在就能开始的人数。点一个节点看上面的人；从交易页复制一位客户的键，整棵树按他的进度点亮。',
    hideEnded: '隐藏已结束的',
    hideIsolated: '隐藏不通向任何答卷的',
    empty: '还没有提交过的发布。',
    noEdges: '你的答卷之间还没有先后。在发布向导的"客户匹配"一步可以要求先完成另一份：那就是路径。',
    toPublications: '去发布',
    label: '你的答卷与客户在上面的位置',
    customer: { label: '按一位客户点亮', placeholder: '客户的键（交易页中）',
      show: '查看', clear: '回到全部', bad: '键是 32 位字母数字，交易页中那样。',
      none: '没有客户用这个键。', shown: '正在显示 {{name}}', someone: '这位客户' },
    node: { pick: '点一个节点看上面的人。', counts: '已点亮 {{lit}} · 答过未合格 {{answered}} · 进行中 {{inProgress}} · 可开始 {{unlocked}} · 锁定 {{locked}}',
      status: '状态', open: '进行中', closed: '未开放', publication: '打开发布' },
    people: { none: '没有人。', more: '更多', when: '完成于 {{when}}', transactions: '交易',
      conversation: '对话', light: '他的树', someone: '答题者' },
    onTree: '在树上查看',
  },
}

const ja = {
  tree: {
    nav: 'ツリー',
    title: 'ツリー',
    intro: 'クイズをツリーとして表示します。先に別のクイズを求めるものは一段上です。各ノードには、達成（合格）したお客様、不合格で回答したお客様、今すぐ挑戦できるお客様の人数が出ます。ノードを選ぶとその人たちが表示されます。取引ページのお客様キーを貼ると、その人の進み具合でツリーが点灯します。',
    hideEnded: '終了したクイズを隠す',
    hideIsolated: 'どこにもつながらないクイズを隠す',
    empty: '提出した公開はまだありません。',
    noEdges: 'クイズ同士はまだつながっていません。公開ウィザードの「顧客マッチング」で先に別のクイズを求めると、道筋が描かれます。',
    toPublications: '公開へ',
    label: 'あなたのクイズと、その上のお客様',
    customer: { label: 'お客様一人で点灯', placeholder: 'お客様キー（取引ページ）',
      show: '表示', clear: '全員に戻る', bad: 'キーは取引ページにある 32 桁の英数字です。',
      none: 'このキーのお客様はいません。', shown: '{{name}} を表示中', someone: 'このお客様' },
    node: { pick: 'ノードを選ぶとその上の人が表示されます。', counts: '達成 {{lit}} · 不合格で回答 {{answered}} · 進行中 {{inProgress}} · 挑戦できる {{unlocked}} · ロック中 {{locked}}',
      status: '状態', open: '受付中', closed: '受付外', publication: '公開を開く' },
    people: { none: '誰もいません。', more: 'さらに', when: '{{when}} に完了', transactions: '取引',
      conversation: '会話', light: 'この人のツリー', someone: '回答者' },
    onTree: 'ツリーで見る',
  },
}

export const treeMessages = { en, zh, ja }

addMessages(SPONSOR, treeMessages)
