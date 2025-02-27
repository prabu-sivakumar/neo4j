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
package org.neo4j.bolt.protocol.common.message.result;

import java.io.IOException;
import org.neo4j.values.AnyValue;

/**
 * Represents a data stream of records, this is the output cypher produces.
 * <p>
 * Stream contains nominally uniform records meaning each record has the same set of named fields. However, the contents of these fields may vary by both type
 * and value and may be null.
 */
public interface BoltResult extends AutoCloseable {
    /** Positional names for all fields in every record of this stream. */
    String[] fieldNames();

    /**
     * @return {@code true} if there could be more records, {@code false} otherwise.
     */
    boolean handleRecords(RecordConsumer recordConsumer, long size) throws Throwable;

    /**
     * @return {@code true} if there could be more records, {@code false} otherwise.
     */
    boolean discardRecords(DiscardingRecordConsumer recordConsumer, long size) throws Throwable;

    @Override
    void close();

    interface RecordConsumer {

        /**
         * Called once before the each received record.
         *
         * @param numberOfFields The number of fields in the record
         */
        void beginRecord(int numberOfFields) throws IOException;

        /**
         * Called once for each field of the record, in order of increasing offsets.
         *
         * @param value the value of this field
         */
        void consumeField(AnyValue value) throws IOException;

        /**
         * Called once at the end of each received record.
         */
        void endRecord() throws IOException;

        /**
         * Called when an error is received.
         */
        void onError() throws IOException;

        /**
         * Associate arbitrary metadata with the result stream. This will get transferred at the end of the stream. Please stick to Neo4j type system types
         * (Map, List, Integer, Float, Boolean, String etc)
         */
        void addMetadata(String key, AnyValue value);
    }

    abstract class DiscardingRecordConsumer implements RecordConsumer {
        @Override
        public void beginRecord(int numberOfFields) {
            // discard
        }

        @Override
        public void consumeField(AnyValue value) {
            // discard
        }

        @Override
        public void endRecord() {
            // discard
        }

        @Override
        public void onError() {
            // discard
        }
    }

    BoltResult EMPTY = new BoltResult() {
        private final String[] nothing = new String[0];

        @Override
        public void close() {}

        @Override
        public String[] fieldNames() {
            return nothing;
        }

        @Override
        public boolean handleRecords(RecordConsumer recordConsumer, long size) {
            return false;
        }

        @Override
        public boolean discardRecords(DiscardingRecordConsumer recordConsumer, long size) {
            return false;
        }

        @Override
        public String toString() {
            return "EmptyBoltResult{}";
        }
    };
}
