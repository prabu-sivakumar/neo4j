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
package org.neo4j.internal.batchimport.input;

import static java.lang.String.format;

import java.util.Map;

/**
 * Collects items and is {@link #close() closed} after any and all items have been collected.
 * The {@link Collector} is responsible for closing whatever closeable resource received from the importer.
 */
public interface Collector extends AutoCloseable {
    void collectBadRelationship(
            Object startId, Group startIdGroup, String type, Object endId, Group endIdGroup, Object specificValue);

    void collectDuplicateNode(Object id, long actualId, Group group);

    void collectNodeViolatingConstraint(
            Object id, long actualId, Map<String, Object> properties, String constraintDescription);

    void collectExtraColumns(String source, long row, String value);

    long badEntries();

    boolean isCollectingBadRelationships();

    /**
     * Flushes whatever changes to the underlying resource supplied from the importer.
     */
    @Override
    void close();

    Collector EMPTY = new Collector() {
        @Override
        public void collectExtraColumns(String source, long row, String value) {}

        @Override
        public void close() {}

        @Override
        public long badEntries() {
            return 0;
        }

        @Override
        public void collectBadRelationship(
                Object startId,
                Group startIdGroup,
                String type,
                Object endId,
                Group endIdGroup,
                Object specificValue) {}

        @Override
        public void collectDuplicateNode(Object id, long actualId, Group group) {}

        @Override
        public void collectNodeViolatingConstraint(
                Object id, long actualId, Map<String, Object> properties, String constraintDescription) {}

        @Override
        public boolean isCollectingBadRelationships() {
            return true;
        }
    };

    Collector STRICT = new Collector() {
        @Override
        public void collectExtraColumns(String source, long row, String value) {
            throw new IllegalStateException(format("Bad extra column '%s' index:%d in '%s'", value, row, source));
        }

        @Override
        public void close() {}

        @Override
        public long badEntries() {
            return 0;
        }

        @Override
        public void collectBadRelationship(
                Object startId, Group startIdGroup, String type, Object endId, Group endIdGroup, Object specificValue) {
            throw new IllegalStateException(format(
                    "Bad relationship (%s:%s)-[%s]->(%s:%s) %s",
                    startId, startIdGroup, type, endId, endIdGroup, specificValue));
        }

        @Override
        public void collectDuplicateNode(Object id, long actualId, Group group) {
            throw new IllegalStateException(format("Bad duplicate node %s:%s id:%d", id, group, actualId));
        }

        @Override
        public void collectNodeViolatingConstraint(
                Object id, long actualId, Map<String, Object> properties, String constraintDescription) {
            throw new IllegalStateException(
                    format("Bad node violating constraint %s %s id:%s", properties, constraintDescription, id));
        }

        @Override
        public boolean isCollectingBadRelationships() {
            return false;
        }
    };
}
