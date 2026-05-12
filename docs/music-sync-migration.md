# Music Sync Migration Guide

这套 Spotify -> 网易云同步能力已经尽量收敛成独立模块，迁移到新项目时优先按“复制模块 + 少量接线”的方式做。

## 需要迁移的文件

### 1. 业务模块

- `src/main/java/com/cbm/card/mall/modules/sync/config/MusicSyncConfig.java`
- `src/main/java/com/cbm/card/mall/modules/sync/config/SpotifySyncProperties.java`
- `src/main/java/com/cbm/card/mall/modules/sync/config/NeteaseSyncProperties.java`
- `src/main/java/com/cbm/card/mall/modules/sync/controller/SpotifySyncController.java`
- `src/main/java/com/cbm/card/mall/modules/sync/job/SpotifySyncScheduler.java`
- `src/main/java/com/cbm/card/mall/modules/sync/mapper/SpotifySyncAccountMapper.java`
- `src/main/java/com/cbm/card/mall/modules/sync/mapper/TrackSyncRecordMapper.java`
- `src/main/java/com/cbm/card/mall/modules/sync/model/SpotifySyncAccount.java`
- `src/main/java/com/cbm/card/mall/modules/sync/model/TrackSyncRecord.java`
- `src/main/java/com/cbm/card/mall/modules/sync/service/SpotifySyncService.java`
- `src/main/java/com/cbm/card/mall/modules/sync/service/impl/SpotifySyncServiceImpl.java`
- `src/main/java/com/cbm/card/mall/modules/sync/service/netease/NeteaseMusicClient.java`
- `src/main/java/com/cbm/card/mall/modules/sync/service/spotify/SpotifyApiClient.java`

### 2. 公共配置

- `src/main/java/com/cbm/card/mall/config/SyncHttpConfig.java`

### 3. 数据库脚本

- `sql/music_sync.sql`

## 当前项目里真正的宿主改动

为了把功能接进当前脚手架，只做了这些宿主侧改动：

1. `application.yml` 增加了 `music-sync.*` 配置
2. `application.yml` 的白名单增加了 `/sync/**`

现在已经把 `@EnableScheduling` 从启动类移走了，放进了 `MusicSyncConfig`，所以主启动类不再需要特殊改动。

## 迁移到新项目的最小条件

目标项目需要具备：

- Spring Boot Web
- Redis
- Jackson
- 定时任务支持
- 一个 HTTP 客户端 Bean
- 持久化能力

当前实现默认用的是 MyBatis-Plus，所以如果新项目不是 MyBatis-Plus，需要替换这两部分：

- `modules/sync/mapper/*`
- `modules/sync/model/*` 与对应的持久化调用

如果新项目也是 MyBatis-Plus，基本可以直接复用。

## 迁移步骤

1. 复制 `modules/sync` 整个目录
2. 复制 `SyncHttpConfig.java`
3. 执行 `sql/music_sync.sql`
4. 把 `application.yml` 里的 `music-sync` 配置拷过去
5. 如果目标项目有安全框架，把 `/sync/**` 加到白名单
6. 如果目标项目的 Mapper 扫描路径不同，把 `modules.sync.mapper` 纳入扫描

## 推荐的新项目结构

如果你打算把它从脚手架中剥离出来，推荐放到独立服务里，例如：

- `com.yourapp.musicsync.config`
- `com.yourapp.musicsync.controller`
- `com.yourapp.musicsync.job`
- `com.yourapp.musicsync.persistence`
- `com.yourapp.musicsync.service`
- `com.yourapp.musicsync.client`

## 建议的迁移顺序

最稳的方式不是先删当前代码，而是：

1. 新建一个独立项目
2. 先把 `sync` 模块复制过去
3. 跑通 `authorize-url`
4. 跑通 `netease/probe`
5. 跑通手动 `POST /sync`
6. 最后再决定是否删除当前脚手架中的这套实现

## 当前不建议直接迁移的内容

下面这些内容更像当前脚手架的基础设施，不建议整包带走：

- `common/*`
- `modules/ums/*`
- 当前安全体系的完整实现

新的项目里只需要保留同步功能本身依赖到的少量公共能力即可。
