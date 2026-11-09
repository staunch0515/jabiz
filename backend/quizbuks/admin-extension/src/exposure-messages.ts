/**
 * Texts of the suspicious inflation of exposures (F-S13): the page shared by headquarters and the operators, in every
 * platform language, merged into the extension's messages under `exposure` (and the menu's entry under `menu`).
 */
export const exposureMenu = {
  en: { exposure: 'Exposure counts' },
  zh: { exposure: '曝光计数' },
  ja: { exposure: '表示回数のカウント' },
}

export const exposureMessages = {
  en: {
    title: 'Exposure counts',
    description: 'Exposures of shared videos count once per Taker and publication, and at most the daily cap a Taker a day. The Takers below reached the cap: look at them when the figures of a publication seem inflated. Operators see their own Sponsors\' publications only.',
    taker: 'Taker',
    hits: 'Daily cap reached',
    publications: 'Publications refused',
    lastCappedOn: 'Last reached',
    successes: 'Exposures counted',
    none: 'No Taker has reached the daily cap.',
  },
  zh: {
    title: '曝光计数',
    description: '共享视频的曝光每位学者每个发布只计一次，每位学者每天至多计到每日上限。下面的学者达到过上限：某个发布的数字看起来被刷高时，从这里查看。运营商只看到自己发起方的发布。',
    taker: '学者',
    hits: '达到每日上限',
    publications: '被拒的发布',
    lastCappedOn: '最近一次',
    successes: '已成功次数',
    none: '没有学者达到每日上限。',
  },
  ja: {
    title: '表示回数のカウント',
    description: '共有動画の表示回数はテイカー 1 人・公開 1 件につき 1 回、テイカー 1 人あたり 1 日の上限までカウントします。下のテイカーは上限に達したことがあります。公開の数字が水増しされているように見えるときはここを確認してください。運営パートナーには自分の主催者の公開だけが表示されます。',
    taker: 'テイカー',
    hits: '1 日の上限に達した回数',
    publications: '断った公開',
    lastCappedOn: '最後に達した日',
    successes: 'カウントした表示回数',
    none: '1 日の上限に達したテイカーはいません。',
  },
}
