import { Gift, HandCoins } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { PRIZES_PATH } from '../frame/areas'
import { TopPill, useUpdateWaiting } from '../frame/TopPill'
import { APP } from '../messages'
import { rewardPath, type CashRewardRow, type RedemptionRow } from '../qb/play'
import { navigate } from '../qb/router'

/** Where the taker's taps on the pill are kept: what was shown once is not shown again on this device. */
const SEEN_KEY = 'qb.home.notices.seen'

function seenNow(): ReadonlySet<string> {
  try {
    const value = JSON.parse(window.localStorage.getItem(SEEN_KEY) ?? '[]')
    return new Set(Array.isArray(value) ? value.map(String) : [])
  } catch {
    return new Set()
  }
}

function remember(keys: readonly string[]) {
  try {
    window.localStorage.setItem(SEEN_KEY, JSON.stringify(keys))
  } catch {
    // A browser that keeps nothing shows the pill again next time.
  }
}

/** One waiting item as the pill knows it: its key changes with its state, so a new state shows again. */
interface Waiting { key: string; path: string }

/**
 * 2026-11-28 (the requester): what waits for the taker (a prize to collect, a cash reward to see to) is one pill under
 * the header, x.com's "Show new posts", no longer bands that list each item at the top of the home. A tap goes to it
 * (one item: its page; more: the prizes) and the pill goes; it comes back only for something new or changed. A prize
 * first, then a cash reward; nothing while a new version of the app waits (its own pill, `UpdatePill`).
 */
export function HomeNotice({ prizes, cash }: { prizes: readonly RedemptionRow[]; cash: readonly CashRewardRow[] }) {
  const { t } = useTranslation(APP)
  const [seen, setSeen] = useState(seenNow)
  const updating = useUpdateWaiting()
  const prizeItems: Waiting[] = prizes.map((r) => ({ key: `prize:${r.redemptionId}:${r.status}`, path: rewardPath(r) }))
  const cashItems: Waiting[] = cash.map((r) => ({ key: `cash:${r.cashRewardId}:${r.status}`, path: rewardPath(r) }))
  const newPrizes = prizeItems.filter((item) => !seen.has(item.key))
  const newCash = cashItems.filter((item) => !seen.has(item.key))
  if (updating || (newPrizes.length === 0 && newCash.length === 0)) return null
  const open = (items: Waiting[]) => {
    // What is waiting now is seen; only those are kept (the list never grows past what waits).
    const keys = [...prizeItems, ...cashItems].map((item) => item.key)
    remember(keys)
    setSeen(new Set(keys))
    navigate(items.length === 1 ? items[0].path : PRIZES_PATH)
  }
  return newPrizes.length > 0
    ? <TopPill icon={<Gift aria-hidden />} label={t('redeem.openTitle', { count: newPrizes.length })}
      onClick={() => open(newPrizes)} testId="home-redeem" />
    : <TopPill icon={<HandCoins aria-hidden />} label={t('cash.openTitle', { count: newCash.length })}
      onClick={() => open(newCash)} testId="home-cash" />
}
