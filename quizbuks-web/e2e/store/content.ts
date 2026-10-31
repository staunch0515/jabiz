import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { loadBrand } from '../../brands/brand.mjs'

/**
 * What the store's screenshots show (screenshots.spec.ts): per language a sponsor, a taker and three quizzes written in
 * that language. The first option of each question is the right one (the app shuffles them). The facts are checked;
 * keep them so when editing, the pictures are public.
 */

export type Language = 'ja' | 'zh' | 'en'

export interface StoreQuiz {
  title: string
  intro: string
  /** The prize at the sponsor's shop for a pass (the publication's `redeemDescription`). */
  prize: string
  /** The prize's value in Kudos, as the sponsor states it (shown with the prize). */
  value: number
  /** The cover's two colours, RGB. */
  colours: [[number, number, number], [number, number, number]]
  questions: { stem: string; options: [string, string, string] }[]
}

interface StoreContent {
  company: string
  /** The sponsor's contact name. */
  contact: string
  taker: string
  quizzes: [StoreQuiz, StoreQuiz, StoreQuiz]
}

const GREEN: StoreQuiz['colours'] = [[4, 120, 87], [110, 231, 183]]
const COFFEE: StoreQuiz['colours'] = [[120, 53, 15], [251, 191, 36]]
const BLUE: StoreQuiz['colours'] = [[30, 64, 175], [125, 211, 252]]

export const CONTENT: Record<Language, StoreContent> = {
  ja: {
    company: 'さくら珈琲店',
    contact: '桜井',
    taker: 'さとう はな',
    quizzes: [
      { title: '日本の世界遺産クイズ', intro: '日本の世界遺産について **3 問**。2 問以上の正解で、さくら珈琲店の特典がもらえます。',
        prize: 'ドリップコーヒー 1 杯', value: 300, colours: GREEN, questions: [
          { stem: '富士山が世界文化遺産に登録されたのは何年？', options: ['2013年', '2010年', '2016年'] },
          { stem: '厳島神社がある県は？', options: ['広島県', '山口県', '岡山県'] },
          { stem: '白川郷の合掌造り集落がある県は？', options: ['岐阜県', '長野県', '新潟県'] },
        ] },
      { title: 'コーヒーの基本', intro: 'さくら珈琲店から、コーヒーにまつわる **3 問**。',
        prize: '焼き菓子 1 個', value: 200, colours: COFFEE, questions: [
          { stem: 'エスプレッソにスチームミルクを加えた飲み物は？', options: ['カフェラテ', 'アメリカーノ', 'リストレット'] },
          { stem: '世界で流通するコーヒー豆の二大品種は？', options: ['アラビカとロブスタ', 'ブルーマウンテンとマンデリン', 'ブルボンとカティモール'] },
          { stem: '浅煎りの豆の特徴は？', options: ['酸味がはっきりしている', '苦味が強い', '表面に油が多い'] },
        ] },
      { title: '防災の基本チェック', intro: 'いざという時のために。**3 問**で確かめましょう。',
        prize: 'コーヒー豆 100g 10% オフ', value: 150, colours: BLUE, questions: [
          { stem: '揺れを感じたら、まずすることは？', options: ['頭を守り身の安全を確保する', 'すぐに外へ飛び出す', 'エレベーターで避難する'] },
          { stem: '災害用伝言ダイヤルの番号は？', options: ['171', '117', '177'] },
          { stem: '飲料水の備蓄の目安は 1 人 1 日何リットル？', options: ['3リットル', '1リットル', '10リットル'] },
        ] },
    ],
  },
  zh: {
    company: '晨光咖啡',
    contact: '林晨',
    taker: '王小明',
    quizzes: [
      { title: '世界首都知多少', intro: '关于世界各国首都的 **3 道题**，答对 2 道以上即可在晨光咖啡领取奖品。',
        prize: '手冲咖啡一杯', value: 300, colours: GREEN, questions: [
          { stem: '澳大利亚的首都是？', options: ['堪培拉', '悉尼', '墨尔本'] },
          { stem: '加拿大的首都是？', options: ['渥太华', '多伦多', '温哥华'] },
          { stem: '巴西的首都是？', options: ['巴西利亚', '里约热内卢', '圣保罗'] },
        ] },
      { title: '咖啡入门', intro: '晨光咖啡出题：关于咖啡的 **3 道题**。',
        prize: '烘焙点心一份', value: 200, colours: COFFEE, questions: [
          { stem: '在意式浓缩中加入蒸汽牛奶的饮品是？', options: ['拿铁', '美式咖啡', '短萃浓缩'] },
          { stem: '世界上最主要的两大咖啡豆品种是？', options: ['阿拉比卡与罗布斯塔', '蓝山与曼特宁', '波旁与卡蒂姆'] },
          { stem: '浅烘焙咖啡豆的特点是？', options: ['酸味较明显', '苦味较重', '表面油脂多'] },
        ] },
      { title: '健康生活小常识', intro: '日常健康的 **3 道题**，看看你答对几道。',
        prize: '咖啡豆 100g 九折', value: 150, colours: BLUE, questions: [
          { stem: '成年人每晚建议的睡眠时间大约是？', options: ['7–9 小时', '4–5 小时', '11–12 小时'] },
          { stem: '世界卫生组织建议成年人每周至少进行多少分钟中等强度运动？', options: ['150 分钟', '30 分钟', '600 分钟'] },
          { stem: '用肥皂洗手时，建议至少搓洗多少秒？', options: ['20 秒', '5 秒', '2 秒'] },
        ] },
    ],
  },
  en: {
    company: 'Harbor Coffee Co.',
    contact: 'Jordan',
    taker: 'Alex Kim',
    quizzes: [
      { title: 'World capitals', intro: 'Three questions about **capital cities**. Score 2 or more for a treat at Harbor Coffee Co.',
        prize: 'A cup of drip coffee', value: 300, colours: GREEN, questions: [
          { stem: 'What is the capital of Australia?', options: ['Canberra', 'Sydney', 'Melbourne'] },
          { stem: 'What is the capital of Canada?', options: ['Ottawa', 'Toronto', 'Vancouver'] },
          { stem: 'What is the capital of Brazil?', options: ['Brasília', 'Rio de Janeiro', 'São Paulo'] },
        ] },
      { title: 'Coffee basics', intro: 'Three questions about coffee from **Harbor Coffee Co.**',
        prize: 'A pastry of your choice', value: 200, colours: COFFEE, questions: [
          { stem: 'Which drink is espresso with steamed milk?', options: ['Latte', 'Americano', 'Ristretto'] },
          { stem: 'What are the two main coffee species grown worldwide?', options: ['Arabica and Robusta', 'Blue Mountain and Mandheling', 'Bourbon and Catimor'] },
          { stem: 'What is typical of a light roast?', options: ['A brighter acidity', 'A stronger bitterness', 'An oily surface'] },
        ] },
      { title: 'Ocean facts', intro: 'How well do you know the sea? **Three questions.**',
        prize: '10% off 100 g of coffee beans', value: 150, colours: BLUE, questions: [
          { stem: 'Which is the largest ocean?', options: ['The Pacific', 'The Atlantic', 'The Indian'] },
          { stem: 'Where is the deepest known point of the ocean?', options: ['The Mariana Trench', 'The Puerto Rico Trench', 'The Java Trench'] },
          { stem: 'About how much of Earth’s surface is ocean?', options: ['About 71%', 'About 50%', 'About 90%'] },
        ] },
    ],
  },
}

