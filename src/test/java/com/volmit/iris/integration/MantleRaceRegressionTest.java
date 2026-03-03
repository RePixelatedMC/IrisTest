package com.volmit.iris.integration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("mantle-race")
class MantleRaceRegressionTest {
    @Test
    void mantleDataMustMatchTheGeneratedWorld() throws IOException {
        Path reportPath = Path.of(System.getProperty("iris.test.mantleRace.reportFile"));
        assertTrue(Files.exists(reportPath), "Mantle race report was not generated: " + reportPath);

        Properties report = new Properties();
        try (InputStream inputStream = Files.newInputStream(reportPath)) {
            report.load(inputStream);
        }

        String status = report.getProperty("status", "MISSING");
        assertEquals("PASS", status, failureMessage(reportPath, report));
    }

    private String failureMessage(Path reportPath, Properties report) {
        List<String> lines = new ArrayList<>();
        lines.add("Mantle race regression failed.");
        lines.add("Report: " + reportPath);
        lines.add("Status: " + report.getProperty("status", "MISSING"));
        lines.add("Reason: " + report.getProperty("statusReason", "unknown"));
        lines.add("Phase: " + report.getProperty("phase", "unknown"));
        lines.add("Dimension: " + report.getProperty("dimension", "unknown"));
        lines.add("Radius: " + report.getProperty("radius", "unknown"));
        lines.add("Chunks scanned: " + report.getProperty("chunksScanned", "0") + "/" + report.getProperty("totalChunks", "0"));
        lines.add("Minimum chunks required: " + report.getProperty("minChunksRequired", "0"));
        lines.add("Ungenerated chunks: " + report.getProperty("ungeneratedChunks", "0"));
        lines.add("Raw mantle blocks scanned: " + report.getProperty("mantleBlocks", "0"));
        lines.add("Raw mismatched blocks: " + report.getProperty("mismatchedBlocks", "0"));
        lines.add("Raw missing blocks: " + report.getProperty("missingBlocks", "0"));
        lines.add("Raw non-air mismatches: " + report.getProperty("mismatchedNonAirBlocks", "0"));
        lines.add("Stable mantle blocks scanned: " + report.getProperty("stableMantleBlocks", "0"));
        lines.add("Stable mismatched blocks: " + report.getProperty("stableMismatchedBlocks", "0"));
        lines.add("Stable missing blocks: " + report.getProperty("missingStableBlocks", "0"));
        lines.add("Stable non-air mismatches: " + report.getProperty("stableNonAirMismatches", "0"));
        lines.add("Stable missing rate: " + report.getProperty("missingStableRatePercent", "0") + "% (max " + report.getProperty("maxMissingStableRatePercent", "0") + "%)");
        lines.add("Stable mismatch rate: " + report.getProperty("stableMismatchRatePercent", "0") + "% (max " + report.getProperty("maxStableMismatchRatePercent", "0") + "%)");
        lines.add("Ignored transient mismatches: " + report.getProperty("ignoredTransientBlocks", "0"));

        String error = report.getProperty("error");
        if (error != null && !error.isBlank()) {
            lines.add("Error:");
            lines.add(error);
        }

        for (int i = 0; ; i++) {
            String sample = report.getProperty("stableSample." + i);
            if (sample == null) {
                break;
            }
            lines.add("Stable sample " + i + ": " + sample);
        }

        for (int i = 0; ; i++) {
            String sample = report.getProperty("rawSample." + i);
            if (sample == null) {
                break;
            }
            lines.add("Raw sample " + i + ": " + sample);
        }

        return String.join(System.lineSeparator(), lines);
    }
}
