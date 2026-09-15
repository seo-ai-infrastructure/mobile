package com.example.duoplus_probe.car;

import com.example.duoplus_probe.sim.Scenario;
import com.example.duoplus_probe.sim.SimMath;

/** Small main-thread adapter boundary; car tests never need to start a real playback service. */
interface CarPlaybackSource {
    void acquire();
    void release();
    Frame read();
    void start();
    void pause();
    void resume();
    void stop();
    void loadDemo();

    final class Frame {
        final Scenario scenario;
        final SimMath.Pose pose;
        final String state, message;
        final double scenarioMs;
        final boolean valid;

        Frame(Scenario scenario, SimMath.Pose pose, String state, double scenarioMs, String message) {
            this(scenario,pose,state,scenarioMs,message,true);
        }
        Frame(Scenario scenario, SimMath.Pose pose, String state, double scenarioMs, String message,boolean valid) {
            this.scenario = scenario;
            this.pose = pose;
            this.state = state == null ? "IDLE" : state;
            this.scenarioMs = scenarioMs;
            this.message = message == null ? "" : message;
            this.valid=valid;
        }
    }
}
