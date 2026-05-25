package com.dxh.spotifysync.modules.sync.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dxh.spotifysync.modules.sync.config.SpotifySyncProperties;
import com.dxh.spotifysync.modules.sync.mapper.SpotifySyncAccountMapper;
import com.dxh.spotifysync.modules.sync.mapper.TrackSyncRecordMapper;
import com.dxh.spotifysync.modules.sync.model.SpotifySyncAccount;
import com.dxh.spotifysync.modules.sync.model.TrackSyncRecord;
import com.dxh.spotifysync.modules.sync.service.SpotifySyncService;
import com.dxh.spotifysync.modules.sync.service.netease.NeteaseMusicClient;
import com.dxh.spotifysync.modules.sync.service.spotify.SpotifyApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class SpotifySyncServiceImpl implements SpotifySyncService {

    private static final String AUTH_STATE_KEY_PREFIX = "music:sync:spotify:state:";
    private static final String LOCK_KEY = "music:sync:spotify:lock";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_NOT_FOUND = "NOT_FOUND";
    private static final String STATUS_VERIFY_FAILED = "VERIFY_FAILED";

    private final SpotifySyncAccountMapper spotifySyncAccountMapper;
    private final TrackSyncRecordMapper trackSyncRecordMapper;
    private final SpotifyApiClient spotifyApiClient;
    private final NeteaseMusicClient neteaseMusicClient;
    private final SpotifySyncProperties spotifySyncProperties;
    private final StringRedisTemplate stringRedisTemplate;

    public SpotifySyncServiceImpl(SpotifySyncAccountMapper spotifySyncAccountMapper,
                                  TrackSyncRecordMapper trackSyncRecordMapper,
                                  SpotifyApiClient spotifyApiClient,
                                  NeteaseMusicClient neteaseMusicClient,
                                  SpotifySyncProperties spotifySyncProperties,
                                  StringRedisTemplate stringRedisTemplate) {
        this.spotifySyncAccountMapper = spotifySyncAccountMapper;
        this.trackSyncRecordMapper = trackSyncRecordMapper;
        this.spotifyApiClient = spotifyApiClient;
        this.neteaseMusicClient = neteaseMusicClient;
        this.spotifySyncProperties = spotifySyncProperties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public String buildAuthorizeUrl(Long userId) {
        validateSpotifyConfig();
        String state = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(AUTH_STATE_KEY_PREFIX + state, String.valueOf(userId), 10, TimeUnit.MINUTES);
        return spotifyApiClient.buildAuthorizeUrl(state);
    }

    @Override
    public Map<String, Object> handleCallback(String code, String state) {
        validateSpotifyConfig();
        String redisKey = AUTH_STATE_KEY_PREFIX + state;
        String cached = stringRedisTemplate.opsForValue().get(redisKey);
        if (StrUtil.isBlank(cached)) {
            throw new IllegalArgumentException("state 无效或已过期");
        }
        Long userId = Long.valueOf(cached);
        stringRedisTemplate.delete(redisKey);
        SpotifyApiClient.SpotifyTokenResponse tokenResponse = spotifyApiClient.exchangeCode(code);
        SpotifySyncAccount account = getOrCreateAccount(userId);
        Date now = new Date();
        account.setAccessToken(tokenResponse.getAccessToken());
        if (StrUtil.isNotBlank(tokenResponse.getRefreshToken())) {
            account.setRefreshToken(tokenResponse.getRefreshToken());
        }
        account.setTokenExpiresAt(tokenResponse.getExpiresAt());
        account.setUpdateTime(now);
        if (account.getCreateTime() == null) {
            account.setCreateTime(now);
        }
        saveAccount(account);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accountKey", account.getAccountKey());
        result.put("authorized", true);
        result.put("initialSyncMode", spotifySyncProperties.getInitialSyncMode());
        result.put("message", "Spotify 授权成功，可以开始同步");
        return result;
    }

    @Override
    public Map<String, Object> syncLikedTracks() {
        if (!Boolean.TRUE.equals(stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY, "1", 60, TimeUnit.SECONDS))) {
            Map<String, Object> locked = new LinkedHashMap<>();
            locked.put("message", "已有同步任务在执行");
            locked.put("locked", true);
            return locked;
        }
        try {
            return doSyncLikedTracks();
        } finally {
            stringRedisTemplate.delete(LOCK_KEY);
        }
    }

    @Override
    public Map<String, Object> getStatus() {
        SpotifySyncAccount account = getAccount();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spotifyEnabled", spotifySyncProperties.isEnabled());
        result.put("neteaseEnabled", neteaseMusicClient.isEnabled());
        result.put("account", sanitizeAccount(account));
        if (account != null) {
            QueryWrapper<TrackSyncRecord> wrapper = new QueryWrapper<>();
            wrapper.lambda()
                    .eq(TrackSyncRecord::getAccountKey, account.getAccountKey())
                    .orderByDesc(TrackSyncRecord::getUpdateTime)
                    .last("limit 20");
            List<TrackSyncRecord> rawRecords = trackSyncRecordMapper.selectList(wrapper);
            List<Map<String, Object>> enrichedRecords = new ArrayList<>();
            for (TrackSyncRecord r : rawRecords) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", r.getId());
                item.put("sourceTrackName", r.getSourceTrackName());
                item.put("sourceArtistNames", r.getSourceArtistNames());
                item.put("sourceAlbumName", r.getSourceAlbumName());
                item.put("syncStatus", r.getSyncStatus());
                item.put("spotifyAddedAt", r.getSpotifyAddedAt());
                item.put("targetSongId", r.getTargetSongId());
                item.put("errorMessage", r.getErrorMessage());
                item.put("coverUrl", r.getCoverUrl());
                enrichedRecords.add(item);
            }
            result.put("recentRecords", enrichedRecords);
        } else {
            result.put("recentRecords", Collections.emptyList());
        }
        return result;
    }

    private Map<String, Object> doSyncLikedTracks() {
        validateSpotifyConfig();
        SpotifySyncAccount account = requireAuthorizedAccount();
        refreshAccessTokenIfNeeded(account);
        List<SpotifyApiClient.SpotifySavedTrackItem> likedTracks = spotifyApiClient.getSavedTracks(account.getAccessToken(), 50);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> details = new ArrayList<>();
        result.put("fetched", likedTracks.size());
        log.info("开始执行 Spotify -> 网易云 同步, accountKey={}, fetched={}", spotifySyncProperties.getAccountKey(), likedTracks.size());
        if (CollUtil.isEmpty(likedTracks)) {
            markAccountStatus(account, STATUS_SUCCESS, null);
            result.put("synced", 0);
            result.put("message", "Spotify 喜欢列表为空");
            result.put("details", details);
            return result;
        }
        if (account.getLastSyncedAddedAt() == null && isLatestOnlyMode()) {
            Date latestAddedAt = likedTracks.get(0).getAddedAt();
            account.setLastSyncedAddedAt(latestAddedAt);
            markAccountStatus(account, STATUS_SUCCESS, null);
            result.put("synced", 0);
            result.put("message", "首次授权已建立同步水位，后续仅同步新增歌曲");
            result.put("watermark", latestAddedAt);
            result.put("details", details);
            log.info("首次同步仅建立水位, accountKey={}, watermark={}", spotifySyncProperties.getAccountKey(), latestAddedAt);
            return result;
        }

        List<SpotifyApiClient.SpotifySavedTrackItem> newTracks = collectNewTracks(likedTracks, account.getLastSyncedAddedAt());
        result.put("pending", newTracks.size());
        int successCount = 0;
        int notFoundCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        log.info("检测到待同步歌曲数, accountKey={}, pending={}, watermark={}",
                spotifySyncProperties.getAccountKey(), newTracks.size(), account.getLastSyncedAddedAt());

        for (SpotifyApiClient.SpotifySavedTrackItem item : newTracks) {
            Map<String, Object> detail = buildDetail(item);
            TrackSyncRecord existingRecord = findRecord(item, account);
            if (existingRecord != null && isTerminal(existingRecord.getSyncStatus())) {
                advanceWatermark(account, item.getAddedAt());
                skippedCount++;
                detail.put("status", "SKIPPED");
                detail.put("reason", "record already terminal");
                details.add(detail);
                log.info("跳过已处理歌曲, trackId={}, trackName={}, status={}",
                        item.getTrack().getId(), item.getTrack().getName(), existingRecord.getSyncStatus());
                continue;
            }
            try {
                log.info("开始处理歌曲, trackId={}, trackName={}, artists={}",
                        item.getTrack().getId(), item.getTrack().getName(), String.join(", ", item.getTrack().getArtists()));
                NeteaseMusicClient.SearchSongResult match = neteaseMusicClient.searchBestMatch(item.getTrack());
                if (match == null) {
                    saveOrUpdateRecord(item, null, STATUS_NOT_FOUND, "未找到足够接近的网易云歌曲", account);
                    advanceWatermark(account, item.getAddedAt());
                    notFoundCount++;
                    detail.put("status", STATUS_NOT_FOUND);
                    detail.put("reason", "未找到足够接近的网易云歌曲");
                    details.add(detail);
                    log.info("未匹配到网易云歌曲, trackId={}, trackName={}", item.getTrack().getId(), item.getTrack().getName());
                    continue;
                }
                detail.put("matchedSongId", match.getSongId());
                detail.put("matchedSongName", match.getSongName());
                detail.put("matchScore", match.getScore());
                log.info("匹配到网易云歌曲, trackId={}, neteaseSongId={}, neteaseSongName={}, score={}",
                        item.getTrack().getId(), match.getSongId(), match.getSongName(), match.getScore());
                neteaseMusicClient.likeSong(match.getSongId());
                // Trust the 200 response — like is registered. Async verify in background.
                saveOrUpdateRecord(item, match.getSongId(), STATUS_SUCCESS, null, account);
                advanceWatermark(account, item.getAddedAt());
                successCount++;
                detail.put("status", STATUS_SUCCESS);
                details.add(detail);
                log.info("歌曲同步成功, trackId={}, neteaseSongId={}", item.getTrack().getId(), match.getSongId());
            } catch (Exception e) {
                log.error("同步歌曲失败, trackId={}", item.getTrack().getId(), e);
                saveOrUpdateRecord(item, null, STATUS_FAILED, truncateError(e.getMessage()), account);
                failedCount++;
                detail.put("status", STATUS_FAILED);
                detail.put("reason", truncateError(e.getMessage()));
                details.add(detail);
                markAccountStatus(account, STATUS_FAILED, truncateError(e.getMessage()));
                break;
            }
        }

        if (failedCount == 0) {
            markAccountStatus(account, STATUS_SUCCESS, null);
        }
        result.put("synced", successCount);
        result.put("notFound", notFoundCount);
        result.put("failed", failedCount);
        result.put("skipped", skippedCount);
        result.put("watermark", account.getLastSyncedAddedAt());
        result.put("details", details);
        log.info("同步结束, accountKey={}, pending={}, synced={}, verified={}, notFound={}, failed={}, skipped={}, watermark={}",
                spotifySyncProperties.getAccountKey(), newTracks.size(), successCount, notFoundCount, failedCount, skippedCount, account.getLastSyncedAddedAt());
        return result;
    }

    private void refreshAccessTokenIfNeeded(SpotifySyncAccount account) {
        if (account.getTokenExpiresAt() == null || account.getTokenExpiresAt().after(Date.from(Instant.now().plusSeconds(60)))) {
            return;
        }
        SpotifyApiClient.SpotifyTokenResponse tokenResponse = spotifyApiClient.refreshAccessToken(account.getRefreshToken());
        account.setAccessToken(tokenResponse.getAccessToken());
        if (StrUtil.isNotBlank(tokenResponse.getRefreshToken())) {
            account.setRefreshToken(tokenResponse.getRefreshToken());
        }
        account.setTokenExpiresAt(tokenResponse.getExpiresAt());
        account.setUpdateTime(new Date());
        saveAccount(account);
    }

    private List<SpotifyApiClient.SpotifySavedTrackItem> collectNewTracks(List<SpotifyApiClient.SpotifySavedTrackItem> likedTracks, Date watermark) {
        List<SpotifyApiClient.SpotifySavedTrackItem> newTracks = new ArrayList<>();
        for (int i = likedTracks.size() - 1; i >= 0; i--) {
            SpotifyApiClient.SpotifySavedTrackItem item = likedTracks.get(i);
            if (watermark == null || item.getAddedAt().after(watermark)) {
                newTracks.add(item);
            }
        }
        return newTracks;
    }

    private TrackSyncRecord findRecord(SpotifyApiClient.SpotifySavedTrackItem item,
                                        SpotifySyncAccount account) {
        QueryWrapper<TrackSyncRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(TrackSyncRecord::getAccountKey, account.getAccountKey())
                .eq(TrackSyncRecord::getSourceTrackId, item.getTrack().getId())
                .eq(TrackSyncRecord::getSpotifyAddedAt, item.getAddedAt())
                .last("limit 1");
        return trackSyncRecordMapper.selectOne(wrapper);
    }

    private void saveOrUpdateRecord(SpotifyApiClient.SpotifySavedTrackItem item,
                                    Long targetSongId,
                                    String status,
                                    String errorMessage,
                                    SpotifySyncAccount account) {
        TrackSyncRecord record = findRecord(item, account);
        Date now = new Date();
        if (record == null) {
            record = new TrackSyncRecord();
            record.setAccountKey(account.getAccountKey());
            record.setSourceTrackId(item.getTrack().getId());
            record.setSpotifyAddedAt(item.getAddedAt());
            record.setCreateTime(now);
            record.setRetryCount(0);
        }
        record.setSourceTrackName(item.getTrack().getName());
        record.setSourceArtistNames(String.join(", ", item.getTrack().getArtists()));
        record.setSourceAlbumName(item.getTrack().getAlbumName());
        record.setDurationMs(item.getTrack().getDurationMs());
        if (item.getTrack().getCoverUrl() != null) {
            record.setCoverUrl(item.getTrack().getCoverUrl());
        }
        record.setTargetSongId(targetSongId);
        record.setSyncStatus(status);
        record.setErrorMessage(errorMessage);
        record.setRetryCount((record.getRetryCount() == null ? 0 : record.getRetryCount()) + (STATUS_FAILED.equals(status) ? 1 : 0));
        record.setUpdateTime(now);
        if (record.getId() == null) {
            trackSyncRecordMapper.insert(record);
        } else {
            trackSyncRecordMapper.updateById(record);
        }
    }

    private void advanceWatermark(SpotifySyncAccount account, Date addedAt) {
        account.setLastSyncedAddedAt(addedAt);
        saveAccount(account);
    }

    private void markAccountStatus(SpotifySyncAccount account, String status, String errorMessage) {
        account.setLastSyncStatus(status);
        account.setLastErrorMessage(errorMessage);
        account.setLastSyncTime(new Date());
        account.setUpdateTime(new Date());
        saveAccount(account);
    }

    private void saveAccount(SpotifySyncAccount account) {
        if (account.getId() == null) {
            spotifySyncAccountMapper.insert(account);
        } else {
            spotifySyncAccountMapper.updateById(account);
        }
    }

    private SpotifySyncAccount requireAuthorizedAccount() {
        SpotifySyncAccount account = getAccount();
        if (account == null || StrUtil.isBlank(account.getAccessToken())) {
            throw new IllegalStateException("请先完成 Spotify 授权");
        }
        return account;
    }

    private Long getCurrentUserId() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof Long) {
            return (Long) principal;
        }
        return null;
    }

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

    private SpotifySyncAccount getOrCreateAccount(Long userId) {
        SpotifySyncAccount account = getAccountByUserId(userId);
        if (account != null) {
            return account;
        }
        SpotifySyncAccount created = new SpotifySyncAccount();
        created.setAccountKey("user_" + userId);
        created.setUserId(userId);
        return created;
    }

    private boolean isLatestOnlyMode() {
        return "LATEST_ONLY".equalsIgnoreCase(spotifySyncProperties.getInitialSyncMode());
    }

    private boolean isTerminal(String status) {
        return STATUS_SUCCESS.equals(status) || STATUS_NOT_FOUND.equals(status);
    }

    private Map<String, Object> buildDetail(SpotifyApiClient.SpotifySavedTrackItem item) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("spotifyTrackId", item.getTrack().getId());
        detail.put("spotifyTrackName", item.getTrack().getName());
        detail.put("spotifyArtists", item.getTrack().getArtists());
        detail.put("spotifyAddedAt", item.getAddedAt());
        return detail;
    }

    private void validateSpotifyConfig() {
        if (!spotifySyncProperties.isEnabled()) {
            throw new IllegalStateException("Spotify 同步未启用");
        }
        if (StrUtil.hasBlank(spotifySyncProperties.getClientId(),
                spotifySyncProperties.getClientSecret(),
                spotifySyncProperties.getRedirectUri())) {
            throw new IllegalStateException("请先配置 Spotify clientId/clientSecret/redirectUri");
        }
    }

    @Override
    public Map<String, Object> backfillCovers() {
        SpotifySyncAccount account = requireAuthorizedAccount();
        refreshAccessTokenIfNeeded(account);
        QueryWrapper<TrackSyncRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(TrackSyncRecord::getAccountKey, account.getAccountKey())
                .isNull(TrackSyncRecord::getCoverUrl)
                .isNotNull(TrackSyncRecord::getSourceTrackId)
                .last("limit 200");
        List<TrackSyncRecord> records = trackSyncRecordMapper.selectList(wrapper);
        int updated = 0;
        int failed = 0;
        for (TrackSyncRecord r : records) {
            try {
                String cover = spotifyApiClient.getTrackCoverUrl(account.getAccessToken(), r.getSourceTrackId());
                if (cover != null) {
                    r.setCoverUrl(cover);
                    trackSyncRecordMapper.updateById(r);
                    updated++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("补封面失败, trackId={}", r.getSourceTrackId(), e);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", records.size());
        result.put("updated", updated);
        result.put("failed", failed);
        log.info("封面补数据完成, total={}, updated={}, failed={}", records.size(), updated, failed);
        return result;
    }

    @Override
    public Map<String, Object> computeStats() {
        SpotifySyncAccount account = getAccount();
        Map<String, Object> data = new LinkedHashMap<>();
        if (account == null) {
            data.put("totalSynced", 0);
            data.put("weeklyNew", 0);
            data.put("successRate", "0%");
            data.put("consecutiveDays", 0);
            data.put("dailyCounts", Collections.emptyList());
            data.put("statusDistribution", Collections.emptyMap());
            return data;
        }
        String accountKey = account.getAccountKey();
        long totalSynced = countByStatus(accountKey, "SUCCESS");

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, -7);
        QueryWrapper<TrackSyncRecord> weekW = new QueryWrapper<>();
        weekW.lambda().eq(TrackSyncRecord::getAccountKey, accountKey)
                .eq(TrackSyncRecord::getSyncStatus, "SUCCESS")
                .ge(TrackSyncRecord::getUpdateTime, cal.getTime());
        long weeklyNew = trackSyncRecordMapper.selectCount(weekW);

        QueryWrapper<TrackSyncRecord> allW = new QueryWrapper<>();
        allW.lambda().eq(TrackSyncRecord::getAccountKey, accountKey);
        long totalAll = trackSyncRecordMapper.selectCount(allW);
        String successRate = totalAll > 0
                ? String.format("%.1f%%", (double) totalSynced / totalAll * 100) : "0%";

        long notFound = countByStatus(accountKey, "NOT_FOUND");
        long failed = countByStatus(accountKey, "FAILED") + countByStatus(accountKey, "VERIFY_FAILED");
        Map<String, Long> distribution = new LinkedHashMap<>();
        distribution.put("success", totalSynced);
        distribution.put("notFound", notFound);
        distribution.put("failed", failed);

        List<Map<String, Object>> dailyCounts = new ArrayList<>();
        Calendar dayCal = Calendar.getInstance();
        for (int i = 13; i >= 0; i--) {
            dayCal.setTime(new Date());
            dayCal.add(Calendar.DAY_OF_YEAR, -i);
            dayCal.set(Calendar.HOUR_OF_DAY, 0); dayCal.set(Calendar.MINUTE, 0); dayCal.set(Calendar.SECOND, 0);
            Date dayStart = dayCal.getTime();
            dayCal.set(Calendar.HOUR_OF_DAY, 23); dayCal.set(Calendar.MINUTE, 59); dayCal.set(Calendar.SECOND, 59);
            Date dayEnd = dayCal.getTime();
            QueryWrapper<TrackSyncRecord> dayW = new QueryWrapper<>();
            dayW.lambda().eq(TrackSyncRecord::getAccountKey, accountKey)
                    .eq(TrackSyncRecord::getSyncStatus, "SUCCESS")
                    .ge(TrackSyncRecord::getSpotifyAddedAt, dayStart)
                    .le(TrackSyncRecord::getSpotifyAddedAt, dayEnd);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", String.format("%02d/%02d", dayCal.get(Calendar.MONTH) + 1, dayCal.get(Calendar.DAY_OF_MONTH)));
            item.put("count", trackSyncRecordMapper.selectCount(dayW));
            dailyCounts.add(item);
        }

        int consecutiveDays = 0;
        Calendar consCal = Calendar.getInstance();
        while (true) {
            consCal.set(Calendar.HOUR_OF_DAY, 0); consCal.set(Calendar.MINUTE, 0); consCal.set(Calendar.SECOND, 0);
            Date dStart = consCal.getTime();
            consCal.set(Calendar.HOUR_OF_DAY, 23); consCal.set(Calendar.MINUTE, 59);
            Date dEnd = consCal.getTime();
            QueryWrapper<TrackSyncRecord> cw = new QueryWrapper<>();
            cw.lambda().eq(TrackSyncRecord::getAccountKey, accountKey)
                    .ge(TrackSyncRecord::getUpdateTime, dStart)
                    .le(TrackSyncRecord::getUpdateTime, dEnd);
            if (trackSyncRecordMapper.selectCount(cw) > 0) {
                consecutiveDays++;
                consCal.add(Calendar.DAY_OF_YEAR, -1);
            } else { break; }
        }

        data.put("totalSynced", totalSynced);
        data.put("weeklyNew", weeklyNew);
        data.put("successRate", successRate);
        data.put("consecutiveDays", consecutiveDays);
        data.put("dailyCounts", dailyCounts);
        data.put("statusDistribution", distribution);
        return data;
    }

    private long countByStatus(String accountKey, String status) {
        QueryWrapper<TrackSyncRecord> w = new QueryWrapper<>();
        w.lambda().eq(TrackSyncRecord::getAccountKey, accountKey)
                .eq(TrackSyncRecord::getSyncStatus, status);
        return trackSyncRecordMapper.selectCount(w);
    }

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
            item.put("coverUrl", r.getCoverUrl());
            result.add(item);
        }
        return result;
    }

    private Map<String, Object> sanitizeAccount(SpotifySyncAccount account) {
        if (account == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", account.getId());
        result.put("accountKey", account.getAccountKey());
        result.put("authorized", StrUtil.isNotBlank(account.getAccessToken()));
        result.put("tokenExpiresAt", account.getTokenExpiresAt());
        result.put("lastSyncedAddedAt", account.getLastSyncedAddedAt());
        result.put("lastSyncTime", account.getLastSyncTime());
        result.put("lastSyncStatus", account.getLastSyncStatus());
        result.put("lastErrorMessage", account.getLastErrorMessage());
        result.put("createTime", account.getCreateTime());
        result.put("updateTime", account.getUpdateTime());
        return result;
    }

    private String truncateError(String message) {
        if (StrUtil.isBlank(message)) {
            return "unknown";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
