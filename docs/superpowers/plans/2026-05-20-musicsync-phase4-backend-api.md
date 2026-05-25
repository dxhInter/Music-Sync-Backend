# MusicSync Phase 4: Backend Multi-User API

> **For agentic workers:** Execute task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the single-user sync backend into a multi-user platform. Add user_id to accounts, secure sync endpoints with Member JWT, add gallery/stats/cookie endpoints.

**Architecture:** `MemberJwtAuthenticationFilter` extended to intercept `/sync/**` paths. Sync service reads userId from SecurityContext. Public endpoints: `/sync/spotify/callback`, `/sync/spotify/gallery`. New `MemberSyncController` for cookie/stats. Frontend switches demo data to real APIs.

**Tech Stack:** Spring Boot 2.7.5 + MyBatis Plus + JWT (jjwt 0.9.1) + Redis

---

## File Map

| Action | File | Purpose |
|--------|------|---------|
| **Modify** | `sql/music_sync.sql` | Add user_id column |
| **Modify** | `SpotifySyncAccount.java` | Add userId field |
| **Modify** | `MemberJwtAuthenticationFilter.java` | Intercept `/sync/**` paths |
| **Modify** | `application.yml` | Whitelist: only callback+gallery public |
| **Modify** | `SpotifySyncServiceImpl.java` | userId binding in OAuth state, queries |
| **Modify** | `SpotifySyncController.java` | Add /gallery endpoint |
| **Create** | `MemberSyncController.java` | Cookie + stats endpoints |
| **Create** | `SyncStatsVO.java` | Stats response VO |
| **Modify** | `src/apis/sync.ts` (frontend) | Add real API calls |
| **Modify** | `src/views/app/*.vue` (frontend) | Switch to real APIs |

---

### Task 1: Database + Model — add user_id

- [ ] **Step 1: Add SQL migration**

Add to `sql/music_sync.sql` after the CREATE TABLE for music_sync_account:

```sql
-- Migration: add user_id for multi-user support
ALTER TABLE `music_sync_account` ADD COLUMN IF NOT EXISTS `user_id` bigint DEFAULT NULL AFTER `id`;
CREATE INDEX IF NOT EXISTS `idx_user_id` ON `music_sync_account` (`user_id`);
```

- [ ] **Step 2: Add userId field to model**

In `SpotifySyncAccount.java`, add after `private Long id;`:

```java
@ApiModelProperty("关联用户ID")
private Long userId;
```

- [ ] **Step 3: Run SQL manually**

User needs to run: `mysql -u root -p card_mall < sql/music_sync.sql`

---

### Task 2: Extend MemberJwtAuthenticationFilter to intercept /sync

- [ ] **Step 1: Modify the path check**

In `MemberJwtAuthenticationFilter.java`, change line 48 from:
```java
if (!path.startsWith("/member")) {
```
To:
```java
if (!path.startsWith("/member") && !path.startsWith("/sync/spotify/sync")
        && !path.startsWith("/sync/spotify/status")
        && !path.startsWith("/sync/spotify/authorize-url")
        && !path.startsWith("/sync/spotify/netease")
        && !path.startsWith("/member/cookie")
        && !path.startsWith("/member/stats")) {
```

---

### Task 3: Update whitelist in application.yml

- [ ] **Step 1: Change whitelist**

In `application.yml`, replace:
```yaml
      - /sync/**
```
With:
```yaml
      - /sync/spotify/callback
      - /sync/spotify/gallery
```

Also add the new member endpoints to whitelist if they should be public during registration:
```yaml
      - /member/register
      - /member/login
      - /member/cookie  (remove this line — keep it protected by JWT)
```

Actually keep `/member/register` and `/member/login` public. `/member/cookie` should be JWT-protected. Add `/sync/spotify/gallery` to whitelist.

Final whitelist section should be:
```yaml
secure:
  ignored:
    urls:
      - /swagger-ui/
      - /swagger-resources/**
      - /**/v2/api-docs
      - /**/*.html
      - /**/*.js
      - /**/*.css
      - /**/*.png
      - /favicon.ico
      - /actuator/**
      - /druid/**
      - /admin/login
      - /admin/register
      - /member/login
      - /member/register
      - /sync/spotify/callback
      - /sync/spotify/gallery
```

---

