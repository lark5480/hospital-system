package com.hospital.core.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hospital.core.iam.application.MenuService;
import com.hospital.core.iam.domain.Menu;
import com.hospital.core.iam.infrastructure.MenuAuthorityMapper;
import com.hospital.core.iam.infrastructure.MenuMapper;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock MenuMapper menuMapper;
    @Mock MenuAuthorityMapper menuAuthorityMapper;

    @InjectMocks MenuService menuService;

    /**
     * R-54: {@code SecurityContextHolder} 基于 ThreadLocal,用例里 {@code setAuthentication} 后若不清空,
     * 会泄漏到同线程执行的后续测试(按权限裁剪的 MenuService / VisitService 等会读到脏身份)。
     * 用例前后各清一次:前者防被他人污染,后者防污染他人(修复点)。
     */
    @BeforeEach
    void clearSecurityContextBefore() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContextAfter() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentMenu_filtersByAuthorities() {
        Menu dashboard = new Menu();
        dashboard.setId(1L);
        dashboard.setKey("dashboard");
        dashboard.setTitle("工作台");
        dashboard.setPath("/dashboard");
        dashboard.setIcon("HomeFilled");
        dashboard.setSortOrder(1);
        dashboard.setVisible(true);

        Menu visits = new Menu();
        visits.setId(2L);
        visits.setKey("visits");
        visits.setTitle("门诊就诊");
        visits.setPath("/visits");
        visits.setIcon("FirstAidKit");
        visits.setSortOrder(2);
        visits.setVisible(true);

        when(menuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(dashboard, visits));
        when(menuAuthorityMapper.findAuthoritiesByMenuId(1L)).thenReturn(List.of());
        when(menuAuthorityMapper.findAuthoritiesByMenuId(2L)).thenReturn(List.of("visit:entry"));

        // 设置用户权限
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test", null,
                        List.of(new SimpleGrantedAuthority("visit:entry"))));

        var result = menuService.currentMenu();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).key()).isEqualTo("dashboard");
        assertThat(result.get(1).key()).isEqualTo("visits");
    }

    @Test
    void currentMenu_hidesUnauthorizedMenus() {
        Menu dashboard = new Menu();
        dashboard.setId(1L);
        dashboard.setKey("dashboard");
        dashboard.setTitle("工作台");
        dashboard.setPath("/dashboard");
        dashboard.setIcon("HomeFilled");
        dashboard.setSortOrder(1);
        dashboard.setVisible(true);

        Menu cashier = new Menu();
        cashier.setId(3L);
        cashier.setKey("cashier");
        cashier.setTitle("收费管理");
        cashier.setPath("/cashier");
        cashier.setIcon("Coin");
        cashier.setSortOrder(3);
        cashier.setVisible(true);

        when(menuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(dashboard, cashier));
        when(menuAuthorityMapper.findAuthoritiesByMenuId(1L)).thenReturn(List.of());
        when(menuAuthorityMapper.findAuthoritiesByMenuId(3L)).thenReturn(List.of("charge:pay"));

        // 设置用户权限（只有visit:entry，没有charge:pay）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test", null,
                        List.of(new SimpleGrantedAuthority("visit:entry"))));

        var result = menuService.currentMenu();

        // 只有dashboard可见，cashier不可见
        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("dashboard");
    }

    /**
     * R-54 补充用例:父菜单自身授权通过、但其全部子菜单被权限过滤时,父菜单仍应返回(children 为空)。
     * 依赖 {@code MenuService.toMenuItem} 的分支:visibleChildren 为空时回落到父节点自身的权限判断。
     */
    @Test
    void currentMenu_parentAuthorizedButAllChildrenFiltered_returnsParent() {
        Menu pharmacy = menu(10L, null, "pharmacy", "药事管理", null, "Box", 5, true);
        Menu prescriptions = menu(11L, 10L, "pharmacy-prescriptions", "处方发药",
                "/pharmacy/prescriptions", "List", 0, true);

        when(menuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(pharmacy, prescriptions));
        // 父菜单要求 visit:entry(当前用户持有);子菜单要求 charge:pay(当前用户不具备)
        when(menuAuthorityMapper.findAuthoritiesByMenuId(10L)).thenReturn(List.of("visit:entry"));
        when(menuAuthorityMapper.findAuthoritiesByMenuId(11L)).thenReturn(List.of("charge:pay"));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test", null,
                        List.of(new SimpleGrantedAuthority("visit:entry"))));

        var result = menuService.currentMenu();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("pharmacy");
        // 子菜单全部被过滤 → 父菜单以"无可见子节点"的形式返回
        assertThat(result.get(0).children()).isEmpty();
    }

    /**
     * R-54 实测行为:匿名/未登录时 {@code currentAuthorities()} 返回空集合,
     * 但 {@code toMenuItem} 仍会按 authority 裁剪 —— 只有"无权限要求"的菜单可见。
     *
     * <p>因此 {@link MenuService} 类注释声称的"匿名态返回完整菜单"并<b>不</b>普遍成立:
     * 仅当所有菜单都不带权限要求(menu_authority 为空)时才等价于完整菜单。
     * 本用例固化真实语义:无权限要求的 dashboard 保留,要求 visit:entry 的 visits 被过滤。
     */
    @Test
    void currentMenu_anonymous_filtersMenusRequiringAuthority() {
        Menu dashboard = menu(1L, null, "dashboard", "工作台", "/dashboard", "HomeFilled", 1, true);
        Menu visits = menu(2L, null, "visits", "门诊就诊", "/visits", "FirstAidKit", 2, true);

        when(menuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(dashboard, visits));
        when(menuAuthorityMapper.findAuthoritiesByMenuId(1L)).thenReturn(List.of());
        when(menuAuthorityMapper.findAuthoritiesByMenuId(2L)).thenReturn(List.of("visit:entry"));

        // 不设置 Authentication → 匿名(等价于未登录)
        var result = menuService.currentMenu();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("dashboard");
    }

    private static Menu menu(Long id, Long parentId, String key, String title,
                             String path, String icon, int sortOrder, boolean visible) {
        Menu m = new Menu();
        m.setId(id);
        m.setParentId(parentId);
        m.setKey(key);
        m.setTitle(title);
        m.setPath(path);
        m.setIcon(icon);
        m.setSortOrder(sortOrder);
        m.setVisible(visible);
        return m;
    }
}
