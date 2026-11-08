# 邮件发送故障的跟进（实施计划，S1）

来源：总控 2026-11-08（staging 04:30–05:20 UTC 邮件全部失败，`Connect timed out`；服务器在 Hetzner，缺省封锁出站 465；`.env` 中 465 配了 STARTTLS、
用户名不是 `api_token`、发件人是 Gmail 地址、有重复的行）。

## 做什么

1. **部署前的检查**（`deploy/quizbuks/server/check-env.sh`，每次部署在服务器上运行）新增：同一个变量写了几行即拒绝；`SPRING_MAIL_HOST` 有值时，465 必须
   `…_SMTP_SSL_ENABLE=true` 且不得 `…_STARTTLS_REQUIRED=true`，587 不得开 SSL；主机是 `smtp.mx.cloudflare.net` 时端口必须 465、用户名必须 `api_token`、发件人不能是 Gmail。
   只输出变量名，不输出值（与原有的检查相同）。放在部署前的检查而不是上线前的设置检查（`env-check.rules`）：后者的语法没有条件，且只在上线前运行一次。
2. **DEPLOY.md "2b. 发信的服务器"**：两种端口的设置、Hetzner 的端口封锁与测法、Cloudflare Email Sending 的完整步骤与 `.env` 样例、用 `docker compose up -d` 让 `.env` 生效、Grafana 告警邮件的发信。
3. **troubleshooting T15** 与三语帮助页文案；两条只读查询（列名 `attempted_time`）。
4. **operations.md §5** 告警表加"邮件发送"一行：平台已有的 `jabiz-outbox-failed`（`consumer=jabiz.mail`）就是"邮件连续发送失败"的告警，不需要新规则。

## 测试

`tools/quizbuks/test/check-env.test.sh`：重复的行；587 + STARTTLS、465 + SSL 通过；465 没开 SSL、465 要求 STARTTLS、587 开 SSL（大写 TRUE）拒绝；
Cloudflare 照文档通过；staging 当时的设置（不含密码）报出全部五个问题且不输出值；Cloudflare 主机大小写混写、587 拒绝；没有邮件服务器时不检查。

## 风险

- staging 的 `.env` 在需求方改好之前，下一次部署会在检查这一步停下（这正是目的）；检查的输出指出要改的变量名。

## 删除的功能

无。
