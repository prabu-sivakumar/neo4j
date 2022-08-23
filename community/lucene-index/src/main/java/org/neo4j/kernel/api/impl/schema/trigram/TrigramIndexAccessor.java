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
package org.neo4j.kernel.api.impl.schema.trigram;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.ToLongFunction;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.neo4j.internal.helpers.collection.BoundedIterable;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.api.impl.index.AbstractLuceneIndexAccessor;
import org.neo4j.kernel.api.impl.index.DatabaseIndex;
import org.neo4j.kernel.api.index.IndexEntriesReader;
import org.neo4j.kernel.api.index.IndexUpdater;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.kernel.impl.api.index.IndexUpdateMode;
import org.neo4j.kernel.impl.index.schema.IndexUpdateIgnoreStrategy;
import org.neo4j.values.storable.Value;

class TrigramIndexAccessor extends AbstractLuceneIndexAccessor<ValueIndexReader, DatabaseIndex<ValueIndexReader>> {

    TrigramIndexAccessor(
            DatabaseIndex<ValueIndexReader> luceneIndex,
            IndexDescriptor descriptor,
            IndexUpdateIgnoreStrategy ignoreStrategy) {
        super(luceneIndex, descriptor, ignoreStrategy);
    }

    @Override
    protected IndexUpdater getIndexUpdater(IndexUpdateMode mode) {
        return new Updater(mode.requiresIdempotency(), mode.requiresRefresh());
    }

    @Override
    public BoundedIterable<Long> newAllEntriesValueReader(
            long fromIdInclusive, long toIdExclusive, CursorContext cursorContext) {
        return super.newAllEntriesReader(TrigramDocumentStructure::getNodeId, fromIdInclusive, toIdExclusive);
    }

    @Override
    public IndexEntriesReader[] newAllEntriesValueReader(ToLongFunction<Document> entityIdReader, int numPartitions) {
        return super.newAllEntriesValueReader(TrigramDocumentStructure::getNodeId, numPartitions);
    }

    private class Updater extends AbstractLuceneIndexUpdater {

        Updater(boolean idempotent, boolean refresh) {
            super(idempotent, refresh);
        }

        @Override
        protected void addIdempotent(long entityId, Value[] values) {
            try {
                Document document = TrigramDocumentStructure.createLuceneDocument(entityId, values[0]);
                writer.updateOrDeleteDocument(TrigramDocumentStructure.newTermForChangeOrRemove(entityId), document);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        protected void add(long entityId, Value[] values) {
            try {
                Document document = TrigramDocumentStructure.createLuceneDocument(entityId, values[0]);
                writer.nullableAddDocument(document);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        protected void change(long entityId, Value[] values) {
            try {
                Term term = TrigramDocumentStructure.newTermForChangeOrRemove(entityId);
                Document document = TrigramDocumentStructure.createLuceneDocument(entityId, values[0]);
                writer.updateOrDeleteDocument(term, document);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        protected void remove(long entityId) {
            try {
                Term term = TrigramDocumentStructure.newTermForChangeOrRemove(entityId);
                writer.deleteDocuments(term);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
