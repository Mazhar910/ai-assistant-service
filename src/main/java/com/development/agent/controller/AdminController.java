package com.development.agent.controller;

import com.development.agent.service.AdminService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    public ResponseEntity<?> getAllUsers(@RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        if (page != null && size != null) {
            Pageable pageable = PageRequest.of(page, size);
            return ResponseEntity.ok(adminService.getAllUsers(pageable));
        }
        // Backward compatible: no pagination params -> plain list (existing UI)
        return ResponseEntity.ok(adminService.getAllUsers());
    }

    @PutMapping("/user/{userId}/toggle-access")
    public ResponseEntity<?> toggleAccess(@PathVariable Long userId) {
        return ResponseEntity.ok(adminService.toggleUserAccess(userId));
    }

    @PutMapping("/user/{userId}/role")
    public ResponseEntity<?> changeRole(@PathVariable Long userId, @RequestBody Map<String, String> body) {
        String role = body.get("role");
        if (role == null || (!role.equals("ADMIN") && !role.equals("USER"))) {
            return ResponseEntity.badRequest().body(Map.of("message", "Role must be ADMIN or USER"));
        }
        return ResponseEntity.ok(adminService.changeUserRole(userId, role));
    }

    @DeleteMapping("/user/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable Long userId) {
        return ResponseEntity.ok(adminService.deleteUser(userId));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getOverallStats() {
        return ResponseEntity.ok(adminService.getOverallStats());
    }

    @GetMapping("/stats/user/{userId}")
    public ResponseEntity<?> getUserStats(@PathVariable Long userId) {
        return ResponseEntity.ok(adminService.getUserStats(userId));
    }
}
