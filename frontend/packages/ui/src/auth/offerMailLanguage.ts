import { i18n, LANGUAGE_NAMES, mailLanguageOffer, setMailLanguage, type Language } from '@jabiz/client'
import { notify } from '../components/notify'
import { AUTH_NAMESPACE } from './messages'
import { failure } from './problems'

/**
 * After the interface language changed to `lang`: when the signed-in user's mail goes in another language, offers
 * (a notice with a button) to send it in `lang` too. Nothing changes unless the user accepts: the interface language
 * is the browser's, the mail language the account's (decision D49 item 3). Call it from the language switch, after
 * `changeLanguage`; it does nothing without a session or when it cannot tell.
 */
export async function offerMailLanguage(lang: Language, set: (locale: Language) => Promise<void> = setMailLanguage) {
  const offer = await mailLanguageOffer(lang)
  if (!offer) return
  const t = i18n.getFixedT(lang, AUTH_NAMESPACE)
  const language = LANGUAGE_NAMES[offer]
  notify.info(t('mailPreferences.offer', { language }), {
    duration: 10000,
    action: {
      label: t('mailPreferences.offerAccept'),
      onClick: () => {
        set(offer).then(
          () => notify.success(t('mailPreferences.languageSaved', { language })),
          (e: unknown) => notify.error(failure(e, t('mailPreferences.failed'))),
        )
      },
    },
  })
}
