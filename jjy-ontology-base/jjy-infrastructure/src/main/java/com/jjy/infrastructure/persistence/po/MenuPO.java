package com.jjy.infrastructure.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * t_menu 持久化对象（资源菜单表：GROUP / MENU / BUTTON 统一管理）
 *
 * <p>由 V21 迁移创建。设计要点：</p>
 * <ul>
 *   <li>code：业务主键（资源码），如 menu.dashboard / btn.customer.create</li>
 *   <li>type：GROUP=顶级分组 / MENU=可点击页面 / BUTTON=页内操作</li>
 *   <li>permission：准入权限（CSV，OR 关系；空=无限制；*=超管专属）</li>
 *   <li>可见性由"角色权限码 ∩ 菜单准入权限"决定，<b>不需要</b> t_role_menu 关联表</li>
 * </ul>
 *
 * @author jjy-base
 */
@Data
@TableName("t_menu")
public class MenuPO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 资源码（业务主键） */
    private String code;

    /** 显示名 */
    private String name;

    /** GROUP / MENU / BUTTON */
    private String type;

    /** 父节点 ID（顶级为 NULL） */
    private Long parentId;

    /** 前端路由 path（MENU 才有） */
    private String path;

    /** 图标（MENU/GROUP 才有） */
    private String icon;

    /** 同级排序 */
    private Integer sort;

    /** 准入权限 CSV（OR 关系；空=无限制；*=超管专属） */
    private String permission;

    /** 1=启用 0=停用 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;

    @TableLogic
    private Integer flag;
}
