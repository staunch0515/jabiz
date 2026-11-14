import { addMessages } from '@jabiz/client'
import { SPONSOR } from '../messages'

/** F-S13: a publication's exposures of another's shared video, part of the namespace `sponsor`. */
const en = {
  exposure: {
    title: 'Exposures of the shared video',
    hint: 'Your publication uses another Sponsor\'s shared video. One exposure is counted when a Taker watches it for long enough: in the vertical mode for a vertical video, on the quiz\'s start page for a horizontal one; at most once per Taker. Your own views and your staff\'s are not counted.',
    purchased: 'Bought',
    unlimited: 'Unlimited',
    successes: 'Counted',
    billable: 'Charged',
    overshoot: 'Beyond what you bought: {{n}} (not charged)',
    usedUp: 'All the exposures bought are used: the publication ends within a minute.',
    opens: 'Pushes opened',
    goes: 'Went on to answer',
    fee: {
      title: 'Exposure fee',
      price: 'Price per exposure {{unit}}',
      left: '{{left}} of {{bought}} left',
      pending: 'The exposures counted since the last settlement come to about {{amount}} (before tax).',
      low: 'Only {{left}} of the {{bought}} exposures you bought are left. Buy more to keep the publication running.',
      buyMore: 'Buy more exposures',
      buyTitle: 'Buy more exposures',
      buyText: 'You buy more at the price frozen at your submission ({{unit}} each). The amount is set aside from your Platform fee account at once, with tax; what is not used comes back after the publication ends. Exposures already counted beyond what you had bought are charged together with the new ones, up to the new total.',
      additional: 'How many more',
      quote: 'This sets aside {{amount}} more, with tax.',
      short: 'Your Platform fee account has {{available}} available; {{shortfall}} short. Top up first.',
      confirm: 'Buy',
      bought: 'Bought: {{n}} more.',
      settlements: 'Settlements',
      row: '{{period}}: {{n}} exposures, {{amount}} and tax {{tax}}',
      finalRow: '{{period}} (last): {{n}} exposures, {{amount}} and tax {{tax}}',
      help: 'You pay for the exposures that are counted, never more than you bought; what is left of what you set aside comes back after the last settlement.',
    },
  },
}

const zh: typeof en = {
  exposure: {
    title: '共享视频的曝光',
    hint: '你的发布用了其他发起方共享的视频。学者看得够久即计一次曝光：竖视频在竖屏模式中，横视频在开始答题页；每位学者至多一次。你自己和你的店员看不计。',
    purchased: '已购',
    unlimited: '不限',
    successes: '已成功',
    billable: '计费',
    overshoot: '超出已购 {{n}} 次（不计费）',
    usedUp: '已购的曝光已用完：发布将在一分钟内结束。',
    opens: '推送被打开',
    goes: '去答题',
    fee: {
      title: '曝光费',
      price: '每次曝光 {{unit}}',
      left: '剩余 {{left}} / {{bought}} 次',
      pending: '上次结算以来计入的曝光约 {{amount}}（未含税）。',
      low: '你购买的 {{bought}} 次曝光只剩 {{left}} 次。追加购买可让发布继续进行。',
      buyMore: '追加购买曝光',
      buyTitle: '追加购买曝光',
      buyText: '按提交时冻结的单价（每次 {{unit}}）追加购买。金额含税，立即从平台费账户冻结；没用完的在发布结束后退回。此前已计入、超出已购的次数，会随新买的一并收费，以新的总数为限。',
      additional: '再买多少次',
      quote: '这次会再冻结 {{amount}}（含税）。',
      short: '平台费账户可用 {{available}}，还差 {{shortfall}}。请先充值。',
      confirm: '购买',
      bought: '已追加购买 {{n}} 次。',
      settlements: '结算记录',
      row: '{{period}}：{{n}} 次，{{amount}}，税 {{tax}}',
      finalRow: '{{period}}（最终）：{{n}} 次，{{amount}}，税 {{tax}}',
      help: '按计入的曝光次数付费，不会超过你购买的次数；冻结的款项用不完的部分在最终结算后退回。',
    },
  },
}

const ja: typeof en = {
  exposure: {
    title: '共有動画の表示回数',
    hint: 'この公開は他の主催者の共有動画を使っています。テイカーが十分な時間見ると 1 回の表示としてカウントします（縦型の動画は縦型モード、横型の動画はクイズの開始ページで、テイカー 1 人につき 1 回まで）。あなた自身とあなたのスタッフの視聴はカウントしません。',
    purchased: '購入済み',
    unlimited: '上限なし',
    successes: 'カウント済み',
    billable: '課金の対象',
    overshoot: '購入した回数を超えた分：{{n}} 回（課金なし）',
    usedUp: '購入した表示回数を使い切りました。公開は 1 分以内に終了します。',
    opens: '開かれたプッシュ',
    goes: '回答に進んだ人',
    fee: {
      title: '露出料',
      price: '1 回あたり {{unit}}',
      left: '残り {{left}} / {{bought}} 回',
      pending: '前回の精算以降にカウントされた表示は約 {{amount}}（税抜）です。',
      low: '購入した {{bought}} 回のうち残りは {{left}} 回です。追加購入すると公開を続けられます。',
      buyMore: '表示回数を追加購入',
      buyTitle: '表示回数を追加購入',
      buyText: '提出時に固定された単価（1 回あたり {{unit}}）で追加購入します。金額は税込みでプラットフォーム利用料アカウントからすぐに確保され、使われなかった分は公開の終了後に返却されます。すでにカウントされていて購入回数を超えていた分は、新しい回数と合わせて、新しい合計を上限に課金されます。',
      additional: '追加する回数',
      quote: '今回、税込みで追加で {{amount}} を確保します。',
      short: 'プラットフォーム利用料アカウントの利用可能額は {{available}} で、{{shortfall}} 不足しています。先にチャージしてください。',
      confirm: '購入',
      bought: '{{n}} 回を追加購入しました。',
      settlements: '精算',
      row: '{{period}}：{{n}} 回、{{amount}}、税 {{tax}}',
      finalRow: '{{period}}（最終）：{{n}} 回、{{amount}}、税 {{tax}}',
      help: 'カウントされた表示回数に対して支払います（購入した回数が上限です）。確保した金額の使われなかった分は、最終の精算後に返却されます。',
    },
  },
}

export const exposureMessages = { en, zh, ja }

addMessages(SPONSOR, exposureMessages)
