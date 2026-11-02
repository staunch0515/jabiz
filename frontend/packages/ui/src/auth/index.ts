/**
 * The sign-in and account building blocks (decision D43; docs/design/12-frontend.md section 12): whole pages and parts
 * of pages for signing in (with a password or a mailed link), signing up, the pages of mailed links, resetting and
 * changing a password, two-step verification and step-up, identity providers and linked provider accounts, the session
 * list, the mail preferences, deleting one's own account, the unsubscribe page and the banners of a signed-in user.
 * They act for the frontend's sign-in entry (`@jabiz/client`), depend on no router (navigation goes through
 * `onNavigate` / `hrefOf` and callbacks) and speak the namespace `auth` in every platform language.
 */
export { AUTH_NAMESPACE, authMessages } from './messages'
export { AuthFrame, AuthField, AuthSubmit, AuthLink, AuthError, Outcome, ContinueTo, navigateTo } from './frame'
export type { AuthFrameProps, AuthNavigation } from './frame'
export { PasswordInput } from './PasswordInput'
export type { PasswordInputProps } from './PasswordInput'
export { SignInForm } from './SignInForm'
export type { SignInFormProps } from './SignInForm'
export { SignUpForm, SignUpSent } from './SignUpForm'
export type { SignUpFormProps, SignUpSentProps, SignUpSubmitted, SignUpValues } from './SignUpForm'
export {
  MailLinkPage,
  VerifyEmailPanel,
  JoinPanel,
  PasswordResetConfirm,
  InvitationPanel,
  ResendVerification,
  NewPasswordFields,
} from './MailLinkPage'
export type { MailLinkPageProps, MailLinkPageBase, MailLinkInfo, NewPassword } from './MailLinkPage'
export { EmailLinkForm, EmailLinkPanel } from './EmailLink'
export type { EmailLinkFormProps, EmailLinkPanelProps } from './EmailLink'
export { PasswordResetRequest } from './PasswordResetRequest'
export type { PasswordResetRequestProps } from './PasswordResetRequest'
export { MfaCodeStep } from './MfaCodeStep'
export type { MfaCodeStepProps } from './MfaCodeStep'
export { MfaEnrollment } from './MfaEnrollment'
export type { MfaEnrollmentProps, Enrollment } from './MfaEnrollment'
export { MfaSettings } from './MfaSettings'
export type { MfaSettingsProps, MfaStatus } from './MfaSettings'
export { StepUpProvider } from './StepUpProvider'
export type { StepUpProviderProps } from './StepUpProvider'
export { OidcButtons, useOidcProviders, GoogleMark, ProviderMark } from './OidcButtons'
export type { OidcButtonsProps, OidcProvider } from './OidcButtons'
export { OidcCallback } from './OidcCallback'
export type { OidcCallbackProps } from './OidcCallback'
export { ChangePasswordForm } from './ChangePasswordForm'
export type { ChangePasswordFormProps } from './ChangePasswordForm'
export { SessionList, SessionDeviceIcon, sessionDeviceName, effectiveMinutes } from './SessionList'
export type { SessionListProps } from './SessionList'
export { MailPreferences } from './MailPreferences'
export type { MailPreferencesProps } from './MailPreferences'
export { PushPreferences } from './PushPreferences'
export type { PushPreferencesProps } from './PushPreferences'
export { offerMailLanguage } from './offerMailLanguage'
export { MailUnsubscribe } from './MailUnsubscribe'
export type { MailUnsubscribeProps, MailUnsubscribeState } from './MailUnsubscribe'
export { LinkedIdentities } from './LinkedIdentities'
export type { LinkedIdentitiesProps } from './LinkedIdentities'
export { AccountDeletion, NoticeChecklist } from './AccountDeletion'
export type { AccountDeletionProps } from './AccountDeletion'
export { AccountDeletionRequest, AccountDeletionConfirm } from './AccountDeletionLink'
export type { AccountDeletionRequestProps, AccountDeletionConfirmProps } from './AccountDeletionLink'
export { VerifyEmailBanner, OfflineBanner, PwaUpdatePrompt, ServiceUnavailableNotice } from './banners'
export type { VerifyEmailBannerProps, PwaUpdatePromptProps } from './banners'
export { PwaInstallBanner, PwaInstallGuide } from './install'
export type { PwaInstallBannerProps, PwaInstallGuideProps } from './install'
export { invalidLink, passwordProblems, passwordRequired } from './problems'
