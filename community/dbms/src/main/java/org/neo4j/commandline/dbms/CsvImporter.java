/*
 * Copyright (c) 2002-2019 "Neo4j,"
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
package org.neo4j.commandline.dbms;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.time.ZoneId;
import java.util.Collection;
import java.util.function.Supplier;

import org.neo4j.commandline.admin.IncorrectUsage;
import org.neo4j.commandline.admin.OutsideWorld;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.internal.batchimport.Configuration;
import org.neo4j.internal.batchimport.input.BadCollector;
import org.neo4j.internal.batchimport.input.Collector;
import org.neo4j.internal.batchimport.input.IdType;
import org.neo4j.internal.batchimport.input.csv.CsvInput;
import org.neo4j.internal.helpers.Args;
import org.neo4j.io.ByteUnit;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.tooling.ImportTool;

import static java.nio.charset.Charset.defaultCharset;
import static org.neo4j.internal.batchimport.input.Collectors.badCollector;
import static org.neo4j.internal.batchimport.input.Collectors.collect;
import static org.neo4j.internal.batchimport.input.csv.DataFactories.defaultFormatNodeFileHeader;
import static org.neo4j.internal.batchimport.input.csv.DataFactories.defaultFormatRelationshipFileHeader;
import static org.neo4j.kernel.impl.util.Converters.withDefault;
import static org.neo4j.tooling.ImportTool.csvConfiguration;
import static org.neo4j.tooling.ImportTool.extractInputFiles;
import static org.neo4j.tooling.ImportTool.importConfiguration;
import static org.neo4j.tooling.ImportTool.nodeData;
import static org.neo4j.tooling.ImportTool.relationshipData;
import static org.neo4j.tooling.ImportTool.validateInputFiles;

class CsvImporter implements Importer
{
    private final Collection<Args.Option<File[]>> nodesFiles;
    private final Collection<Args.Option<File[]>> relationshipsFiles;
    private final IdType idType;
    private final Charset inputEncoding;
    private final Config databaseConfig;
    private final Args args;
    private final OutsideWorld outsideWorld;
    private final DatabaseLayout databaseLayout;
    private final String reportFileName;
    private final boolean ignoreBadRelationships;
    private final boolean ignoreDuplicateNodes;
    private final boolean ignoreExtraColumns;
    private final Boolean highIO;
    private final boolean normalizeTypes;

    CsvImporter( Args args, Config databaseConfig, OutsideWorld outsideWorld, DatabaseLayout databaseLayout ) throws IncorrectUsage
    {
        this.args = args;
        this.outsideWorld = outsideWorld;
        this.databaseLayout = databaseLayout;
        nodesFiles = extractInputFiles( args, "nodes", outsideWorld.errorStream() );
        relationshipsFiles = extractInputFiles( args, "relationships", outsideWorld.errorStream() );
        reportFileName = args.interpretOption( "report-file", withDefault( ImportCommand.DEFAULT_REPORT_FILE_NAME ), s -> s );
        ignoreExtraColumns = args.getBoolean( "ignore-extra-columns", false );
        ignoreDuplicateNodes = args.getBoolean( "ignore-duplicate-nodes", false );
        ignoreBadRelationships = args.getBoolean( "ignore-missing-nodes", false );
        try
        {
            validateInputFiles( nodesFiles, relationshipsFiles );
        }
        catch ( IllegalArgumentException e )
        {
            throw new IncorrectUsage( e.getMessage() );
        }

        idType = args.interpretOption( "id-type", withDefault( IdType.STRING ),
                from -> IdType.valueOf( from.toUpperCase() ) );
        inputEncoding = Charset.forName( args.get( "input-encoding", defaultCharset().name() ) );
        highIO = args.getBoolean( "high-io", null, true ); // intentionally left as null if not specified
        normalizeTypes = args.getBoolean( "normalize-types", true );
        this.databaseConfig = databaseConfig;
    }

    @Override
    public void doImport() throws IOException
    {
        FileSystemAbstraction fs = outsideWorld.fileSystem();
        File reportFile = new File( reportFileName );

        OutputStream badOutput = new BufferedOutputStream( fs.openAsOutputStream( reportFile, false ) );
        try ( Collector badCollector = badCollector( badOutput, isIgnoringSomething() ? BadCollector.UNLIMITED_TOLERANCE : 0,
                collect( ignoreBadRelationships, ignoreDuplicateNodes, ignoreExtraColumns ) ) )
        {
            Configuration configuration = importConfiguration( null, false, databaseLayout, highIO );

            // Extract the default time zone from the database configuration
            ZoneId dbTimeZone = databaseConfig.get( GraphDatabaseSettings.db_temporal_timezone );
            Supplier<ZoneId> defaultTimeZone = () -> dbTimeZone;

            CsvInput input = new CsvInput( nodeData( inputEncoding, nodesFiles ), defaultFormatNodeFileHeader( defaultTimeZone, normalizeTypes ),
                    relationshipData( inputEncoding, relationshipsFiles ), defaultFormatRelationshipFileHeader( defaultTimeZone, normalizeTypes ), idType,
                    csvConfiguration( args, false ).toBuilder()
                            .withBufferSize( (int) ByteUnit.mebiBytes( 4 ) )
                            .withTrimStrings( true )
                            .withEmptyQuotedStringsAsNull( false )
                            .withLegacyStyleQuoting( false )
                            .build(),
                    new CsvInput.PrintingMonitor( outsideWorld.outStream() ) );

            ImportTool.doImport( outsideWorld.errorStream(), outsideWorld.errorStream(), outsideWorld.inStream(), databaseLayout, reportFile, fs,
                    nodesFiles, relationshipsFiles, false, input, this.databaseConfig, badCollector, configuration, false );
        }
    }

    private boolean isIgnoringSomething()
    {
        return ignoreBadRelationships || ignoreDuplicateNodes || ignoreExtraColumns;
    }
}
