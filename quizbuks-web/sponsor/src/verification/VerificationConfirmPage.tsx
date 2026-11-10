import { Alert, AlertDescription, AlertTitle, PageState } from '@jabiz/ui'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { confirmVerification, VERIFICATION_PATH, VERIFICATION_QUERY } from './api'
import './messages'

/**
 * Where the link mailed to the company's address lands (F-M3): signed in, it confirms the address once
 * (`QB_SPONSOR_VERIFICATION_CONFIRM`) and the verification goes to the operator's review. The token is used once; a
 * second visit, an expired or replaced link, or another account's says so.
 */
export function VerificationConfirmPage({ search }: { search: string }) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const token = new URLSearchParams(search).get('token')
  const confirm = useMutation({
    mutationFn: (value: string) => confirmVerification(value),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: VERIFICATION_QUERY }),
  })
  // Once per page: a token is used once (a render twice must not use it twice).
  const asked = useRef(false)
  useEffect(() => {
    if (token && !asked.current) {
      asked.current = true
      confirm.mutate(token)
    }
  }, [token, confirm])

  const back = <Link to={VERIFICATION_PATH} className="text-primary underline">{t('verification.back')}</Link>
  return (
    <div className="flex max-w-xl flex-col gap-4" data-testid="verification-confirm">
      <h2 className="text-xl font-semibold">{t('verification.confirmTitle')}</h2>
      {!token ? (
        <p className="text-sm">{t('verification.noToken')}</p>
      ) : confirm.isPending || confirm.isIdle ? (
        <PageState kind="loading" title={t('verification.confirming')} />
      ) : confirm.isError ? (
        <Alert variant="destructive" data-testid="verification-confirm-failed">
          <AlertTitle>{t('verification.confirmFailed')}</AlertTitle>
          <AlertDescription>{problemText(confirm.error)}</AlertDescription>
        </Alert>
      ) : (
        <p role="status" className="text-sm" data-testid="verification-confirmed">{t('verification.confirmed')}</p>
      )}
      <p className="text-sm">{back}</p>
    </div>
  )
}
