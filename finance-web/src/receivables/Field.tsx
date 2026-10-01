import type { ReactNode } from 'react'

/** A form field with its label above it. */
export default function Field({ id, label, grow, children }: { id: string; label: string; grow?: boolean;
  children: ReactNode }) {
  return (
    <div style={grow ? { flex: 1, minWidth: 280 } : undefined}>
      <label htmlFor={id} style={{ display: 'block' }}>
        {label}
      </label>
      {children}
    </div>
  )
}
