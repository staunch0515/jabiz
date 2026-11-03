import {
  Alert,
  AlertDescription,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
} from '@jabiz/ui'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { fieldProblem, generalProblem } from '../qb/problems'
import { buyKudos, parseKudos, sellKudos, WALLET_QUERY, type ExchangeOutput } from './api'
import './messages'

/**
 * Buying Kudos with the cash account, or selling them back (Q87, `QB_KUDOS_BUY` / `QB_KUDOS_SELL`): one for one, as
 * the server records it. The server checks the switch, the cash, what is available and the fees unpaid; the dialog
 * shows its refusal and, on success, both balances as answered.
 */
export function ExchangeDialog({ direction, open, onOpenChange, onDone }: {
  direction: 'BUY' | 'SELL'
  open: boolean
  onOpenChange: (open: boolean) => void
  onDone: (output: ExchangeOutput) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [text, setText] = useState('')
  const [invalid, setInvalid] = useState(false)
  const exchange = useMutation({
    mutationFn: (amount: number) => (direction === 'BUY' ? buyKudos(amount) : sellKudos(amount)),
    onSuccess: async (output) => {
      await queryClient.invalidateQueries({ queryKey: WALLET_QUERY })
      close()
      onDone(output)
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const amount = parseKudos(text)
    setInvalid(amount === null)
    if (amount !== null && !exchange.isPending) exchange.mutate(amount)
  }
  function close() {
    setText('')
    setInvalid(false)
    exchange.reset()
    onOpenChange(false)
  }
  const problem = invalid ? t('wallet.cash.dialog.invalid') : fieldProblem(exchange.error, 'amount')
  const general = generalProblem(exchange.error, ['amount'])
  const key = direction === 'BUY' ? 'buy' : 'sell'
  return (
    <Dialog open={open} onOpenChange={(next) => (next ? onOpenChange(true) : close())}>
      <DialogContent data-testid="exchange-dialog" data-direction={direction}>
        <form noValidate onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t(`wallet.cash.dialog.${key}Title`)}</DialogTitle>
            <DialogDescription>{t(`wallet.cash.dialog.${key}Hint`)}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-amount`}>{t('wallet.cash.dialog.amount')}</Label>
            <Input id={`${id}-amount`} value={text} inputMode="numeric" autoComplete="off" required maxLength={17}
              aria-invalid={problem ? true : undefined}
              aria-describedby={`${id}-amount-hint${problem ? ` ${id}-amount-error` : ''}`}
              data-testid="exchange-amount" onChange={(event) => {
                setText(event.target.value)
                setInvalid(false)
                exchange.reset()
              }} />
            <p id={`${id}-amount-hint`} className="text-muted-foreground text-sm">{t('wallet.cash.dialog.amountHint')}</p>
            {problem && <p id={`${id}-amount-error`} className="text-destructive text-sm">{problem}</p>}
          </div>
          {general && (
            <Alert variant="destructive" data-testid="exchange-problem"><AlertDescription>{general}</AlertDescription></Alert>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={close}>{t('wallet.cash.dialog.cancel')}</Button>
            <Button type="submit" disabled={exchange.isPending} aria-busy={exchange.isPending || undefined}
              data-testid="exchange-submit">
              {t(`wallet.cash.dialog.${key}`)}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** What an exchange answered, in words: both balances after it. */
export function exchangeDone(t: (key: string, values?: Record<string, unknown>) => string, output: ExchangeOutput) {
  return t(output.direction === 'BUY' ? 'wallet.cash.bought' : 'wallet.cash.sold', {
    amount: formatKudos(output.amount), kudos: formatKudos(output.kudosBalance), cash: formatKudos(output.cashBalance),
  })
}
