package com.hospital.core.iam;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
