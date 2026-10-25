# QuizBuks behind the server's Caddy (deploy/quizbuks/DEPLOY.md). Rendered by the deploy workflow for each
# environment (__DOMAIN__: the environment's host name, __APP_NAME__: its APP_NAME) and installed as
# /opt/apps/<APP_NAME>/site.caddy. One upstream serves the API and all front ends: the app at /taker/, the sponsor
# console at /sponsor/, the administration at /admin/ and the business site at /biz/ (the bare address redirects to it).
__DOMAIN__ {
	encode zstd gzip
	reverse_proxy __APP_NAME__-app:8080
}
