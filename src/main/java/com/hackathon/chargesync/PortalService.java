package com.hackathon.chargesync;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** Shared in-memory service behind the driver and station portals. */
@Service
public class PortalService {
    private static final double TRIP_RESERVE_PERCENT = 15.0;
    private final LinkedHashMap<String, Station> stations = new LinkedHashMap<>();
    private final LinkedHashMap<String, Booking> bookings = new LinkedHashMap<>();

    public PortalService() {
        seedDemoStations();
    }

    public synchronized List<Station> getStations() {
        recalculate();
        return new ArrayList<>(stations.values());
    }

    public synchronized List<Booking> getBookings(String stationId, String driverAccountId) {
        recalculate();
        return bookings.values().stream()
            .filter(b -> stationId == null || stationId.isBlank() || stationId.equals(b.assignedStationId))
            .filter(b -> driverAccountId == null || driverAccountId.isBlank() || driverAccountId.equals(b.driverAccountId))
            .sorted(Comparator.comparingInt((Booking b) -> b.priorityRank == 0 ? Integer.MAX_VALUE : b.priorityRank)
                .thenComparing(b -> b.createdAt))
            .collect(Collectors.toList());
    }

    public synchronized Station createStation(StationRequest request) {
        validateStation(request);
        String id = "station-" + UUID.randomUUID().toString().substring(0, 8);
        Station station = new Station();
        station.id = id;
        applyStationConfig(station, request, null);
        stations.put(id, station);
        recalculate();
        return station;
    }

    public synchronized Station updateStation(String id, StationRequest request) {
        validateStation(request);
        Station station = stationOr404(id);
        applyStationConfig(station, request, station.docks);
        recalculate();
        return station;
    }

    public synchronized Dock updateDockStatus(String stationId, String dockId, String requestedStatus) {
        Station station = stationOr404(stationId);
        Dock dock = dockOr404(station, dockId);
        if (dock.bookingId != null) {
            throw conflict("This dock has an active booking. Complete or cancel it before changing dock status.");
        }
        String status = normalizeDockStatus(requestedStatus, false);
        dock.status = status;
        dock.sessionLabel = "OCCUPIED".equals(status) ? "External / station-reported session" : "";
        recalculate();
        return dock;
    }

    public synchronized Booking createBooking(DriverRequest request, String driverAccountId) {
        validateDriver(request);

        Station originStation = null;
        if (request.currentStationId != null && !request.currentStationId.isBlank()) {
            originStation = stationOr404(request.currentStationId);
        }
        double originLat = request.currentLatitude != null
            ? request.currentLatitude : originStation == null ? Double.NaN : originStation.latitude;
        double originLon = request.currentLongitude != null
            ? request.currentLongitude : originStation == null ? Double.NaN : originStation.longitude;
        if (!Double.isFinite(originLat) || !Double.isFinite(originLon)) {
            throw badRequest("Select your current station or enter both current-location coordinates.");
        }

        String connector = normalizeConnector(request.connector);
        Station selected = null;
        Dock selectedDock = null;
        if (originStation != null) {
            selectedDock = availableDock(originStation, connector);
            if (selectedDock != null) selected = originStation;
        }
        if (selected == null) {
            final double lat = originLat;
            final double lon = originLon;
            List<Station> nearest = stations.values().stream()
                .sorted(Comparator.comparingDouble(s -> distanceKm(lat, lon, s.latitude, s.longitude)))
                .toList();
            for (Station candidate : nearest) {
                Dock dock = availableDock(candidate, connector);
                if (dock != null) {
                    selected = candidate;
                    selectedDock = dock;
                    break;
                }
            }
        }
        if (selected == null || selectedDock == null) {
            throw conflict("No nearby station has an available compatible dock. Ask a station operator to open a dock.");
        }

        double tripSoc = request.estimatedTripKm > 0
            ? Math.min(100, TRIP_RESERVE_PERCENT + request.estimatedTripKm / request.rangeAt100Km * 100.0)
            : 0;
        double target = Math.max(request.targetSoc, tripSoc);
        if (target <= request.batteryPercent + 0.01) {
            throw badRequest("The current battery already meets the requested trip charge target.");
        }

        Booking booking = new Booking();
        booking.id = "CS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        booking.driverName = clean(request.driverName, "Driver");
        booking.driverAccountId = driverAccountId;
        booking.destination = clean(request.destination, "Not provided");
        booking.connector = connector;
        booking.chargeMode = normalizeChargeMode(request.chargeMode);
        booking.requestedStationId = originStation == null ? "" : originStation.id;
        booking.assignedStationId = selected.id;
        booking.assignedStationName = selected.name;
        booking.assignedDockId = selectedDock.id;

        booking.tripDistanceKm = request.estimatedTripKm;
        booking.rangeAt100Km = request.rangeAt100Km;
        booking.batteryPercent = request.batteryPercent;
        booking.batteryCapacityKwh = request.batteryCapacityKwh;
        booking.requestedTargetSoc = request.targetSoc;
        booking.requiredTripSoc = round1(tripSoc);
        booking.targetSoc = round1(target);
        booking.vehicleMaxKw = request.vehicleMaxKw;
        booking.departureMinutes = request.departureMinutes;
        booking.originalDepartureMinutes = request.departureMinutes;
        booking.pricePerKwh = selected.pricePerKwh;
        booking.distanceKm = round2(distanceKm(originLat, originLon, selected.latitude, selected.longitude));
        booking.redirected = originStation != null && !originStation.id.equals(selected.id);
        booking.routeMessage = booking.redirected
            ? originStation.name + " is full or has no compatible dock; routed to the nearest station with an open dock."
            : "Booked at your selected station.";
        booking.status = "WAITING_FOR_POWER";
        booking.createdAt = Instant.now().toString();

        bookings.put(booking.id, booking);
        selectedDock.status = "RESERVED";
        selectedDock.bookingId = booking.id;
        selectedDock.sessionLabel = booking.driverName;
        recalculate();
        return booking;
    }

