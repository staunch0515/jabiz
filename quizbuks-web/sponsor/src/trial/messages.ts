import { BRAND } from '../qb/brand'
/** The trial page's texts (Q48c), part of the console's namespace `sponsor` (`src/messages.ts` adds them). */
export const trialEn = {
  trial: {
    title: 'A quiz made from your products',
    intro: `This is how ${BRAND.name} works for a business like yours: customers answer a short quiz about your products, and you reward the ones who do well. Try it.`,
    madeFor: 'Made for {{company}} from your own website.',
    question: 'Question {{n}} of {{total}}',
    check: 'Check my answers',
    score: 'You got {{right}} of {{total}} right.',
    right: 'Right',
    wrong: 'The right answer: {{answer}}',
    again: 'Try again',
    ctaTitle: 'Publish quizzes like this one',
    cta: 'Sign up as a sponsor: you edit this quiz or write your own, choose the rewards, and publish when you are ready.',
    signUp: 'Sign up as a sponsor',
    missing: 'This trial is no longer available.',
    failed: 'The trial could not be read. Try again later.',
  },
}

export const trialZh = {
  trial: {
    title: '用贵公司产品制作的 Quiz',
    intro: `${BRAND.name} 能为像贵公司这样的商家做什么：顾客回答关于贵公司产品的简短 Quiz，您奖励答得好的人。来试一试吧。`,
    madeFor: '根据 {{company}} 自己的网站为您制作。',
    question: '第 {{n}} 题，共 {{total}} 题',
    check: '查看结果',
    score: '答对 {{right}} / {{total}} 题。',
    right: '正确',
    wrong: '正确答案：{{answer}}',
    again: '再试一次',
    ctaTitle: '发布这样的 Quiz',
    cta: '注册成为商家：可以修改这份 Quiz 或自己编写，设定奖励，准备好后发布。',
    signUp: '注册成为商家',
    missing: '该试答已不可用。',
    failed: '无法读取试答，请稍后再试。',
  },
}

export const trialJa = {
  trial: {
    title: '御社の商品から作ったクイズ',
    intro: `${BRAND.name} は御社のような企業にこう役立ちます：お客様が商品についての短いクイズに答え、よくできた方に御社が特典を贈ります。お試しください。`,
    madeFor: '{{company}} のウェブサイトの内容から作成しました。',
    question: '第 {{n}} 問（全 {{total}} 問）',
    check: '答え合わせ',
    score: '{{total}} 問中 {{right}} 問正解です。',
    right: '正解',
    wrong: '正解：{{answer}}',
    again: 'もう一度',
    ctaTitle: 'このようなクイズを公開しませんか',
    cta: 'スポンサーとして登録すると、このクイズを編集したり自分で作ったりし、特典を決めて、準備ができたら公開できます。',
    signUp: 'スポンサーとして登録',
    missing: 'このお試しはもう利用できません。',
    failed: 'お試しを読み込めませんでした。しばらくしてからお試しください。',
  },
}
