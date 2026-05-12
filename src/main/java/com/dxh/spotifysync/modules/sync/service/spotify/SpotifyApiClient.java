package com.dxh.spotifysync.modules.sync.service.spotify;

import com.dxh.spotifysync.modules.sync.config.SpotifySyncProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

@Component
public class SpotifyApiClient {

    private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final String SAVED_TRACKS_URL = "https://api.spotify.com/v1/me/tracks";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SpotifySyncProperties spotifySyncProperties;

    public SpotifyApiClient(RestTemplate restTemplate,
                            ObjectMapper objectMapper,
                            SpotifySyncProperties spotifySyncProperties) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.spotifySyncProperties = spotifySyncProperties;
    }

    public String buildAuthorizeUrl(String state) {
        return UriComponentsBuilder.fromHttpUrl(AUTHORIZE_URL)
                .queryParam("client_id", spotifySyncProperties.getClientId())
                .queryParam("response_type", "code")
                .queryParam("redirect_uri", spotifySyncProperties.getRedirectUri())
                .queryParam("scope", spotifySyncProperties.getScope())
                .queryParam("state", state)
                .build(true)
                .toUriString();
    }

    public SpotifyTokenResponse exchangeCode(String code) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", spotifySyncProperties.getRedirectUri());
        JsonNode jsonNode = postToken(body);
        return parseTokenResponse(jsonNode);
    }

    public SpotifyTokenResponse refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "refresh_token");
        body.add("refresh_token", refreshToken);
        JsonNode jsonNode = postToken(body);
        return parseTokenResponse(jsonNode);
    }

    public List<SpotifySavedTrackItem> getSavedTracks(String accessToken, int limit) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);
        URI uri = UriComponentsBuilder.fromHttpUrl(SAVED_TRACKS_URL)
                .queryParam("limit", limit)
                .build(true)
                .toUri();
        ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, requestEntity, String.class);
        return parseSavedTracks(response.getBody());
    }

    private JsonNode postToken(MultiValueMap<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set(HttpHeaders.AUTHORIZATION, buildBasicAuthorization());
        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity(TOKEN_URL, requestEntity, String.class);
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("解析 Spotify token 响应失败", e);
        }
    }

    private SpotifyTokenResponse parseTokenResponse(JsonNode jsonNode) {
        String accessToken = text(jsonNode, "access_token");
        String refreshToken = text(jsonNode, "refresh_token");
        long expiresIn = jsonNode.path("expires_in").asLong(3600L);
        Date expiresAt = Date.from(Instant.now().plusSeconds(expiresIn));
        return new SpotifyTokenResponse(accessToken, refreshToken, expiresAt);
    }

    private List<SpotifySavedTrackItem> parseSavedTracks(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode items = root.path("items");
            List<SpotifySavedTrackItem> result = new ArrayList<>();
            Iterator<JsonNode> iterator = items.elements();
            while (iterator.hasNext()) {
                JsonNode itemNode = iterator.next();
                JsonNode trackNode = itemNode.path("track");
                if (trackNode.isMissingNode() || trackNode.isNull()) {
                    continue;
                }
                List<String> artists = new ArrayList<>();
                Iterator<JsonNode> artistIterator = trackNode.path("artists").elements();
                while (artistIterator.hasNext()) {
                    artists.add(text(artistIterator.next(), "name"));
                }
                SpotifyTrack track = new SpotifyTrack(
                        text(trackNode, "id"),
                        text(trackNode, "name"),
                        text(trackNode.path("album"), "name"),
                        artists,
                        trackNode.path("duration_ms").asLong(0L)
                );
                Date addedAt = Date.from(Instant.parse(text(itemNode, "added_at")));
                result.add(new SpotifySavedTrackItem(addedAt, track));
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("解析 Spotify liked tracks 响应失败", e);
        }
    }

    private String buildBasicAuthorization() {
        String plain = spotifySyncProperties.getClientId() + ":" + spotifySyncProperties.getClientSecret();
        String encoded = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        return "Basic " + encoded;
    }

    private String text(JsonNode node, String fieldName) {
        JsonNode field = node.path(fieldName);
        return field.isMissingNode() || field.isNull() ? null : field.asText();
    }

    @Data
    @AllArgsConstructor
    public static class SpotifyTokenResponse {
        private String accessToken;
        private String refreshToken;
        private Date expiresAt;
    }

    @Data
    @AllArgsConstructor
    public static class SpotifySavedTrackItem {
        private Date addedAt;
        private SpotifyTrack track;
    }

    @Data
    @AllArgsConstructor
    public static class SpotifyTrack {
        private String id;
        private String name;
        private String albumName;
        private List<String> artists;
        private long durationMs;
    }
}
