package com.hospital.core.iam;

import com.hospital.core.platform.infrastructure.RoleAuthorityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 菜单裁剪服务:按当前登录用户持有的 authorities 过滤 {@link MenuConfig} 中的导航树。
 * - 已登录态:从 SecurityContext 取 authority,仅保留有权限可见的节点。
 * - 匿名/未登录态:返回完整菜单(兼容本地联调零摩擦)。
 *
 * 角色↔权限映射已入库(platform.role_authority),管理员后台可调,不再硬编码。
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuConfig menuConfig;
    private final RoleAuthorityMapper roleAuthorityMapper;

    public java.util.List<MenuItem> currentMenu() {
        Collection<? extends GrantedAuthority> authorities = currentAuthorities();
        Set<String> authoritySet = authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        return menuConfig.registry().stream()
                .map(item -> filter(item, authoritySet))
                .filter(Objects::nonNull)
                .toList();
    }

    private Collection<? extends GrantedAuthority> currentAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return java.util.List.of();
        }
        return auth.getAuthorities();
    }

    private MenuItem filter(MenuItem item, Set<String> authorities) {
        // 未登录/匿名 → 全量可见
        if (authorities.isEmpty()) {
            return item;
        }

        java.util.List<MenuItem> visibleChildren = item.children().stream()
                .map(child -> filter(child, authorities))
                .filter(Objects::nonNull)
                .toList();

        if (!visibleChildren.isEmpty()) {
            // 分组节点:只要仍有可见子项即保留(自身权限不作要求)
            return new MenuItem(item.key(), item.title(), item.path(), item.icon(), item.authorities(), visibleChildren);
        }

        boolean selfVisible = item.authorities().isEmpty()
                || item.authorities().stream().anyMatch(authorities::contains);
        return selfVisible ? item : null;
    }
}
