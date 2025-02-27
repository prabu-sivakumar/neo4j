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
package org.neo4j.storageengine.api;

/**
 * Transaction id plus meta data that says something about its contents, for comparison.
 */
public record TransactionId(long transactionId, int checksum, long commitTimestamp) {
    /**
     * Transaction id, generated by {@link TransactionIdStore#nextCommittingTransactionId()},
     * here accessible after that transaction being committed.
     */
    @Override
    public long transactionId() {
        return transactionId;
    }

    /**
     * Commit timestamp. Timestamp when transaction with transactionId was committed.
     */
    @Override
    public long commitTimestamp() {
        return commitTimestamp;
    }

    /**
     * Checksum of a transaction, generated from transaction meta data or its contents.
     */
    @Override
    public int checksum() {
        return checksum;
    }
}
