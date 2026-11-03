import { Button, PageState } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link, navigate } from '../qb/router'
import { notStaff, readStaffOf, STAFF_PATH, STAFF_QUERY } from '../staff/api'
import { PassPrizeCard } from './PassCounter'
import './messages'

/**
 * The page a taker's pass opens (`/pass/<key>`, Q89c): at a staff member's phone, the prize to hand over; anybody
 * else is told it is a customer's pass for the counter. Opening it does nothing; only the button hands the prize over.
 */
export function PassPage({ passKey }: { passKey: string }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const place = useQuery({ queryKey: STAFF_QUERY, queryFn: readStaffOf })
  if (place.isPending) return <PageState kind="loading" />
  if (place.isError && !notStaff(place.error)) {
    return <PageState kind="error" description={problemText(place.error)}
      action={<Button variant="outline" onClick={() => void place.refetch()}>{tQb('retry')}</Button>} />
  }
  if (!place.data) {
    return <PageState kind="empty" titleAs="h1" title={t('pass.counter.notStaffTitle')}
      description={t('pass.counter.notStaff')} />
  }
  return (
    <section className="flex flex-col gap-4" aria-labelledby="pass-page-title" data-testid="pass-page">
      <Link to={STAFF_PATH} className="text-muted-foreground inline-flex w-fit items-center gap-1 text-sm">
        <ArrowLeft aria-hidden className="size-4" />{t('pass.counter.toDesk')}
      </Link>
      <h1 id="pass-page-title" className="text-xl font-semibold">{t('pass.counter.scanned')}</h1>
      <PassPrizeCard read={{ scannedPass: passKey }} onNext={() => navigate(STAFF_PATH, { replace: true })} />
    </section>
  )
}
