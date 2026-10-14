package com.hackathon.chargesync;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class SimulationService {
    private SimulationState state = new SimulationState();

    public SimulationService() { reset("crowded", 15, false); }

    public synchronized SimulationState state() { return state; }

    public synchronized SimulationState reset(String scenario, double limit, boolean cheapTariff) {
        state = new SimulationState();
        state.scenario = scenario;
        state.stationLimitKw = Math.max(1, Math.min(100, limit));
        state.tariffInrPerKwh = cheapTariff ? 5 : 8;
        state.vehicles = new ArrayList<>();
        state.vehicles.add(new Vehicle("EV-01",60,25,80,11,90));
        state.vehicles.add(new Vehicle("EV-02",50,50,85,7,60));
        state.vehicles.add(new Vehicle("EV-03",75,65,90,11,180));
        state.vehicles.add(new Vehicle("EV-04",40,35,75,7,120));
        if ("crowded".equals(scenario) || "urgent".equals(scenario)) {
            state.vehicles.add(new Vehicle("EV-05",60,15,80,11,"urgent".equals(scenario)?25:45));
            state.vehicles.add(new Vehicle("EV-06",45,70,95,7,150));
        }
        if ("urgent".equals(scenario)) {
            state.vehicles.get(0).departureMinute = 25;
            state.vehicles.get(1).departureMinute = 35;
            state.tariffInrPerKwh = 5;
        }
        snapshot();
        return state;
    }

    public synchronized SimulationState setLimit(double limit) {
        state.stationLimitKw = Math.max(1, Math.min(100, limit));
        allocateOnly();
        snapshot();
        return state;
    }

    public synchronized SimulationState setTariff(double tariff) {
        state.tariffInrPerKwh = Math.max(0, tariff);
        return state;
    }

    public synchronized SimulationState addVehicle(VehicleRequest request) {
        if (state.vehicles.size() >= 12) throw badRequest("A simulation can include up to 12 vehicles.");
        String id = clean(request == null ? null : request.id, "EV-" + String.format(Locale.ROOT, "%02d", state.vehicles.size() + 1));
        if (state.vehicles.stream().anyMatch(v -> v.id.equalsIgnoreCase(id))) throw badRequest("That vehicle ID is already in use.");
        Vehicle vehicle = new Vehicle(id, 60, 20, 80, 50, state.simulationMinute + 90);
        applyVehicleInput(vehicle, request);
        state.vehicles.add(vehicle);
        allocateOnly();
        snapshot();
        return state;
    }

    public synchronized SimulationState updateVehicle(String id, VehicleRequest request) {
        Vehicle vehicle = state.vehicles.stream().filter(v -> v.id.equalsIgnoreCase(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found."));
        applyVehicleInput(vehicle, request);
        allocateOnly();
        snapshot();
        return state;
    }

    public synchronized SimulationState removeVehicle(String id) {
        boolean removed = state.vehicles.removeIf(v -> v.id.equalsIgnoreCase(id));
        if (!removed) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vehicle not found.");
        allocateOnly();
        snapshot();
        return state;
    }

    private void applyVehicleInput(Vehicle vehicle, VehicleRequest request) {
        if (request == null) throw badRequest("Vehicle details are required.");
        String destination = clean(request.destination, "City center");
        String connector = request.connector == null ? "CCS2" : request.connector.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("CCS2", "TYPE 2", "CHADEMO").contains(connector)) throw badRequest("Choose CCS2, Type 2, or CHAdeMO.");
        String mode = request.chargeMode == null ? "NORMAL" : request.chargeMode.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("ECO", "NORMAL", "HYPER").contains(mode)) throw badRequest("Choose Eco, Normal, or Hyper charging.");
        double capacity = range(request.batteryCapacityKwh, 5, 300, "Battery capacity must be 5–300 kWh.");
        double soc = range(request.soc, 0, 99.9, "Battery level must be 0–99.9%.");
        double target = range(request.targetSoc, 1, 100, "Charge target must be 1–100%.");
        if (target <= soc) throw badRequest("Charge target must be higher than the current battery level.");
        double maxKw = range(request.maxKw, 1, 500, "Vehicle charging limit must be 1–500 kW.");
        int departure = request.departureMinute == null ? state.simulationMinute + 90 : request.departureMinute;
        if (departure < state.simulationMinute + 5 || departure > state.simulationMinute + 1440) {
            throw badRequest("Departure must be 5 minutes to 24 hours from now.");
        }
        vehicle.destination = destination;
        vehicle.connector = connector;
        vehicle.chargeMode = mode;
        vehicle.emergency = request.emergency;
        vehicle.batteryCapacityKwh = capacity;
        vehicle.soc = soc;
        vehicle.targetSoc = target;
        vehicle.maxKw = maxKw;
        vehicle.departureMinute = departure;
        vehicle.status = "WAITING";
    }

    public synchronized SimulationState start() { state.running = true; return state; }
    public synchronized SimulationState pause() { state.running = false; return state; }

    public synchronized SimulationState tick() {
        final double dtHours = 5.0 / 60.0;
        allocateOnly();
        double sum = state.totalAllocatedKw();
        if (sum > state.stationLimitKw + 1e-6) state.overloadEvents++;
        for (Vehicle v : state.vehicles) {
            double energy = v.allocatedKw * dtHours;
            double need = v.energyNeededKwh();
            energy = Math.min(energy, need);
            if (energy > 0) {
                v.soc = Math.min(v.targetSoc, v.soc + energy / v.batteryCapacityKwh * 100.0);
                v.energyDeliveredKwh += energy;
                v.costInr += energy * state.tariffInrPerKwh;
                v.status = "CHARGING";
            } else if (v.soc >= v.targetSoc - 0.001) {
                v.status = "TARGET REACHED";
            } else if (state.simulationMinute >= v.departureMinute) {
                v.status = "DEPARTED — TARGET MISSED";
            } else {
                v.status = "WAITING";
            }
        }
        state.simulationMinute += 5;
        // Recalculate displayed allocations for the new state.
        allocateOnly();
        snapshot();
        return state;
    }

    private void allocateOnly() {
        state.vehicles.forEach(v -> v.allocatedKw = 0);
        List<Vehicle> candidates = state.vehicles.stream()
            .filter(v -> v.energyNeededKwh() > 0.001 && state.simulationMinute < v.departureMinute)
            .toList();
        Map<Vehicle, Double> demand = new LinkedHashMap<>();
        Map<Vehicle, Double> weights = new LinkedHashMap<>();
        for (Vehicle v : candidates) {
            double hoursLeft = Math.max(1.0 / 60.0, (v.departureMinute - state.simulationMinute) / 60.0);
            double modeFactor = chargeModeFactor(v.chargeMode);
            demand.put(v, Math.min(v.maxKw * modeFactor, v.energyNeededKwh() / hoursLeft));
            weights.put(v, Math.max(0.1, urgency(v)) * modeFactor * (v.emergency ? 4.0 : 1.0));
        }

        // Weighted progressive filling shares a constrained station while giving
        // higher-urgency vehicles a larger portion. Capped vehicles leave the pool.
        double remaining = state.stationLimitKw;
        Set<Vehicle> active = new LinkedHashSet<>(candidates);
        while (remaining > 1e-6 && !active.isEmpty()) {
            double totalWeight = active.stream().mapToDouble(weights::get).sum();
            double distributed = 0;
            List<Vehicle> capped = new ArrayList<>();
            for (Vehicle v : active) {
                double share = remaining * weights.get(v) / totalWeight;
                double room = demand.get(v) - v.allocatedKw;
                double addition = Math.min(room, share);
                v.allocatedKw += addition;
                distributed += addition;
                if (room <= share + 1e-6) capped.add(v);
            }
            remaining -= distributed;
            active.removeAll(capped);
            if (distributed <= 1e-6) break;
        }
    }

    private double urgency(Vehicle v) {
        double hoursLeft = Math.max(1.0/60.0, (v.departureMinute - state.simulationMinute) / 60.0);
        return v.energyNeededKwh() / hoursLeft / Math.max(0.1, v.maxKw);
    }

    private double chargeModeFactor(String mode) {
        return switch (mode == null ? "NORMAL" : mode.toUpperCase(Locale.ROOT)) {
            case "ECO" -> 0.40;
            case "HYPER" -> 1.00;
            default -> 0.70;
        };
    }

    private double range(Double value, double min, double max, String message) {
        if (value == null || !Double.isFinite(value) || value < min || value > max) throw badRequest(message);
        return value;
    }

    private String clean(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text.substring(0, Math.min(80, text.length()));
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void snapshot() {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("minute", state.simulationMinute);
        row.put("loadKw", Math.round(state.totalAllocatedKw()*100.0)/100.0);
        row.put("energyKwh", Math.round(state.totalEnergyKwh()*100.0)/100.0);
        row.put("costInr", Math.round(state.totalCostInr()*100.0)/100.0);
        state.history.add(row);
        if (state.history.size() > 100) state.history.remove(0);
    }
}
