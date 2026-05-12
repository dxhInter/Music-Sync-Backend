# Spotify Sync

Sync newly liked songs from Spotify into NetEase Cloud Music.

## Features

- Spotify OAuth authorization
- Poll Spotify liked songs manually or on a schedule
- Search and like matching songs in NetEase Cloud Music
- Store sync account state and sync records in MySQL
- Use Redis for login token cache and sync locking
- Member auth demo APIs: register, login, info, logout

## Stack

- Spring Boot 2.7
- MyBatis-Plus
- MySQL
- Redis
- Spotify Web API
- NetEase-compatible local API service

## Project Structure

- App entry: [MusicSyncApplication.java](/Users/xinhaodu/Documents/git-repos/springboot-starter-music-sync/src/main/java/com/dxh/spotifysync/MusicSyncApplication.java)
- Spotify sync module: [src/main/java/com/dxh/spotifysync/modules/sync](/Users/xinhaodu/Documents/git-repos/springboot-starter-music-sync/src/main/java/com/dxh/spotifysync/modules/sync)
- SQL scripts: [sql](/Users/xinhaodu/Documents/git-repos/springboot-starter-music-sync/sql)
- Main config: [application.yml](/Users/xinhaodu/Documents/git-repos/springboot-starter-music-sync/src/main/resources/application.yml)
- Local example config: [application-local.example.yml](/Users/xinhaodu/Documents/git-repos/springboot-starter-music-sync/application-local.example.yml)

## Prerequisites

- JDK 8+
- Maven 3.8+
- MySQL 5.7+ or 8.x
- Redis
- A Spotify Developer app
- A running NetEase-compatible API service, for example on `http://127.0.0.1:3000`

## Database Initialization

Use the merged script if you want one-click initialization:

- [sql/init-all-merged.sql](/Users/xinhaodu/Documents/git-repos/springboot-starter-music-sync/sql/init-all-merged.sql)

It includes:

- original starter tables
- `ums_member`
- `music_sync_account`
- `music_sync_record`

## Local Configuration

Do not put real secrets in `application.yml`.

Create a local override file in one of these locations:

- project root: `application-local.yml`
- resources: `src/main/resources/application-local.yml`

You can start from:

- [application-local.example.yml](/Users/xinhaodu/Documents/git-repos/springboot-starter-music-sync/application-local.example.yml)

Example:

```yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/music_sync?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false
    username: root
    password: your-db-password
  redis:
    host: localhost
    database: 0
    port: 6379
    password:
    timeout: 3000ms

jwt:
  secret: replace-with-your-jwt-secret

music-sync:
  spotify:
    client-id: your-spotify-client-id
    client-secret: your-spotify-client-secret
    redirect-uri: http://127.0.0.1:8080/sync/spotify/callback
  netease:
    enabled: true
    base-url: http://127.0.0.1:3000
    cookie: MUSIC_U=...; __csrf=...
```

`application-local.yml` is ignored by git.

## Spotify Setup

Create a Spotify app in the Spotify Developer Dashboard and configure this redirect URI:

```text
http://127.0.0.1:8080/sync/spotify/callback
```

Then put `client-id` and `client-secret` into your local config.

## NetEase Setup

This project expects a NetEase-compatible local API service.

Typical requirements:

- service is running on `http://127.0.0.1:3000`
- `/login/status` is reachable
- you already have a valid NetEase login cookie

Put the cookie into `music-sync.netease.cookie`.

## Run Locally

```bash
mvn spring-boot:run
```

Or run `MusicSyncApplication` from your IDE.

## Main APIs

### Spotify Sync

- `GET /sync/spotify/authorize-url`
- `GET /sync/spotify/callback`
- `GET /sync/spotify/status`
- `POST /sync/spotify/sync`
- `GET /sync/spotify/netease/probe`
- `GET /sync/spotify/netease/search?keywords=...`

### Member Demo

- `POST /member/register`
- `POST /member/login`
- `GET /member/info`
- `POST /member/logout`

## Recommended Verification Flow

1. Start MySQL, Redis, and the NetEase-compatible API service.
2. Run the SQL initialization script.
3. Start this Spring Boot app.
4. Call `GET /sync/spotify/authorize-url`.
5. Open the returned Spotify authorization URL and finish OAuth.
6. Call `GET /sync/spotify/status` and confirm the sync account exists.
7. Like a new song in Spotify.
8. Call `POST /sync/spotify/sync`.
9. Verify the result in the response, logs, and NetEase liked songs.

## Notes

- Scheduler is disabled by default.
- Enable it with:

```yml
music-sync:
  scheduler:
    enabled: true
```

- Default polling interval:

```yml
music-sync:
  spotify:
    poll-interval-ms: 30000
```

- Sensitive values should stay in local config or environment variables, not in committed files.
