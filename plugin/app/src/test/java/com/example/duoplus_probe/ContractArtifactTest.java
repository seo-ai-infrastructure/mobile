package com.example.duoplus_probe;

import com.example.duoplus_probe.sim.PlaybackEngine;
import com.example.duoplus_probe.sim.Scenario;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Emits the real Java export for independent validation against the public schema. */
public final class ContractArtifactTest {
    @Test public void exportScenarioReplayForSchemaValidation() throws Exception {
        Path demo = Paths.get("src/main/assets/demo-scenario.json");
        Scenario scenario = Scenario.parse(new JSONObject(new String(Files.readAllBytes(demo), StandardCharsets.UTF_8)));
        PlaybackEngine engine = new PlaybackEngine(scenario);
        long boot = 80_000_000_000L;
        engine.start(boot);
        engine.tick(boot);
        engine.tick(boot + 1_000_000_000L);
        engine.pause(boot + 1_500_000_000L);
        engine.resume(boot + 4_000_000_000L);
        engine.tick(boot + 4_000_000_000L);
        engine.tick(boot + 6_000_000_000L);
        engine.stop();
        Path output = Paths.get("build/reports/contract-report.json");
        Files.createDirectories(output.getParent());
        Files.write(output, new JSONObject(engine.report(boot + 6_000_000_000L)).toString(2).getBytes(StandardCharsets.UTF_8));
    }
}
