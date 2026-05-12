package com.dxh.spotifysync.modules.ums.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dxh.spotifysync.modules.ums.mapper.UmsRoleMenuRelationMapper;
import com.dxh.spotifysync.modules.ums.model.UmsRoleMenuRelation;
import com.dxh.spotifysync.modules.ums.service.UmsRoleMenuRelationService;
import org.springframework.stereotype.Service;

/**
 * 角色菜单关系管理Service实现类
 */
@Service
public class UmsRoleMenuRelationServiceImpl extends ServiceImpl<UmsRoleMenuRelationMapper, UmsRoleMenuRelation> implements UmsRoleMenuRelationService {
}
