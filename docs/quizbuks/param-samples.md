# 受控参数填写示例（CONTROL_CHANGE_PROPOSE）

## 怎么填

在平台管理系统的流程页打开 `CONTROL_CHANGE_PROPOSE`，各栏这样填：

| 栏 | 填什么 |
|---|---|
| targetEntity | `SysParam` |
| targetId | 留空 |
| delete | 关 |
| values | 下表"values（整行复制）"一列的内容 |
| effectiveTime | 留空（发布即生效）；要定时生效就填以后的时间 |
| reason | 写原因，如 `initial` |

提出之后，换**另一个账号**到"数据 → SysControlChange"，在这一行执行 `CONTROL_CHANGE_PUBLISH`。两账号方案下是账号 A 提出、账号 B 发布。

要注意四点：

1. 参数名两边不要加反引号或空格。
2. 每行都带了 `valueKind`，必须和下表一字不差。
   - 参数还没建出来时（`QB_SETUP` 的提案还没发布），必须带它；
   - 已经建出来时，带上也没关系，但不能改。
3. 同一个参数如果已经有一条 `QB_SETUP` 提出、还没发布的提案，先发布那一条，再提出新值；否则后发布的会被拒绝（`CONTROL_TARGET_CHANGED`）。
4. 金额、天数等数字写成带引号的文字，如 `"1000"`；开关写 `true` 或 `false`，不带引号。

## 1. 收据开具方

未设名称时，发起方开不出收据。登记号与税的措辞，须财务、法务确认。

| 参数 | 含义 | 类型 | 示例值 | values（整行复制） |
|---|---|---|---|---|
| `qb.issuer.name` | 开具方名称 | 文字，至多 200 字 | 株式会社クイズブックス | `{"paramKey": "qb.issuer.name", "value": "株式会社クイズブックス", "valueKind": {"type": "text", "maxLength": 200}}` |
| `qb.issuer.address` | 开具方地址 | 文字，至多 500 字 | 東京都千代田区丸の内1-1-1 | `{"paramKey": "qb.issuer.address", "value": "東京都千代田区丸の内1-1-1", "valueKind": {"type": "text", "maxLength": 500}}` |
| `qb.issuer.registration-no` | 适格发票登记号 | 文字，至多 50 字 | T1234567890123 | `{"paramKey": "qb.issuer.registration-no", "value": "T1234567890123", "valueKind": {"type": "text", "maxLength": 50}}` |

## 2. 科目映射（会计系统的科目代码）

- 类型都是文字，至多 20 字（字母、数字、`. _ -`）。示例值暂用科目号本身，请换成会计系统中的真实代码。
- 没映射的科目会让分录导出被拒。
- 查看位置："QuizBuks 财务 → 分录导出 → 科目映射"。

| 参数 | 科目 | values（整行复制） |
|---|---|---|
| `qb.journal.account.1110` | Stripe 余额 | `{"paramKey": "qb.journal.account.1110", "value": "1110", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.1120` | 银行存款 | `{"paramKey": "qb.journal.account.1120", "value": "1120", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.1130` | 平台费应收 | `{"paramKey": "qb.journal.account.1130", "value": "1130", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.2110` | 发起方预付款 | `{"paramKey": "qb.journal.account.2110", "value": "2110", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.2120` | 应付奖励 | `{"paramKey": "qb.journal.account.2120", "value": "2120", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.2130` | 在途转账 | `{"paramKey": "qb.journal.account.2130", "value": "2130", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.2140` | 应付运营商分成 | `{"paramKey": "qb.journal.account.2140", "value": "2140", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.2150` | 应交消费税 | `{"paramKey": "qb.journal.account.2150", "value": "2150", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.2160` | 发起方现金账户 | `{"paramKey": "qb.journal.account.2160", "value": "2160", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.2170` | 未结算的参加费 | `{"paramKey": "qb.journal.account.2170", "value": "2170", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.2180` | 平台费预付款 | `{"paramKey": "qb.journal.account.2180", "value": "2180", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.2190` | 发起方的平台费 Kudos | `{"paramKey": "qb.journal.account.2190", "value": "2190", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.4110` | 服务收入 | `{"paramKey": "qb.journal.account.4110", "value": "4110", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.4120` | 平台费收入 | `{"paramKey": "qb.journal.account.4120", "value": "4120", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.5110` | 支付手续费 | `{"paramKey": "qb.journal.account.5110", "value": "5110", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.5120` | 推广费 | `{"paramKey": "qb.journal.account.5120", "value": "5120", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.5130` | 运营商分成 | `{"paramKey": "qb.journal.account.5130", "value": "5130", "valueKind": {"type": "text", "maxLength": 20}}` |
| `qb.journal.account.5140` | 推广费（平台费 Kudos） | `{"paramKey": "qb.journal.account.5140", "value": "5140", "valueKind": {"type": "text", "maxLength": 20}}` |

