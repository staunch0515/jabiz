/**
 * Texts of the figures of blocking (F-S8): headquarters' page, in every platform language, merged into the extension's
 * messages under `blocks` (and the menu's entry under `menu`).
 */
export const blockMenu = {
  en: { blocks: 'Blocking' },
  zh: { blocks: '屏蔽' },
  ja: { blocks: 'ブロック' },
}

export const blockMessages = {
  en: {
    title: 'Blocking',
    description: 'Takers block a quiz, every round of a quiz, a Sponsor or a video provider; nothing paid gets past a block. Blocks are private: this page shows totals only, never who blocked what.',
    kinds: { PUBLICATION: 'Quizzes', QUIZ: 'Every round of a quiz', SPONSOR: 'Sponsors', PROVIDER: 'Video providers' },
    blocks: '{{n}} blocks',
    blockers: 'by {{n}} Takers',
    all: 'Takers blocking anything',
    sponsors: 'Sponsors most blocked',
    sponsorsHint: 'Takers who blocked the Sponsor, one of its quizzes or it as a video provider, rounded down to tens as the Sponsor sees it. Sponsors with fewer than {{min}} are not listed.',
    sponsor: 'Sponsor',
    sponsorBlockers: 'Takers (rounded)',
    none: 'No Sponsor has {{min}} or more Takers blocking it.',
  },
  zh: {
    title: '屏蔽',
    description: '学者可以屏蔽一个 Quiz、一个 Quiz 的所有期、一个发起方或一个视频制作方；付多少钱都越不过屏蔽。屏蔽只属于学者本人：这一页只有合计，不显示谁屏蔽了什么。',
    kinds: { PUBLICATION: 'Quiz', QUIZ: '一个 Quiz 的所有期', SPONSOR: '发起方', PROVIDER: '视频制作方' },
    blocks: '{{n}} 次屏蔽',
    blockers: '来自 {{n}} 位学者',
    all: '有屏蔽的学者',
    sponsors: '被屏蔽最多的发起方',
    sponsorsHint: '屏蔽了该发起方、它的某个 Quiz 或它作为视频制作方的学者，与发起方看到的一样向下取整到十位。少于 {{min}} 人的发起方不列出。',
    sponsor: '发起方',
    sponsorBlockers: '学者（取整）',
    none: '没有被 {{min}} 位以上学者屏蔽的发起方。',
  },
  ja: {
    title: 'ブロック',
    description: 'テイカーはクイズ、クイズのすべての回、主催者、動画の提供者をブロックできます。いくら支払ってもブロックは越えられません。ブロックはテイカー本人だけのもので、このページには合計だけを表示し、誰が何をブロックしたかは表示しません。',
    kinds: { PUBLICATION: 'クイズ', QUIZ: 'クイズのすべての回', SPONSOR: '主催者', PROVIDER: '動画の提供者' },
    blocks: '{{n}} 件のブロック',
    blockers: 'テイカー {{n}} 人',
    all: 'ブロックしているテイカー',
    sponsors: 'ブロックが多い主催者',
    sponsorsHint: '主催者、そのクイズ、または動画の提供者としての主催者をブロックしたテイカーの数です。主催者に表示するのと同じく 10 の位に切り捨てています。{{min}} 人未満の主催者は表示しません。',
    sponsor: '主催者',
    sponsorBlockers: 'テイカー（切り捨て）',
    none: '{{min}} 人以上にブロックされている主催者はいません。',
  },
}
