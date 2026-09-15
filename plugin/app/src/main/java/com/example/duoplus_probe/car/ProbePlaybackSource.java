package com.example.duoplus_probe.car;

import android.content.Context;

import com.example.duoplus_probe.console.DriveSession;

final class ProbePlaybackSource implements CarPlaybackSource {
    private final DriveSession session;

    ProbePlaybackSource(Context context) { session = DriveSession.get(context); }
    @Override public void acquire() { session.acquire(); }
    @Override public void release() { session.release(); }
    @Override public Frame read() {
        DriveSession.Frame frame = session.read();
        return new Frame(frame.scenario, frame.pose, frame.state, frame.scenarioMs, frame.message,frame.valid);
    }
    @Override public void start() { session.start(); }
    @Override public void pause() { session.pause(); }
    @Override public void resume() { session.resume(); }
    @Override public void stop() { session.stop(); }
    @Override public void loadDemo() { session.loadDemo(); }
}