    public synchronized Booking cancelBooking(String id) {
        Booking booking = bookingOr404(id);
        if (isActive(booking)) {
            booking.status = "CANCELLED";
            releaseDock(booking);
            recalculate();
        }
        return booking;
    }

    public synchronized Booking completeBooking(String id) {
        Booking booking = bookingOr404(id);
        if (isActive(booking)) {
            booking.status = "COMPLETED_BY_STATION";
            releaseDock(booking);
            recalculate();
        }
        return booking;
    }

    /** Advances all active demo charging sessions by five minutes. */
    public synchronized List<Booking> advanceFiveMinutes() {
        recalculate();
        for (Booking booking : bookings.values()) {
            if (!isActive(booking)) continue;
            double delivered = Math.min(booking.allocatedKw / 12.0, remainingKwh(booking));
            if (delivered > 0) {
                booking.energyDeliveredKwh += delivered;
                booking.batteryPercent = Math.min(booking.targetSoc,
                    booking.batteryPercent + delivered / booking.batteryCapacityKwh * 100.0);
                booking.costInr += delivered * booking.pricePerKwh;
            }
            booking.departureMinutes = Math.max(0, booking.departureMinutes - 5);
            if (remainingKwh(booking) <= 0.001) {
                booking.status = "COMPLETED";
                releaseDock(booking);
            } else if (booking.departureMinutes == 0) {
                booking.status = "DEPARTED_TARGET_MISSED";
                releaseDock(booking);
            }
        }
        recalculate();
        return new ArrayList<>(bookings.values());
    }

