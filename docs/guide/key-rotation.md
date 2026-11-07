# 密钥轮换手册（决策 D78）

平台有四把密钥，都只来自环境变量。本手册说明怎样在不停机、不丢数据的情况下换掉它们，以及泄露时怎么办。
先在恢复出的副本上演练一次（`tools/ops/restore-drill.sh` 加 `DRILL_ROTATE=true`，见 13 §8），再在正式环境做。

| 密钥 | 保护什么 | 旧密钥变量 | 旧密钥保留多久 |
|---|---|---|---|
| `JABIZ_JWT_SECRET` | 访问令牌、挑战令牌、邮件中的退订链接 | `JABIZ_JWT_SECRET_PREVIOUS` | 定期轮换：`jabiz.security.jwt.previous-retention`（缺省 365 天，自平台首次见到它起；过期后自动不再接受，日志提示删去）；泄露：立即撤下 |
| `JABIZ_MFA_KEY` | 用户的 TOTP 密钥（`sec_user_mfa_version.secret`） | `JABIZ_MFA_KEY_PREVIOUS` | 到任务 `jabiz.key-rotation` 找不到剩余为止 |
| `JABIZ_SEAL_KEY` | 应用密封保存的密码与令牌（`SealedFields` 声明的字段） | `JABIZ_SEAL_KEY_PREVIOUS` | 同上 |
| `JABIZ_INTEGRITY_KEY` | 只追加表的封存链、抹除的证据 | `JABIZ_INTEGRITY_KEY_PREVIOUS` | **永久**：旧的封存块从不重签 |

几把旧密钥用逗号分隔（如连续两次轮换）。新密钥：`openssl rand -base64 48`。每把新密钥与旧密钥都要放进**离线副本**（与备份分开保存，`operations.md` §2.3）。

## 定期轮换（或只有密钥泄露、库没有泄露）

1. 生成新密钥，把**当前值**移到 `*_PREVIOUS`（已有旧密钥时追加在后面），新值设为当前值。四把可以一起换，也可以只换一把。
2. 滚动重启各实例。启动检查 `KEYS` 会提示"轮换进行中"（警告，不阻止启动）。
   - JWT：已登录的人不受影响（旧令牌照常验签，到期后换发的是新密钥签的）。
   - MFA、SEAL：旧密钥下的值照常能解开。
   - INTEGRITY：新的封存块用新密钥，旧块按记下的密钥标识用旧密钥校验。
3. 等任务 `jabiz.key-rotation`（每 10 分钟）把 MFA 与 SEAL 下的值重新加密完：管理系统"定时任务"页看它最近几次运行，输出 `mfaSecrets`、`sealedValues` 为 0、
   `more=false` 即完成；急的话在该页补跑（D77）。`unreadable` 大于 0 表示有值的上下文与声明不符（应用的 `SealedFields` 写错了，或数据被改过），
   这些值撤下旧密钥后就解不开了：先查明再继续。
4. 完成后从环境中删去 `JABIZ_MFA_KEY_PREVIOUS`、`JABIZ_SEAL_KEY_PREVIOUS`，再滚动重启；`JABIZ_JWT_SECRET_PREVIOUS` 在保留期过后删去（过期后平台已不再接受）；
   `JABIZ_INTEGRITY_KEY_PREVIOUS` 不删。
5. 运行一次 `INTEGRITY_VERIFY`（或等每天的任务），确认封存链完整。

## 泄露时

先判断泄露的范围：

- **只有密钥泄露**（例如环境变量出现在日志或截图里，数据库没有泄露）：按上节立即轮换；JWT 不保留旧密钥（撤下即让所有会话重新登录），
  MFA、SEAL 在重新加密完成后立即撤下。
- **密钥与数据库副本一同泄露**：只追加的历史版本中仍有旧密钥加密的密文，换密钥保护不了它们。要换掉被保护的秘密本身：
  - `JABIZ_MFA_KEY`：所有人重新绑定二次验证（旧的 TOTP 密钥作废）：一位管理员执行 `SEC_MFA_REENROLL_ALL_REQUEST`（写明原因），
    另一位管理员在 24 小时内执行 `SEC_MFA_REENROLL_ALL_APPROVE`；任务 `jabiz.key-rotation`（10 分钟内，或在"定时任务"页补跑）收回批准之前设定的全部二次验证、
    结束这些用户的会话，他们下次登录时设定新的。通行密钥照常可用。
  - `JABIZ_SEAL_KEY`：应用保存的密码、令牌全部重设或重新授权（发起方的邮件服务器密码、第三方的访问令牌等），使旧密文解出的东西没有用处。
  - `JABIZ_JWT_SECRET`：立即撤下（没有存储的数据）。
  - `JABIZ_INTEGRITY_KEY`：持有者可以对以后的数据伪造封存。换密钥；旧块保留旧密钥用于校验，泄露之后才封存的块要重点核对。
- 两种情况都记入安全事件记录：时间、范围、处理、各步完成的时间。

## 应用要做的事

- 用 `SealSecret` 保存的每个字段都在一个 `SealedFields` Bean 中声明，上下文与密封时完全一致（`SealedField.of(实体, 字段, "上下文字段")`，
  或 `SealedField.of(实体, 字段, row -> …)`）。不声明的字段不会被重新加密，旧密钥撤下后就解不开了。
- 密封字段不要放在 write-once 实体中（启动检查 `KEYS` 报错）；字段应当是 `sensitive`。
- 应用自己的核对（恢复演练中的模板）照常；轮换演练不需要应用另写代码。
