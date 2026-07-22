package com.hospital.core.iam.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hospital.core.iam.application.MenuItem;
import com.hospital.core.iam.application.MenuService;

@RestController
public class MenuController {

    private final MenuService menuService;

    public MenuController(MenuService menuService) {
        this.menuService = menuService;
    }

    @GetMapping("/api/core/iam/menu")
    public ResponseEntity<List<MenuItem>> menu() {
        return ResponseEntity.ok(menuService.currentMenu());
    }
}
