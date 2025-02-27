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
package org.neo4j.kernel.api;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.internal.kernel.api.security.AuthSubject;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.kernel.api.exceptions.Status;
import org.neo4j.kernel.api.query.ExecutingQuery;
import org.neo4j.kernel.impl.api.TransactionExecutionStatistic;
import org.neo4j.kernel.impl.api.transaction.trace.TransactionInitializationTrace;
import org.neo4j.lock.ActiveLock;

/**
 * View of a {@link KernelTransaction} that provides a limited set of actions against the transaction.
 */
public interface KernelTransactionHandle {

    /**
     * The start time of the underlying transaction. I.e. basically {@link System#currentTimeMillis()} when user
     * called {@link Kernel#beginTransaction(KernelTransaction.Type, LoginContext)}.
     *
     * @return the transaction start time.
     */
    long startTime();

    /**
     * The start time of the underlying transaction.
     *
     * This can be used to measure elapsed time in a safe way that is not affected by system time changes.
     *
     * @return nanoTime at the start of the transaction.
     */
    long startTimeNanos();

    /**
     * Underlying transaction specific timeout. In case if timeout is 0 - transaction does not have a timeout.
     * @return transaction timeout in milliseconds, <b>0 in case if transaction does not have a timeout<b/>
     */
    long timeoutMillis();

    /**
     * Check if the underlying transaction is open.
     *
     * @return {@code true} if the underlying transaction {@link KernelTransaction#close()} was not called, {@code false} otherwise.
     */
    boolean isOpen();

    /**
     * Check if the underlying transaction is closing. Closing means that the transaction is closed by the user and currently doing commit or rollback.
     *
     * @return {@code true} if the underlying transaction ({@link KernelTransaction#close()} is called, but not finished, {@code false} otherwise.
     */
    boolean isClosing();

    /**
     * Mark the underlying transaction for termination.
     *
     * @param reason the reason for termination.
     * @return {@code true} if the underlying transaction was marked for termination, {@code false} otherwise
     * (when this handle represents an old transaction that has been closed).
     */
    boolean markForTermination(Status reason);

    /**
     * Security context of underlying transaction that transaction has when handle was created.
     *
     * @return underlying transaction security context
     */
    AuthSubject subject();

    /**
     * Metadata of underlying transaction that transaction has when handle was created.
     * @return underlying transaction metadata
     */
    Map<String, Object> getMetaData();

    /**
     * Transaction termination reason that transaction had when handle was created.
     *
     * @return transaction termination reason.
     */
    Optional<Status> terminationReason();

    /**
     * Check if this handle points to the same underlying transaction as the given one.
     *
     * @param tx the expected transaction.
     * @return {@code true} if this handle represents {@code tx}, {@code false} otherwise.
     */
    boolean isUnderlyingTransaction(KernelTransaction tx);

    /**
     * User transaction id of underlying transaction. User transaction id is a not negative long number.
     * Should be unique across transactions.
     * @return user transaction id
     */
    long getTransactionSequenceNumber();

    /**
     * User transaction name of the underlying transaction.
     * User transaction name consists of the name prefix and user transaction id.
     * Should be unique across transactions.
     * @return user transaction name
     */
    String getUserTransactionName();

    /**
     * Query currently executing, if any, that use the underlying transaction
     */
    Optional<ExecutingQuery> executingQuery();

    /**
     * @return the lock requests granted for this transaction.
     */
    Stream<ActiveLock> activeLocks();

    /**
     * Provide underlying transaction execution statistics. For example: elapsed time, allocated bytes etc
     * @return transaction statistics projection
     */
    TransactionExecutionStatistic transactionStatistic();

    /**
     * Provide stack trace of particular transaction initialisation call if that is available, empty record otherwise
     * @return transaction initialization trace
     */
    TransactionInitializationTrace transactionInitialisationTrace();

    /**
     * Provide underlying transaction originator details
     * @return transaction originator details
     */
    Optional<ClientConnectionInfo> clientInfo();

    /**
     * @return whether or not this transaction is a schema transaction. Type of transaction is decided
     * on first write operation, be it data or schema operation.
     */
    boolean isSchemaTransaction();

    /**
     * Provide additional status details from underlying transaction
     * @return additional status or empty string if not available.
     */
    String getStatusDetails();
}
