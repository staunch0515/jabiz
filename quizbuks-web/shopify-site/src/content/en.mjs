// English, the site's main language (Shopify's review and most merchants read it). The privacy policy follows what
// the code keeps (docs/quizbuks/shopify/S4-launch.md W-2); its legal review is the owner's.
export const en = {
  siteName: 'QuizBuks for Shopify',
  description: 'Turn product videos into a short quiz: shoppers who pass get a one-time discount code on your product page.',
  skip: 'Skip to the content',
  navLabel: 'Pages',
  languageLabel: 'Language',
  nav: { home: 'Home', privacy: 'Privacy', support: 'Support' },
  footer: 'QuizBuks for Shopify · Questions: {email}',
  home: {
    title: 'QuizBuks for Shopify',
    lead: 'Turn a product video into a short quiz. Shoppers who pass get a one-time discount code, right on your product page.',
    howTitle: 'How it works',
    steps: [
      { title: 'Create a campaign', text: 'In Shopify admin, open QuizBuks and add a video, a few questions and the discount.' },
      { title: 'Add the block', text: 'In the theme editor, add the QuizBuks block to your product page template. No code to change.' },
      { title: 'Shoppers answer', text: 'A shopper who passes gets a unique code for the order, valid for at least seven days.' },
    ],
    sections: [
      {
        title: 'Made for Shopify',
        items: [
          'Runs inside your Shopify admin; your staff sign in with Shopify.',
          'Discount codes are made in your store\'s own discounts, each usable once.',
          'Plans are billed by Shopify, with a free plan to start.',
          'The video loads only when a shopper asks to watch it, so your product page stays fast.',
        ],
      },
      {
        title: 'Plans',
        paragraphs: ['The free plan includes a number of quiz attempts a month; paid plans include more. See and change the plan in Shopify admin: Apps → QuizBuks → Plan and use.'],
      },
    ],
  },
  privacy: {
    title: 'Privacy policy',
    updated: 'Last updated: 27 October 2026',
    intro: [
      'This policy explains what QuizBuks for Shopify ("the app", "we") collects when a store installs it and when shoppers answer its quizzes, what we use it for and how long we keep it. Questions: {email}.',
    ],
    sections: [
      {
        title: 'Data about your store',
        items: [
          'The store\'s myshopify.com address and its name.',
          'For each staff member who opens the app: their Shopify staff ID and their role in the app. No names, email addresses or passwords.',
          'The access token Shopify gives the app, kept encrypted.',
          'The store\'s plan as Shopify reports it, and the number of quizzes completed in each billing period.',
          'The quiz campaigns you create: names, questions, video links and the discount.',
          'The discount codes the app creates in your store, kept encrypted.',
        ],
      },
      {
        title: 'Data about shoppers',
        items: [
          'A shopper signed in to your store: their Shopify customer ID, their quiz attempts (answers, score, times) and the discount code they received.',
          'A shopper who is not signed in: a random key the quiz keeps in their browser. We keep only a one-way digest of it with their attempts. The browser also keeps their attempt and code, to show the code again.',
          'We do not collect shoppers\' names, email addresses, postal addresses, phone numbers, orders or payment details. We do not use IP addresses to identify shoppers, and we do not keep them with quiz data.',
          'We do not sell data, show advertising or track shoppers across other sites.',
        ],
      },
      {
        title: 'How we use it',
        paragraphs: ['To run the quizzes and hand out the codes, to stop abuse (for example, one attempt per shopper and limits on how many codes go out a day), to count the quizzes your plan includes, and to answer your questions.'],
      },
      {
        title: 'Who processes it',
        items: [
          'Shopify: the app creates and deletes discounts in your store and reads your plan through Shopify\'s API.',
          'YouTube or Vimeo: a campaign\'s video player loads only after a shopper chooses to watch it.',
          'Our hosting provider, which runs the app on our behalf.',
        ],
      },
      {
        title: 'How long we keep it',
        items: [
          'While the app is installed, for as long as it is needed for the purposes above.',
          'Discount codes are deleted about two weeks after their discount ends.',
          'After you uninstall the app, the store\'s data is kept until Shopify asks us to erase it (about 48 hours later), and then deleted: the store, its staff memberships, the access token, campaigns, codes, signed-in shoppers\' links and the plan and usage.',
          'When Shopify forwards a shopper\'s request, we report or erase that shopper\'s data. After erasure their attempts no longer link to them.',
          'A few records are kept in append-only form for the integrity of the service and cannot be removed by these requests: the content of quizzes that were published, the log of changes to records (with personal fields masked), and the sign-in accounts of staff members, named only by the store\'s address and the staff ID.',
        ],
      },
      {
        title: 'Security',
        paragraphs: ['Data travels over encrypted connections. Access tokens and discount codes are stored encrypted, and only the app\'s own processes can read them.'],
      },
      {
        title: 'Your rights',
        paragraphs: ['Merchants can ask us about, correct or delete their data at {email}. Shoppers can ask the store they bought from, which can pass the request to us through Shopify, or write to us directly.'],
      },
      {
        title: 'Changes',
        paragraphs: ['We will update this page when the app\'s handling of data changes, and change the date above.'],
      },
    ],
  },
  support: {
    title: 'Support',
    intro: ['Write to {email}: we answer in English, Chinese and Japanese. Please include your store\'s myshopify.com address.'],
    sections: [
      {
        title: 'Adding the quiz to your product page',
        paragraphs: ['In Shopify admin, open Online Store → Themes → Customize, choose a product page template and add the QuizBuks block (Apps). Publish a campaign in QuizBuks first: the block shows nothing without one.'],
      },
      {
        title: 'The quiz does not show on my store',
        items: [
          'There is no live campaign: publish or resume one in QuizBuks → Quiz campaigns.',
          'The store is not connected: QuizBuks → Overview → Connect.',
          'Today\'s discount codes are all handed out: the quiz comes back the next day (UTC).',
          'The plan\'s quiz attempts for this period are used up: it comes back with the next period, or now with a higher plan.',
        ],
      },
      {
        title: 'Changing the plan',
        paragraphs: ['QuizBuks → Plan and use → Choose a plan opens Shopify\'s page of the plans. Shopify bills the plan with your store\'s other charges.'],
      },
      {
        title: 'Uninstalling',
        paragraphs: ['Uninstall from Shopify admin → Apps. Shopify ends the subscription and removes the app\'s discounts; we delete the store\'s data when Shopify asks us to, about 48 hours later (see the privacy policy).'],
      },
    ],
  },
}
