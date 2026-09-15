package com.example.duoplus_probe.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MessageInfo;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

/** The car host draws the controls; our renderer draws only the map surface. */
final class DriveScreen extends Screen {
    private final CarNavigationCoordinator navigation;
    private final CarSurfaceRenderer surface;
    private boolean visible;

    DriveScreen(CarContext context, CarNavigationCoordinator navigation) {
        super(context);
        this.navigation = navigation;
        surface = new CarSurfaceRenderer(context, navigation::mapFrame);
        navigation.setListener(() -> {
            if (visible) invalidate();
        });
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onCreate(@NonNull LifecycleOwner owner) { surface.attach(); }
            @Override public void onStart(@NonNull LifecycleOwner owner) {
                visible = true;
                surface.setVisible(true);
                navigation.refresh();
            }
            @Override public void onStop(@NonNull LifecycleOwner owner) {
                visible = false;
                surface.setVisible(false);
            }
            @Override public void onDestroy(@NonNull LifecycleOwner owner) {
                navigation.setListener(null);
                surface.close();
            }
        });
    }

    @NonNull @Override public Template onGetTemplate() {
        CarPlaybackSource.Frame frame = navigation.frame();
        String primary = frame.scenario == null ? "Load demo" : "RUNNING".equals(frame.state)
                ? "Pause" : "PAUSED".equals(frame.state) ? "Resume" : "Start";
        ActionStrip actions = new ActionStrip.Builder()
                .addAction(new Action.Builder().setTitle(primary).setOnClickListener(navigation::primaryAction).build())
                .addAction(new Action.Builder().setTitle("Stop").setOnClickListener(navigation::stop).build())
                .addAction(new Action.Builder().setTitle("Dialer").setOnClickListener(navigation::openDialer).build())
                .build();
        NavigationTemplate.Builder template = new NavigationTemplate.Builder().setActionStrip(actions);
        CarTrip trip = navigation.trip();
        if (trip != null && "RUNNING".equals(frame.state)) {
            template.setNavigationInfo(trip.routing).setDestinationTravelEstimate(trip.travel);
        } else {
            String detail = !navigation.hostMessage().isEmpty() ? navigation.hostMessage()
                    : !frame.message.isEmpty() && !"null".equals(frame.message) ? frame.message
                    : frame.scenario == null ? "Load a probe scenario to preview its route"
                    : frame.scenario.name + " · " + frame.state;
            template.setNavigationInfo(new MessageInfo.Builder("SIMULATION · Probe route")
                    .setText(detail).build());
        }
        return template.build();
    }

    void configurationChanged() {
        surface.requestDraw();
        if (visible) invalidate();
    }
}
