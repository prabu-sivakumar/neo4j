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
package org.neo4j.internal.id;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.neo4j.configuration.Config;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.logging.InternalLog;
import org.neo4j.logging.internal.LogService;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.scheduler.Group;
import org.neo4j.scheduler.JobHandle;
import org.neo4j.scheduler.JobMonitoringParams;
import org.neo4j.scheduler.JobScheduler;

/**
 * Storage id controller that provide buffering possibilities to be able so safely free and reuse ids.
 * Allows perform clear and maintenance operations over currently buffered set of ids.
 * @see BufferingIdGeneratorFactory
 */
public class BufferedIdController extends LifecycleAdapter implements IdController {
    private static final String BUFFERED_ID_CONTROLLER = "idController";
    private final BufferingIdGeneratorFactory bufferingIdGeneratorFactory;
    private final JobScheduler scheduler;
    private final CursorContextFactory contextFactory;
    private final String databaseName;
    private final InternalLog log;
    private JobHandle<?> jobHandle;
    private volatile boolean running;
    private final Lock maintenanceLock = new ReentrantLock();

    public BufferedIdController(
            BufferingIdGeneratorFactory bufferingIdGeneratorFactory,
            JobScheduler scheduler,
            CursorContextFactory contextFactory,
            String databaseName,
            LogService logService) {
        this.bufferingIdGeneratorFactory = bufferingIdGeneratorFactory;
        this.scheduler = scheduler;
        this.contextFactory = contextFactory;
        this.databaseName = databaseName;
        this.log = logService.getInternalLog(BufferedIdController.class);
    }

    @Override
    public void init() throws Exception {
        bufferingIdGeneratorFactory.init();
    }

    @Override
    public void start() throws Exception {
        bufferingIdGeneratorFactory.start();
        running = true;
        var monitoringParams = JobMonitoringParams.systemJob(databaseName, "ID generator maintenance");
        jobHandle =
                scheduler.scheduleRecurring(Group.STORAGE_MAINTENANCE, monitoringParams, this::maintenance, 1, SECONDS);
    }

    @Override
    public void stop() throws Exception {
        running = false;
        if (jobHandle != null) {
            jobHandle.cancel();
            jobHandle = null;
            maintenanceLock.lock();
            maintenanceLock.unlock();
        }
        bufferingIdGeneratorFactory.stop();
    }

    @Override
    public void shutdown() throws Exception {
        bufferingIdGeneratorFactory.shutdown();
    }

    @Override
    public void maintenance() {
        maintenanceLock.lock();
        try {
            if (running) {
                try (var cursorContext = contextFactory.create(BUFFERED_ID_CONTROLLER)) {
                    bufferingIdGeneratorFactory.maintenance(cursorContext);
                } catch (Throwable t) {
                    log.error("Exception when performing id maintenance", t);
                }
            }
        } finally {
            maintenanceLock.unlock();
        }
    }

    @Override
    public void initialize(
            FileSystemAbstraction fs,
            Path baseBufferPath,
            Config config,
            Supplier<TransactionSnapshot> snapshotSupplier,
            IdFreeCondition condition,
            MemoryTracker memoryTracker)
            throws IOException {
        bufferingIdGeneratorFactory.initialize(fs, baseBufferPath, config, snapshotSupplier, condition, memoryTracker);
    }
}
