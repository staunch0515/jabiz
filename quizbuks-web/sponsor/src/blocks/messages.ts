import { addMessages } from '@jabiz/client'
import { SPONSOR } from '../messages'

/** F-S8: the dashboard's count of takers blocking the Sponsor, part of the namespace `sponsor`. */
const en = {
  blockers: {
    label: 'Takers who blocked you',
    value: 'About {{n}}',
    fewer: 'Fewer than {{n}}',
    hint: 'Takers who blocked you, one of your quizzes or you as a video provider. Rounded down to tens, never who or what: blocks are private.',
  },
}

const zh: typeof en = {
  blockers: {
    label: '屏蔽你的学者',
    value: '约 {{n}} 人',
    fewer: '少于 {{n}} 人',
    hint: '屏蔽了你、你的某个 Quiz 或你作为视频制作方的学者。向下取整到十位，不显示是谁、屏蔽了什么：屏蔽只属于学者本人。',
  },
}

const ja: typeof en = {
  blockers: {
    label: 'あなたをブロックしたテイカー',
    value: '約 {{n}} 人',
    fewer: '{{n}} 人未満',
    hint: 'あなた、あなたのクイズ、または動画の提供者としてのあなたをブロックしたテイカーです。10 の位に切り捨てて表示し、誰が何をブロックしたかは表示しません（ブロックはテイカー本人だけのものです）。',
  },
}

export const blockerMessages = { en, zh, ja }

addMessages(SPONSOR, blockerMessages)
