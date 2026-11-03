import { Button, Input, Label, notify } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { problemText } from '../qb/problems'
import { playSound } from '../qb/sound'
import { checkPass, passDigitsOf, redeemPass } from './api'

/**
 * Q89c (D-Q89-10): the other way round from the codes above: the taker shows their pass and reads out its six digits;
 * the sponsor looks the prize up and hands it over. The server finds only this counter's passes and limits the
 * lookups (six digits are guessable).
 */
export function CustomerPass({ onHandedOver }: { onHandedOver: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [text, setText] = useState('')
  const [digits, setDigits] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const typed = passDigitsOf(text)
  const prize = useQuery({ queryKey: ['qb', 'customer-pass', digits], queryFn: () => checkPass(digits!),
    enabled: !!digits, retry: false, staleTime: Infinity, gcTime: 0 })
  const handOver = useMutation({
    mutationFn: () => redeemPass(prize.data!.passId),
    onSuccess: () => {
      setDone(true)
      notify.success(t('publishing.redeem.pass.handedOver'))
      playSound('reward')
      onHandedOver()
    },
  })
  const next = () => {
    setDigits(null)
    setText('')
    setDone(false)
    handOver.reset()
    void queryClient.removeQueries({ queryKey: ['qb', 'customer-pass'] })
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (typed) setDigits(typed)
  }
  return (
    <section className="flex flex-col gap-3 rounded-lg border p-4" aria-labelledby={`${id}-title`}
      data-testid="redeem-customer-pass">
      <h2 id={`${id}-title`} className="text-lg font-semibold">{t('publishing.redeem.pass.title')}</h2>
      <p className="text-muted-foreground text-sm">{t('publishing.redeem.pass.intro')}</p>
      {!digits ? (
        <form noValidate className="flex flex-col gap-2" onSubmit={submit}>
          <Label htmlFor={`${id}-digits`}>{t('publishing.redeem.pass.digits')}</Label>
          <Input id={`${id}-digits`} value={text} inputMode="numeric" autoComplete="off" maxLength={12}
            className="max-w-48 font-mono" aria-describedby={`${id}-hint`} data-testid="redeem-pass-digits"
            onChange={(event) => setText(event.target.value)} />
          <p id={`${id}-hint`} className="text-muted-foreground text-xs">{t('publishing.redeem.pass.digitsHint')}</p>
          <Button type="submit" className="w-fit" disabled={!typed} data-testid="redeem-pass-read">
            {t('publishing.redeem.pass.read')}
          </Button>
        </form>
      ) : prize.isPending ? (
        <div className="bg-muted h-24 animate-pulse rounded-lg" aria-hidden />
      ) : prize.isError ? (
        <div className="flex flex-col gap-2">
          <p role="alert" className="text-destructive text-sm">{problemText(prize.error)}</p>
          <Button variant="outline" className="w-fit" onClick={next}>{t('publishing.redeem.pass.another')}</Button>
        </div>
      ) : (
        <div className="flex flex-col gap-3" data-testid="redeem-pass-prize">
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            <dt className="text-muted-foreground">{t('publishing.redeem.pass.prize')}</dt>
            <dd className="whitespace-pre-line font-medium" data-testid="redeem-pass-description">
              {prize.data.description}</dd>
            <dt className="text-muted-foreground">{t('publishing.redeem.pass.publication')}</dt>
            <dd>{prize.data.publicationName ?? '—'}</dd>
            <dt className="text-muted-foreground">{t('publishing.redeem.pass.taker')}</dt>
            <dd data-testid="redeem-pass-taker">{prize.data.takerName ?? '?'}</dd>
            <dt className="text-muted-foreground">{t('publishing.redeem.pass.value')}</dt>
            <dd>{formatKudos(prize.data.amount)}</dd>
          </dl>
          {handOver.isError && <p role="alert" className="text-destructive text-sm">{problemText(handOver.error)}</p>}
          <div className="flex flex-wrap gap-2">
            {!done && (
              <Button onClick={() => handOver.mutate()} disabled={handOver.isPending} data-testid="redeem-pass-hand-over">
                <Check aria-hidden />
                {t('publishing.redeem.pass.handOver')}
              </Button>
            )}
            {(done || handOver.isError) && (
              <Button variant="outline" onClick={next} data-testid="redeem-pass-next">
                {t('publishing.redeem.pass.another')}
              </Button>
            )}
          </div>
        </div>
      )}
    </section>
  )
}
