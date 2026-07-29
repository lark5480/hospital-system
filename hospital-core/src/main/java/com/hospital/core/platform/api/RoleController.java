package com.hospital.core.platform.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.platform.annotation.AuditLog;
import com.hospital.core.platform.domain.Role;
import com.hospital.core.platform.domain.RoleAuthority;
import com.hospital.core.platform.infrastructure.RoleAuthorityMapper;
import com.hospital.core.platform.infrastructure.RoleMapper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@Tag(name = "平台功能", description = "角色与权限管理")
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('system:admin')")
public class RoleController {

    private final RoleAuthorityMapper roleAuthorityMapper;
    private final RoleMapper roleMapper;

    @Operation(summary = "查询角色列表及其权限")
    @GetMapping("/api/core/iam/roles")
    public ResponseEntity<List<RoleWithAuthorities>> list() {
        List<Role> roles = roleMapper.selectList(null);
        List<RoleWithAuthorities> result = roles.stream().map(r -> {
            Set<String> auths = roleAuthorityMapper.findAuthoritiesByRole(r.getCode());
            return new RoleWithAuthorities(r.getCode(), r.getName(), r.getDescription(), new ArrayList<>(auths));
        }).toList();
        return ResponseEntity.ok(result);
    }

    @AuditLog(action = "SAVE_ROLE_AUTHORITIES")
    @Operation(summary = "保存角色权限")
    @PutMapping("/api/core/iam/roles/{code}/authorities")
    public ResponseEntity<?> saveAuthorities(@Parameter(description = "角色编码") @PathVariable String code, @RequestBody List<String> authorities) {
        roleAuthorityMapper.delete(new LambdaQueryWrapper<RoleAuthority>().eq(RoleAuthority::getRoleCode, code));
        if (authorities != null) {
            for (String auth : authorities) {
                RoleAuthority ra = new RoleAuthority();
                ra.setRoleCode(code);
                ra.setAuthority(auth);
                roleAuthorityMapper.insert(ra);
            }
        }
        return ResponseEntity.ok(Map.of("updated", code, "authorities", authorities));
    }

    public record RoleWithAuthorities(String code, String name, String description, List<String> authorities) {}
}