    private void seedDemoStations() {
        Station central = new Station();
        central.id = "central";
        central.name = "Central Charge Hub";
        central.area = "Demo district A";
        central.latitude = 12.0000;
        central.longitude = 77.0000;
        central.maxOutputKw = 60;
        central.pricePerKwh = 18;
        central.docks.add(dock("C-01", "CCS2", 60, "OCCUPIED", "External demo session"));
        central.docks.add(dock("C-02", "CCS2", 60, "OCCUPIED", "External demo session"));
        stations.put(central.id, central);

        Station east = new Station();
        east.id = "east";
        east.name = "East Market Station";
        east.area = "Demo district B";
        east.latitude = 12.0120;
        east.longitude = 77.0040;
        east.maxOutputKw = 90;
        east.pricePerKwh = 21;
        east.docks.add(dock("E-01", "CCS2", 60, "AVAILABLE", ""));
        east.docks.add(dock("E-02", "CCS2", 60, "AVAILABLE", ""));
        east.docks.add(dock("E-03", "Type 2", 22, "AVAILABLE", ""));
        stations.put(east.id, east);

        Station north = new Station();
        north.id = "north";
        north.name = "Northside Charge Point";
        north.area = "Demo district C";
        north.latitude = 12.0180;
        north.longitude = 76.9890;
        north.maxOutputKw = 50;
        north.pricePerKwh = 16;
        north.docks.add(dock("N-01", "CCS2", 30, "AVAILABLE", ""));
        north.docks.add(dock("N-02", "Type 2", 22, "AVAILABLE", ""));
        stations.put(north.id, north);

        Station virtual = new Station();
        virtual.id = "virtual";
        virtual.name = "ChargeSync Virtual Power Station";
        virtual.area = "Virtual Demo District";
        virtual.latitude = 12.0000;
        virtual.longitude = 77.0000;
        virtual.maxOutputKw = 60;
        virtual.pricePerKwh = 18;
        virtual.docks.add(dock("VP-01", "CCS2", 60, "AVAILABLE", ""));
        virtual.docks.add(dock("VP-02", "CCS2", 60, "AVAILABLE", ""));
        stations.put(virtual.id, virtual);
        recalculate();
    }

    private void applyStationConfig(Station station, StationRequest request, List<Dock> previousDocks) {
        station.name = clean(request.name, "New Charge Station");
        station.area = clean(request.area, "Area not set");
        station.latitude = request.latitude;
        station.longitude = request.longitude;
        station.maxOutputKw = request.maxOutputKw;
        station.pricePerKwh = request.pricePerKwh;
        station.hardwareLink = "VIRTUAL STATION · simulated dock status";

        Map<String, Dock> old = new HashMap<>();
        if (previousDocks != null) previousDocks.forEach(d -> old.put(d.id, d));
        List<DockRequest> requestedDocks = request.docks == null ? new ArrayList<>() : request.docks;
        if (requestedDocks.isEmpty() && previousDocks == null) {
            requestedDocks = new ArrayList<>();
            requestedDocks.add(new DockRequest("", "CCS2", Math.min(60, request.maxOutputKw), "AVAILABLE"));
            requestedDocks.add(new DockRequest("", "CCS2", Math.min(60, request.maxOutputKw), "AVAILABLE"));
        }
        if (requestedDocks.isEmpty()) throw badRequest("A station needs at least one dock.");

        Set<String> ids = new HashSet<>();
        List<Dock> next = new ArrayList<>();
        int number = 1;
        for (DockRequest input : requestedDocks) {
            String id = input.id == null || input.id.isBlank()
                ? "D-" + String.format(Locale.ROOT, "%02d", number++) : input.id.trim();
            if (!ids.add(id)) throw badRequest("Dock IDs must be unique within a station.");
            Dock existing = old.get(id);
            Dock dock = existing == null ? new Dock() : existing;
            dock.id = id;
            dock.connector = normalizeConnector(input.connector);
            dock.maxKw = finiteRange(input.maxKw, 1, 500, "Dock output must be between 1 and 500 kW.");
            if (existing == null) {
                dock.status = normalizeDockStatus(input.status, false);
                dock.bookingId = null;
            } else if (existing.bookingId == null) {
                dock.status = normalizeDockStatus(input.status, false);
            }
            next.add(dock);
        }
        for (Dock existing : old.values()) {
            if (existing.bookingId != null && !ids.contains(existing.id)) {
                throw conflict("Cannot remove dock " + existing.id + " while it has an active booking.");
            }
        }
        station.docks = next;
    }

    private void validateStation(StationRequest request) {
        if (request == null) throw badRequest("Station details are required.");
        request.maxOutputKw = finiteRange(request.maxOutputKw, 1, 1000, "Station output must be between 1 and 1000 kW.");
        request.pricePerKwh = finiteRange(request.pricePerKwh, 0, 10000, "Price must be between 0 and 10000 per kWh.");
        request.latitude = finiteRange(request.latitude, -90, 90, "Latitude must be between -90 and 90.");
        request.longitude = finiteRange(request.longitude, -180, 180, "Longitude must be between -180 and 180.");
    }

