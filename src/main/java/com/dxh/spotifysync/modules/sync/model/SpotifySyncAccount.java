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
@TableName("music_sync_account")
@ApiModel(value = "SpotifySyncAccount对象", description = "Spotify 同步账户")
public class SpotifySyncAccount implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty("账户标识")
    private String accountKey;

    @ApiModelProperty("访问令牌")
    private String accessToken;

    @ApiModelProperty("刷新令牌")
    private String refreshToken;

    @ApiModelProperty("访问令牌过期时间")
    private Date tokenExpiresAt;

    @ApiModelProperty("最近一次已推进的 Spotify liked_at 水位")
    private Date lastSyncedAddedAt;

    @ApiModelProperty("最近一次同步时间")
    private Date lastSyncTime;

    @ApiModelProperty("最近一次同步状态")
    private String lastSyncStatus;

    @ApiModelProperty("最近一次错误信息")
    private String lastErrorMessage;

    @ApiModelProperty("创建时间")
    private Date createTime;

    @ApiModelProperty("更新时间")
    private Date updateTime;
}
