import { addMessages } from '@jabiz/client'
import { SPONSOR } from '../messages'

/**
 * The tree page's texts (Q85), part of the console's namespace `sponsor` (added here, next to the page). Since F-S2c2 (D5)
 * only what the page says while it is being updated.
 */
const en = {
  tree: {
    nav: 'Tree',
    title: 'Tree',
    soon: 'Coming soon: this page is being updated for the knowledge maps.',
    toPublications: 'Your publications',
    onTree: 'See on the tree',
  },
}

const zh = {
  tree: {
    nav: '科技树',
    title: '科技树',
    soon: '即将更新：这一页正在改为知识结构图。',
    toPublications: '去发布',
    onTree: '在树上查看',
  },
}

const ja = {
  tree: {
    nav: 'ツリー',
    title: 'ツリー',
    soon: '近日更新予定：このページは知識マップ向けに更新中です。',
    toPublications: '公開へ',
    onTree: 'ツリーで見る',
  },
}

export const treeMessages = { en, zh, ja }

addMessages(SPONSOR, treeMessages)