    private void validateDriver(DriverRequest r) {
        if (r == null) throw badRequest("Booking details are required.");
        if (r.departureMinutes < 5 || r.departureMinutes > 1440) throw badRequest("Departure must be 5 minutes to 24 hours away.");
        r.batteryPercent = finiteRange(r.batteryPercent, 0, 100, "Battery level must be 0–100%.");
        r.batteryCapacityKwh = finiteRange(r.batteryCapacityKwh, 5, 300, "Battery capacity must be 5–300 kWh.");
        r.targetSoc = finiteRange(r.targetSoc, 1, 100, "Charge target must be 1–100%.");
        if (r.targetSoc <= r.batteryPercent) throw badRequest("Charge target must be higher than the current battery level.");
        r.vehicleMaxKw = finiteRange(r.vehicleMaxKw, 1, 500, "Vehicle charging limit must be 1–500 kW.");
        r.estimatedTripKm = finiteRange(r.estimatedTripKm, 0, 5000, "Trip distance must be 0–5000 km.");
        r.rangeAt100Km = finiteRange(r.rangeAt100Km, 1, 2000, "Vehicle range must be 1–2000 km.");
        if (r.currentLatitude != null) finiteRange(r.currentLatitude, -90, 90, "Latitude must be between -90 and 90.");
        if (r.currentLongitude != null) finiteRange(r.currentLongitude, -180, 180, "Longitude must be between -180 and 180.");
        if ((r.currentLatitude == null) != (r.currentLongitude == null)) throw badRequest("Enter both current-location coordinates, or neither.");
        if (r.currentStationId == null && (r.currentLatitude == null || r.currentLongitude == null)) {
            throw badRequest("Select your current station or enter both current-location coordinates.");
        }
    }

    private void recalculate() {
        for (Booking booking : bookings.values()) {
            booking.allocatedKw = 0;
            if (isActive(booking)) updatePriority(booking);
        }

        for (Station station : stations.values()) {
            List<Booking> active = bookings.values().stream()
                .filter(b -> station.id.equals(b.assignedStationId) && isActive(b))
                .filter(b -> dockForBooking(station, b) != null)
                .toList();
            Map<Booking, Double> demand = new LinkedHashMap<>();
            Map<Booking, Double> weight = new LinkedHashMap<>();
            for (Booking booking : active) {
                Dock dock = dockForBooking(station, booking);
                double hoursLeft = Math.max(5.0 / 60.0, booking.departureMinutes / 60.0);
                double modeFactor = chargeModeFactor(booking.chargeMode);
                double modeCap = Math.min(Math.min(booking.vehicleMaxKw, dock.maxKw), station.maxOutputKw) * modeFactor;
                demand.put(booking, Math.min(modeCap, remainingKwh(booking) / hoursLeft));
                weight.put(booking, booking.priorityScore * modeFactor);
            }
            double remaining = station.maxOutputKw;
            Set<Booking> pool = new LinkedHashSet<>(active);
            while (remaining > 1e-7 && !pool.isEmpty()) {
                double totalWeight = pool.stream().mapToDouble(weight::get).sum();
                double distributed = 0;
                List<Booking> capped = new ArrayList<>();
                for (Booking booking : pool) {
                    double share = remaining * weight.get(booking) / Math.max(1e-9, totalWeight);
                    double room = demand.get(booking) - booking.allocatedKw;
                    double addition = Math.min(room, share);
                    booking.allocatedKw += addition;
                    distributed += addition;
                    if (room <= share + 1e-7) capped.add(booking);
                }
                remaining -= distributed;
                pool.removeAll(capped);
                if (distributed <= 1e-7) break;
            }
            active.stream().sorted(Comparator.comparingDouble((Booking b) -> b.priorityScore).reversed())
                .forEachOrdered(new java.util.function.Consumer<>() {
                    private int rank = 1;
                    @Override public void accept(Booking b) {
                        b.priorityRank = rank++;
                        b.estimatedMinutes = b.allocatedKw > 0 ? round1(remainingKwh(b) / b.allocatedKw * 60) : -1;
                        b.estimatedCostInr = round2(remainingKwh(b) * b.pricePerKwh);
                        b.canMakeTrip = b.estimatedMinutes >= 0 && b.estimatedMinutes <= b.departureMinutes;
                        Dock dock = dockForBooking(station, b);
                        if (dock != null) dock.status = b.allocatedKw > 0 ? "CHARGING" : "RESERVED";
                        b.status = b.allocatedKw > 0 ? "CHARGING" : "WAITING_FOR_POWER";
                    }
                });
            for (Dock dock : station.docks) {
                if (dock.bookingId == null && ("CHARGING".equals(dock.status) || "RESERVED".equals(dock.status))) {
                    dock.status = "AVAILABLE";
                }
            }
            station.allocatedKw = round2(active.stream().mapToDouble(b -> b.allocatedKw).sum());
            station.availablePowerKw = round2(Math.max(0, station.maxOutputKw - station.allocatedKw));
            station.availableDocks = (int) station.docks.stream().filter(d -> "AVAILABLE".equals(d.status)).count();
            station.activeBookings = active.size();
        }
    }

