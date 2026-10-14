package com.hackathon.chargesync;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SimulationController {
    private final SimulationService service;
    private final AuthService auth;

    public SimulationController(SimulationService service, AuthService auth) {
        this.service = service;
        this.auth = auth;
    }

    @GetMapping("/state") public SimulationState state(HttpSession session) { auth.requireAccount(session); return service.state(); }
    @PostMapping("/start") public SimulationState start(HttpSession session) { auth.requireAccount(session); return service.start(); }
    @PostMapping("/pause") public SimulationState pause(HttpSession session) { auth.requireAccount(session); return service.pause(); }
    @PostMapping("/tick") public SimulationState tick(HttpSession session) { auth.requireAccount(session); return service.tick(); }

    @PostMapping("/reset") public SimulationState reset(@RequestBody(required=false) Map<String,Object> body, HttpSession session) {
        auth.requireAccount(session);
        if (body == null) body = Map.of();
        String scenario = String.valueOf(body.getOrDefault("scenario", "crowded"));
        double limit = Double.parseDouble(String.valueOf(body.getOrDefault("limit", 15)));
        boolean cheap = Boolean.parseBoolean(String.valueOf(body.getOrDefault("cheapTariff", false)));
        return service.reset(scenario, limit, cheap);
    }

    @PostMapping("/limit") public SimulationState limit(@RequestBody Map<String,Object> body, HttpSession session) {
        auth.requireAccount(session);
        return service.setLimit(Double.parseDouble(String.valueOf(body.getOrDefault("limit", 15))));
    }

    @PostMapping("/tariff") public SimulationState tariff(@RequestBody Map<String,Object> body, HttpSession session) {
        auth.requireAccount(session);
        return service.setTariff(Double.parseDouble(String.valueOf(body.getOrDefault("tariff", 8))));
    }

    @PostMapping("/vehicles") public SimulationState addVehicle(@RequestBody VehicleRequest body, HttpSession session) {
        auth.requireAccount(session);
        return service.addVehicle(body);
    }

    @PutMapping("/vehicles/{id}") public SimulationState updateVehicle(@PathVariable String id,
                                                                          @RequestBody VehicleRequest body,
                                                                          HttpSession session) {
        auth.requireAccount(session);
        return service.updateVehicle(id, body);
    }

    @DeleteMapping("/vehicles/{id}") public SimulationState removeVehicle(@PathVariable String id, HttpSession session) {
        auth.requireAccount(session);
        return service.removeVehicle(id);
    }
}
