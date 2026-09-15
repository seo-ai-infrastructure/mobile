package com.example.duoplus_probe;

import android.app.Activity;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.URL;
import java.util.Locale;

/** A visible test target: no GPS permission, sensor provider or third-party account involved. */
public final class HookDiagnosticsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, values, traceStatus;
    private long previousReceipt;
    private double lastGapMs;
    private final Runnable tick = new Runnable() {
        @Override public void run() { showLocation(); handler.postDelayed(this, 250); }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(16, 22, 32));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        body.setPadding(pad, pad * 2, pad, pad);
        scroll.addView(body);
        body.addView(text("DUOPLUS / DIAGNOSTICS", 12, Color.rgb(98, 215, 194)));
        TextView title = text("Hook probe", 32, Color.WHITE);
        title.setPadding(0, dp(12), 0, dp(8));
        body.addView(title);
        body.addView(text("SIMULATION TEST · This app displays synthetic samples only.", 16, Color.rgb(205, 215, 226)));
        status = text("Awaiting test stream", 22, Color.WHITE);
        status.setPadding(0, dp(32), 0, dp(16));
        body.addView(status);
        values = text("", 17, Color.rgb(205, 215, 226));
        values.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.addView(values);
        Button button = new Button(this);
        button.setText("Generate URL hook event");
        LinearLayout.LayoutParams buttonLayout = new LinearLayout.LayoutParams(-1, -2);
        buttonLayout.topMargin = dp(32);
        body.addView(button, buttonLayout);
        traceStatus = text("Creates a URLConnection to test interception. No HTTP request is sent.", 14, Color.rgb(170, 187, 204));
        traceStatus.setPadding(0, dp(12), 0, 0);
        body.addView(traceStatus);
        button.setOnClickListener(view -> new Thread(() -> {
            String result;
            try {
                new URL("https://example.com/diagnostics?case=probe").openConnection();
                result = "URL API called. Read the module events to confirm interception.";
            } catch (Exception error) { result = "URL API failed: " + error.getClass().getSimpleName(); }
            final String message = result;
            runOnUiThread(() -> { if (!isDestroyed()) traceStatus.setText(message); });
        }, "probe-url").start());
        setContentView(scroll);
    }

    @Override protected void onResume() { super.onResume(); handler.post(tick); }
    @Override protected void onPause() { handler.removeCallbacks(tick); super.onPause(); }

    private void showLocation() {
        // Each test read uses a new Location to demonstrate coherent per-object snapshots.
        Location location = new Location("probe-original");
        String provider = location.getProvider();
        boolean active = "duoplus-test".equals(provider);
        status.setText(active ? "Hook observed · simulated" : "Awaiting test stream");
        status.setTextColor(active ? Color.rgb(98, 215, 194) : Color.WHITE);
        if (!active) {
            previousReceipt = 0;
            values.setText("No fresh sample observed.\n\nLoad the module, restart this app,\nand start the probe stream.\n\nExpired samples clear after 3.5 s.");
            return;
        }
        long receipt = location.getElapsedRealtimeNanos();
        if (previousReceipt != 0 && receipt != previousReceipt) lastGapMs = (receipt - previousReceipt) / 1_000_000d;
        previousReceipt = receipt;
        values.setText(String.format(Locale.US,
                "Latitude   %.7f\nLongitude  %.7f\nSpeed      %.2f m/s\nBearing    %.1f°\nAccuracy   %.1f m\nAltitude   %s\nMock       %s\nSample age %.0f ms\nArrival Δ  %.1f ms",
                location.getLatitude(), location.getLongitude(), location.getSpeed(), location.getBearing(),
                location.getAccuracy(), location.hasAltitude() ? String.format(Locale.US, "%.1f m", location.getAltitude()) : "absent",
                location.isFromMockProvider(), (SystemClock.elapsedRealtimeNanos() - receipt) / 1_000_000d, lastGapMs));
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(dp(4), 1);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
