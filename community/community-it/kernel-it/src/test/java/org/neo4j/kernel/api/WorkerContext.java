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

class WorkerContext<T extends AutoCloseable> implements AutoCloseable
{
    private final T cursor;
    private final KernelTransaction.ExecutionContext context;
    private final KernelTransaction owner;

    WorkerContext( T cursor, KernelTransaction.ExecutionContext context, KernelTransaction owner )
    {
        this.cursor = cursor;
        this.context = context;
        this.owner = owner;
    }

    @Override
    public void close() throws Exception
    {
        cursor.close();
        owner.mergeExecutionContext( context );
    }

    public T getCursor()
    {
        return cursor;
    }

    public KernelTransaction.ExecutionContext getContext()
    {
        return context;
    }

    public KernelTransaction getTransaction()
    {
        return owner;
    }
}