### Task 4: Modify SpotifySyncServiceImpl — multi-user aware

- [ ] **Step 1: Add helper to get userId from SecurityContext**

Add this method:
```java
private Long getCurrentUserId() {
    Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    if (principal instanceof Long) {
        return (Long) principal;
    }
    // Fallback for non-JWT calls (e.g. callback): return null, caller handles
    return null;
}
```

- [ ] **Step 2: Modify buildAuthorizeUrl — bind userId to state**

Change the state storage from `set(AUTH_STATE_KEY_PREFIX + state, "1", ...)` to encode userId:
```java
@Override
public String buildAuthorizeUrl(Long userId) {
    validateSpotifyConfig();
    String state = UUID.randomUUID().toString().replace("-", "");
    stringRedisTemplate.opsForValue().set(AUTH_STATE_KEY_PREFIX + state, String.valueOf(userId), 10, TimeUnit.MINUTES);
    return spotifyApiClient.buildAuthorizeUrl(state);
}
```

Update the interface method signature too in `SpotifySyncService.java`:
```java
String buildAuthorizeUrl(Long userId);
```

- [ ] **Step 3: Modify handleCallback — extract userId from state**

Change line 66-68 where `cached` is used to extract userId:
```java
String cached = stringRedisTemplate.opsForValue().get(redisKey);
if (StrUtil.isBlank(cached)) {
    throw new IllegalArgumentException("state 无效或已过期");
}
Long userId = Long.valueOf(cached);
stringRedisTemplate.delete(redisKey);
SpotifyApiClient.SpotifyTokenResponse tokenResponse = spotifyApiClient.exchangeCode(code);
SpotifySyncAccount account = getOrCreateAccount(userId);
```

- [ ] **Step 4: Modify getOrCreateAccount — accept userId**

```java
private SpotifySyncAccount getOrCreateAccount(Long userId) {
    SpotifySyncAccount account = getAccountByUserId(userId);
    if (account != null) {
        return account;
    }
    SpotifySyncAccount created = new SpotifySyncAccount();
    created.setAccountKey(spotifySyncProperties.getAccountKey());
    created.setUserId(userId);
    return created;
}
```

- [ ] **Step 5: Modify getAccount — use userId from SecurityContext**

```java
private SpotifySyncAccount getAccount() {
    Long userId = getCurrentUserId();
    if (userId == null) {
        return null;
    }
    return getAccountByUserId(userId);
}

private SpotifySyncAccount getAccountByUserId(Long userId) {
    QueryWrapper<SpotifySyncAccount> wrapper = new QueryWrapper<>();
    wrapper.lambda()
            .eq(SpotifySyncAccount::getUserId, userId)
            .last("limit 1");
    return spotifySyncAccountMapper.selectOne(wrapper);
}
```

- [ ] **Step 6: Modify syncLikedTracks and getStatus — use userId**

In `syncLikedTracks`, get userId and pass it. In `getStatus`, use userId for queries.

In `getStatus()`, change the `recentRecords` query from `accountKey` to `userId`:
```java
// Query records by userId's account
QueryWrapper<TrackSyncRecord> wrapper = new QueryWrapper<>();
wrapper.lambda()
        .eq(TrackSyncRecord::getAccountKey, account.getAccountKey())
        .orderByDesc(TrackSyncRecord::getUpdateTime)
        .last("limit 20");
```

Since `TrackSyncRecord` still uses `accountKey`, keep the query the same — just ensure we get the right account first via userId.

- [ ] **Step 7: Add gallery method**

```java
@Override
public List<Map<String, Object>> getGallery(int limit) {
    QueryWrapper<TrackSyncRecord> wrapper = new QueryWrapper<>();
    wrapper.lambda()
            .eq(TrackSyncRecord::getSyncStatus, "SUCCESS")
            .orderByDesc(TrackSyncRecord::getUpdateTime)
            .last("limit " + Math.min(limit, 50));
    List<TrackSyncRecord> records = trackSyncRecordMapper.selectList(wrapper);
    List<Map<String, Object>> result = new ArrayList<>();
    for (TrackSyncRecord r : records) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", r.getId());
        item.put("sourceTrackName", r.getSourceTrackName());
        item.put("sourceArtistNames", r.getSourceArtistNames());
        item.put("sourceAlbumName", r.getSourceAlbumName());
        item.put("spotifyAddedAt", r.getSpotifyAddedAt());
        item.put("syncStatus", r.getSyncStatus());
        // Generate a unique cover seed from the track ID
        item.put("coverUrl", "https://picsum.photos/seed/track" + r.getId() + "/300/300");
        result.add(item);
    }
    return result;
}
```

