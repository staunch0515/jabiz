# QuizBuks 的商店 App

答题者的 App（`quizbuks-web/app`，`/taker/`）在 Google Play 与 App Store 上的外壳（规划 `docs/quizbuks/plans/Q49-mobile-apps.md`）。
商家后台、招商网站与充值不进 App。

| 目录 | 内容 | 计划 |
|---|---|---|
| `android/` | Trusted Web Activity（Chrome 打开 `/taker/`） | `docs/quizbuks/plans/Q49b-android.md` |
| `ios/` | Capacitor（Q49c，待平台 17j、17k、17l） | — |

App 中判断是否在商店 App 里只用 `quizbuks-web/app/src/shell.ts`。
