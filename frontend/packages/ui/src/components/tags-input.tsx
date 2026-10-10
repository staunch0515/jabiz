import { useState, type ComponentProps } from 'react'
import { XIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { cn, UI_SCOPE } from '../lib/utils'
import { Badge } from './ui/badge'

type InputAttributes = Omit<
  ComponentProps<'input'>,
  'value' | 'onChange' | 'type' | 'defaultValue' | 'className' | 'children'
>

export interface TagsInputProps extends InputAttributes {
  value: string[]
  onChange: (value: string[]) => void
  className?: string
}

/**
 * A list of short texts (tags, codes): typed into one field, each added on Enter or a comma (or when the field is
 * left), removed with its own button or Backspace in the empty field. A tag already in the list is not added twice.
 * The text field takes the id, name and aria attributes.
 */
export function TagsInput({
  value,
  onChange,
  placeholder,
  disabled,
  readOnly,
  className,
  onBlur,
  ...input
}: TagsInputProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const [text, setText] = useState('')
  const locked = disabled || readOnly

  const add = (raw: string) => {
    const tags = raw
      .split(',')
      .map((tag) => tag.trim())
      .filter((tag) => tag !== '' && !value.includes(tag))
    if (tags.length > 0) onChange([...value, ...new Set(tags)])
    setText('')
  }

  return (
    <div
      data-slot="tags-input"
      className={cn(
        UI_SCOPE,
        'border-input dark:bg-input/30 flex min-h-9 w-full flex-wrap items-center gap-1 rounded-md border bg-transparent px-2 py-1 shadow-xs',
        'focus-within:border-ring focus-within:ring-ring/50 focus-within:ring-[3px]',
        'has-aria-invalid:border-destructive',
        disabled && 'opacity-50',
        className,
      )}
    >
      {value.length > 0 && (
        <ul className="contents">
          {value.map((tag) => (
            <li key={tag} className="contents">
              <Badge variant="secondary" className="gap-0.5 pr-0.5">
                {tag}
                {!locked && (
                  <button
                    type="button"
                    className={cn(UI_SCOPE, 'hover:bg-accent focus-visible:ring-ring/50 rounded-sm p-0.5 outline-none focus-visible:ring-[3px]')}
                    aria-label={t('tags.remove', { tag })}
                    onClick={() => onChange(value.filter((other) => other !== tag))}
                  >
                    <XIcon aria-hidden />
                  </button>
                )}
              </Badge>
            </li>
          ))}
        </ul>
      )}
      <input
        {...input}
        type="text"
        value={text}
        disabled={disabled}
        readOnly={readOnly}
        placeholder={value.length === 0 ? (placeholder ?? t('tags.placeholder')) : undefined}
        className={cn(UI_SCOPE, 'placeholder:text-muted-foreground min-w-24 flex-1 bg-transparent py-1 text-base outline-none md:text-sm')}
        onChange={(event) => {
          const next = event.target.value
          if (next.includes(',')) add(next)
          else setText(next)
        }}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            // Enter adds the tag; with nothing typed it may submit the form around the field.
            if (text.trim() !== '') {
              event.preventDefault()
              add(text)
            }
          } else if (event.key === 'Backspace' && text === '' && value.length > 0 && !locked) {
            onChange(value.slice(0, -1))
          }
        }}
        onBlur={(event) => {
          if (text.trim() !== '') add(text)
          onBlur?.(event)
        }}
      />
    </div>
  )
}
