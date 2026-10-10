import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import { api } from '../api/client'

/**
 * Where the unsubscribe link of a notification mail leads (docs/design/18-numbering-approvals-tasks.md section 5.6):
 * posts the link's token once and says whether the notifications are off. Open to everyone, outside the sign-in: the
 * signed token is the credential. Plain React and its own texts, so that it does not depend on the component library.
 */
const TEXTS = {
  en: {
    title: 'Unsubscribe',
    working: 'Turning these notifications off…',
    done: 'You will no longer receive these notifications. You can turn them on again in your account settings.',
    invalid: 'This unsubscribe link is invalid.',
    failed: 'Something went wrong. Please try the link again later.',
  },
  ja: {
    title: '配信停止',
    working: 'このお知らせの配信を停止しています…',
    done: 'このお知らせは今後届きません。アカウント設定から再開できます。',
    invalid: 'この配信停止リンクは無効です。',
    failed: '問題が発生しました。しばらくしてからもう一度リンクを開いてください。',
  },
  zh: {
    title: '退订',
    working: '正在退订这类通知…',
    done: '您将不再收到这类通知。可以在账户设置中重新开启。',
    invalid: '此退订链接无效。',
    failed: '出现问题，请稍后再次打开链接。',
  },
} as const

type State = 'working' | 'done' | 'invalid' | 'failed'

export default function MailUnsubscribePage() {
  const { i18n } = useTranslation()
  const [params] = useSearchParams()
  const token = params.get('token')
  const [state, setState] = useState<State>(token ? 'working' : 'invalid')
  const sent = useRef(false)
  const language = (i18n.language?.slice(0, 2) ?? 'en') as keyof typeof TEXTS
  const texts = TEXTS[language] ?? TEXTS.en

  useEffect(() => {
    // Once, even when React runs effects twice in development; repeating it would change nothing anyway.
    if (!token || sent.current) return
    sent.current = true
    api.POST('/api/auth/mail/unsubscribe', { body: { token } })
      .then(({ response }) => setState(response.ok ? 'done' : response.status === 422 ? 'invalid' : 'failed'))
      .catch(() => setState('failed'))
  }, [token])

  return (
    <main style={{ maxWidth: 480, margin: '15vh auto', padding: '0 16px', fontFamily: 'system-ui, sans-serif' }}>
      <h1 style={{ fontSize: 24 }}>{texts.title}</h1>
      <p role={state === 'working' ? 'status' : 'alert'} data-testid="unsubscribe-state" data-state={state}>
        {texts[state]}
      </p>
    </main>
  )
}
