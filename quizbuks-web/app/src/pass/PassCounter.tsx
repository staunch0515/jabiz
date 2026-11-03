import { Button, Input, Label, notify } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, ScanLine } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { formatKudos } from '../qb/kudos'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { playSound } from '../qb/sound'
import { SCAN_PATH } from '../scan/ScanPage'
import { checkPass, COUNTER_QUERY, digitsOf, notStaff, redeemPass, STAFF_QUERY, type PassRead } from '../staff/api'
import './messages'

/**
 * What a taker's pass is for, read at the counter (Q89c): the prize, its quiz and the customer, and the button that
 * hands it over (in the staff member's name). Asked once per pass read; the server decides everything.
 */
export function PassPrizeCard({ read, onNext }: { read: PassRead; onNext?: () => void }) {
  const { t } = useTranslation(APP)
  const queryClient = useQueryClient()
  const [done, setDone] = useState(false)
  const prize = useQuery({ queryKey: ['qb', 'staff-pass', read], queryFn: () => checkPass(read), retry: false,
    staleTime: Infinity, gcTime: 0 })
  const handOver = useMutation({
    mutationFn: () => redeemPass(prize.data!.passId),
    onSuccess: async () => {
      setDone(true)
      notify.success(t('pass.counter.handedOver'))
      playSound('reward')
      await queryClient.invalidateQueries({ queryKey: COUNTER_QUERY })
    },
    onError: async (error) => {
      if (notStaff(error)) await queryClient.invalidateQueries({ queryKey: STAFF_QUERY })
    },
  })
  if (prize.isPending) return <div className="bg-muted h-32 animate-pulse rounded-lg" aria-hidden />
  if (prize.isError) {
    return (
      <div className="flex flex-col gap-2" data-testid="pass-prize-error">
        <p role="alert" className="text-destructive text-sm">{problemText(prize.error)}</p>
        {onNext && <Button variant="outline" className="self-start" onClick={onNext}>{t('pass.counter.another')}</Button>}
      </div>
    )
  }
  const data = prize.data
  return (
    <div className="flex flex-col gap-3 rounded-lg border p-3" data-testid="pass-prize">
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-muted-foreground">{t('pass.counter.prize')}</dt>
        <dd className="whitespace-pre-line font-medium" data-testid="pass-prize-description">{data.description}</dd>
        <dt className="text-muted-foreground">{t('pass.counter.publication')}</dt>
        <dd>{data.publicationName ?? '—'}</dd>
        <dt className="text-muted-foreground">{t('pass.counter.taker')}</dt>
        <dd data-testid="pass-prize-taker">{data.takerName ?? '?'}</dd>
        <dt className="text-muted-foreground">{t('pass.counter.value')}</dt>
        <dd>{formatKudos(data.amount)}</dd>
      </dl>
      {handOver.isError && <p role="alert" className="text-destructive text-sm">{problemText(handOver.error)}</p>}
      <div className="flex flex-wrap gap-2">
        {!done && (
          <Button onClick={() => handOver.mutate()} disabled={handOver.isPending} data-testid="pass-hand-over">
            <Check aria-hidden />
            {t('pass.counter.handOver')}
          </Button>
        )}
        {(done || handOver.isError) && onNext && (
          <Button variant="outline" onClick={onNext} data-testid="pass-next">{t('pass.counter.another')}</Button>
        )}
      </div>
    </div>
  )
}

/**
 * The counter's other way (Q89c): the customer shows their pass; the staff member scans it (the app's scanner opens
 * its page) or types the six digits read out.
 */
export function CustomerPass() {
  const { t } = useTranslation(APP)
  const id = useId()
  const [text, setText] = useState('')
  const [read, setRead] = useState<PassRead | null>(null)
  const digits = digitsOf(text)
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (digits) setRead({ typedDigits: digits })
  }
  return (
    <section className="flex flex-col gap-3 rounded-lg border p-4" aria-labelledby={`${id}-title`}
      data-testid="staff-customer-pass">
      <h2 id={`${id}-title`} className="text-lg font-semibold">{t('pass.counter.title')}</h2>
      <p className="text-muted-foreground text-sm">{t('pass.counter.intro')}</p>
      <Link to={SCAN_PATH} className="inline-flex w-fit items-center gap-2 underline underline-offset-4"
        data-testid="staff-scan-customer">
        <ScanLine aria-hidden className="size-4" />
        {t('pass.counter.scan')}
      </Link>
      {read ? (
        <PassPrizeCard read={read} onNext={() => {
          setRead(null)
          setText('')
        }} />
      ) : (
        <form noValidate className="flex flex-col gap-2" onSubmit={submit}>
          <Label htmlFor={`${id}-digits`}>{t('pass.counter.digits')}</Label>
          <Input id={`${id}-digits`} value={text} inputMode="numeric" autoComplete="off" maxLength={12}
            className="max-w-48 font-mono" aria-describedby={`${id}-hint`} data-testid="staff-pass-digits"
            onChange={(event) => setText(event.target.value)} />
          <p id={`${id}-hint`} className="text-muted-foreground text-xs">{t('pass.counter.digitsHint')}</p>
          <Button type="submit" className="self-start" disabled={!digits} data-testid="staff-pass-read">
            {t('pass.counter.read')}
          </Button>
        </form>
      )}
    </section>
  )
}
