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
package org.neo4j.kernel.impl.transaction.log;

import java.io.IOException;
import org.neo4j.kernel.impl.transaction.CommittedTransactionRepresentation;

/**
 * Accessor of transactions and meta data information about transactions.
 */
public interface LogicalTransactionStore {
    /**
     * Acquires a {@link TransactionCursor cursor} which will provide {@link CommittedTransactionRepresentation}
     * instances for committed transactions, starting from the specified {@code transactionIdToStartFrom}.
     * Transactions will be returned from the cursor in transaction-id-sequential order.
     *
     * @param transactionIdToStartFrom id of the first transaction that the cursor will return.
     * @return an {@link TransactionCursor} capable of returning {@link CommittedTransactionRepresentation} instances
     * for committed transactions, starting from the specified {@code transactionIdToStartFrom}.
     * @throws NoSuchTransactionException if the requested transaction hasn't been committed,
     * or if the transaction has been committed, but information about it is no longer available for some reason.
     * @throws IOException if there was an I/O related error looking for the start transaction.
     */
    TransactionCursor getTransactions(long transactionIdToStartFrom) throws IOException;

    /**
     * Acquires a {@link TransactionCursor cursor} which will provide {@link CommittedTransactionRepresentation}
     * instances for committed transactions, starting from the specified {@link LogPosition}.
     * This is useful for placing a cursor at a position referred to by a {@link CheckpointInfo}.
     * Transactions will be returned from the cursor in transaction-id-sequential order.
     *
     * @param position {@link LogPosition} of the first transaction that the cursor will return.
     * @return an {@link TransactionCursor} capable of returning {@link CommittedTransactionRepresentation} instances
     * for committed transactions, starting from the specified {@code position}.
     * @throws NoSuchTransactionException if the requested transaction hasn't been committed,
     * or if the transaction has been committed, but information about it is no longer available for some reason.
     * @throws IOException if there was an I/O related error looking for the start transaction.
     */
    TransactionCursor getTransactions(LogPosition position) throws IOException;

    /**
     * Acquires a {@link TransactionCursor cursor} which will provide {@link CommittedTransactionRepresentation}
     * instances for committed transactions, starting from the end of the whole transaction stream
     * back to (and including) the transaction at {@link LogPosition}.
     * Transactions will be returned in reverse order from the end of the transaction stream and backwards in
     * descending order of transaction id.
     *
     * @param backToPosition {@link LogPosition} of the lowest (last to be returned) transaction.
     * @return an {@link TransactionCursor} capable of returning {@link CommittedTransactionRepresentation} instances
     * for committed transactions in the given range in reverse order.
     * @throws IOException if there was an I/O related error looking for the start transaction.
     */
    TransactionCursor getTransactionsInReverseOrder(LogPosition backToPosition) throws IOException;
}
