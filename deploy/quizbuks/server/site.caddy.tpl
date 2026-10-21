# QuizBuks behind the server's Caddy (deploy/quizbuks/DEPLOY.md). Rendered by the deploy workflow for each
# environment (__DOMAIN__: the environment's host name, __APP_NAME__: its APP_NAME) and installed as
# /opt/apps/<APP_NAME>/site.caddy. One upstream serves the API and all three front ends: the app at /, the sponsor
# console at /sponsor/ and the administration at /admin/.
__DOMAIN__ {
	encode zstd gzip
	reverse_proxy __APP_NAME__-app:8080
}
