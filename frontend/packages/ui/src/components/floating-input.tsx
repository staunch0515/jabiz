import { useId, useState, type ComponentProps, type ReactNode } from 'react'

import { cn } from '../lib/utils'
import { Input } from './ui/input'

/** What {@link FloatingInput} hands to `renderInput`: the input's own props, its id and classes included. */
export type FloatingInputFieldProps = ComponentProps<'input'> & { id: string; className: string }

export type FloatingInputProps = Omit<ComponentProps<'input'>, 'placeholder'> & {
  /** The field's name, shown inside the box: large while it is empty, small above the text once focused or filled. */
  label: string
  /** The refusal under the field (`aria-invalid` and `aria-describedby` follow). */
  error?: string
  /** Renders the input itself (a password field with its "show" button, say); a plain input when absent. */
  renderInput?: (props: FloatingInputFieldProps) => ReactNode
}

/**
 * A text field with its label inside the box (phase 17zz30, 12 §13.1): 62 px high, corners of 6 px, the label 17 px
 * while the field is empty and unfocused, 12 px at the top otherwise; focused, a 2 px border in the accent colour.
 * The label is the field's `<label>`, so its name.
 */
export function FloatingInput({ label, error, renderInput, id, className, onFocus, onBlur, ...input }: FloatingInputProps) {
  const ownId = useId()
  const errorId = useId()
  const inputId = id ?? ownId
  const [focused, setFocused] = useState(false)
  const field: FloatingInputFieldProps = {
    ...input,
    id: inputId,
    // An empty placeholder lets CSS tell an empty field (`:placeholder-shown`) from a filled one.
    placeholder: ' ',
    'aria-invalid': error ? true : input['aria-invalid'],
    'aria-describedby': error ? errorId : input['aria-describedby'],
    onFocus: (event) => {
      setFocused(true)
      onFocus?.(event)
    },
    onBlur: (event) => {
      setFocused(false)
      onBlur?.(event)
    },
    className: cn(
      'h-[3.875rem] rounded-[6px] px-2.5 pt-6 pb-1.5 text-[1.0625rem] md:text-[1.0625rem] shadow-none',
      'focus-visible:border-primary focus-visible:ring-0 focus-visible:border-2 focus-visible:px-[9px]',
      className,
    ),
  }
  return (
    <div className="flex flex-col gap-1.5" data-slot="floating-input">
      <div className="group relative">
        {renderInput ? renderInput(field) : <Input {...field} />}
        <label
          htmlFor={inputId}
          className={cn(
            'pointer-events-none absolute left-2.5 leading-[18px] transition-all duration-150',
            focused
              ? 'text-primary top-2 text-xs'
              : 'text-muted-foreground top-2 text-xs group-has-[input:placeholder-shown]:top-5 group-has-[input:placeholder-shown]:text-[1.0625rem]',
          )}
        >
          {label}
        </label>
      </div>
      {error && (
        <p id={errorId} className="text-destructive text-sm">
          {error}
        </p>
      )}
    </div>
  )
}
