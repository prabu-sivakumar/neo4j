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
package org.neo4j.io.pagecache.tracing.cursor;

public record CursorStatisticSnapshot(
        long pins,
        long unpins,
        long hits,
        long faults,
        long noFaults,
        long failedFaults,
        long bytesRead,
        long bytesWritten,
        long evictions,
        long evictionExceptions,
        long flushes,
        long merges,
        long snapshotsLoaded,
        long copiedPages,
        long chainsPatched) {
    public CursorStatisticSnapshot(PageCursorTracer cursorTracer) {
        this(
                cursorTracer.pins(),
                cursorTracer.unpins(),
                cursorTracer.hits(),
                cursorTracer.faults(),
                cursorTracer.noFaults(),
                cursorTracer.failedFaults(),
                cursorTracer.bytesRead(),
                cursorTracer.bytesWritten(),
                cursorTracer.evictions(),
                cursorTracer.evictionExceptions(),
                cursorTracer.flushes(),
                cursorTracer.merges(),
                cursorTracer.snapshotsLoaded(),
                cursorTracer.copiedPages(),
                cursorTracer.chainsPatched());
    }
}
