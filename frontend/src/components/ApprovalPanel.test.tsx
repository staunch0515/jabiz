import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/problem'
import i18n from '../i18n'
import ApprovalPanel from './ApprovalPanel'

const runProcess = vi.fn()

vi.mock('../lib/calls', () => ({ runProcess: (...args: unknown[]) => runProcess(...args) }))

function renderPanel(onDecided = vi.fn()) {
  render(
    <App>
      <ApprovalPanel requestId="r-1" onDecided={onDecided} />
    </App>,
  )
  return onDecided
}

describe('ApprovalPanel', () => {
  beforeEach(async () => {
    runProcess.mockReset()
    await i18n.changeLanguage('en')
  })

  it('approves the current level and reports the new status', async () => {
    runProcess.mockResolvedValue({ status: 'APPROVED' })
    const onDecided = renderPanel()
    fireEvent.click(screen.getByTestId('approval-approve'))
    await waitFor(() => expect(onDecided).toHaveBeenCalledWith('APPROVED'))
    expect(runProcess).toHaveBeenCalledWith('APPROVAL_DECIDE', { requestId: 'r-1', decision: 'APPROVE',
      reason: undefined })
  })

  it('rejects only with a reason', async () => {
    runProcess.mockResolvedValue({ status: 'REJECTED' })
    const onDecided = renderPanel()
    const reject = screen.getByTestId('approval-reject') as HTMLButtonElement
    expect(reject.disabled).toBe(true)
    fireEvent.change(screen.getByLabelText('Reason (needed to reject)'), { target: { value: ' No invoice ' } })
    expect(reject.disabled).toBe(false)
    fireEvent.click(reject)
    await waitFor(() => expect(onDecided).toHaveBeenCalledWith('REJECTED'))
    expect(runProcess).toHaveBeenCalledWith('APPROVAL_DECIDE', { requestId: 'r-1', decision: 'REJECT',
      reason: 'No invoice' })
  })

  it('shows the refusal of the server', async () => {
    runProcess.mockRejectedValue(new ApiError(422, { violations: [
      { field: 'requestId', ruleCode: 'APPROVAL_OWN_REQUEST', message: 'You prepared this.' }] }))
    const onDecided = renderPanel()
    fireEvent.click(screen.getByTestId('approval-approve'))
    expect((await screen.findByTestId('approval-error')).textContent).toContain('You prepared this.')
    expect(onDecided).not.toHaveBeenCalled()
  })
})