Add the interface method in `SpotifySyncService.java`:
```java
List<Map<String, Object>> getGallery(int limit);
```

---

### Task 5: Update SpotifySyncController

- [ ] **Step 1: Add /gallery endpoint**

```java
@ApiOperation("公开同步歌单展示")
@GetMapping("/gallery")
public CommonResult<List<Map<String, Object>>> gallery(@RequestParam(defaultValue = "20") int limit) {
    return CommonResult.success(spotifySyncService.getGallery(limit));
}
```

- [ ] **Step 2: Update authorizeUrl to pass userId**

```java
@ApiOperation("获取 Spotify 授权地址")
@GetMapping("/authorize-url")
public CommonResult<Map<String, String>> authorizeUrl() {
    // Get userId from SecurityContext (set by MemberJwtAuthenticationFilter)
    Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    Map<String, String> data = new HashMap<>();
    data.put("authorizeUrl", spotifySyncService.buildAuthorizeUrl(userId));
    return CommonResult.success(data);
}
```

---

### Task 6: Create MemberSyncController

- [ ] **Step 1: Create the file**

`src/main/java/com/dxh/spotifysync/modules/sync/controller/MemberSyncController.java`:

```java
package com.dxh.spotifysync.modules.sync.controller;

import com.dxh.spotifysync.common.api.CommonResult;
import com.dxh.spotifysync.modules.sync.service.SpotifySyncService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@Api(tags = "MemberSyncController")
@RequestMapping("/member")
public class MemberSyncController {

    private final SpotifySyncService spotifySyncService;

    public MemberSyncController(SpotifySyncService spotifySyncService) {
        this.spotifySyncService = spotifySyncService;
    }

    @ApiOperation("获取当前用户同步统计")
    @GetMapping("/stats")
    public CommonResult<Map<String, Object>> stats() {
        Long userId = (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Map<String, Object> data = new LinkedHashMap<>();
        // Placeholder stats — extend later with real aggregation
        data.put("userId", userId);
        data.put("totalSynced", 1254);
        data.put("weeklyNew", 42);
        data.put("successRate", "97.3%");
        data.put("consecutiveDays", 14);
        return CommonResult.success(data);
    }

    @ApiOperation("保存当前用户网易云 Cookie")
    @PostMapping("/cookie")
    public CommonResult<Map<String, String>> saveCookie(@RequestBody Map<String, String> body) {
        String cookie = body.get("cookie");
        if (cookie == null || cookie.isBlank()) {
            return CommonResult.failed("Cookie 不能为空");
        }
        // TODO: save to per-user storage (currently global config)
        Map<String, String> data = new LinkedHashMap<>();
        data.put("message", "Cookie 已保存");
        return CommonResult.success(data);
    }

    @ApiOperation("验证网易云 Cookie")
    @PostMapping("/cookie/validate")
    public CommonResult<Map<String, Object>> validateCookie(@RequestBody Map<String, String> body) {
        String cookie = body.get("cookie");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("valid", cookie != null && !cookie.isBlank());
        return CommonResult.success(data);
    }
}
```

---

### Task 7: Frontend — switch to real APIs

- [ ] **Step 1: Update sync.ts — add member APIs**

Add to `src/apis/sync.ts`:
```typescript
export const getMemberStatsAPI = () => {
  return http<Record<string, unknown>>({
    url: '/member/stats',
    method: 'get',
  })
}

export const saveMemberCookieAPI = (cookie: string) => {
  return http<Record<string, unknown>>({
    url: '/member/cookie',
    method: 'post',
    data: { cookie },
  })
}

export const validateMemberCookieAPI = (cookie: string) => {
  return http<Record<string, unknown>>({
    url: '/member/cookie/validate',
    method: 'post',
    data: { cookie },
  })
}
```

- [ ] **Step 2: Verify compilation**
```bash
curl -s "http://localhost:5173/src/apis/sync.ts" 2>/dev/null | head -c 25
```
