package com.hospital.core.iam.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.core.iam.domain.Menu;
import com.hospital.core.iam.domain.MenuAuthority;
import com.hospital.core.iam.infrastructure.MenuAuthorityMapper;
import com.hospital.core.iam.infrastructure.MenuMapper;
import com.hospital.core.platform.annotation.AuditLog;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "身份认证与菜单", description = "菜单管理（增删改查、排序）")
@RestController
@RequestMapping("/api/core/iam/menu-manage")
@PreAuthorize("hasAuthority('system:admin')")
@RequiredArgsConstructor
public class MenuManageController {

    private final MenuMapper menuMapper;
    private final MenuAuthorityMapper menuAuthorityMapper;

    @Operation(summary = "查询全部菜单列表")
    @GetMapping
    public List<Menu> listAll() {
        List<Menu> all = menuMapper.selectList(new QueryWrapper<Menu>().orderByAsc("sort_order"));
        for (Menu menu : all) {
            menu.setAuthorities(menuAuthorityMapper.findAuthoritiesByMenuId(menu.getId()));
        }
        return all;
    }

    @Operation(summary = "获取菜单树结构")
    @GetMapping("/tree")
    public List<Menu> tree() {
        List<Menu> all = listAll();
        return buildTree(all, null);
    }

    @AuditLog(action = "CREATE_MENU")
    @Operation(summary = "创建菜单")
    @PostMapping
    public Menu create(@RequestBody Menu menu) {
        menu.setCreatedAt(LocalDateTime.now());
        menuMapper.insert(menu);
        saveAuthorities(menu.getId(), menu.getAuthorities());
        return menu;
    }

    @AuditLog(action = "UPDATE_MENU")
    @Operation(summary = "更新菜单")
    @PutMapping("/{id}")
    public Menu update(@Parameter(description = "菜单ID") @PathVariable Long id, @RequestBody Menu menu) {
        menu.setId(id);
        menuMapper.updateById(menu);
        // 先删后插权限
        menuAuthorityMapper.delete(new QueryWrapper<MenuAuthority>().eq("menu_id", id));
        saveAuthorities(id, menu.getAuthorities());
        return menu;
    }

    @AuditLog(action = "DELETE_MENU")
    @Operation(summary = "删除菜单")
    @DeleteMapping("/{id}")
    public void delete(@Parameter(description = "菜单ID") @PathVariable Long id) {
        menuMapper.deleteById(id);
        menuAuthorityMapper.delete(new QueryWrapper<MenuAuthority>().eq("menu_id", id));
    }

    @AuditLog(action = "SORT_MENU")
    @Operation(summary = "调整菜单排序")
    @PutMapping("/{id}/sort")
    public void sort(@Parameter(description = "菜单ID") @PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Menu menu = menuMapper.selectById(id);
        menu.setSortOrder(body.get("sortOrder"));
        menuMapper.updateById(menu);
    }

    private void saveAuthorities(Long menuId, List<String> authorities) {
        if (authorities == null) return;
        for (String auth : authorities) {
            MenuAuthority ma = new MenuAuthority();
            ma.setMenuId(menuId);
            ma.setAuthority(auth);
            menuAuthorityMapper.insert(ma);
        }
    }

    private List<Menu> buildTree(List<Menu> allMenus, Long parentId) {
        return allMenus.stream()
                .filter(menu -> Objects.equals(menu.getParentId(), parentId))
                .peek(menu -> menu.setChildren(buildTree(allMenus, menu.getId())))
                .toList();
    }
}
