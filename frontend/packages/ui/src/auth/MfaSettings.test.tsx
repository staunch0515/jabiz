import { ApiError, i18n } from '@jabiz/client'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectAccessible } from '@jabiz/ui/testing'
import { MfaSettings } from './index'

vi.mock('qrcode', () => ({ default: { toCanvas: vi.fn(async () => undefined) } }))

describe('MfaSettings', () => {
  beforeEach(async () => {
    await act(() => i18n.changeLanguage('en'))
  })

  it('says that two-step verification is on and how many recovery codes are left', async () => {
    render(<MfaSettings status={async () => ({ enrolled: true, recoveryCodesLeft: 7 })} />)
    const status = await screen.findByTestId('mfa-status')
    expect(status).toHaveTextContent('Two-step verification is on.')
    expect(status).toHaveTextContent('7 recovery codes left.')
    expect(screen.queryByTestId('mfa-start')).toBeNull()
    await expectAccessible()
  })

  it('sets it up while it is off, then reads the status again', async () => {
    const status = vi.fn()
      .mockResolvedValueOnce({ enrolled: false })
      .mockResolvedValueOnce({ enrolled: true, recoveryCodesLeft: 10 })
    const begin = vi.fn(async () => ({ secret: 'JBSWY3DPEE', otpauthUri: 'otpauth://totp/jabiz:amy?secret=JBSWY3DPEE' }))
    const confirm = vi.fn(async () => ['AAAAA-BBBBB'])
    const onEnrolled = vi.fn()
    const read = async () => status()
    render(<MfaSettings status={read} begin={begin} confirm={confirm} onEnrolled={onEnrolled} />)

    expect(await screen.findByTestId('mfa-status')).toHaveTextContent('Two-step verification is off.')
    await expectAccessible()
    fireEvent.click(screen.getByTestId('mfa-start'))
    await screen.findByTestId('mfa-secret')
    fireEvent.change(screen.getByTestId('mfa-code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByTestId('mfa-confirm'))
    await screen.findByTestId('mfa-recovery-code')
    expect(confirm).toHaveBeenCalledWith('123456')
    fireEvent.click(screen.getByTestId('mfa-saved'))

    await waitFor(() => expect(screen.getByTestId('mfa-status')).toHaveTextContent('10 recovery codes left.'))
    expect(status).toHaveBeenCalledTimes(2)
    expect(onEnrolled).toHaveBeenCalledOnce()
  })

  it('shows why the status could not be read', async () => {
    const status = async () => {
      throw new ApiError(500, { violations: [{ ruleCode: 'X', message: 'The server is down.' }] })
    }
    render(<MfaSettings status={status} />)
    expect(await screen.findByTestId('mfa-settings-error')).toHaveTextContent('The server is down.')
  })

  it('shows the error, not the old status with the enrolment, when reading again after an enrolment fails', async () => {
    const status = vi.fn()
      .mockResolvedValueOnce({ enrolled: false })
      .mockRejectedValueOnce(new ApiError(503, { violations: [{ ruleCode: 'X', message: 'Try again later.' }] }))
    const read = async () => status()
    render(<MfaSettings status={read} begin={async () => ({ secret: 'K', otpauthUri: 'otpauth://totp/x?secret=K' })}
      confirm={async () => ['AAAAA-BBBBB']} />)
    await screen.findByTestId('mfa-status')
    fireEvent.click(screen.getByTestId('mfa-start'))
    await screen.findByTestId('mfa-secret')
    fireEvent.change(screen.getByTestId('mfa-code'), { target: { value: '123456' } })
    fireEvent.click(screen.getByTestId('mfa-confirm'))
    fireEvent.click(await screen.findByTestId('mfa-saved'))

    expect(await screen.findByTestId('mfa-settings-error')).toHaveTextContent('Try again later.')
    expect(screen.queryByTestId('mfa-status')).toBeNull()
    expect(screen.queryByTestId('mfa-start')).toBeNull()
  })

  it('reads the status again when the caller changes its refresh key', async () => {
    const status = vi.fn()
      .mockResolvedValueOnce({ enrolled: false })
      .mockResolvedValueOnce({ enrolled: true, recoveryCodesLeft: 10 })
    const read = async () => status()
    const { rerender } = render(<MfaSettings status={read} refreshKey={1} />)
    expect(await screen.findByTestId('mfa-status')).toHaveTextContent('Two-step verification is off.')
    rerender(<MfaSettings status={read} refreshKey={2} />)
    await waitFor(() => expect(screen.getByTestId('mfa-status')).toHaveTextContent('10 recovery codes left.'))
  })

  it('speaks every platform language', async () => {
    await act(() => i18n.changeLanguage('zh'))
    render(<MfaSettings status={async () => ({ enrolled: true, recoveryCodesLeft: 2 })} />)
    expect(await screen.findByTestId('mfa-status')).toHaveTextContent('剩余 2 个恢复码。')
  })
})
