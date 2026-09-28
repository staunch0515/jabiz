import { useT } from '../i18n/locale'
import styles from './FilterBar.module.css'

export interface FilterOption {
  value: string
  label: string
  /** Shown before the label and hidden from screen readers (a flag, a theme's emoji). */
  icon?: string
}

export interface FilterGroup {
  /** The query string parameter holding this group's choices. */
  name: string
  legend: string
  options: FilterOption[]
}

interface Props {
  label: string
  groups: FilterGroup[]
  selected: Record<string, readonly string[]>
  onChange: (name: string, values: string[]) => void
  onClear: () => void
  /** Announced politely whenever it changes (design section 9.3). */
  status: string
}

/**
 * Filters as groups of checkboxes (design section 9.3): the page keeps the choices in its address, so a filtered list
 * can be shared; the number of results is announced to screen readers.
 */
export function FilterBar({ label, groups, selected, onChange, onClear, status }: Props) {
  const t = useT()
  const any = Object.values(selected).some((values) => values.length > 0)
  return (
    <div className={styles.bar} role="search" aria-label={label}>
      {groups
        .filter((group) => group.options.length > 0)
        .map((group) => (
          <fieldset key={group.name} className={styles.group}>
            <legend className="label">{group.legend}</legend>
            {group.options.map((option) => {
              const checked = (selected[group.name] ?? []).includes(option.value)
              return (
                <label key={option.value} className={styles.chip}>
                  <input
                    type="checkbox"
                    name={group.name}
                    value={option.value}
                    checked={checked}
                    onChange={() => {
                      const current = selected[group.name] ?? []
                      onChange(group.name, checked ? current.filter((v) => v !== option.value) : [...current, option.value])
                    }}
                  />
                  <span>
                    {option.icon ? <span aria-hidden="true">{option.icon}</span> : null}
                    {option.label}
                  </span>
                </label>
              )
            })}
          </fieldset>
        ))}
      <div className={styles.footer}>
        <p className="label" role="status" aria-live="polite">
          {status}
        </p>
        {any ? (
          <button type="button" className={styles.clear} onClick={onClear}>
            {t('stories.clear')}
          </button>
        ) : null}
      </div>
    </div>
  )
}
