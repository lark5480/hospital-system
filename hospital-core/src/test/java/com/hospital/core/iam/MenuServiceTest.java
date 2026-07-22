package com.hospital.core.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.List;

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
}
