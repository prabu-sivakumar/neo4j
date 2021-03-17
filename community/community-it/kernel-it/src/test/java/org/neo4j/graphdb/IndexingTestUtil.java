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
package org.neo4j.graphdb;

import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.neo4j.common.EntityType;
import org.neo4j.graphdb.schema.IndexDefinition;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.kernel.impl.coreapi.schema.IndexDefinitionImpl;

import static org.assertj.core.api.Assertions.assertThat;

public class IndexingTestUtil
{
    public static void assertOnlyDefaultTokenIndexesExists( GraphDatabaseService db )
    {
        try ( var tx = db.beginTx() )
        {
            var schemas = StreamSupport.stream( tx.schema().getIndexes().spliterator(), false ).map( IndexDefinitionImpl.class::cast )
                                       .map( IndexDefinitionImpl::getIndexReference ).map( IndexDescriptor::schema )
                                       .collect( Collectors.toList() );
            assertThat( schemas ).allMatch( SchemaDescriptor::isAnyTokenSchemaDescriptor );
            assertThat( schemas.stream().map( SchemaDescriptor::entityType ) ).containsExactlyInAnyOrder( EntityType.NODE, EntityType.RELATIONSHIP );
        }
    }

    public static void dropAllIndexes( GraphDatabaseService db )
    {
        try ( var tx = db.beginTx() )
        {
            tx.schema().getIndexes().forEach( IndexDefinition::drop );
            tx.commit();
        }
    }
}