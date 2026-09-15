package com.example.duoplus_probe.car;

import androidx.car.app.model.DateTimeWithZone;
import androidx.car.app.model.Distance;
import androidx.car.app.navigation.model.Destination;
import androidx.car.app.navigation.model.Maneuver;
import androidx.car.app.navigation.model.RoutingInfo;
import androidx.car.app.navigation.model.Step;
import androidx.car.app.navigation.model.TravelEstimate;
import androidx.car.app.navigation.model.Trip;

import com.example.duoplus_probe.sim.Scenario;

import java.util.Locale;
import java.util.TimeZone;

/** Host DTOs containing only the labeled fixture route, never a platform location. */
final class CarTrip {
    final Trip trip;
    final RoutingInfo routing;
    final TravelEstimate travel;

    CarTrip(RouteProgress progress, double scenarioMs) {
        Scenario scenario = progress.scenario;
        RouteProgress.Estimate estimate = progress.at(scenarioMs);
        Distance distance = distance(estimate.remainingMeters);
        // The ETA belongs to the fixture's synthetic UTC timeline, not the wall clock.
        travel = new TravelEstimate.Builder(distance, DateTimeWithZone.create(
                scenario.utcOriginMs + scenario.durationMs, TimeZone.getTimeZone("UTC")))
                .setRemainingTimeSeconds(estimate.remainingSeconds).build();
        Scenario.Knot end = scenario.trajectory.get(scenario.trajectory.size() - 1);
        Destination destination = new Destination.Builder()
                .setName("SIMULATION · " + scenario.name)
                .setAddress(String.format(Locale.US, "Fixture endpoint %.5f, %.5f", end.lat, end.lon)).build();
        Step endpoint = new Step.Builder("SIMULATION · Route endpoint")
                .setManeuver(new Maneuver.Builder(Maneuver.TYPE_DESTINATION).build()).build();
        routing = new RoutingInfo.Builder().setCurrentStep(endpoint, distance).build();
        trip = new Trip.Builder().addDestination(destination, travel).addStep(endpoint, travel)
                .setCurrentRoad("SIMULATION · Fixture route").build();
    }

    private static Distance distance(double meters) {
        return meters < 1000 ? Distance.create(Math.round(meters), Distance.UNIT_METERS)
                : Distance.create(Math.round(meters / 100d) / 10d, Distance.UNIT_KILOMETERS_P1);
    }
}
