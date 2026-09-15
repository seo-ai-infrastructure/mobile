package com.example.duoplus_probe.car;

import android.content.Intent;
import android.content.res.Configuration;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

final class ProbeCarSession extends Session {
    private CarNavigationCoordinator navigation;
    private DriveScreen screen;

    ProbeCarSession() {
        getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onDestroy(@NonNull LifecycleOwner owner) {
                if (navigation != null) navigation.close();
            }
        });
    }

    @NonNull @Override public Screen onCreateScreen(@NonNull Intent intent) {
        navigation = new CarNavigationCoordinator(getCarContext(),
                new ProbePlaybackSource(getCarContext()));
        screen = new DriveScreen(getCarContext(), navigation);
        return screen;
    }

    @Override public void onCarConfigurationChanged(@NonNull Configuration configuration) {
        if (screen != null) screen.configurationChanged();
    }
}