    private void updatePriority(Booking b) {
        double energy = remainingKwh(b);
        double hours = Math.max(5.0 / 60.0, b.departureMinutes / 60.0);
        double urgency = energy / hours / Math.max(1, b.vehicleMaxKw);
        b.priorityScore = (1 + Math.min(25, urgency)) * (b.batteryPercent < 20 ? 1.5 : 1);
        b.priority = urgency >= 1.5 || b.batteryPercent < 15 ? "HIGH" : "STANDARD";
    }

    private String normalizeChargeMode(String mode) {
        if (mode == null || mode.isBlank()) return "NORMAL";
        String normalized = mode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ECO", "NORMAL", "HYPER").contains(normalized)) {
            throw badRequest("Charging mode must be Eco, Normal, or Hyper.");
        }
        return normalized;
    }

    private double chargeModeFactor(String mode) {
        return switch (mode == null ? "NORMAL" : mode.toUpperCase(Locale.ROOT)) {
            case "ECO" -> 0.40;
            case "HYPER" -> 1.00;
            default -> 0.70; // midpoint between Eco and Hyper
        };
    }

    private void releaseDock(Booking booking) {
        Station station = stations.get(booking.assignedStationId);
        if (station == null) return;
        Dock dock = station.docks.stream().filter(d -> booking.id.equals(d.bookingId)).findFirst().orElse(null);
        if (dock != null) {
            dock.bookingId = null;
            dock.status = "AVAILABLE";
            dock.sessionLabel = "";
        }
    }

    private Dock availableDock(Station station, String connector) {
        return station.docks.stream()
            .filter(d -> "AVAILABLE".equals(d.status) && connector.equalsIgnoreCase(d.connector))
            .findFirst().orElse(null);
    }

    private Dock dockForBooking(Station station, Booking booking) {
        return station.docks.stream().filter(d -> booking.id.equals(d.bookingId)).findFirst().orElse(null);
    }

    private boolean isActive(Booking b) {
        return "CHARGING".equals(b.status) || "WAITING_FOR_POWER".equals(b.status);
    }

    private double remainingKwh(Booking b) {
        return Math.max(0, (b.targetSoc - b.batteryPercent) * b.batteryCapacityKwh / 100.0);
    }

    private Station stationOr404(String id) {
        Station station = stations.get(id);
        if (station == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found.");
        return station;
    }

    private Booking bookingOr404(String id) {
        Booking booking = bookings.get(id);
        if (booking == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found.");
        return booking;
    }

    private Dock dockOr404(Station station, String id) {
        return station.docks.stream().filter(d -> d.id.equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dock not found."));
    }

    private void validateStationDockFields(DockRequest dock) {
        dock.maxKw = finiteRange(dock.maxKw, 1, 500, "Dock output must be between 1 and 500 kW.");
        dock.connector = normalizeConnector(dock.connector);
    }

    private String normalizeDockStatus(String status, boolean allowCharging) {
        String value = status == null ? "AVAILABLE" : status.trim().toUpperCase(Locale.ROOT);
        if ("AVAILABLE".equals(value) || "OCCUPIED".equals(value) || "MAINTENANCE".equals(value)
            || (allowCharging && ("CHARGING".equals(value) || "RESERVED".equals(value)))) return value;
        throw badRequest("Dock status must be Available, Occupied, or Maintenance.");
    }

    private String normalizeConnector(String connector) {
        String value = clean(connector, "CCS2");
        if (!List.of("CCS2", "TYPE 2", "CHADEMO").contains(value.toUpperCase(Locale.ROOT))) {
            throw badRequest("Connector must be CCS2, Type 2, or CHAdeMO.");
        }
        return value.equalsIgnoreCase("type 2") ? "Type 2" : value.toUpperCase(Locale.ROOT);
    }

    private static Dock dock(String id, String connector, double maxKw, String status, String session) {
        Dock dock = new Dock();
        dock.id = id;
        dock.connector = connector;
        dock.maxKw = maxKw;
        dock.status = status;
        dock.sessionLabel = session;
        return dock;
    }

    private static String clean(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double finiteRange(double value, double min, double max, String message) {
        if (!Double.isFinite(value) || value < min || value > max) throw badRequest(message);
        return value;
    }

    private static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double round1(double n) { return Math.round(n * 10.0) / 10.0; }
    private static double round2(double n) { return Math.round(n * 100.0) / 100.0; }
    private static ResponseStatusException badRequest(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }

    public static class Dock {
        public String id;
        public String connector;
        public double maxKw;
        public String status;
        public String bookingId;
        public String sessionLabel = "";
        public Dock() { }
    }

    public static class Station {
        public String id;
        public String name;
        public String area;
        public double latitude;
        public double longitude;
        public double maxOutputKw;
        public double pricePerKwh;
        public List<Dock> docks = new ArrayList<>();
        public String hardwareLink = "VIRTUAL STATION · simulated dock status";
        public double allocatedKw;
        public double availablePowerKw;
        public int availableDocks;
        public int activeBookings;
        public Station() { }
    }

    public static class Booking {
        public String id;
        public String driverAccountId;
        public String driverName;
        public String destination;
        public String connector;
        public String chargeMode = "NORMAL";
        public String requestedStationId;
        public String assignedStationId;
        public String assignedStationName;
        public String assignedDockId;
        public String status;
        public String priority;
        public String routeMessage;
        public String createdAt;

        public boolean redirected;
        public boolean canMakeTrip;
        public double tripDistanceKm;
        public double rangeAt100Km;
        public double batteryPercent;
        public double batteryCapacityKwh;
        public double requestedTargetSoc;
        public double requiredTripSoc;
        public double targetSoc;
        public double vehicleMaxKw;
        public int departureMinutes;
        public int originalDepartureMinutes;
        public double pricePerKwh;
        public double distanceKm;
        public double allocatedKw;
        public double priorityScore;
        public int priorityRank;
        public double energyDeliveredKwh;
        public double costInr;
        public double estimatedMinutes;
        public double estimatedCostInr;
        public Booking() { }
    }

    public static class DriverRequest {
        public String driverName;
        public String destination;
        public String connector;
        public String chargeMode;
        public String currentStationId;
        public Double currentLatitude;
        public Double currentLongitude;
        public int departureMinutes;
        public double batteryPercent;
        public double batteryCapacityKwh;
        public double targetSoc;
        public double vehicleMaxKw;
        public double estimatedTripKm;
        public double rangeAt100Km;

        public DriverRequest() { }
    }

    public static class StationRequest {
        public String name;
        public String area;
        public double latitude;
        public double longitude;
        public double maxOutputKw;
        public double pricePerKwh;
        public List<DockRequest> docks = new ArrayList<>();
        public StationRequest() { }
    }

    public static class DockRequest {
        public String id;
        public String connector;
        public double maxKw;
        public String status;
        public DockRequest() { }
        public DockRequest(String id, String connector, double maxKw, String status) {
            this.id = id; this.connector = connector; this.maxKw = maxKw; this.status = status;
        }
    }
}
