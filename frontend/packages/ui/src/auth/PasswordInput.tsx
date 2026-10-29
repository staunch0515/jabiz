import { Eye, EyeOff } from 'lucide-react'
import { useCallback, useEffect, useId, useRef, useState, type ComponentProps, type Ref } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '../components/ui/button'
import { Input } from '../components/ui/input'
import { cn } from '../lib/utils'
import { AUTH_NAMESPACE } from './messages'

export type PasswordInputProps = Omit<ComponentProps<'input'>, 'type'>

/**
 * A password field with a button at its end that shows what was typed and hides it again (phase 17p). The button is
 * a toggle (`aria-pressed`) with one name in every state, as toggle buttons should; its icon shows the state. The
 * field goes back to hidden when its form is sent, so that the browser's password manager sees a password field and
 * the text is not left on screen.
 */
export function PasswordInput({ className, id, ref, disabled, ...input }: PasswordInputProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const [shown, setShown] = useState(false)
  const ownId = useId()
  const inputId = id ?? ownId
  const element = useRef<HTMLInputElement | null>(null)
  const setRef = useCallback(
    (node: HTMLInputElement | null) => {
      element.current = node
      assign(ref, node)
    },
    [ref],
  )

  useEffect(() => {
    const form = element.current?.form
    if (!shown || !form) return
    const hide = () => {
      // Before the form's own handlers, so that what reads the field on submit sees a password field.
      if (element.current) element.current.type = 'password'
      setShown(false)
    }
    form.addEventListener('submit', hide, { capture: true })
    return () => form.removeEventListener('submit', hide, { capture: true })
  }, [shown])

  const Icon = shown ? EyeOff : Eye
  return (
    <div className="relative">
      <Input
        {...input}
        ref={setRef}
        id={inputId}
        type={shown ? 'text' : 'password'}
        disabled={disabled}
        className={cn('pr-10', className)}
      />
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        aria-label={t('common.showTyped')}
        aria-pressed={shown}
        aria-controls={inputId}
        disabled={disabled}
        onClick={() => setShown((now) => !now)}
        className="text-muted-foreground hover:text-foreground absolute top-1/2 right-1 size-7 -translate-y-1/2"
        data-testid="password-reveal"
      >
        <Icon aria-hidden />
      </Button>
    </div>
  )
}

function assign<T>(ref: Ref<T> | undefined, value: T | null) {
  if (typeof ref === 'function') ref(value)
  else if (ref) ref.current = value
}
