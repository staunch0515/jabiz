import { addMessages } from '@jabiz/client'
import { SPONSOR } from '../messages'

/** The help panel's texts (F-C10c), part of the Sponsor pages' namespace; the conversation's are shared (`qb`). */
const en = {
  helpPanel: {
    open: 'Help: ask the AI',
    openThread: 'Open the conversation',
  },
}

type Texts = typeof en

const zh: Texts = {
  helpPanel: {
    open: '帮助：问 AI',
    openThread: '查看对话',
  },
}

const ja: Texts = {
  helpPanel: {
    open: 'ヘルプ：AI に質問',
    openThread: '会話を開く',
  },
}

export const helpPanelMessages = { en, zh, ja }

addMessages(SPONSOR, helpPanelMessages)
