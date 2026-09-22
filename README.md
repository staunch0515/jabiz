```java
my-app/
├── backend/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/main/
│       ├── java/com/example/app/
│       └── resources/
│           ├── application.yml
│           └── db/migration/V1__init.sql
├── frontend/                 # Vite + React + TS
├── .github/workflows/ci.yml
├── docker-compose.yml
└── .gitignore
```


docker compose up -d                                  # 数据库
cd backend && ./gradlew bootRun                       # :8080，启动时 Flyway 自动建表
cd frontend && npm install && npm run dev             # :5173，/api 代理到 8080


curl -X POST localhost:8080/api/todos -H 'Content-Type: application/json' -d '{"title":"hello"}'
curl localhost:8080/api/todos
curl localhost:8080/actuator/health

生产打包：cd backend && ./gradlew bootJar，得到的 build/libs/app-0.0.1-SNAPSHOT.jar 已包含前端静态资源，java -jar 即可单进程同时提供 API 和页面。

PgAdmin 14:

`http://localhost:5050`

Email： `admin@admin.com` 

password ：admin
