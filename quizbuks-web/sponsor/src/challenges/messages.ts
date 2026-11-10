import { addMessages } from '@jabiz/client'
import { SPONSOR } from '../messages'

/** The challenges' texts (F-S4c), under `challenges.*` of the Sponsor app's namespace. */
export const challengesMessages = {
  en: {
    challenges: {
      none: 'No challenges yet',
      noneHint: 'Your operator has not opened a challenge yet. When it does, it shows here.',
      notApproved: 'You can answer challenges once your Sponsor application is approved.',
      start: 'Answer “{{name}}”',
      answered: 'Challenges you answered',
      back: 'Back to the challenges',
      taken: 'You have answered this challenge. Its result is in the challenges.',
      finishing: 'Your answers are being submitted…',
      resultTitle: 'Challenge result',
      passed: 'Passed',
      notPassed: 'Not passed',
      score: 'Score: {{score}} / {{full}}',
      waitNext: 'Not passed this time. Each challenge is answered once: wait for your operator\'s next challenge of this node.',
      lighting: 'Passed: the node is being lit…',
      lit: '“{{name}}” is lit.',
      grant: 'Granted credit {{amount}} (once; it expires: see when in the platform fee account).',
      toAccount: 'Platform fee account',
      questions: 'Questions',
    },
  },
  zh: {
    challenges: {
      none: '还没有挑战',
      noneHint: '所属运营商还没有开放挑战。开放后会显示在这里。',
      notApproved: '发起方申请通过后即可答挑战。',
      start: '答“{{name}}”',
      answered: '答过的挑战',
      back: '回到挑战',
      taken: '这个挑战你已经答过了，结果在挑战页。',
      finishing: '正在提交你的答案…',
      resultTitle: '挑战结果',
      passed: '合格',
      notPassed: '不合格',
      score: '得分：{{score}} / {{full}}',
      waitNext: '这次没有合格。每个挑战只能答一次：请等运营商这个节点的下一场挑战。',
      lighting: '已合格：节点正在点亮…',
      lit: '“{{name}}”已点亮。',
      grant: '赠送额度 {{amount}}（一次；有到期日，见平台费账户）。',
      toAccount: '平台费账户',
      questions: '题目',
    },
  },
  ja: {
    challenges: {
      none: 'チャレンジはまだありません',
      noneHint: '所属する運営パートナーがまだチャレンジを公開していません。公開されるとここに表示されます。',
      notApproved: '主催者の申し込みが承認されるとチャレンジに回答できます。',
      start: '「{{name}}」に回答する',
      answered: '回答したチャレンジ',
      back: 'チャレンジに戻る',
      taken: 'このチャレンジには回答済みです。結果はチャレンジのページにあります。',
      finishing: '回答を送信しています…',
      resultTitle: 'チャレンジの結果',
      passed: '合格',
      notPassed: '不合格',
      score: '得点：{{score}} / {{full}}',
      waitNext: '今回は合格しませんでした。チャレンジの回答は 1 回だけです。運営パートナーのこのノードの次のチャレンジをお待ちください。',
      lighting: '合格しました。ノードを点灯しています…',
      lit: '「{{name}}」が点灯しました。',
      grant: '付与クレジット {{amount}}（1 回。有効期限はプラットフォーム利用料アカウントで確認できます）。',
      toAccount: 'プラットフォーム利用料アカウント',
      questions: '問題',
    },
  },
} as const

addMessages(SPONSOR, challengesMessages)
