/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.transaction.log.checkpoint;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.neo4j.internal.helpers.Format.duration;

import java.io.IOException;
import java.time.Clock;
import java.util.function.BooleanSupplier;
import org.neo4j.graphdb.Resource;
import org.neo4j.io.pagecache.IOController;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.io.pagecache.tracing.DatabaseFlushEvent;
import org.neo4j.kernel.database.DatabaseTracers;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.kernel.impl.transaction.log.pruning.LogPruning;
import org.neo4j.kernel.impl.transaction.tracing.LogCheckPointEvent;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.InternalLog;
import org.neo4j.logging.InternalLogProvider;
import org.neo4j.monitoring.Health;
import org.neo4j.storageengine.api.MetadataProvider;
import org.neo4j.storageengine.api.TransactionId;
import org.neo4j.time.Stopwatch;

public class CheckPointerImpl extends LifecycleAdapter implements CheckPointer {
    private static final String CHECKPOINT_TAG = "checkpoint";
    private static final long NO_TRANSACTION_ID = -1;
    private static final String IO_DETAILS_TEMPLATE =
            "Checkpoint flushed %d pages (%d%% of total available pages), in %d IOs. Checkpoint performed with IO limit: %s, paused in total %d times( %d millis).";
    private static final String UNLIMITED_IO_CONTROLLER_LIMIT = "unlimited";

    private final CheckpointAppender checkpointAppender;
    private final MetadataProvider metadataProvider;
    private final CheckPointThreshold threshold;
    private final ForceOperation forceOperation;
    private final LogPruning logPruning;
    private final Health databaseHealth;
    private final InternalLog log;
    private final DatabaseTracers tracers;
    private final StoreCopyCheckPointMutex mutex;
    private final CursorContextFactory cursorContextFactory;
    private final Clock clock;
    private final IOController ioController;

    private volatile long lastCheckPointedTx;

    public CheckPointerImpl(
            MetadataProvider metadataProvider,
            CheckPointThreshold threshold,
            ForceOperation forceOperation,
            LogPruning logPruning,
            CheckpointAppender checkpointAppender,
            Health databaseHealth,
            InternalLogProvider logProvider,
            DatabaseTracers tracers,
            StoreCopyCheckPointMutex mutex,
            CursorContextFactory cursorContextFactory,
            Clock clock,
            IOController ioController) {
        this.checkpointAppender = checkpointAppender;
        this.metadataProvider = metadataProvider;
        this.threshold = threshold;
        this.forceOperation = forceOperation;
        this.logPruning = logPruning;
        this.databaseHealth = databaseHealth;
        this.log = logProvider.getLog(CheckPointerImpl.class);
        this.tracers = tracers;
        this.mutex = mutex;
        this.cursorContextFactory = cursorContextFactory;
        this.clock = clock;
        this.ioController = ioController;
    }

    @Override
    public void start() {
        var lastClosedTransaction = metadataProvider.getLastClosedTransaction();
        threshold.initialize(lastClosedTransaction.transactionId(), lastClosedTransaction.logPosition());
    }

    @Override
    public long forceCheckPoint(TriggerInfo info) throws IOException {
        try (Resource lock = mutex.checkPoint()) {
            return doCheckPoint(info);
        }
    }

    @Override
    public long tryCheckPoint(TriggerInfo info) throws IOException {
        return tryCheckPoint(info, () -> false);
    }

    @Override
    public long tryCheckPointNoWait(TriggerInfo info) throws IOException {
        return tryCheckPoint(info, () -> true);
    }

    @Override
    public long tryCheckPoint(TriggerInfo info, BooleanSupplier timeout) throws IOException {
        Resource lockAttempt = mutex.tryCheckPoint();
        if (lockAttempt != null) {
            try (lockAttempt) {
                return doCheckPoint(info);
            }
        } else {
            try (Resource lock = mutex.tryCheckPoint(timeout)) {
                if (lock != null) {
                    log.info(info.describe(lastCheckPointedTx) + " Check pointing was already running, completed now");
                    return lastCheckPointedTx;
                } else {
                    return NO_TRANSACTION_ID;
                }
            }
        }
    }

