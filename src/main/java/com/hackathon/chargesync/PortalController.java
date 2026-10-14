package com.hackathon.chargesync;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/portal")
public class PortalController {
    private final PortalService portal;
    private final AuthService auth;

    public PortalController(PortalService portal, AuthService auth) {
        this.portal = portal;
        this.auth = auth;
    }

    @GetMapping("/stations")
    public List<PortalService.Station> stations(HttpSession session) {
        AuthService.Account account = auth.requireAccount(session);
        List<PortalService.Station> stations = portal.getStations();
        if ("STATION".equals(account.role)) return stations.stream().filter(s -> Objects.equals(s.id, account.stationId)).toList();
        return stations;
    }

    @PostMapping("/stations")
    public PortalService.Station createStation(@RequestBody PortalService.StationRequest request, HttpSession session) {
        AuthService.Account account = auth.requireRole(session, "STATION");
        PortalService.Station station = portal.createStation(request);
        auth.assignStation(account, station.id);
        return station;
    }

    @PutMapping("/stations/{stationId}")
    public PortalService.Station updateStation(@PathVariable String stationId,
                                               @RequestBody PortalService.StationRequest request,
                                               HttpSession session) {
        AuthService.Account account = auth.requireRole(session, "STATION");
        auth.requireStationOwnership(account, stationId);
        return portal.updateStation(stationId, request);
    }

    @PutMapping("/stations/{stationId}/docks/{dockId}/status")
    public PortalService.Dock updateDockStatus(@PathVariable String stationId,
                                               @PathVariable String dockId,
                                               @RequestBody Map<String, String> body,
                                               HttpSession session) {
        AuthService.Account account = auth.requireRole(session, "STATION");
        auth.requireStationOwnership(account, stationId);
        return portal.updateDockStatus(stationId, dockId, body.get("status"));
    }

    @GetMapping("/bookings")
    public List<PortalService.Booking> bookings(@RequestParam(required = false) String stationId, HttpSession session) {
        AuthService.Account account = auth.requireAccount(session);
        if ("STATION".equals(account.role)) {
            if (stationId != null && !stationId.isBlank()) auth.requireStationOwnership(account, stationId);
            return portal.getBookings(account.stationId, null);
        }
        return portal.getBookings(null, account.id);
    }

    @PostMapping("/bookings")
    public PortalService.Booking createBooking(@RequestBody PortalService.DriverRequest request, HttpSession session) {
        AuthService.Account account = auth.requireRole(session, "DRIVER");
        return portal.createBooking(request, account.id);
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    public PortalService.Booking cancelBooking(@PathVariable String bookingId, HttpSession session) {
        AuthService.Account account = auth.requireAccount(session);
        requireBookingAccess(bookingId, account);
        return portal.cancelBooking(bookingId);
    }

    @PostMapping("/bookings/{bookingId}/complete")
    public PortalService.Booking completeBooking(@PathVariable String bookingId, HttpSession session) {
        AuthService.Account account = auth.requireRole(session, "STATION");
        requireBookingAccess(bookingId, account);
        return portal.completeBooking(bookingId);
    }

    @PostMapping("/advance")
    public List<PortalService.Booking> advanceFiveMinutes(HttpSession session) {
        auth.requireRole(session, "STATION");
        return portal.advanceFiveMinutes();
    }

    private void requireBookingAccess(String bookingId, AuthService.Account account) {
        List<PortalService.Booking> visible = "STATION".equals(account.role)
            ? portal.getBookings(account.stationId, null) : portal.getBookings(null, account.id);
        if (visible.stream().noneMatch(b -> bookingId.equals(b.id))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This booking belongs to a different account.");
        }
    }
}
