package com.hackathon.chargesync;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    @GetMapping("/session")
    public Map<String, Object> session(HttpSession session) {
        return auth.sessionView(auth.current(session));
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest body, HttpServletRequest request) {
        AuthService.Account account = auth.authenticate(body.email, body.password, body.role);
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(AuthService.SESSION_ACCOUNT_ID, account.id);
        return auth.sessionView(account);
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegistrationRequest body, HttpServletRequest request) {
        PortalService.StationRequest station = null;
        if ("STATION".equalsIgnoreCase(body.role)) {
            station = new PortalService.StationRequest();
            station.name = body.stationName;
            station.area = body.stationArea;
            station.latitude = body.latitude == null ? 12.0 : body.latitude;
            station.longitude = body.longitude == null ? 77.0 : body.longitude;
            station.maxOutputKw = body.maxOutputKw == null ? 60 : body.maxOutputKw;
            station.pricePerKwh = body.pricePerKwh == null ? 18 : body.pricePerKwh;
            station.docks = body.docks;
        }
        AuthService.Account account = auth.register(body.email, body.password, body.role, station);
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session.setAttribute(AuthService.SESSION_ACCOUNT_ID, account.id);
        return auth.sessionView(account);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        if (session != null) session.invalidate();
        return Map.of("authenticated", false);
    }

    public static class LoginRequest { public String email; public String password; public String role; }
    public static class RegistrationRequest {
        public String email; public String password; public String role;
        public String stationName; public String stationArea;
        public Double latitude; public Double longitude; public Double maxOutputKw; public Double pricePerKwh;
        public java.util.List<PortalService.DockRequest> docks;
    }
}
