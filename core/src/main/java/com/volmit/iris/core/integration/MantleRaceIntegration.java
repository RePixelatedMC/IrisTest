package com.volmit.iris.core.integration;

import com.volmit.iris.Iris;
import com.volmit.iris.core.IrisSettings;
import com.volmit.iris.core.pregenerator.PregenTask;
import com.volmit.iris.core.tools.IrisToolbelt;
import com.volmit.iris.engine.framework.Engine;
import com.volmit.iris.util.data.B;
import com.volmit.iris.util.format.Form;
import com.volmit.iris.util.io.IO;
import com.volmit.iris.util.mantle.Mantle;
import com.volmit.iris.util.mantle.MantleChunk;
import com.volmit.iris.util.scheduling.J;
import io.github.ensgijs.nbt.mca.McaRegionFile;
import io.github.ensgijs.nbt.mca.TerrainChunk;
import io.github.ensgijs.nbt.mca.io.LoadFlags;
import io.github.ensgijs.nbt.mca.io.McaFileHelpers;
import io.github.ensgijs.nbt.tag.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class MantleRaceIntegration {
    private static final String PREFIX = "iris.integration.mantleRace.";
    private static final int DEFAULT_RADIUS = 5000;
    private static final int DEFAULT_MIN_CHUNKS = 15_000;
    private static final double DEFAULT_MAX_MISSING_STABLE_RATE = 0.0002D;
    private static final double DEFAULT_MAX_STABLE_MISMATCH_RATE = 0.0005D;
    private static final int DEFAULT_SCAN_THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final long REGION_LOAD_FLAGS = LoadFlags.BLOCK_STATES | LoadFlags.RELEASE_CHUNK_DATA_TAG;
    private static final int PROGRESS_INTERVAL = 512;
    private static final int SAMPLE_LIMIT = 25;
    private static final DateTimeFormatter RUN_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    private final String dimension = System.getProperty(PREFIX + "dimension", IrisSettings.get().getGenerator().getDefaultWorldType());
    private final String worldName = System.getProperty(PREFIX + "worldName", "mantle-race-test");
    private final File resultFile = new File(System.getProperty(PREFIX + "resultFile",
            new File(Iris.instance.getDataFolder(), "mantle-race-report.properties").getAbsolutePath()));
    private final int radius = Integer.getInteger(PREFIX + "radius", DEFAULT_RADIUS);
    private final int minChunks = Integer.getInteger(PREFIX + "minChunks", DEFAULT_MIN_CHUNKS);
    private final double maxMissingStableRate = doubleProperty(PREFIX + "maxMissingStableRate", DEFAULT_MAX_MISSING_STABLE_RATE);
    private final double maxStableMismatchRate = doubleProperty(PREFIX + "maxStableMismatchRate", DEFAULT_MAX_STABLE_MISMATCH_RATE);
    private final int scanThreads = Integer.getInteger(PREFIX + "scanThreads", DEFAULT_SCAN_THREADS);
    private final BlockData airBlockData = Bukkit.createBlockData(Material.AIR);
    private final Map<String, BlockData> blockDataCache = new ConcurrentHashMap<>();
    private final Map<Material, Boolean> stablePlacementCache = new ConcurrentHashMap<>();

    public static void tryStart() {
        if (!Boolean.getBoolean(PREFIX + "enabled") || !STARTED.compareAndSet(false, true)) {
            return;
        }

        Thread.ofVirtual()
                .name("iris-mantle-race-integration")
                .start(() -> new MantleRaceIntegration().runSafely());
    }

    private void runSafely() {
        Report report = new Report(dimension, worldName, radius);

        try {
            Iris.info("Mantle race integration: preparing world \"%s\" with dimension \"%s\" and radius %d", worldName, dimension, radius);
            World world = prepareWorld();
            report.phase = "scan";
            scanWorld(world, report);
            evaluateReport(report);
        } catch (Throwable e) {
            report.status = "ERROR";
            report.statusReason = "Unexpected exception during integration run";
            report.error = stackTraceOf(e);
            Iris.reportError(e);
            e.printStackTrace();
        } finally {
            report.finishedAt = System.currentTimeMillis();
            writeReport(report);
            J.s(Bukkit::shutdown);
        }
    }

    private World prepareWorld() throws Exception {
        IO.delete(new File(Bukkit.getWorldContainer(), worldName));

        return IrisToolbelt.createWorld()
                .dimension(dimension)
                .name(worldName)
                .seed(1337L)
                .benchmark(true)
                .pregen(PregenTask.builder()
                        .radiusX(radius)
                        .radiusZ(radius)
                        .build())
                .create();
    }

    private void scanWorld(World world, Report report) throws Exception {
        Engine engine = IrisToolbelt.access(world).getEngine();
        Mantle mantle = engine.getMantle().getMantle();
        int minHeight = world.getMinHeight();
        int maxHeight = world.getMaxHeight();
        File worldFolder = world.getWorldFolder();
        PregenTask scanTask = PregenTask.builder()
                .radiusX(radius)
                .radiusZ(radius)
                .build();

        Map<RegionPos, List<ChunkPos>> chunksByRegion = new LinkedHashMap<>();
        scanTask.iterateAllChunks((chunkX, chunkZ) -> {
            report.totalChunks++;
            RegionPos regionPos = new RegionPos(chunkX >> 5, chunkZ >> 5);
            chunksByRegion.computeIfAbsent(regionPos, ignored -> new ArrayList<>())
                    .add(new ChunkPos(chunkX, chunkZ));
        });

        flushAndUnloadWorld(world, engine, report);

        File regionDirectory = new File(worldFolder, "region");
        if (!regionDirectory.isDirectory()) {
            throw new IllegalStateException("World region directory is missing: " + regionDirectory.getAbsolutePath());
        }

        report.phase = "scan";
        report.scanBackend = "region-files";
        List<Map.Entry<RegionPos, List<ChunkPos>>> regionEntries = new ArrayList<>(chunksByRegion.entrySet());
        int workerCount = Math.max(1, Math.min(scanThreads, regionEntries.size()));
        Iris.info("Mantle race integration: scanning %s chunks across %s regions with %s worker threads",
                Form.f(report.totalChunks),
                Form.f(regionEntries.size()),
                Form.f(workerCount));

        ExecutorService executor = Executors.newFixedThreadPool(workerCount, Thread.ofVirtual().name("iris-mantle-race-scan-", 0).factory());
        ExecutorCompletionService<RegionStats> completionService = new ExecutorCompletionService<>(executor);

        try {
            for (Map.Entry<RegionPos, List<ChunkPos>> entry : regionEntries) {
                completionService.submit(() -> scanRegion(mantle, minHeight, maxHeight, regionDirectory, entry.getKey(), entry.getValue()));
            }

            for (int i = 0; i < regionEntries.size(); i++) {
                Future<RegionStats> future = completionService.take();
                mergeStats(report, future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Mantle race integration scan was interrupted", e);
        } catch (ExecutionException e) {
            throw unwrapExecutionException(e);
        } finally {
            executor.shutdownNow();
        }
    }

    private void flushAndUnloadWorld(World world, Engine engine, Report report) {
        report.phase = "flush";
        report.scanBackend = "world-save";
        Iris.info("Mantle race integration: saving and unloading world \"%s\" before region scan", world.getName());

        boolean unloaded = J.sfut(() -> {
            engine.save();
            world.save();
            return Bukkit.unloadWorld(world, true);
        }).join();

        if (!unloaded) {
            throw new IllegalStateException("Failed to unload world before region scan: " + world.getName());
        }
    }

    private RegionStats scanRegion(Mantle mantle, int minHeight, int maxHeight, File regionDirectory, RegionPos regionPos, List<ChunkPos> chunks) throws IOException {
        File regionFile = new File(regionDirectory, McaFileHelpers.createNameFromRegionLocation(regionPos.x, regionPos.z));
        McaRegionFile region = regionFile.isFile() ? McaFileHelpers.readAuto(regionFile, REGION_LOAD_FLAGS) : null;
        RegionStats stats = new RegionStats();

        for (ChunkPos chunk : chunks) {
            try {
                scanChunk(mantle, minHeight, maxHeight, chunk.x, chunk.z, region, stats);
            } catch (Exception e) {
                throw new RuntimeException("Failed while scanning chunk " + chunk.x + "," + chunk.z, e);
            }
        }

        return stats;
    }

    private void scanChunk(Mantle mantle, int minHeight, int maxHeight, int chunkX, int chunkZ, McaRegionFile region, RegionStats stats) {
        stats.chunksScanned++;

        TerrainChunk terrainChunk = region == null ? null : region.getChunk(chunkX, chunkZ);
        if (terrainChunk == null) {
            stats.ungeneratedChunks++;
            return;
        }

        MantleChunk mantleChunk = mantle.getChunk(chunkX, chunkZ).use();
        try {
            mantleChunk.iterate(BlockData.class, (x, y, z, expected) -> {
                if (expected == null || expected.getMaterial().isAir()) {
                    return;
                }

                int worldY = y + minHeight;
                if (worldY < minHeight || worldY >= maxHeight) {
                    return;
                }

                stats.mantleBlocks++;

                boolean stablePlacement = isStablePlacement(expected);
                if (stablePlacement) {
                    stats.stableMantleBlocks++;
                }

                BlockData actual = readRegionBlockData(terrainChunk, x, worldY, z);
                if (actual.matches(expected)) {
                    return;
                }

                stats.mismatchedBlocks++;
                if (actual.getMaterial().isAir()) {
                    stats.missingBlocks++;
                } else {
                    stats.mismatchedNonAirBlocks++;
                }

                if (stats.rawSamples.size() < SAMPLE_LIMIT) {
                    stats.rawSamples.add(sample(chunkX, chunkZ, x, worldY, z, expected, actual));
                }

                if (!stablePlacement) {
                    stats.ignoredTransientBlocks++;
                    return;
                }

                stats.stableMismatchedBlocks++;
                if (actual.getMaterial().isAir()) {
                    stats.missingStableBlocks++;
                } else {
                    stats.stableNonAirMismatches++;
                }

                if (stats.stableSamples.size() < SAMPLE_LIMIT) {
                    stats.stableSamples.add(sample(chunkX, chunkZ, x, worldY, z, expected, actual));
                }
            });
        } finally {
            mantleChunk.release();
        }
    }

    private void mergeStats(Report report, RegionStats stats) {
        report.chunksScanned += stats.chunksScanned;
        report.ungeneratedChunks += stats.ungeneratedChunks;
        report.mantleBlocks += stats.mantleBlocks;
        report.mismatchedBlocks += stats.mismatchedBlocks;
        report.missingBlocks += stats.missingBlocks;
        report.mismatchedNonAirBlocks += stats.mismatchedNonAirBlocks;
        report.stableMantleBlocks += stats.stableMantleBlocks;
        report.stableMismatchedBlocks += stats.stableMismatchedBlocks;
        report.missingStableBlocks += stats.missingStableBlocks;
        report.stableNonAirMismatches += stats.stableNonAirMismatches;
        report.ignoredTransientBlocks += stats.ignoredTransientBlocks;

        appendSamples(report.rawSamples, stats.rawSamples);
        appendSamples(report.stableSamples, stats.stableSamples);

        if (report.chunksScanned % PROGRESS_INTERVAL == 0 || report.chunksScanned == report.totalChunks) {
            logProgress(report);
        }
    }

    private void appendSamples(List<String> destination, List<String> source) {
        if (destination.size() >= SAMPLE_LIMIT || source.isEmpty()) {
            return;
        }

        int remaining = SAMPLE_LIMIT - destination.size();
        destination.addAll(source.subList(0, Math.min(remaining, source.size())));
    }

    private void logProgress(Report report) {
        double missingStableRate = ratio(report.missingStableBlocks, report.stableMantleBlocks);
        double stableMismatchRate = ratio(report.stableMismatchedBlocks, report.stableMantleBlocks);
        double chunkProgress = ratio(report.chunksScanned, report.totalChunks);
        double chunksPerSecond = computeChunksPerSecond(report);
        long etaMillis = computeEtaMillis(report, chunksPerSecond);
        Iris.info(
                "Mantle race integration: scanned %s of %s (%.0f%%) %s/s ETA: %s | raw: %s mismatches=%s (missing=%s, non-air=%s) | stable: %s mismatches=%s (missing=%s, non-air=%s, missingRate=%.5f%%, mismatchRate=%.5f%%) | ignored transient=%s",
                Form.f(report.chunksScanned),
                Form.f(report.totalChunks),
                chunkProgress * 100D,
                Form.f(chunksPerSecond),
                Form.duration(etaMillis, 2),
                Form.f(report.mantleBlocks),
                Form.f(report.mismatchedBlocks),
                Form.f(report.missingBlocks),
                Form.f(report.mismatchedNonAirBlocks),
                Form.f(report.stableMantleBlocks),
                Form.f(report.stableMismatchedBlocks),
                Form.f(report.missingStableBlocks),
                Form.f(report.stableNonAirMismatches),
                percent(missingStableRate),
                percent(stableMismatchRate),
                Form.f(report.ignoredTransientBlocks)
        );
    }

    private RuntimeException unwrapExecutionException(ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }

        return new RuntimeException(cause);
    }

    private void evaluateReport(Report report) {
        report.minChunksRequired = minChunks;
        report.maxMissingStableRate = maxMissingStableRate;
        report.maxStableMismatchRate = maxStableMismatchRate;
        report.missingStableRate = ratio(report.missingStableBlocks, report.stableMantleBlocks);
        report.stableMismatchRate = ratio(report.stableMismatchedBlocks, report.stableMantleBlocks);

        if (report.chunksScanned != report.totalChunks) {
            report.status = "FAIL";
            report.statusReason = "Scan did not complete all expected chunks";
            return;
        }

        if (report.ungeneratedChunks > 0) {
            report.status = "FAIL";
            report.statusReason = "Generated world still had ungenerated chunks during scan";
            return;
        }

        if (report.chunksScanned < minChunks || report.stableMantleBlocks == 0) {
            report.status = "INSUFFICIENT_SAMPLE";
            report.statusReason = "Scan completed, but the sample was too small for a regression decision";
            return;
        }

        if (report.missingStableRate > maxMissingStableRate) {
            report.status = "FAIL";
            report.statusReason = "Stable missing block rate exceeded the configured threshold";
            return;
        }

        if (report.stableMismatchRate > maxStableMismatchRate) {
            report.status = "FAIL";
            report.statusReason = "Stable mismatch rate exceeded the configured threshold";
            return;
        }

        report.status = "PASS";
        report.statusReason = "Stable missing and mismatch rates stayed within thresholds";
    }

    private boolean isStablePlacement(BlockData expected) {
        if (expected == null) {
            return false;
        }

        return stablePlacementCache.computeIfAbsent(expected.getMaterial(), ignored -> {
            if (B.isAir(expected) || B.isFluid(expected)) {
                return false;
            }

            if (B.isFoliage(expected) || B.isDecorant(expected)) {
                return false;
            }

            if (expected.getMaterial().name().endsWith("_LEAVES")) {
                return false;
            }

            return B.isSolid(expected);
        });
    }

    private BlockData readRegionBlockData(TerrainChunk terrainChunk, int relX, int worldY, int relZ) {
        CompoundTag state = terrainChunk.getBlockAt(relX, worldY, relZ);
        if (state == null) {
            return airBlockData;
        }

        String blockDataString = toBlockDataString(state);
        return blockDataCache.computeIfAbsent(blockDataString, Bukkit::createBlockData);
    }

    private String toBlockDataString(CompoundTag state) {
        String name = state.getString("Name", Material.AIR.getKey().toString());
        CompoundTag properties = state.getCompoundTag("Properties");
        if (properties == null || properties.isEmpty()) {
            return name;
        }

        List<String> keys = new ArrayList<>(properties.keySet());
        Collections.sort(keys);

        StringBuilder builder = new StringBuilder(name).append('[');
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }

            String key = keys.get(i);
            builder.append(key).append('=').append(properties.getString(key));
        }

        return builder.append(']').toString();
    }

    private String sample(int chunkX, int chunkZ, int relX, int worldY, int relZ, BlockData expected, BlockData actual) {
        int worldX = (chunkX << 4) + relX;
        int worldZ = (chunkZ << 4) + relZ;
        return "chunk=" + chunkX + "," + chunkZ
                + " world=" + worldX + "," + worldY + "," + worldZ
                + " expected=" + expected.getAsString()
                + " actual=" + actual.getAsString()
                + " stable=" + isStablePlacement(expected);
    }

    private void writeReport(Report report) {
        resultFile.getParentFile().mkdirs();

        Properties properties = new Properties();
        properties.setProperty("status", report.status);
        properties.setProperty("phase", report.phase);
        properties.setProperty("dimension", report.dimension);
        properties.setProperty("world", report.worldName);
        properties.setProperty("scanBackend", report.scanBackend);
        properties.setProperty("radius", Integer.toString(report.radius));
        properties.setProperty("startedAt", Long.toString(report.startedAt));
        properties.setProperty("finishedAt", Long.toString(report.finishedAt));
        properties.setProperty("chunksScanned", Long.toString(report.chunksScanned));
        properties.setProperty("totalChunks", Long.toString(report.totalChunks));
        properties.setProperty("ungeneratedChunks", Long.toString(report.ungeneratedChunks));
        properties.setProperty("mantleBlocks", Long.toString(report.mantleBlocks));
        properties.setProperty("mismatchedBlocks", Long.toString(report.mismatchedBlocks));
        properties.setProperty("missingBlocks", Long.toString(report.missingBlocks));
        properties.setProperty("mismatchedNonAirBlocks", Long.toString(report.mismatchedNonAirBlocks));
        properties.setProperty("stableMantleBlocks", Long.toString(report.stableMantleBlocks));
        properties.setProperty("stableMismatchedBlocks", Long.toString(report.stableMismatchedBlocks));
        properties.setProperty("missingStableBlocks", Long.toString(report.missingStableBlocks));
        properties.setProperty("stableNonAirMismatches", Long.toString(report.stableNonAirMismatches));
        properties.setProperty("ignoredTransientBlocks", Long.toString(report.ignoredTransientBlocks));
        properties.setProperty("minChunksRequired", Integer.toString(report.minChunksRequired));
        properties.setProperty("maxMissingStableRate", Double.toString(report.maxMissingStableRate));
        properties.setProperty("maxStableMismatchRate", Double.toString(report.maxStableMismatchRate));
        properties.setProperty("maxMissingStableRatePercent", Double.toString(percent(report.maxMissingStableRate)));
        properties.setProperty("maxStableMismatchRatePercent", Double.toString(percent(report.maxStableMismatchRate)));
        properties.setProperty("missingStableRate", Double.toString(report.missingStableRate));
        properties.setProperty("stableMismatchRate", Double.toString(report.stableMismatchRate));
        properties.setProperty("missingStableRatePercent", Double.toString(percent(report.missingStableRate)));
        properties.setProperty("stableMismatchRatePercent", Double.toString(percent(report.stableMismatchRate)));
        properties.setProperty("statusReason", report.statusReason);

        if (report.error != null) {
            properties.setProperty("error", report.error);
        }

        for (int i = 0; i < report.stableSamples.size(); i++) {
            properties.setProperty("stableSample." + i, report.stableSamples.get(i));
        }

        for (int i = 0; i < report.rawSamples.size(); i++) {
            properties.setProperty("rawSample." + i, report.rawSamples.get(i));
        }

        File resultsRoot = resultFile.getParentFile();
        String runId = RUN_TIMESTAMP.format(Instant.ofEpochMilli(report.finishedAt)) + "-" + report.status.toLowerCase();
        File runReportFile = new File(resultsRoot, "report-" + runId + ".properties");

        try (FileOutputStream outputStream = new FileOutputStream(resultFile)) {
            properties.store(outputStream, "Mantle race integration report");
        } catch (IOException e) {
            Iris.reportError(e);
            e.printStackTrace();
            return;
        }

        try {
            Files.copy(resultFile.toPath(), runReportFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Iris.reportError(e);
            e.printStackTrace();
        }
    }

    private String stackTraceOf(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0D;
        }

        return (double) numerator / (double) denominator;
    }

    private static double percent(double ratio) {
        return ratio * 100D;
    }

    private static double computeChunksPerSecond(Report report) {
        long elapsedMillis = Math.max(1L, System.currentTimeMillis() - report.startedAt);
        return report.chunksScanned / (elapsedMillis / 1000D);
    }

    private static long computeEtaMillis(Report report, double chunksPerSecond) {
        if (report.totalChunks <= report.chunksScanned || chunksPerSecond <= 0D) {
            return 0L;
        }

        return Math.round(((report.totalChunks - report.chunksScanned) / chunksPerSecond) * 1000D);
    }

    private static double doubleProperty(String key, double defaultValue) {
        return Double.parseDouble(System.getProperty(key, Double.toString(defaultValue)));
    }

    private static final class Report {
        private final String dimension;
        private final String worldName;
        private final int radius;
        private final long startedAt = System.currentTimeMillis();
        private final List<String> stableSamples = new ArrayList<>();
        private final List<String> rawSamples = new ArrayList<>();

        private String status = "RUNNING";
        private String statusReason = "Integration run has not finished yet";
        private String phase = "prepare";
        private String scanBackend = "live-world";
        private String error;
        private long finishedAt;
        private long totalChunks;
        private long chunksScanned;
        private long ungeneratedChunks;
        private long mantleBlocks;
        private long mismatchedBlocks;
        private long missingBlocks;
        private long mismatchedNonAirBlocks;
        private long stableMantleBlocks;
        private long stableMismatchedBlocks;
        private long missingStableBlocks;
        private long stableNonAirMismatches;
        private long ignoredTransientBlocks;
        private int minChunksRequired;
        private double maxMissingStableRate;
        private double maxStableMismatchRate;
        private double missingStableRate;
        private double stableMismatchRate;

        private Report(String dimension, String worldName, int radius) {
            this.dimension = dimension;
            this.worldName = worldName;
            this.radius = radius;
        }
    }

    private static final class RegionStats {
        private final List<String> stableSamples = new ArrayList<>();
        private final List<String> rawSamples = new ArrayList<>();
        private long chunksScanned;
        private long ungeneratedChunks;
        private long mantleBlocks;
        private long mismatchedBlocks;
        private long missingBlocks;
        private long mismatchedNonAirBlocks;
        private long stableMantleBlocks;
        private long stableMismatchedBlocks;
        private long missingStableBlocks;
        private long stableNonAirMismatches;
        private long ignoredTransientBlocks;
    }

    private record RegionPos(int x, int z) {
    }

    private record ChunkPos(int x, int z) {
    }
}
