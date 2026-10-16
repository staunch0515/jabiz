import { defineExtension } from '@jabiz/admin'
import { ClipboardCheck, Users } from 'lucide-react'
import { PATH, PERMISSION } from './ids'
import { messages } from './messages'
import PublicationReviewPage from './PublicationReviewPage'
import ReviewsPage from './ReviewsPage'
import SponsorReviewPage from './SponsorReviewPage'
import UserSummaryPage from './UserSummaryPage'
import UsersPage from './UsersPage'

/**
 * QuizBuks's own pages in the platform's admin frontend (docs/quizbuks/02-design.md section 7.3,
 * docs/quizbuks/plans/Q9a-admin-extension.md; platform decision D22): the review of sponsor applications and submitted
 * publications with everything the decision is about, and the user summary (P-01). The generated pages still list and
 * show the entities; these are what the metadata cannot express. Every page reads through the platform's generic
 * endpoints, where the server checks each call; the menu's permissions only decide what is offered.
 */
export default defineExtension({
  routes: [
    { path: PATH.reviews, element: <ReviewsPage /> },
    { path: '/qb/reviews/publications/:publicationId', element: <PublicationReviewPage /> },
    { path: '/qb/reviews/sponsors/:userId', element: <SponsorReviewPage /> },
    { path: PATH.users, element: <UsersPage /> },
    { path: '/qb/users/:userId', element: <UserSummaryPage /> },
  ],
  menu: [
    {
      key: 'quizbuks',
      label: 'menu.group',
      children: [
        {
          key: 'qb-reviews',
          label: 'menu.reviews',
          path: PATH.reviews,
          icon: ClipboardCheck,
          permission: PERMISSION.reviewPublication,
        },
        { key: 'qb-users', label: 'menu.users', path: PATH.users, icon: Users, permission: PERMISSION.usersRead },
      ],
    },
  ],
  messages,
})
