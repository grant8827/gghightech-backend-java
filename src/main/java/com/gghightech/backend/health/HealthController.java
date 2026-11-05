package com.gghightech.backend.health;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Same path and shape as the FastAPI backend's health route, so either
 * service can sit behind the same uptime check. Spring's own, richer check
 * is at /actuator/health.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "gghightech-backend-java");
    }
}
