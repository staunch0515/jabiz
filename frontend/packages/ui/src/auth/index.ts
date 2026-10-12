/**
 * The sign-in and account building blocks (decision D43; docs/design/12-frontend.md section 12): whole pages and parts
 * of pages for signing in, signing up, the pages of mailed links, resetting and changing a password, two-step
 * verification and step-up, identity providers and the banners of a signed-in user. They act for the frontend's
 * sign-in entry (`@jabiz/client`), depend on no router (navigation goes through `onNavigate` / `hrefOf` and callbacks)
 * and speak the namespace `auth` in every platform language.
 */
export { AUTH_NAMESPACE, authMessages } from './messages'
export { AuthFrame, AuthField, AuthSubmit, AuthLink, AuthError, Outcome, ContinueTo, navigateTo } from './frame'
export type { AuthFrameProps, AuthNavigation } from './frame'
export { SignInForm } from './SignInForm'
export type { SignInFormProps } from './SignInForm'
export { SignUpForm, SignUpSent } from './SignUpForm'
export type { SignUpFormProps, SignUpSentProps, SignUpSubmitted, SignUpValues } from './SignUpForm'
export {
  MailLinkPage,
  VerifyEmailPanel,
  JoinPanel,
  PasswordResetConfirm,
  ResendVerification,
  NewPasswordFields,
} from './MailLinkPage'
export type { MailLinkPageProps, MailLinkPageBase, MailLinkInfo, NewPassword } from './MailLinkPage'
export { PasswordResetRequest } from './PasswordResetRequest'
export type { PasswordResetRequestProps } from './PasswordResetRequest'
export { MfaCodeStep } from './MfaCodeStep'
export type { MfaCodeStepProps } from './MfaCodeStep'
export { MfaEnrollment } from './MfaEnrollment'
export type { MfaEnrollmentProps, Enrollment } from './MfaEnrollment'
export { StepUpProvider } from './StepUpProvider'
export type { StepUpProviderProps } from './StepUpProvider'
export { OidcButtons, useOidcProviders } from './OidcButtons'
export type { OidcButtonsProps, OidcProvider } from './OidcButtons'
export { OidcCallback } from './OidcCallback'
export type { OidcCallbackProps } from './OidcCallback'
export { ChangePasswordForm } from './ChangePasswordForm'
export type { ChangePasswordFormProps } from './ChangePasswordForm'
export { VerifyEmailBanner, OfflineBanner, PwaUpdatePrompt } from './banners'
export type { VerifyEmailBannerProps, PwaUpdatePromptProps } from './banners'
export { invalidLink, passwordProblems, passwordRequired } from './problems'
