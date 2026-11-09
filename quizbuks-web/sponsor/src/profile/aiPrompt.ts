import { BRAND } from '../qb/brand'

// F-C5: the prompt sponsors give an AI tool to write a profile package from their website. The text is the one of
// docs/quizbuks/profile-package-prompts.md (aiPrompt.test.ts fails when they differ): the page fills in the parameters
// the sponsor typed, so nothing is copied by hand. Change the document and this file together.

/** The brand's name and the package format's name stand in the texts as tokens; the document spells them out. */
export const BRAND_TOKEN = '%BRAND%'
export const FORMAT_TOKEN = '%FORMAT%'
const FORMAT = 'quizbuks-profile'
const spelt = (text: string) => text.replaceAll(BRAND_TOKEN, BRAND.name).replaceAll(FORMAT_TOKEN, FORMAT)

/** Everything of the main prompt before its parameters. */
export const PROMPT_BEFORE = "你是 %BRAND% 的资料整理助手。请阅读下面【参数】中的企业官网，按【格式规则】生成一个 %BRAND% 发起方资料包（JSON）。\n\n【参数】（使用者只改这里）\n"

/** Everything of the main prompt after its parameters (the format rules). */
export const PROMPT_AFTER = "\n【格式规则】（必须全部遵守）\n1. 只输出一个 ```json 代码块，内容是合法 JSON（双引号、无注释、无末尾逗号、无省略号）。\n   代码块之后再给一张\"官网上没找到\"清单：列出留空的项目，以及每个主要内容取自哪一页的地址。\n2. 顶层固定为：\n   {\"format\": \"%FORMAT%\", \"schema\": 1, \"languages\": [基准语言, 其他语言…],\n    \"basics\": {...}, \"page\": {...}, \"products\": [...]}\n   不写 \"files\"、\"mailServer\"（资料包从不带邮件服务器的密码，也不由你生成）。\n3. 所有文字写成按语言的对象 {\"zh\": \"…\", \"ja\": \"…\", \"en\": \"…\"}，键只能是 \"languages\" 中列出的语言；\n   基准语言必填。只有基准语言时，也可以直接写成字符串。其他语言是你对基准文字的翻译，语义不变，不添加原文没有的内容。\n4. basics：\n   - \"contactName\"：照抄参数中的联系人姓名，≤ 100 字；**不要从网站取任何人的姓名**；\n   - \"company\"：公司的正式名称（取自页眉、页脚、\"公司概要 / 会社概要 / About\"），≤ 100 字，必填；\n   - \"intro\"：简介，2–5 句，取自\"关于我们\"或首页的介绍，不照搬整页，≤ 5000 字，可用 Markdown，不写 HTML；\n   - \"avatar\"：网站的 logo 图片地址 {\"url\": \"https://…\"}，找不到就不写这个键。\n5. page：\n   - \"handle\"：主页的地址名，由官网的域名得出（www.aini-helmet.com → aini-helmet），只用小写字母、数字与连字符，\n     3–30 个字符，不以连字符开头或结尾；\n   - \"headline\"：一句话标语，取自首页的主标题或网站的说明文字，每种语言 ≤ 120 字，必填；\n   - \"website\"：官网的地址，以 https:// 开头，不含空格，≤ 300 字；\n   - \"cover\"：首页主视觉图片地址 {\"url\": \"https://…\"}，找不到就不写这个键。\n6. products（参数为\"不要商品\"时写 []）：\n   - 取自\"产品与服务\"页或在线商店的商品页，按网站上的顺序，个数不超过参数的上限；\n   - 每项：\n     \"name\"：商品名，每种语言 ≤ 100 字，必填；\n     \"description\"：说明，2–3 句，每种语言 ≤ 500 字；\n     \"price\"：价格，数字，必填；日元（JPY）不带小数，其他币种至多 2 位小数；**网站上没有明码标价的商品不要放进来，不要猜价格**；\n     \"currency\"：币种，只能是 USD、EUR、GBP、JPY、CNY、INR 之一，必填；\n     \"shopUrl\"：购买链接，以 https:// 开头，不含空格，≤ 300 字，必填（没有购买链接的商品不放进来）；\n     \"code\"：商品的货号，有才写，只用字母、数字与 . _ -，以字母或数字开头，≤ 40 字；\n     \"image\"：商品图片地址 {\"url\": \"https://…\"}，找不到就不写这个键。\n7. 图片只能写成 {\"url\": \"https://…\"}（https 开头的真实地址，取自网页）；不要写 Base64 或任何图片数据；不要编造地址。\n8. 内容要求：\n   - **不编造**：只用官网上有的内容；没有的项目（简介、标语、封面、商品说明……）留空或不写这个键，并列入\"官网上没找到\"清单；\n   - **不取个人信息**：不取网站上任何个人的姓名、邮箱、电话、社交账号；\"联系方式\"只取公司公开的地址或公开表单的链接，放进 \"intro\" 中；\n   - 公司名与商品名保持原文，不翻译成不存在的名称；价格与币种必须与网页一致；\n   - 不确定的内容宁可留空，也不要编造。\n\n请先在心里核对第 1–8 条，再输出。\n"

/** The fixing prompt around the problems the confirmation lists. */
export const FIX_BEFORE = "下面的 %BRAND% 发起方资料包导入时报错。请只修正报错指出的问题，其他内容保持不变，\n仍按原来的【格式规则】只输出一个 ```json 代码块。不要为了通过检查而编造内容：\n报错的项目在官网上确实没有时，删掉这一项（或留空），并在清单里说明。\n\n报错信息（确认页列出的问题，原样粘贴）：\n"
export const FIX_AFTER = "\n\n文件：\n<粘贴 JSON>\n"
export const FIX_PLACEHOLDER = "<例：products[3].price: A price>"

export interface PromptParams {
  website: string
  contactName: string
  baseLanguage: 'ja' | 'zh' | 'en'
  otherLanguages: ('ja' | 'zh' | 'en')[]
  withProducts: boolean
  /** The products limit as the server says (`qb.products.max`); the document's default when unknown. */
  maxProducts: number
}

/** The default of `qb.products.max`, for a page that could not read it. */
export const DEFAULT_MAX_PRODUCTS = 50

/** The main prompt with the sponsor's parameters. */
export function profilePrompt(p: PromptParams): string {
  const others = p.otherLanguages.filter((language) => language !== p.baseLanguage)
  const lines = [
    `- 官网地址：${p.website.trim()}`,
    `- 联系人姓名（由使用者填写；你只照抄，不要从网站上找）：${p.contactName.trim()}`,
    `- 基准语言：${p.baseLanguage}（ja / zh / en 之一，先用它写）`,
    `- 其他语言：${others.length > 0 ? others.join(', ') : '无'}（译文由你翻译）`,
    `- 要不要商品：${p.withProducts ? '要' : '不要'}`,
    `- 商品个数上限：${p.maxProducts}（超过时只取网站上靠前的）`,
  ]
  return spelt(PROMPT_BEFORE) + lines.join('\n') + '\n' + spelt(PROMPT_AFTER)
}

/** The fixing prompt with the problems the confirmation listed, as they are. */
export function fixPrompt(problems: string[]): string {
  return spelt(FIX_BEFORE) + problems.join('\n') + spelt(FIX_AFTER)
}
