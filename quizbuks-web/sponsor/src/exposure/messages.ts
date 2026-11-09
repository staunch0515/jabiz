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
  },
}

export const exposureMessages = { en, zh, ja }

addMessages(SPONSOR, exposureMessages)
