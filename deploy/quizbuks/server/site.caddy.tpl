# QuizBuks behind the server's Caddy (deploy/quizbuks/DEPLOY.md). Rendered by the deploy workflow for each
# environment (__DOMAIN__: the environment's host name, __APP_NAME__: its APP_NAME, __APP_DIR__: the environment's
# server directory as the Caddy container sees it) and installed as /opt/apps/<APP_NAME>/site.caddy. One upstream
# serves the API and all front ends: the app at /taker/, the sponsor console at /sponsor/, the administration at
# /admin/ and the business site at /biz/ (the bare address redirects to it).
__DOMAIN__ {
	encode zstd gzip

	# Android App Links (docs/quizbuks/plans/Q49b-android.md): the statement the deploy workflow wrote to
	# well-known/assetlinks.json in the environment's directory (kept here rather than the platform's native-apps, 17l). Before maintenance
	# so that Android's verification of the app still succeeds during maintenance; an environment without the file
	# (no Android app configured) falls through to the application, which answers 404.
	@assetlinks {
		path /.well-known/assetlinks.json
		file {
			root __APP_DIR__/well-known
			try_files /assetlinks.json
		}
	}
	handle @assetlinks {
		header Content-Type application/json
		header Cache-Control "public, max-age=300"
		root * __APP_DIR__/well-known
		rewrite * /assetlinks.json
		file_server
	}

	# iOS Universal Links (docs/quizbuks/plans/Q49c-ios.md), the same way: well-known/apple-app-site-association
	# written by the deploy workflow, served as JSON without a redirect (Apple's CDN follows none), during maintenance too.
	@aasa {
		path /.well-known/apple-app-site-association
		file {
			root __APP_DIR__/well-known
			try_files /apple-app-site-association
		}
	}
	handle @aasa {
		header Content-Type application/json
		header Cache-Control "public, max-age=300"
		root * __APP_DIR__/well-known
		rewrite * /apple-app-site-association
		file_server
	}

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

	# The application unreachable (stopped, restarting, not yet listening, crashed): Caddy's own 502 becomes the
	# maintenance page, as with the lock file but without it, so that a `docker stop` or a deployment's restart never
	# shows a bare error. Only Caddy's failure to reach the application counts: the application's own 503 answers
	# (PROCESS_BUSY, QUERY_TIMEOUT) pass through as they are. A shorter Retry-After than planned maintenance: a restart
	# takes a minute or two.
	handle_errors {
		@unreachable expression `{err.status_code} == 502`
		handle @unreachable {
			header Retry-After 60
			header Cache-Control no-store
			root * __APP_DIR__/maintenance
			rewrite * /maintenance.html
			file_server {
				status 503
			}
		}
	}
}
