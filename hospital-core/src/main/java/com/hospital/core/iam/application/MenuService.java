package com.hospital.core.iam.application;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.core.iam.domain.Menu;
import com.hospital.core.iam.infrastructure.MenuAuthorityMapper;
import com.hospital.core.iam.infrastructure.MenuMapper;

import lombok.RequiredArgsConstructor;

/**
 * 菜单裁剪服务:按当前登录用户持有的 authorities 过滤数据库中的导航树。
 * - 已登录态:从 SecurityContext 取 authority,仅保留有权限可见的节点。
 * - 匿名/未登录态:返回完整菜单(兼容本地联调零摩擦)。
 *
 * 菜单数据从 platform.menu + platform.menu_authority 表加载,管理员后台可调,不再硬编码。
 */
@Service
@RequiredArgsConstructor
public class MenuService {

    private final MenuMapper menuMapper;
    private final MenuAuthorityMapper menuAuthorityMapper;

    public java.util.List<MenuItem> currentMenu() {
        Collection<? extends GrantedAuthority> authorities = currentAuthorities();
        Set<String> authoritySet = authorities.stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        // 从数据库加载菜单树
        List<Menu> allMenus = menuMapper.selectList(
                new QueryWrapper<Menu>()
                        .eq("visible", true)
                        .orderByAsc("sort_order"));

        // 加载每个菜单的权限
        for (Menu menu : allMenus) {
            menu.setAuthorities(menuAuthorityMapper.findAuthoritiesByMenuId(menu.getId()));
        }

        // 构建树形结构
        List<Menu> menuTree = buildTree(allMenus, null);

        // 转换为MenuItem并裁剪
        return menuTree.stream()
                .map(menu -> toMenuItem(menu, authoritySet))
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

    private List<Menu> buildTree(List<Menu> allMenus, Long parentId) {
        return allMenus.stream()
                .filter(menu -> Objects.equals(menu.getParentId(), parentId))
                .peek(menu -> menu.setChildren(buildTree(allMenus, menu.getId())))
                .toList();
    }

    private MenuItem toMenuItem(Menu menu, Set<String> authorities) {
        List<MenuItem> visibleChildren = menu.getChildren().stream()
                .map(child -> toMenuItem(child, authorities))
                .filter(Objects::nonNull)
                .toList();

        if (!visibleChildren.isEmpty()) {
            return new MenuItem(menu.getKey(), menu.getTitle(), menu.getPath(),
                    menu.getIcon(), menu.getAuthorities(), visibleChildren);
        }

        boolean selfVisible = menu.getAuthorities() == null || menu.getAuthorities().isEmpty()
                || menu.getAuthorities().stream().anyMatch(authorities::contains);
        return selfVisible ? new MenuItem(menu.getKey(), menu.getTitle(), menu.getPath(),
                menu.getIcon(), menu.getAuthorities(), List.of()) : null;
    }
}
