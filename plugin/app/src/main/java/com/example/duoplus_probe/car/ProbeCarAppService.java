package com.example.duoplus_probe.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;

import com.example.duoplus_probe.BuildConfig;

/** Android Auto projection of the explicitly simulated probe route. */
public final class ProbeCarAppService extends CarAppService {
    @NonNull @Override public HostValidator createHostValidator() {
        if (BuildConfig.DEBUG) return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
        return new HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build();
    }

    @NonNull @Override public Session onCreateSession() { return new ProbeCarSession(); }
}
