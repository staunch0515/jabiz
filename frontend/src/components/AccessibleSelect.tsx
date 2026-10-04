import { Select, type SelectProps } from 'antd'

/**
 * A select without the aria-required a form item gives it: Ant Design puts it on the select's outer element, where it
 * is not allowed (WCAG 4.1.2, axe aria-allowed-attr). Use it as the direct child of a required form item. The
 * requirement is then shown only by the label's mark and announced by the validation message
 * (docs/design/12-frontend.md section 11).
 */
export default function AccessibleSelect(props: SelectProps) {
  const { 'aria-required': ignored, ...rest } = props
  void ignored
  return <Select {...rest} />
}
