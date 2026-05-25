package com.dxh.spotifysync.modules.sync.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("music_sync_record")
@ApiModel(value = "TrackSyncRecord对象", description = "歌曲同步记录")
public class TrackSyncRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty("账户标识")
    private String accountKey;

    @ApiModelProperty("Spotify track id")
    private String sourceTrackId;

    @ApiModelProperty("歌曲名称")
    private String sourceTrackName;

    @ApiModelProperty("歌手名称")
    private String sourceArtistNames;

    @ApiModelProperty("专辑名称")
    private String sourceAlbumName;

    @ApiModelProperty("Spotify liked_at")
    private Date spotifyAddedAt;

    @ApiModelProperty("专辑封面URL")
    private String coverUrl;

    @ApiModelProperty("歌曲时长，单位毫秒")
    private Long durationMs;

    @ApiModelProperty("网易云歌曲 id")
    private Long targetSongId;

    @ApiModelProperty("同步状态 SUCCESS/NOT_FOUND/FAILED")
    private String syncStatus;

    @ApiModelProperty("失败原因")
    private String errorMessage;

    @ApiModelProperty("重试次数")
    private Integer retryCount;

    @ApiModelProperty("创建时间")
    private Date createTime;

    @ApiModelProperty("更新时间")
    private Date updateTime;
}
