package com.dxh.spotifysync.modules.sync.service;

import java.util.Map;

public interface SpotifySyncService {

    String buildAuthorizeUrl();

    Map<String, Object> handleCallback(String code, String state);

    Map<String, Object> syncLikedTracks();

    Map<String, Object> getStatus();
}
