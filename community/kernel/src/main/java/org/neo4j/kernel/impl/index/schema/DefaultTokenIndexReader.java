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

package org.neo4j.kernel.impl.index.schema;

import static org.neo4j.kernel.impl.index.schema.TokenIndexUpdater.rangeOf;
import static org.neo4j.kernel.impl.index.schema.TokenScanValue.RANGE_SIZE;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.neo4j.index.internal.gbptree.GBPTree;
import org.neo4j.index.internal.gbptree.Seeker;
import org.neo4j.internal.kernel.api.IndexQueryConstraints;
import org.neo4j.internal.kernel.api.TokenPredicate;
import org.neo4j.internal.schema.IndexOrder;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.api.index.EntityRange;
import org.neo4j.kernel.api.index.IndexProgressor;
import org.neo4j.kernel.api.index.TokenIndexReader;
import org.neo4j.util.Preconditions;
import org.neo4j.util.VisibleForTesting;

public class DefaultTokenIndexReader implements TokenIndexReader {

    private final GBPTree<TokenScanKey, TokenScanValue> index;

    public DefaultTokenIndexReader(GBPTree<TokenScanKey, TokenScanValue> index) {
        this.index = index;
    }

    @Override
    public void query(
            IndexProgressor.EntityTokenClient client,
            IndexQueryConstraints constraints,
            TokenPredicate query,
            CursorContext cursorContext) {
        query(client, constraints, query, EntityRange.FULL, cursorContext);
    }

    @Override
    public void query(
            IndexProgressor.EntityTokenClient client,
            IndexQueryConstraints constraints,
            TokenPredicate query,
            EntityRange range,
            CursorContext cursorContext) {
        try {
            final int tokenId = query.tokenId();
            final IndexOrder order = constraints.order();
            Seeker<TokenScanKey, TokenScanValue> seeker = seekerForToken(range, tokenId, order, cursorContext);
            IndexProgressor progressor = new TokenScanValueIndexProgressor(seeker, client, order, range);
            client.initialize(progressor, tokenId, order);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public TokenScan entityTokenScan(int tokenId, CursorContext cursorContext) {
        try {
            long highestEntityIdForToken = highestEntityIdForToken(tokenId, cursorContext);
            return new NativeTokenScan(tokenId, highestEntityIdForToken);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public PartitionedTokenScan entityTokenScan(
            int desiredNumberOfPartitions, CursorContext context, TokenPredicate query) {
        try {
            return new NativePartitionedTokenScan(desiredNumberOfPartitions, context, query);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public PartitionedTokenScan entityTokenScan(PartitionedTokenScan leadingPartition, TokenPredicate query) {
        return new NativePartitionedTokenScan((NativePartitionedTokenScan) leadingPartition, query);
    }

    private long highestEntityIdForToken(int tokenId, CursorContext cursorContext) throws IOException {
        try (Seeker<TokenScanKey, TokenScanValue> seeker = index.seek(
                new TokenScanKey(tokenId, Long.MAX_VALUE), new TokenScanKey(tokenId, Long.MIN_VALUE), cursorContext)) {
            return seeker.next() ? (seeker.key().idRange + 1) * RANGE_SIZE : 0;
        }
    }

    private Seeker<TokenScanKey, TokenScanValue> seekerForToken(
            EntityRange range, int tokenId, IndexOrder indexOrder, CursorContext cursorContext) throws IOException {
        long rangeFrom = range.fromInclusive();
        long rangeTo = range.toExclusive();

        if (indexOrder == IndexOrder.DESCENDING) {
            long tmp = rangeFrom;
            rangeFrom = rangeTo;
            rangeTo = tmp;
        }

        TokenScanKey fromKey = new TokenScanKey(tokenId, rangeOf(rangeFrom));
        TokenScanKey toKey = new TokenScanKey(tokenId, rangeOf(rangeTo));
        return index.seek(fromKey, toKey, cursorContext);
    }

    @Override
    public void close() {
        // nothing
    }

    @VisibleForTesting
    static long roundUp(long sizeHint) {
        return ((sizeHint + RANGE_SIZE - 1) / RANGE_SIZE) * RANGE_SIZE;
    }

    private class NativeTokenScan implements TokenScan {
        private final AtomicLong nextStart;
        private final int tokenId;
        private final long max;

        NativeTokenScan(int tokenId, long max) {
            this.tokenId = tokenId;
            this.max = max;
            nextStart = new AtomicLong(0);
        }

        @Override
        public IndexProgressor initialize(
                IndexProgressor.EntityTokenClient client, IndexOrder indexOrder, CursorContext cursorContext) {
            return init(client, Long.MIN_VALUE, Long.MAX_VALUE, indexOrder, cursorContext);
        }

        @Override
        public IndexProgressor initializeBatch(
                IndexProgressor.EntityTokenClient client, int sizeHint, CursorContext cursorContext) {
            if (sizeHint == 0) {
                return IndexProgressor.EMPTY;
            }
            long size = roundUp(sizeHint);
            long start = nextStart.getAndAdd(size);
            long stop = Math.min(start + size, max);
            if (start >= max) {
                return IndexProgressor.EMPTY;
            }
            return init(client, start, stop, IndexOrder.NONE, cursorContext);
        }

        private IndexProgressor init(
                IndexProgressor.EntityTokenClient client,
                long start,
                long stop,
                IndexOrder indexOrder,
                CursorContext cursorContext) {
            Seeker<TokenScanKey, TokenScanValue> cursor;
            EntityRange range = new EntityRange(start, stop);
            try {
                cursor = seekerForToken(range, tokenId, indexOrder, cursorContext);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            return new TokenScanValueIndexProgressor(cursor, client, indexOrder, range);
        }
    }

    private class NativePartitionedTokenScan implements PartitionedTokenScan {
        private final EntityRange range = EntityRange.FULL;
        private final List<TokenScanKey> partitionEdges;
        private final AtomicInteger nextFrom = new AtomicInteger();

        NativePartitionedTokenScan(int desiredNumberOfPartitions, CursorContext cursorContext, TokenPredicate query)
                throws IOException {
            Preconditions.requirePositive(desiredNumberOfPartitions);
            final var tokenId = query.tokenId();
            final var fromInclusive = new TokenScanKey(tokenId, rangeOf(range.fromInclusive()));
            final var toExclusive = new TokenScanKey(tokenId, rangeOf(range.toExclusive()));
            partitionEdges =
                    index.partitionedSeek(fromInclusive, toExclusive, desiredNumberOfPartitions, cursorContext);
        }

        NativePartitionedTokenScan(NativePartitionedTokenScan leadingPartition, TokenPredicate query) {
            final var tokenId = query.tokenId();
            final var leadingEdges = leadingPartition.partitionEdges;
            partitionEdges = new ArrayList<>(leadingEdges.size());
            for (final var leadingEdge : leadingEdges) {
                partitionEdges.add(new TokenScanKey(tokenId, leadingEdge.idRange));
            }
        }

        @Override
        public int getNumberOfPartitions() {
            return partitionEdges.size() - 1;
        }

        @Override
        public IndexProgressor reservePartition(IndexProgressor.EntityTokenClient client, CursorContext cursorContext) {
            final var from = nextFrom.getAndIncrement();
            final var to = from + 1;
            if (to >= partitionEdges.size()) {
                return IndexProgressor.EMPTY;
            }
            try {
                final var fromInclusive = partitionEdges.get(from);
                final var toExclusive = partitionEdges.get(to);
                return new TokenScanValueIndexProgressor(
                        index.seek(fromInclusive, toExclusive, cursorContext), client, IndexOrder.NONE, range);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
