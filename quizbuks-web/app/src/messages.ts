import { addMessages } from '@jabiz/client'
import './qb/messages'

/** The app's own texts (namespace `app`); shared ones are in `qb`, the sign-in pages speak the platform's `auth`. */
export const APP = 'app'

const en = {
  nav: { label: 'App', home: 'Home', account: 'Account' },
  home: {
    title: 'Home',
    greeting: 'Hello, {{name}}',
    empty: 'Quizzes for you will appear here.',
  },
  welcome: {
    title: 'Welcome to QuizBuks',
    intro: 'Tell us who you are and where you live: quizzes are offered by country and region.',
    save: 'Continue',
  },
  profile: {
    title: 'Profile',
    displayName: 'Name',
    country: 'Country or region',
    chooseCountry: 'Choose your country',
    searchCountry: 'Search',
    noCountry: 'No country matches.',
    city: 'City (optional)',
    currency: 'Currency for reference',
    currencyHint: 'Rewards are always in Kudos; the currency only helps you compare.',
    save: 'Save',
  },
}

const zh: typeof en = {
  nav: { label: 'App', home: '首页', account: '账户' },
  home: {
    title: '首页',
    greeting: '你好，{{name}}',
    empty: '适合你的 Quiz 会显示在这里。',
  },
  welcome: {
    title: '欢迎来到 QuizBuks',
    intro: '请告诉我们你的名字和所在地：Quiz 按国家和地区提供。',
    save: '继续',
  },
  profile: {
    title: '资料',
    displayName: '名字',
    country: '国家或地区',
    chooseCountry: '请选择国家',
    searchCountry: '搜索',
    noCountry: '没有匹配的国家。',
    city: '城市（可选）',
    currency: '参考币种',
    currencyHint: '奖励始终以 Kudos 计；币种只供参考比较。',
    save: '保存',
  },
}

const ja: typeof en = {
  nav: { label: 'アプリ', home: 'ホーム', account: 'アカウント' },
  home: {
    title: 'ホーム',
    greeting: 'こんにちは、{{name}} さん',
    empty: 'あなた向けのクイズがここに表示されます。',
  },
  welcome: {
    title: 'QuizBuks へようこそ',
    intro: 'お名前とお住まいの国を教えてください。クイズは国と地域ごとに提供されます。',
    save: '続ける',
  },
  profile: {
    title: 'プロフィール',
    displayName: '名前',
    country: '国・地域',
    chooseCountry: '国を選んでください',
    searchCountry: '検索',
    noCountry: '該当する国がありません。',
    city: '都市（任意）',
    currency: '参考通貨',
    currencyHint: '報酬は常に Kudos です。通貨は比較の参考にのみ使います。',
    save: '保存',
  },
}

addMessages(APP, { en, zh, ja })
