# Spotify Sync

Sync newly liked songs from Spotify to NetEase Cloud Music.

## What It Does

- Authorize with Spotify OAuth
- Detect newly liked songs from Spotify
- Search matching songs in NetEase Cloud Music
- Add matched songs into NetEase liked songs
- Store sync state and history in MySQL

## Stack

- Spring Boot 2.7
- MyBatis-Plus
- MySQL
- Redis
- Spotify Web API
- NetEase-compatible local API service

## Quick Start

1. Initialize database with:

- [sql/init-all-merged.sql](/Users/xinhaodu/Documents/git-repos/springboot-starter-music-sync/sql/init-all-merged.sql)

2. Copy local config template:

- [application-local.example.yml](/Users/xinhaodu/Documents/git-repos/springboot-starter-music-sync/application-local.example.yml)

Create one of:

- `application-local.yml`
- `src/main/resources/application-local.yml`

3. Fill in:

- MySQL connection
- Redis connection
- Spotify `client-id` / `client-secret`
- NetEase cookie

4. Start the app:

```bash
mvn spring-boot:run
```

Or run:

- [MusicSyncApplication.java](/Users/xinhaodu/Documents/git-repos/springboot-starter-music-sync/src/main/java/com/dxh/spotifysync/MusicSyncApplication.java)

## Required Config

Spotify redirect URI:

```text
http://127.0.0.1:8080/sync/spotify/callback
```

NetEase side expects:

- local API service on `http://127.0.0.1:3000`
- valid login cookie

## Main APIs

### Sync

- `GET /sync/spotify/authorize-url`
- `GET /sync/spotify/status`
- `POST /sync/spotify/sync`
- `GET /sync/spotify/netease/probe`
- `GET /sync/spotify/netease/search?keywords=...`

### Member Demo

- `POST /member/register`
- `POST /member/login`
- `GET /member/info`
- `POST /member/logout`

## Recommended Flow

1. Start MySQL, Redis, and NetEase-compatible API service
2. Run the SQL init script
3. Start this app
4. Call `GET /sync/spotify/authorize-url`
5. Finish Spotify authorization
6. Confirm `GET /sync/spotify/status` returns a sync account
7. Like a new song on Spotify
8. Call `POST /sync/spotify/sync`
9. Verify the song appears in NetEase liked songs

## Notes

- Scheduler is disabled by default
- Local secrets should stay in `application-local.yml`
- Do not commit Spotify secrets or NetEase cookies