## 3. 金额参数（按业务确认）

示例值都是系统的缺省值。Kudos 的类型为整数日元等值（`monetary`，JPY，小数 0 位）。

| 参数 | 含义 | 类型 | 示例值 | values（整行复制） |
|---|---|---|---|---|
| `qb.creator-fee-rate` | 平台从每笔奖励抽取的比例 | 小数 0～不到 1，4 位小数（0.1 = 10%） | 0 | `{"paramKey": "qb.creator-fee-rate", "value": "0", "valueKind": {"type": "numeric", "precision": 4, "scale": 4}}` |
| `qb.transfer.threshold` | 奖励累计到多少 Kudos 才转账 | Kudos | 1000 | `{"paramKey": "qb.transfer.threshold", "value": "1000", "valueKind": {"type": "monetary", "currency": "JPY", "scale": 0}}` |
| `qb.transfer.review-above` | 超过多少 Kudos 的转账要审核 | Kudos | 50000 | `{"paramKey": "qb.transfer.review-above", "value": "50000", "valueKind": {"type": "monetary", "currency": "JPY", "scale": 0}}` |
| `qb.transfer.fee` | 每次转账的平台手续费（0 = 不收） | Kudos | 0 | `{"paramKey": "qb.transfer.fee", "value": "0", "valueKind": {"type": "monetary", "currency": "JPY", "scale": 0}}` |
| `qb.topup.min` | 发起方单次最少充值 | Kudos | 1000 | `{"paramKey": "qb.topup.min", "value": "1000", "valueKind": {"type": "monetary", "currency": "JPY", "scale": 0}}` |
| `qb.topup.max` | 发起方单次最多充值 | Kudos | 1000000 | `{"paramKey": "qb.topup.max", "value": "1000000", "valueKind": {"type": "monetary", "currency": "JPY", "scale": 0}}` |

## 4. 开关、奖励与其他

| 参数 | 含义 | 类型 | 首发的值 | values（整行复制） |
|---|---|---|---|---|
| `qb.transfer.enabled` | 每日自动转账 | 开关 | `false`（取得法务意见前保持关） | `{"paramKey": "qb.transfer.enabled", "value": false, "valueKind": {"type": "bool"}}` |
| `qb.reward.default-methods` | 所有发起方都可用的奖励方式，逗号分隔：`PLATFORM`、`SPONSOR_CASH`、`OFFLINE_REDEEM`、`DIGITAL_PRIZE` | 文字，至多 50 字 | 空（首发：没有任何方式，点亮节点或单独开通才有） | `{"paramKey": "qb.reward.default-methods", "value": "", "valueKind": {"type": "text", "maxLength": 50}}` |
| `qb.reward.max-per-attempt` | 每次答题可得的最高奖励（0 = 不限；上限由法务给出） | Kudos | 0 | `{"paramKey": "qb.reward.max-per-attempt", "value": "0", "valueKind": {"type": "monetary", "currency": "JPY", "scale": 0}}` |
| `qb.legal.max-announce-days` | 法律文本最长可提前预告几天 | 整数天，至多 3 位 | 180 | `{"paramKey": "qb.legal.max-announce-days", "value": "180", "valueKind": {"type": "numeric", "precision": 3, "scale": 0}}` |
| `qb.ai.enabled` | 发起方可否用 AI 出题 | 开关 | `true`，但要等 AI 使用说明（法律文本 `AI_NOTICE`）发布之后才发布这条 | `{"paramKey": "qb.ai.enabled", "value": true, "valueKind": {"type": "bool"}}` |
| `qb.help.enabled` | 学者与发起方可否用 AI 问题解答 | 开关 | 同上，AI 使用说明发布之后 | `{"paramKey": "qb.help.enabled", "value": true, "valueKind": {"type": "bool"}}` |

`qb.ai.enabled` 发布之前，启动日志对 `job qb.ai.questions` 与 `job qb.ai.cover` 各有一条警告 `parameter qb.ai.enabled has no value in effect now`。新安装时这是预期的，发布后即消失。
