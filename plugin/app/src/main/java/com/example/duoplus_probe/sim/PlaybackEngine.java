package com.example.duoplus_probe.sim;

/** Compatibility name for existing v1 callers; the dispatcher owns the single timeline. */
public final class PlaybackEngine extends PlaybackDispatcher {
    public PlaybackEngine(Scenario scenario) { super(scenario); }
}
