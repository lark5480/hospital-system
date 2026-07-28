package com.hospital.core.iam.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.iam.application.MenuItem;
import com.hospital.core.iam.application.MenuService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "身份认证与菜单", description = "用户菜单查询")
@RestController
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @Operation(summary = "获取当前用户菜单")
    @GetMapping("/api/core/iam/menu")
    public ResponseEntity<List<MenuItem>> menu() {
        return ResponseEntity.ok(menuService.currentMenu());
    }
}
