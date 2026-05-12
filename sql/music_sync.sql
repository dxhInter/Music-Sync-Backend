CREATE TABLE IF NOT EXISTS `music_sync_account` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_key` varchar(64) NOT NULL,
  `access_token` varchar(2048) DEFAULT NULL,
  `refresh_token` varchar(2048) DEFAULT NULL,
  `token_expires_at` datetime DEFAULT NULL,
  `last_synced_added_at` datetime DEFAULT NULL,
  `last_sync_time` datetime DEFAULT NULL,
  `last_sync_status` varchar(32) DEFAULT NULL,
  `last_error_message` varchar(512) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_key` (`account_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `music_sync_record` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `account_key` varchar(64) NOT NULL,
  `source_track_id` varchar(64) NOT NULL,
  `source_track_name` varchar(255) DEFAULT NULL,
  `source_artist_names` varchar(255) DEFAULT NULL,
  `source_album_name` varchar(255) DEFAULT NULL,
  `spotify_added_at` datetime NOT NULL,
  `duration_ms` bigint DEFAULT NULL,
  `target_song_id` bigint DEFAULT NULL,
  `sync_status` varchar(32) NOT NULL,
  `error_message` varchar(512) DEFAULT NULL,
  `retry_count` int NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_account_track_added` (`account_key`, `source_track_id`, `spotify_added_at`),
  KEY `idx_account_status_update_time` (`account_key`, `sync_status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
