import { addMessages } from '@jabiz/client'
import { QB } from '../messages'

/**
 * The knowledge map component's texts (F-S3), part of the shared namespace `qb` under `tree`; registered by the
 * component. Terms as `docs/quizbuks/glossary.md`: 知识结构图 / 知識マップ / knowledge map; 点亮 / 点灯 / lit.
 * `gates.<key>` says what lighting a gate node opens, one per `qb.tree.gate.<key>-node` parameter (the server names
 * the parameters; the text is the platform's, F-S3 §8 1).
 */
const en = {
  tree: {
    kinds: { PLATFORM: 'Platform map', SPONSOR: 'Sponsor map' },
    views: { label: 'Show as', graph: 'Map', list: 'List' },
    graphLabel: 'Knowledge map {{name}}',
    scrollLabel: 'The map (scroll to see all of it)',
    keyboardHint: 'Arrow keys move between nodes; Enter shows a node.',
    layer: 'Level {{n}}',
    states: {
      LIT: 'Lit', LOCKED: 'Locked', CAN_ANSWER: 'Can answer', WAITING: 'Waiting for the next one',
      MISSING: 'Conditions missing',
    },
    stateHints: {
      LIT: 'You have lit this node.',
      LOCKED: 'Light the nodes below first. Until then its quizzes are not shown to you.',
      CAN_ANSWER: 'Pass one of these to light it:',
      WAITING: 'Only a pass is missing, but there is no quiz you can answer now. Check again later.',
      MISSING: 'Still missing:',
    },
    nodeLabel: '{{name}}: {{state}}',
    noState: '{{name}}',
    details: 'About this node',
    gives: 'Lighting it gives',
    noGives: 'Nothing besides the node itself.',
    gates: {
      'sponsor-apply': 'You can apply to become a Sponsor.',
      referrer: 'You can recommend quizzes to friends.',
      'benefit-provider': 'You can offer sponsored benefits.',
      'benefit-sponsor': 'You can buy sponsored benefits.',
    },
    condition: {
      ALL: 'All of these',
      ANY: 'Any one of these',
      AT_LEAST: 'At least {{n}} of these',
      node: 'Light “{{name}}”',
      beforeAnswer: 'before answering',
      passed: 'Pass a quiz of this node',
      lit: 'lit',
      notLit: 'not lit',
      unknownNode: 'a node',
    },
    litCount: '{{count}} Takers lit it',
    litCountHidden: 'Fewer Takers than shown in statistics',
    unlitCount: '{{count}} Takers answered and have not lit it (as of {{day}})',
    unlitCountHidden: 'Fewer Takers than shown in statistics answered and have not lit it (as of {{day}})',
    unmet: 'Light first:',
    empty: 'This map has no nodes yet.',
  },
}

const zh: typeof en = {
  tree: {
    kinds: { PLATFORM: '平台的图', SPONSOR: '发起方的图' },
    views: { label: '显示方式', graph: '图', list: '列表' },
    graphLabel: '知识结构图 {{name}}',
    scrollLabel: '整张图（可滚动查看）',
    keyboardHint: '方向键在节点之间移动，回车查看节点。',
    layer: '第 {{n}} 层',
    states: { LIT: '已点亮', LOCKED: '锁定', CAN_ANSWER: '可答', WAITING: '等下一场', MISSING: '差条件' },
    stateHints: {
      LIT: '你已经点亮了这个节点。',
      LOCKED: '先点亮下面的节点。在那之前，它的 Quiz 不会显示给你。',
      CAN_ANSWER: '合格其中一个即可点亮：',
      WAITING: '只差合格，但现在没有你能答的 Quiz。请过些时候再来看看。',
      MISSING: '还差：',
    },
    nodeLabel: '{{name}}：{{state}}',
    noState: '{{name}}',
    details: '这个节点',
    gives: '点亮后得到',
    noGives: '除节点本身外没有其他。',
    gates: {
      'sponsor-apply': '可以申请成为发起方。',
      referrer: '可以向朋友推荐 Quiz。',
      'benefit-provider': '可以上架赞助权益。',
      'benefit-sponsor': '可以购买赞助权益。',
    },
    condition: {
      ALL: '以下全部',
      ANY: '以下任一',
      AT_LEAST: '以下至少 {{n}} 项',
      node: '点亮「{{name}}」',
      beforeAnswer: '答题之前',
      passed: '合格一个这个节点的 Quiz',
      lit: '已点亮',
      notLit: '未点亮',
      unknownNode: '一个节点',
    },
    litCount: '{{count}} 位学者已点亮',
    litCountHidden: '人数少于统计的下限',
    unlitCount: '{{count}} 位学者答过但未点亮（截至 {{day}}）',
    unlitCountHidden: '答过但未点亮的人数少于统计的下限（截至 {{day}}）',
    unmet: '先点亮：',
    empty: '这张图还没有节点。',
  },
}

const ja: typeof en = {
  tree: {
    kinds: { PLATFORM: 'プラットフォームのマップ', SPONSOR: '主催者のマップ' },
    views: { label: '表示', graph: 'マップ', list: 'リスト' },
    graphLabel: '知識マップ {{name}}',
    scrollLabel: 'マップ全体（スクロールして見られます）',
    keyboardHint: '矢印キーでノード間を移動し、Enter でノードを表示します。',
    layer: '{{n}} 段目',
    states: {
      LIT: '点灯済み', LOCKED: 'ロック中', CAN_ANSWER: '回答できます', WAITING: '次の機会を待つ', MISSING: '条件不足',
    },
    stateHints: {
      LIT: 'このノードは点灯済みです。',
      LOCKED: '先に下のノードを点灯してください。それまではこのノードの Quiz は表示されません。',
      CAN_ANSWER: 'いずれかに合格すると点灯します：',
      WAITING: '合格だけが足りませんが、いま回答できる Quiz がありません。しばらくしてからご確認ください。',
      MISSING: 'まだ足りないもの：',
    },
    nodeLabel: '{{name}}：{{state}}',
    noState: '{{name}}',
    details: 'このノードについて',
    gives: '点灯すると得られるもの',
    noGives: 'ノード以外にはありません。',
    gates: {
      'sponsor-apply': '主催者に申し込めます。',
      referrer: '友だちに Quiz を推薦できます。',
      'benefit-provider': '協賛特典を出品できます。',
      'benefit-sponsor': '協賛特典を購入できます。',
    },
    condition: {
      ALL: '以下のすべて',
      ANY: '以下のいずれか',
      AT_LEAST: '以下のうち {{n}} 個以上',
      node: '「{{name}}」を点灯',
      beforeAnswer: '回答の前に',
      passed: 'このノードの Quiz に合格',
      lit: '点灯済み',
      notLit: '未点灯',
      unknownNode: 'ノード',
    },
    litCount: '{{count}} 人のテイカーが点灯',
    litCountHidden: '統計の下限より少ない人数です',
    unlitCount: '回答したが未点灯のテイカー {{count}} 人（{{day}} 時点）',
    unlitCountHidden: '回答したが未点灯の人数は統計の下限より少ない人数です（{{day}} 時点）',
    unmet: '先に点灯：',
    empty: 'このマップにはまだノードがありません。',
  },
}

export const treeMessages = { en, zh, ja }

addMessages(QB, treeMessages)

/** The key under `tree.gates` of a gate parameter (`qb.tree.gate.referrer-node` → `referrer`); null for another. */
export function gateKey(param: string): string | null {
  const match = /^qb\.tree\.gate\.(.+)-node$/.exec(param)
  return match && match[1] in en.tree.gates ? match[1] : null
}
