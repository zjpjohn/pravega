/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.test.integration.selftest;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AbstractScheduledService;
import io.pravega.common.AbstractTimer;
import io.pravega.common.concurrent.ExecutorServiceHelpers;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.val;

/**
 * Reports Test State on a periodic basis.
 */
class Reporter extends AbstractScheduledService {
    //region Members

    private static final int ONE_MB = 1024 * 1024;
    private static final String LOG_ID = "Reporter";
    private static final int REPORT_INTERVAL_MILLIS = 1000;
    private final TestState testState;
    private final TestConfig testConfig;
    private final Supplier<ExecutorServiceHelpers.Snapshot> storePoolSnapshotProvider;
    private final ScheduledExecutorService executorService;
    private final AtomicLong lastReportTime = new AtomicLong(-1);
    private final AtomicLong lastReportLength = new AtomicLong(-1);

    //endregion

    //region Constructor

    /**
     * Creates a new instance of the Reporter class.
     *
     * @param testState                 The TestState to attach to.
     * @param testConfig                The TestConfig to use.
     * @param storePoolSnapshotProvider A Supplier that can return a Snapshot of the Store Executor Pool.
     * @param executorService           The executor service to use.
     */
    Reporter(TestState testState, TestConfig testConfig, Supplier<ExecutorServiceHelpers.Snapshot> storePoolSnapshotProvider, ScheduledExecutorService executorService) {
        Preconditions.checkNotNull(testState, "testState");
        Preconditions.checkNotNull(testConfig, "testConfig");
        Preconditions.checkNotNull(storePoolSnapshotProvider, "storePoolSnapshotProvider");
        Preconditions.checkNotNull(executorService, "executorService");
        this.testState = testState;
        this.testConfig = testConfig;
        this.storePoolSnapshotProvider = storePoolSnapshotProvider;
        this.executorService = executorService;
    }

    //endregion

    //region AbstractScheduledService Implementation

    @Override
    protected void runOneIteration() {
        outputState();
    }

    @Override
    protected Scheduler scheduler() {
        return Scheduler.newFixedDelaySchedule(REPORT_INTERVAL_MILLIS, REPORT_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Override
    protected ScheduledExecutorService executor() {
        return this.executorService;
    }

    //endregion

    /**
     * Outputs the current state of the test.
     */
    void outputState() {
        val testPoolSnapshot = ExecutorServiceHelpers.getSnapshot(this.executorService);
        val joinPoolSnapshot = ExecutorServiceHelpers.getSnapshot(ForkJoinPool.commonPool());
        val storePoolSnapshot = this.storePoolSnapshotProvider.get();
        long time = System.nanoTime();
        long producedLength = this.testState.getProducedLength();
        double instantThroughput = this.lastReportTime.get() < 0
                ? -1 : (producedLength - this.lastReportLength.get()) / toSeconds(time - this.lastReportTime.get());

        this.lastReportTime.set(time);
        this.lastReportLength.set(producedLength);

        TestLogger.log(
                LOG_ID,
                "Ops = %s/%s; Data (P/T/C/S): %.1f/%.1f/%.1f/%.1f MB; TPut: %.1f/%.1f MB/s; TPools (Q/T/S): %s, %s, %s.",
                this.testState.getSuccessfulOperationCount(),
                this.testConfig.getOperationCount(),
                toMB(producedLength),
                toMB(this.testState.getVerifiedTailLength()),
                toMB(this.testState.getVerifiedCatchupLength()),
                toMB(this.testState.getVerifiedStorageLength()),
                instantThroughput < 0 ? 0.0 : toMB(instantThroughput),
                toMB(this.testState.getThroughput()),
                formatSnapshot(storePoolSnapshot, "Store"),
                formatSnapshot(testPoolSnapshot, "Test"),
                formatSnapshot(joinPoolSnapshot, "ForkJoin"));
    }

    private String formatSnapshot(ExecutorServiceHelpers.Snapshot s, String name) {
        if (s == null) {
            return String.format("%s = ?/?/?", name);
        }

        return String.format("%s = %d/%d/%d", name, s.getQueueSize(), s.getActiveThreadCount(), s.getPoolSize());
    }

    /**
     * Outputs a summary for all the operation types (Count + Latencies).
     */
    void outputSummary() {
        TestLogger.log(LOG_ID, "Operation Summary");
        outputRow("Operation Type", "Count", "LAvg", "L50", "L90", "L99", "L999");
        for (OperationType ot : TestState.SUMMARY_OPERATION_TYPES) {
            val durations = this.testState.getDurations(ot);
            if (durations == null || durations.count() == 0) {
                continue;
            }

            int[] percentiles = durations.percentiles(0.5, 0.9, 0.99, 0.999);
            outputRow(ot, durations.count(), (int) durations.average(), percentiles[0], percentiles[1], percentiles[2], percentiles[3]);
        }
    }

    private void outputRow(Object opType, Object count, Object lAvg, Object l50, Object l90, Object l99, Object l999) {
        TestLogger.log(LOG_ID, "%18s | %7s | %5s | %5s | %5s | %5s | %5s", opType, count, lAvg, l50, l90, l99, l999);
    }

    private double toMB(double bytes) {
        return bytes / (double) ONE_MB;
    }

    private double toSeconds(long nanos) {
        return (double) nanos / AbstractTimer.NANOS_TO_MILLIS / 1000;
    }
}