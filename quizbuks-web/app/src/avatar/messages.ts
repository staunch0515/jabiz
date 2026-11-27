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
    photo: {
      upload: 'Upload a photo',
      note: 'A JPEG or PNG. The middle square is kept and its location data removed; only you see it.',
      replaces: 'Choosing another avatar or photo deletes this photo.',
      preview: 'Your photo as your avatar',
      use: 'Use this photo',
      cancel: 'Cancel',
      remove: 'Remove photo',
      removeTitle: 'Remove your photo?',
      removeText: 'The photo is deleted, and your initial is shown instead.',
      problems: { notImage: 'Choose a JPEG or PNG picture.', tooLarge: 'This picture is too large. Choose one under 20 MB.' },
    },
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
    photo: {
      upload: '上传照片',
      note: 'JPEG 或 PNG。保留中间的正方形并去掉位置信息，只有你自己看得到。',
      replaces: '选择其他头像或照片后，这张照片会被删除。',
      preview: '作为头像的照片',
      use: '使用这张',
      cancel: '取消',
      remove: '移除照片',
      removeTitle: '移除照片？',
      removeText: '照片会被删除，改为显示名字的首字。',
      problems: { notImage: '请选择 JPEG 或 PNG 图片。', tooLarge: '图片太大，请选择 20 MB 以下的图片。' },
    },
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
    photo: {
      upload: '写真をアップロード',
      note: 'JPEG または PNG。中央の正方形を使い、位置情報は削除されます。あなたにだけ表示されます。',
      replaces: 'ほかのアイコンや写真を選ぶと、この写真は削除されます。',
      preview: 'アイコンにする写真',
      use: 'この写真を使う',
      cancel: 'キャンセル',
      remove: '写真を削除',
      removeTitle: '写真を削除しますか？',
      removeText: '写真は削除され、名前の頭文字が表示されます。',
      problems: { notImage: 'JPEG または PNG の画像を選んでください。', tooLarge: '画像が大きすぎます。20 MB 未満の画像を選んでください。' },
    },
    presets: { p01: 'フクロウ', p02: 'キツネ', p03: 'ネコ', p04: 'ウサギ', p05: 'ペンギン', p06: 'クマ', p07: 'クジラ',
      p08: '本', p09: '角帽', p10: '電球', p11: 'ロケット', p12: '地球' },
  },
}

export const AVATAR_MESSAGES = { en, zh, ja }

addMessages(APP, AVATAR_MESSAGES)
