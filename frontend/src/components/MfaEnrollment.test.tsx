import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/problem'
import i18n from '../i18n'
import MfaEnrollment from './MfaEnrollment'

vi.mock('qrcode', () => ({ default: { toCanvas: vi.fn(async () => undefined) } }))

describe('MfaEnrollment', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
  })

  it('shows the key, confirms a code and shows the recovery codes once', async () => {
    const begin = vi.fn(async () => ({ secret: 'JBSWY3DPEE', otpauthUri: 'otpauth://totp/jabiz:amy?secret=JBSWY3DPEE' }))
    const confirm = vi.fn()
      .mockRejectedValueOnce(new ApiError(422, { violations: [{ ruleCode: 'MFA_CODE_INVALID', message: 'The code is not valid.' }] }))
      .mockResolvedValueOnce(['AAAAA-BBBBB', 'CCCCC-DDDDD'])
    const done = vi.fn()
    render(<MfaEnrollment begin={begin} confirm={confirm} onDone={done} />)

    fireEvent.click(screen.getByTestId('mfa-start'))
    expect((await screen.findByTestId('mfa-secret')).textContent).toContain('JBSWY3DPEE')
    expect(screen.getByTestId('mfa-qr')).toBeTruthy()

    fireEvent.change(screen.getByTestId('mfa-code'), { target: { value: '000000' } })
    fireEvent.click(screen.getByTestId('mfa-confirm'))
    expect((await screen.findByTestId('mfa-error')).textContent).toContain('The code is not valid.')

    fireEvent.change(screen.getByTestId('mfa-code'), { target: { value: ' 123456 ' } })
    fireEvent.click(screen.getByTestId('mfa-confirm'))
    await waitFor(() => expect(screen.getAllByTestId('mfa-recovery-code')).toHaveLength(2))
    expect(confirm).toHaveBeenLastCalledWith('123456')
    fireEvent.click(screen.getByTestId('mfa-saved'))
    expect(done).toHaveBeenCalledOnce()
  })
})
