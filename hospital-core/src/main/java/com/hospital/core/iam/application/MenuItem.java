package com.hospital.core.iam.application;

import java.util.List;

/**
 * 导航菜单节点。后端按当前用户权限裁剪后下发给前端动态渲染侧边栏。
 * - path 为 null 表示纯分组(子菜单),前端用 key 作为 el-sub-menu 的 index。
 * - authorities 为空表示"任意已登录用户可见";非空表示需拥有其中至少一个 authority。
 * - icon 为 Element Plus 图标组件名字符串,前端映射为组件(图标不序列化进前端逻辑)。
 */
public record MenuItem(
        String key,
        String title,
        String path,
        String icon,
        List<String> authorities,
        List<MenuItem> children
) {
    public MenuItem {
        if (authorities == null) authorities = List.of();
        if (children == null) children = List.of();
    }
}
