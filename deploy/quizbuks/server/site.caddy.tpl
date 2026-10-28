# QuizBuks behind the server's Caddy (deploy/quizbuks/DEPLOY.md). Rendered by the deploy workflow for each
# environment (__DOMAIN__: the environment's host name, __APP_NAME__: its APP_NAME, __APP_DIR__: the environment's
# server directory as the Caddy container sees it) and installed as /opt/apps/<APP_NAME>/site.caddy. One upstream
# serves the API and all front ends: the app at /taker/, the sponsor console at /sponsor/, the administration at
# /admin/ and the business site at /biz/ (the bare address redirects to it).
__DOMAIN__ {
	encode zstd gzip

	# Maintenance mode (DEPLOY.md "维护模式"): while the file maintenance/maintenance.lock exists in the environment's
	# directory every request gets the static page maintenance/maintenance.html with 503, the application is not
	# reached. The file is looked up on each request, so creating or removing it needs no reload. The root is given
	# explicitly: a bare absolute path would be taken relative to Caddy's working directory.
	@maintenance file {
		root __APP_DIR__/maintenance
		try_files /maintenance.lock
	}
	handle @maintenance {
		# 503 with Retry-After: crawlers keep the pages and come back, webhook senders (Stripe) retry later.
		header Retry-After 300
		header Cache-Control no-store
		root * __APP_DIR__/maintenance
		rewrite * /maintenance.html
		file_server {
			status 503
		}
	}

	handle {
		reverse_proxy __APP_NAME__-app:8080
	}
}
