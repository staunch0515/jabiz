import { useEffect, useState } from 'react'
import { CheckIcon, CopyIcon, XIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { cn, UI_SCOPE } from '../lib/utils'
import { Button } from './ui/button'

export interface CopyButtonProps {
  /** The text put on the clipboard. */
  value: string
  /** The button's accessible name; "Copy" when absent. Name what is copied when several buttons are near. */
  label?: string
  className?: string
  'data-testid'?: string
}

/**
 * Copies a text (a key, a recovery code, an identifier) to the clipboard. The outcome is shown on the button for
 * a moment and announced politely; without clipboard access (an insecure page, a refusal) it says it could not.
 */
export function CopyButton({ value, label, className, ...rest }: CopyButtonProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle')

  useEffect(() => {
    if (state === 'idle') return
    const timer = window.setTimeout(() => setState('idle'), 2000)
    return () => window.clearTimeout(timer)
  }, [state])

  const copy = async () => {
    try {
      if (!navigator.clipboard) throw new Error('no clipboard')
      await navigator.clipboard.writeText(value)
      setState('copied')
    } catch {
      setState('failed')
    }
  }

  const Icon = state === 'copied' ? CheckIcon : state === 'failed' ? XIcon : CopyIcon
  return (
    <span data-slot="copy-button" className={cn(UI_SCOPE, 'inline-flex items-center', className)}>
      <Button
        variant="ghost"
        size="icon-sm"
        aria-label={label ?? t('copy.copy')}
        title={label ?? t('copy.copy')}
        onClick={() => void copy()}
        {...rest}
      >
        <Icon aria-hidden className={cn(UI_SCOPE, state === 'failed' && 'text-destructive')} />
      </Button>
      <span role="status" className="sr-only">
        {state === 'copied' ? t('copy.copied') : state === 'failed' ? t('copy.failed') : ''}
      </span>
    </span>
  )
}