    @Override
    public long checkPointIfNeeded(TriggerInfo info) throws IOException {
        var lastClosedTransaction = metadataProvider.getLastClosedTransaction();
        if (threshold.isCheckPointingNeeded(
                lastClosedTransaction.transactionId(), lastClosedTransaction.logPosition(), info)) {
            try (Resource lock = mutex.checkPoint()) {
                return doCheckPoint(info);
            }
        }
        return NO_TRANSACTION_ID;
    }

    private long doCheckPoint(TriggerInfo triggerInfo) throws IOException {
        var databaseTracer = tracers.getDatabaseTracer();
        try (var cursorContext = cursorContextFactory.create(CHECKPOINT_TAG);
                LogCheckPointEvent checkPointEvent = databaseTracer.beginCheckPoint()) {
            var lastClosedTxData = metadataProvider.getLastClosedTransaction();
            var lastClosedTransaction = new TransactionId(
                    lastClosedTxData.transactionId(), lastClosedTxData.checksum(), lastClosedTxData.commitTimestamp());
            long lastClosedTransactionId = lastClosedTransaction.transactionId();
            cursorContext.getVersionContext().initWrite(lastClosedTransactionId);
            LogPosition logPosition = lastClosedTxData.logPosition();
            String checkpointReason = triggerInfo.describe(lastClosedTransactionId);
            /*
             * Check kernel health before going into waiting for transactions to be closed, to avoid
             * getting into a scenario where we would await a condition that would potentially never
             * happen.
             */
            databaseHealth.assertHealthy(IOException.class);
            /*
             * First we flush the store. If we fail now or during the flush, on recovery we'll find the
             * earlier check point and replay from there all the log entries. Everything will be ok.
             */
            log.info(checkpointReason + " checkpoint started...");
            Stopwatch startTime = Stopwatch.start();

            try (var flushEvent = checkPointEvent.beginDatabaseFlush()) {
                forceOperation.flushAndForce(flushEvent, cursorContext);
                flushEvent.ioControllerLimit(ioController.configuredLimit());
            }

            /*
             * Check kernel health before going to write the next check point.  In case of a panic this check point
             * will be aborted, which is the safest alternative so that the next recovery will have a chance to
             * repair the damages.
             */
            databaseHealth.assertHealthy(IOException.class);
            checkpointAppender.checkPoint(
                    checkPointEvent, lastClosedTransaction, logPosition, clock.instant(), checkpointReason);
            threshold.checkPointHappened(lastClosedTransactionId, logPosition);
            long durationMillis = startTime.elapsed(MILLISECONDS);
            checkPointEvent.checkpointCompleted(durationMillis);
            log.info(createCheckpointMessageDescription(checkPointEvent, checkpointReason, durationMillis));

            /*
             * Prune up to the version pointed from the latest check point,
             * since it might be an earlier version than the current log version.
             */
            logPruning.pruneLogs(logPosition.getLogVersion());
            lastCheckPointedTx = lastClosedTransactionId;
            return lastClosedTransactionId;
        } catch (Throwable t) {
            // Why only log failure here? It's because check point can potentially be made from various
            // points of execution e.g. background thread triggering check point if needed and during
            // shutdown where it's better to have more control over failure handling.
            log.error("Checkpoint failed", t);
            throw t;
        }
    }

    private String createCheckpointMessageDescription(
            LogCheckPointEvent checkpointEvent, String checkpointReason, long durationMillis) {
        double flushRatio = checkpointEvent.flushRatio();
        long ioLimit = checkpointEvent.getConfiguredIOLimit();
        String ioDetails = IO_DETAILS_TEMPLATE.formatted(
                checkpointEvent.getPagesFlushed(),
                (int) (flushRatio * 100),
                checkpointEvent.getIOsPerformed(),
                ioLimitDescription(ioLimit),
                checkpointEvent.getTimesPaused(),
                checkpointEvent.getMillisPaused());
        return checkpointReason + " checkpoint completed in " + duration(durationMillis) + ". " + ioDetails;
    }

    private String ioLimitDescription(long ioLimit) {
        return ioController.isEnabled() && ioLimit >= 0 ? String.valueOf(ioLimit) : UNLIMITED_IO_CONTROLLER_LIMIT;
    }

    @Override
    public long lastCheckPointedTransactionId() {
        return lastCheckPointedTx;
    }

    @FunctionalInterface
    public interface ForceOperation {
        void flushAndForce(DatabaseFlushEvent flushEvent, CursorContext cursorContext) throws IOException;
    }
}
