import { useId, useState, type ComponentProps } from 'react'
import { CheckIcon, ChevronsUpDownIcon, XIcon } from 'lucide-react'
import { useTranslation } from 'react-i18next'

import { UI_NAMESPACE } from '../i18n'
import { cn, UI_SCOPE } from '../lib/utils'
import { Button } from './ui/button'
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from './ui/command'
import { Popover, PopoverContent, PopoverTrigger } from './ui/popover'
import { Spinner } from './spinner'

export interface ComboboxOption {
  value: string
  /** What the option shows and is searched by. */
  label: string
  /** A second line (a code under a name); searched too. */
  description?: string
  disabled?: boolean
}

type TriggerAttributes = Pick<
  ComponentProps<'button'>,
  'id' | 'aria-label' | 'aria-labelledby' | 'aria-describedby' | 'aria-invalid' | 'aria-required'
>

export interface ComboboxProps extends TriggerAttributes {
  value: string | null | undefined
  onChange: (value: string | null) => void
  options: ComboboxOption[]
  /**
   * Remote mode: called with the text typed into the search field (debounce it, it is called on every key); the
   * options are then the server's answer and are not filtered here. Without it the options are filtered locally.
   */
  onSearch?: (text: string) => void
  /** The options are being fetched (remote mode). */
  loading?: boolean
  /** The chosen value's label when it is not among the options (a remote value chosen earlier). */
  selectedLabel?: string
  placeholder?: string
  searchPlaceholder?: string
  /** Shown when no option matches. */
  emptyText?: string
  /** Offers a button that empties the field. */
  clearable?: boolean
  disabled?: boolean
  required?: boolean
  invalid?: boolean
  className?: string
  'data-testid'?: string
}

/**
 * A choice from a list that can be searched (a dictionary, a referenced entry): a button (`role="combobox"` with
 * `aria-expanded`, `aria-required`, `aria-invalid`) opening a popover with a search field and the options
 * (`role="option"`, cmdk), static or fetched (remote mode). Name it with a <Label htmlFor={id}> or `aria-label`.
 */
export function Combobox({
  value,
  onChange,
  options,
  onSearch,
  loading = false,
  selectedLabel,
  placeholder,
  searchPlaceholder,
  emptyText,
  clearable = false,
  disabled = false,
  required,
  invalid,
  className,
  'aria-invalid': ariaInvalid,
  'aria-required': ariaRequired,
  'data-testid': testId,
  ...trigger
}: ComboboxProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const listId = useId()
  const chosen = value == null ? undefined : options.find((option) => option.value === value)
  const shown = chosen?.label ?? (value == null || value === '' ? undefined : (selectedLabel ?? value))

  const setOpenAndReset = (next: boolean) => {
    setOpen(next)
    if (!next && search !== '') {
      setSearch('')
      onSearch?.('')
    }
  }

  return (
    <div data-slot="combobox" className={cn(UI_SCOPE, 'relative flex w-full items-center', className)}>
      <Popover open={open} onOpenChange={setOpenAndReset}>
        <PopoverTrigger asChild>
          <Button
            {...trigger}
            variant="outline"
            role="combobox"
            aria-haspopup="listbox"
            aria-expanded={open}
            aria-controls={open ? listId : undefined}
            aria-required={required || ariaRequired || undefined}
            aria-invalid={invalid || ariaInvalid || undefined}
            disabled={disabled}
            data-testid={testId}
            className={cn(
              UI_SCOPE,
              // On the outline button's dark tint the muted placeholder would fall below 4.5:1.
              'w-full min-w-40 justify-between font-normal dark:bg-transparent',
              clearable && shown !== undefined && !disabled && 'pr-16',
              shown === undefined && 'text-muted-foreground',
            )}
          >
            <span className="truncate">{shown ?? placeholder ?? t('combobox.placeholder')}</span>
            <ChevronsUpDownIcon aria-hidden className="ml-auto opacity-60" />
          </Button>
        </PopoverTrigger>
        <PopoverContent className="w-(--radix-popover-trigger-width) min-w-56 p-0" align="start">
          <Command shouldFilter={!onSearch}>
            <CommandInput
              value={search}
              onValueChange={(text) => {
                setSearch(text)
                onSearch?.(text)
              }}
              placeholder={searchPlaceholder ?? t('combobox.search')}
              aria-label={searchPlaceholder ?? t('combobox.search')}
            />
            <CommandList id={listId}>
              {loading ? (
                <div className="flex justify-center py-6">
                  <Spinner />
                </div>
              ) : (
                <CommandEmpty>{emptyText ?? t('combobox.empty')}</CommandEmpty>
              )}
              {!loading && options.length > 0 && (
                <CommandGroup>
                  {options.map((option) => (
                    <CommandItem
                      key={option.value}
                      value={option.value}
                      keywords={[option.label, ...(option.description ? [option.description] : [])]}
                      disabled={option.disabled}
                      onSelect={() => {
                        onChange(option.value)
                        setOpenAndReset(false)
                      }}
                    >
                      <CheckIcon aria-hidden className={cn(UI_SCOPE, option.value === value ? 'opacity-100' : 'opacity-0')} />
                      <span className="flex min-w-0 flex-col">
                        <span className="truncate">{option.label}</span>
                        {option.description && (
                          <span className="text-muted-foreground truncate text-xs">{option.description}</span>
                        )}
                      </span>
                    </CommandItem>
                  ))}
                </CommandGroup>
              )}
            </CommandList>
          </Command>
        </PopoverContent>
      </Popover>
      {clearable && shown !== undefined && !disabled && (
        <Button
          variant="ghost"
          size="icon-sm"
          className="absolute right-8 size-7"
          aria-label={t('combobox.clear')}
          onClick={() => onChange(null)}
        >
          <XIcon aria-hidden />
        </Button>
      )}
    </div>
  )
}
