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
    public String buildAuthorizeUrl() {
        validateSpotifyConfig();
        String state = UUID.randomUUID().toString().replace("-", "");
        stringRedisTemplate.opsForValue().set(AUTH_STATE_KEY_PREFIX + state, "1", 10, TimeUnit.MINUTES);
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
        stringRedisTemplate.delete(redisKey);
        SpotifyApiClient.SpotifyTokenResponse tokenResponse = spotifyApiClient.exchangeCode(code);
        SpotifySyncAccount account = getOrCreateAccount();
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
        QueryWrapper<TrackSyncRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(TrackSyncRecord::getAccountKey, spotifySyncProperties.getAccountKey())
                .orderByDesc(TrackSyncRecord::getUpdateTime)
                .last("limit 20");
        result.put("recentRecords", trackSyncRecordMapper.selectList(wrapper));
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
        int verifiedCount = 0;

        log.info("检测到待同步歌曲数, accountKey={}, pending={}, watermark={}",
                spotifySyncProperties.getAccountKey(), newTracks.size(), account.getLastSyncedAddedAt());

        for (SpotifyApiClient.SpotifySavedTrackItem item : newTracks) {
            Map<String, Object> detail = buildDetail(item);
            TrackSyncRecord existingRecord = findRecord(item);
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
                    saveOrUpdateRecord(item, null, STATUS_NOT_FOUND, "未找到足够接近的网易云歌曲");
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
                boolean verified = verifyLikeWithRetry(match.getSongId());
                if (!verified) {
                    saveOrUpdateRecord(item, match.getSongId(), STATUS_VERIFY_FAILED, "已请求喜欢，但校验未通过");
                    failedCount++;
                    detail.put("status", STATUS_VERIFY_FAILED);
                    detail.put("reason", "已请求喜欢，但校验未通过");
                    details.add(detail);
                    markAccountStatus(account, STATUS_FAILED, "已请求喜欢，但校验未通过");
                    log.warn("网易云喜欢校验未通过, trackId={}, neteaseSongId={}", item.getTrack().getId(), match.getSongId());
                    break;
                }
                verifiedCount++;
                saveOrUpdateRecord(item, match.getSongId(), STATUS_SUCCESS, null);
                advanceWatermark(account, item.getAddedAt());
                successCount++;
                detail.put("status", STATUS_SUCCESS);
                detail.put("verified", true);
                details.add(detail);
                log.info("歌曲同步成功并通过校验, trackId={}, neteaseSongId={}", item.getTrack().getId(), match.getSongId());
            } catch (Exception e) {
                log.error("同步歌曲失败, trackId={}", item.getTrack().getId(), e);
                saveOrUpdateRecord(item, null, STATUS_FAILED, truncateError(e.getMessage()));
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
        result.put("verified", verifiedCount);
        result.put("watermark", account.getLastSyncedAddedAt());
        result.put("details", details);
        log.info("同步结束, accountKey={}, pending={}, synced={}, verified={}, notFound={}, failed={}, skipped={}, watermark={}",
                spotifySyncProperties.getAccountKey(), newTracks.size(), successCount, verifiedCount, notFoundCount, failedCount, skippedCount, account.getLastSyncedAddedAt());
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

    private TrackSyncRecord findRecord(SpotifyApiClient.SpotifySavedTrackItem item) {
        QueryWrapper<TrackSyncRecord> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(TrackSyncRecord::getAccountKey, spotifySyncProperties.getAccountKey())
                .eq(TrackSyncRecord::getSourceTrackId, item.getTrack().getId())
                .eq(TrackSyncRecord::getSpotifyAddedAt, item.getAddedAt())
                .last("limit 1");
        return trackSyncRecordMapper.selectOne(wrapper);
    }

    private void saveOrUpdateRecord(SpotifyApiClient.SpotifySavedTrackItem item,
                                    Long targetSongId,
                                    String status,
                                    String errorMessage) {
        TrackSyncRecord record = findRecord(item);
        Date now = new Date();
        if (record == null) {
            record = new TrackSyncRecord();
            record.setAccountKey(spotifySyncProperties.getAccountKey());
            record.setSourceTrackId(item.getTrack().getId());
            record.setSpotifyAddedAt(item.getAddedAt());
            record.setCreateTime(now);
            record.setRetryCount(0);
        }
        record.setSourceTrackName(item.getTrack().getName());
        record.setSourceArtistNames(String.join(", ", item.getTrack().getArtists()));
        record.setSourceAlbumName(item.getTrack().getAlbumName());
        record.setDurationMs(item.getTrack().getDurationMs());
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

    private SpotifySyncAccount getAccount() {
        QueryWrapper<SpotifySyncAccount> wrapper = new QueryWrapper<>();
        wrapper.lambda()
                .eq(SpotifySyncAccount::getAccountKey, spotifySyncProperties.getAccountKey())
                .last("limit 1");
        return spotifySyncAccountMapper.selectOne(wrapper);
    }

    private SpotifySyncAccount getOrCreateAccount() {
        SpotifySyncAccount account = getAccount();
        if (account != null) {
            return account;
        }
        SpotifySyncAccount created = new SpotifySyncAccount();
        created.setAccountKey(spotifySyncProperties.getAccountKey());
        return created;
    }

    private boolean isLatestOnlyMode() {
        return "LATEST_ONLY".equalsIgnoreCase(spotifySyncProperties.getInitialSyncMode());
    }

    private boolean isTerminal(String status) {
        return STATUS_SUCCESS.equals(status) || STATUS_NOT_FOUND.equals(status);
    }

    private boolean verifyLikeWithRetry(Long songId) {
        for (int i = 0; i < 3; i++) {
            if (neteaseMusicClient.verifySongLiked(songId)) {
                return true;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
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
