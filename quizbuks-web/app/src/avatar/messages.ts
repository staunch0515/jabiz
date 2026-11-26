import { addMessages } from '@jabiz/client'
import { APP } from '../messages'

/** The texts of a taker's avatar (Q114a), part of the app's namespace `app`. The presets' names are what a screen reader says. */
const en = {
  avatar: {
    change: 'Change avatar',
    title: 'Choose your avatar',
    hint: 'Only you see your avatar. Choosing one saves it.',
    choices: 'Avatars',
    initial: 'Your initial',
    failed: 'Your avatar could not be saved. Try again.',
    presets: { p01: 'Owl', p02: 'Fox', p03: 'Cat', p04: 'Rabbit', p05: 'Penguin', p06: 'Bear', p07: 'Whale',
      p08: 'Book', p09: 'Mortarboard', p10: 'Light bulb', p11: 'Rocket', p12: 'Globe' },
  },
}

const zh: typeof en = {
  avatar: {
    change: '更换头像',
    title: '选择头像',
    hint: '头像只有你自己看得到。点选即保存。',
    choices: '头像',
    initial: '名字的首字',
    failed: '头像没能保存，请重试。',
    presets: { p01: '猫头鹰', p02: '狐狸', p03: '猫', p04: '兔子', p05: '企鹅', p06: '熊', p07: '鲸鱼', p08: '书',
      p09: '学士帽', p10: '灯泡', p11: '火箭', p12: '地球' },
  },
}

const ja: typeof en = {
  avatar: {
    change: 'アイコンを変更',
    title: 'アイコンを選ぶ',
    hint: 'アイコンはあなたにだけ表示されます。選ぶと保存されます。',
    choices: 'アイコン',
    initial: '名前の頭文字',
    failed: 'アイコンを保存できませんでした。もう一度お試しください。',
    presets: { p01: 'フクロウ', p02: 'キツネ', p03: 'ネコ', p04: 'ウサギ', p05: 'ペンギン', p06: 'クマ', p07: 'クジラ',
      p08: '本', p09: '角帽', p10: '電球', p11: 'ロケット', p12: '地球' },
  },
}

export const AVATAR_MESSAGES = { en, zh, ja }

addMessages(APP, AVATAR_MESSAGES)
