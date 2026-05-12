package com.dxh.spotifysync.modules.sync.service.netease;

import cn.hutool.core.util.StrUtil;
import com.dxh.spotifysync.modules.sync.config.NeteaseSyncProperties;
import com.dxh.spotifysync.modules.sync.service.spotify.SpotifyApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
public class NeteaseMusicClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final NeteaseSyncProperties neteaseSyncProperties;

    public NeteaseMusicClient(RestTemplate restTemplate,
                              ObjectMapper objectMapper,
                              NeteaseSyncProperties neteaseSyncProperties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.neteaseSyncProperties = neteaseSyncProperties;
    }

    public boolean isEnabled() {
        return neteaseSyncProperties.isEnabled() && StrUtil.isNotBlank(neteaseSyncProperties.getCookie());
    }

    public SearchSongResult searchBestMatch(SpotifyApiClient.SpotifyTrack spotifyTrack) {
        if (!isEnabled()) {
            throw new IllegalStateException("网易云同步未启用，请先配置 music-sync.netease");
        }
        String keywords = spotifyTrack.getName() + " " + firstArtist(spotifyTrack);
        return searchBestMatchByKeyword(keywords, spotifyTrack);
    }

    public SearchSongResult searchBestMatchByKeyword(String keywords, SpotifyApiClient.SpotifyTrack spotifyTrack) {
        URI uri = UriComponentsBuilder.fromHttpUrl(trimSlash(neteaseSyncProperties.getBaseUrl()) + "/cloudsearch")
                .queryParam("keywords", keywords)
                .queryParam("type", 1)
                .queryParam("limit", neteaseSyncProperties.getSearchLimit())
                .build()
                .encode()
                .toUri();
        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(buildHeaders()), String.class);
        List<SearchSongResult> candidates = parseCandidates(response.getBody());
        SearchSongResult best = null;
        for (SearchSongResult candidate : candidates) {
            double score = calculateScore(spotifyTrack, candidate);
            candidate.setScore(score);
            if (best == null || score > best.getScore()) {
                best = candidate;
            }
        }
        if (best == null || best.getScore() < neteaseSyncProperties.getMinScore()) {
            return null;
        }
        return best;
    }

    public List<SearchSongResult> searchSongs(String keywords) {
        if (!isEnabled()) {
            throw new IllegalStateException("网易云同步未启用，请先配置 music-sync.netease");
        }
        URI uri = UriComponentsBuilder.fromHttpUrl(trimSlash(neteaseSyncProperties.getBaseUrl()) + "/cloudsearch")
                .queryParam("keywords", keywords)
                .queryParam("type", 1)
                .queryParam("limit", neteaseSyncProperties.getSearchLimit())
                .build()
                .encode()
                .toUri();
        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(buildHeaders()), String.class);
        return parseCandidates(response.getBody());
    }

    public Map<String, Object> probe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", neteaseSyncProperties.isEnabled());
        result.put("cookieConfigured", StrUtil.isNotBlank(neteaseSyncProperties.getCookie()));
        result.put("baseUrl", neteaseSyncProperties.getBaseUrl());
        try {
            JsonNode root = fetchLoginStatus();
            result.put("reachable", true);
            result.put("code", root.path("code").asInt());
            result.put("userId", root.path("data").path("account").path("id").asLong(0L));
            result.put("nickname", root.path("data").path("profile").path("nickname").asText(""));
            result.put("rawBody", root);
        } catch (Exception e) {
            result.put("reachable", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    public void likeSong(Long songId) {
        URI uri = UriComponentsBuilder.fromHttpUrl(trimSlash(neteaseSyncProperties.getBaseUrl()) + "/like")
                .queryParam("id", songId)
                .queryParam("like", true)
                .build()
                .encode()
                .toUri();
        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(buildHeaders()), String.class);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("调用网易云喜欢接口失败");
        }
        log.info("网易云喜欢接口调用完成, songId={}, httpStatus={}", songId, response.getStatusCodeValue());
    }

    public long getCurrentUserId() {
        JsonNode root = fetchLoginStatus();
        long userId = root.path("data").path("account").path("id").asLong(0L);
        if (userId <= 0L) {
            throw new IllegalStateException("未获取到网易云登录用户");
        }
        return userId;
    }

    public boolean verifySongLiked(Long songId) {
        long userId = getCurrentUserId();
        Set<Long> likedSongIds = getLikedSongIds(userId);
        boolean liked = likedSongIds.contains(songId);
        log.info("网易云喜欢校验结果, userId={}, songId={}, liked={}", userId, songId, liked);
        return liked;
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, neteaseSyncProperties.getCookie());
        return headers;
    }

    private JsonNode fetchLoginStatus() {
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(trimSlash(neteaseSyncProperties.getBaseUrl()) + "/login/status")
                    .build()
                    .encode()
                    .toUri();
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(buildHeaders()), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("调用网易云登录状态接口失败");
            }
            return root;
        } catch (Exception e) {
            throw new IllegalStateException("获取网易云登录状态失败", e);
        }
    }

    private Set<Long> getLikedSongIds(long userId) {
        try {
            Long playlistId = getLikedPlaylistId(userId);
            URI uri = UriComponentsBuilder.fromHttpUrl(trimSlash(neteaseSyncProperties.getBaseUrl()) + "/playlist/detail")
                    .queryParam("id", playlistId)
                    .build()
                    .encode()
                    .toUri();
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(buildHeaders()), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode trackIds = root.path("playlist").path("trackIds");
            Set<Long> ids = new HashSet<>();
            Iterator<JsonNode> iterator = trackIds.elements();
            while (iterator.hasNext()) {
                ids.add(iterator.next().path("id").asLong());
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException("获取网易云喜欢列表失败", e);
        }
    }

    private Long getLikedPlaylistId(long userId) {
        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(trimSlash(neteaseSyncProperties.getBaseUrl()) + "/user/playlist")
                    .queryParam("uid", userId)
                    .queryParam("limit", 1)
                    .build()
                    .encode()
                    .toUri();
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(buildHeaders()), String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode playlistNode = root.path("playlist").path(0);
            long playlistId = playlistNode.path("id").asLong(0L);
            if (playlistId <= 0L) {
                throw new IllegalStateException("未找到喜欢歌曲歌单");
            }
            return playlistId;
        } catch (Exception e) {
            throw new IllegalStateException("获取网易云喜欢歌单失败", e);
        }
    }

    private List<SearchSongResult> parseCandidates(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode songs = root.path("result").path("songs");
            List<SearchSongResult> candidates = new ArrayList<>();
            Iterator<JsonNode> iterator = songs.elements();
            while (iterator.hasNext()) {
                JsonNode songNode = iterator.next();
                List<String> artists = new ArrayList<>();
                Iterator<JsonNode> artistIterator = songNode.path("ar").elements();
                while (artistIterator.hasNext()) {
                    artists.add(artistIterator.next().path("name").asText());
                }
                candidates.add(new SearchSongResult(
                        songNode.path("id").asLong(),
                        songNode.path("name").asText(),
                        songNode.path("al").path("name").asText(),
                        artists,
                        songNode.path("dt").asLong(),
                        0D
                ));
            }
            return candidates;
        } catch (Exception e) {
            throw new IllegalStateException("解析网易云搜索结果失败", e);
        }
    }

    private double calculateScore(SpotifyApiClient.SpotifyTrack spotifyTrack, SearchSongResult candidate) {
        double titleScore = normalizedMatchScore(spotifyTrack.getName(), candidate.getSongName());
        double artistScore = normalizedMatchScore(firstArtist(spotifyTrack), firstArtist(candidate));
        double albumScore = normalizedMatchScore(spotifyTrack.getAlbumName(), candidate.getAlbumName());
        double durationScore = durationScore(spotifyTrack.getDurationMs(), candidate.getDurationMs());
        return titleScore * 0.45D + artistScore * 0.35D + albumScore * 0.05D + durationScore * 0.15D;
    }

    private double normalizedMatchScore(String left, String right) {
        if (StrUtil.isBlank(left) || StrUtil.isBlank(right)) {
            return 0D;
        }
        String normalizedLeft = normalize(left);
        String normalizedRight = normalize(right);
        if (normalizedLeft.equals(normalizedRight)) {
            return 1D;
        }
        if (normalizedLeft.contains(normalizedRight) || normalizedRight.contains(normalizedLeft)) {
            return 0.85D;
        }
        return 0D;
    }

    private double durationScore(long left, long right) {
        long delta = Math.abs(left - right);
        if (delta <= 1000L) {
            return 1D;
        }
        if (delta <= 3000L) {
            return 0.8D;
        }
        if (delta <= 5000L) {
            return 0.5D;
        }
        return 0D;
    }

    private String normalize(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fa5]", "");
    }

    private String firstArtist(SpotifyApiClient.SpotifyTrack spotifyTrack) {
        return spotifyTrack.getArtists() == null || spotifyTrack.getArtists().isEmpty() ? "" : spotifyTrack.getArtists().get(0);
    }

    private String firstArtist(SearchSongResult result) {
        return result.getArtists() == null || result.getArtists().isEmpty() ? "" : result.getArtists().get(0);
    }

    private String trimSlash(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Data
    @AllArgsConstructor
    public static class SearchSongResult {
        private Long songId;
        private String songName;
        private String albumName;
        private List<String> artists;
        private long durationMs;
        private double score;
    }
}