/** The line of the feature graphic per language. */
const TAGLINE: Record<Language, [string, string]> = {
  ja: ['クイズに答えて、お店の特典を', 'お店や企業のクイズに答えるだけ'],
  zh: ['答题，领取门店好礼', '回答商家出的问卷，到店领取奖品'],
  en: ['Answer quizzes. Win treats.', 'Quizzes from shops and brands, prizes at their counter'],
}

// The brand profile QB_BRAND chooses (Q54): its icon, name and colour.
const BRAND = loadBrand()
const ICON = `data:image/png;base64,${readFileSync(join(BRAND.dir, 'public', 'icon-512.png')).toString('base64')}`

/** The feature graphic (1024 × 500) as a page: the brand's icon, name and colour, the language's line. */
export function FEATURE(language: Language): string {
  const [title, subtitle] = TAGLINE[language]
  return `<!doctype html><html lang="${language}"><body style="margin:0;width:1024px;height:500px;display:flex;
align-items:center;gap:56px;padding:0 72px;box-sizing:border-box;background:linear-gradient(135deg,${BRAND.themeColor},color-mix(in srgb,${BRAND.themeColor} 70%,#ffffff));
font-family:'Noto Sans CJK JP','Noto Sans CJK SC','Noto Sans',sans-serif;color:#fff">
<img src="${ICON}" width="220" height="220" style="border-radius:48px;box-shadow:0 12px 32px rgba(0,0,0,.25)" alt="">
<div><div style="font-size:30px;font-weight:600;opacity:.9">${BRAND.names[language]}</div>
<div style="font-size:52px;font-weight:700;line-height:1.2;margin:12px 0">${title}</div>
<div style="font-size:26px;opacity:.9">${subtitle}</div></div></body></html>`
}
