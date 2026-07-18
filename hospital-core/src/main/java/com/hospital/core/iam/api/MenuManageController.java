package com.hospital.core.iam.api;

import com.hospital.core.iam.domain.Menu;
import com.hospital.core.iam.domain.MenuAuthority;
import com.hospital.core.iam.infrastructure.MenuAuthorityMapper;
import com.hospital.core.iam.infrastructure.MenuMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/core/iam/menu-manage")
@PreAuthorize("hasAuthority('system:admin')")
@RequiredArgsConstructor
public class MenuManageController {

    private final MenuMapper menuMapper;
    private final MenuAuthorityMapper menuAuthorityMapper;

    @GetMapping
    public List<Menu> listAll() {
        List<Menu> all = menuMapper.selectList(new QueryWrapper<Menu>().orderByAsc("sort_order"));
        for (Menu menu : all) {
            menu.setAuthorities(menuAuthorityMapper.findAuthoritiesByMenuId(menu.getId()));
        }
        return all;
    }

    @GetMapping("/tree")
    public List<Menu> tree() {
        List<Menu> all = listAll();
        return buildTree(all, null);
    }

    @PostMapping
    public Menu create(@RequestBody Menu menu) {
        menu.setCreatedAt(LocalDateTime.now());
        menuMapper.insert(menu);
        saveAuthorities(menu.getId(), menu.getAuthorities());
        return menu;
    }

    @PutMapping("/{id}")
    public Menu update(@PathVariable Long id, @RequestBody Menu menu) {
        menu.setId(id);
        menuMapper.updateById(menu);
        // 先删后插权限
        menuAuthorityMapper.delete(new QueryWrapper<MenuAuthority>().eq("menu_id", id));
        saveAuthorities(id, menu.getAuthorities());
        return menu;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        menuMapper.deleteById(id);
        menuAuthorityMapper.delete(new QueryWrapper<MenuAuthority>().eq("menu_id", id));
    }

    @PutMapping("/{id}/sort")
    public void sort(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
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
