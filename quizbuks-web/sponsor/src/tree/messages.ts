import { addMessages } from '@jabiz/client'
import { SPONSOR } from '../messages'

/**
 * The knowledge map page's texts (F-S3c; Q85's tree page before), part of the Sponsor app's namespace `sponsor` (added
 * here, next to the page). The map itself is the shared component's (`qb/tree/messages.ts`).
 */
const en = {
  tree: {
    nav: 'Knowledge map',
    title: 'Knowledge map',
    intro: 'Your map in effect and how many Takers lit each node. Publications bound to a node light it when passed.',
    toPublications: 'Your publications',
    onTree: 'See on the map',
    none: 'You have no knowledge map yet.',
    choose: 'Map',
    status: { ACTIVE: 'In effect', RETIRED: 'Retired' },
    open: {
      DRAFT: 'Version {{n}} is a draft.',
      REVIEW: 'Version {{n}} is in review.',
      REJECTED: 'Version {{n}} was sent back: {{reason}}',
    },
    inEffect: 'Version {{n}} in effect',
    noVersion: 'No version is in effect yet.',
    running: '{{count}} publications bound and not ended',
  },
}

const zh: typeof en = {
  tree: {
    nav: '知识结构图',
    title: '知识结构图',
    intro: '你生效中的图，以及每个节点有多少学者点亮。绑定节点的发布合格即点亮该节点。',
    toPublications: '去发布',
    onTree: '在图上查看',
    none: '你还没有知识结构图。',
    choose: '图',
    status: { ACTIVE: '生效中', RETIRED: '已停用' },
    open: {
      DRAFT: '第 {{n}} 版是草稿。',
      REVIEW: '第 {{n}} 版审核中。',
      REJECTED: '第 {{n}} 版被退回：{{reason}}',
    },
    inEffect: '生效版本：第 {{n}} 版',
    noVersion: '还没有生效的版本。',
    running: '绑定且未结束的发布 {{count}} 个',
  },
}

const ja: typeof en = {
  tree: {
    nav: '知識マップ',
    title: '知識マップ',
    intro: '有効なマップと、各ノードを点灯したテイカーの人数です。ノードに紐づく公開に合格すると、そのノードが点灯します。',
    toPublications: '公開へ',
    onTree: 'マップで見る',
    none: 'まだ知識マップがありません。',
    choose: 'マップ',
    status: { ACTIVE: '有効', RETIRED: '停止済み' },
    open: {
      DRAFT: 'バージョン {{n}} は下書きです。',
      REVIEW: 'バージョン {{n}} は審査中です。',
      REJECTED: 'バージョン {{n}} は差し戻されました：{{reason}}',
    },
    inEffect: '有効なバージョン：{{n}}',
    noVersion: '有効なバージョンはまだありません。',
    running: '紐づいていて終了していない公開 {{count}} 件',
  },
}

export const treeMessages = { en, zh, ja }

addMessages(SPONSOR, treeMessages)
