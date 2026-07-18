package com.hospital.core.platform.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hospital.core.platform.domain.Role;
import com.hospital.core.platform.domain.RoleAuthority;
import com.hospital.core.platform.infrastructure.RoleAuthorityMapper;
import com.hospital.core.platform.infrastructure.RoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('system:admin')")
public class RoleController {

    private final RoleAuthorityMapper roleAuthorityMapper;
    private final RoleMapper roleMapper;

    @GetMapping("/api/core/iam/roles")
    public ResponseEntity<List<RoleWithAuthorities>> list() {
        List<Role> roles = roleMapper.selectList(null);
        List<RoleWithAuthorities> result = roles.stream().map(r -> {
            Set<String> auths = roleAuthorityMapper.findAuthoritiesByRole(r.getCode());
            return new RoleWithAuthorities(r.getCode(), r.getName(), r.getDescription(), new ArrayList<>(auths));
        }).toList();
        return ResponseEntity.ok(result);
    }

    @PutMapping("/api/core/iam/roles/{code}/authorities")
    public ResponseEntity<?> saveAuthorities(@PathVariable String code, @RequestBody List<String> authorities) {
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
