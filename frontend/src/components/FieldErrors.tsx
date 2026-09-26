import type { Violation } from '../meta/types'

/**
 * Violations of one field, from the client check or from the server. Both come with the rule code, which is kept on
 * the element: the client and the server report the same codes (decision D15).
 */
export default function FieldErrors({ violations }: { violations: Violation[] }) {
  return (
    <>
      {violations.map((v) => (
        <div
          key={v.ruleCode + v.message}
          className="ant-form-item-explain-error"
          style={{ color: 'var(--ant-color-error, #ff4d4f)' }}
          data-rule-code={v.ruleCode}
          role="alert"
        >
          {v.message}
        </div>
      ))}
    </>
  )
}
