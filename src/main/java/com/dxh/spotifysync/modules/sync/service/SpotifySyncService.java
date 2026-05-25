package com.dxh.spotifysync.modules.sync.service;

import java.util.List;
import java.util.Map;

public interface SpotifySyncService {

    String buildAuthorizeUrl(Long userId);

    Map<String, Object> handleCallback(String code, String state);

    Map<String, Object> syncLikedTracks();

    Map<String, Object> getStatus();

    List<Map<String, Object>> getGallery(int limit);

    Map<String, Object> backfillCovers();

    Map<String, Object> computeStats();
}
