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
package org.neo4j.server.security.systemgraph;

import static org.neo4j.dbms.database.ComponentVersion.SECURITY_USER_COMPONENT;
import static org.neo4j.dbms.database.KnownSystemComponentVersion.UNKNOWN_VERSION;
import static org.neo4j.server.security.systemgraph.versions.KnownCommunitySecurityComponentVersion.USER_ID;
import static org.neo4j.server.security.systemgraph.versions.KnownCommunitySecurityComponentVersion.USER_LABEL;

import org.neo4j.configuration.Config;
import org.neo4j.dbms.database.AbstractSystemGraphComponent;
import org.neo4j.dbms.database.ComponentVersion;
import org.neo4j.dbms.database.KnownSystemComponentVersions;
import org.neo4j.dbms.database.SystemGraphComponent;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.kernel.api.security.AbstractSecurityLog;
import org.neo4j.internal.kernel.api.security.SecurityContext;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.impl.coreapi.TransactionImpl;
import org.neo4j.logging.Log;
import org.neo4j.logging.LogProvider;
import org.neo4j.server.security.auth.UserRepository;
import org.neo4j.server.security.systemgraph.versions.CommunitySecurityComponentVersion_1_40;
import org.neo4j.server.security.systemgraph.versions.CommunitySecurityComponentVersion_2_41;
import org.neo4j.server.security.systemgraph.versions.CommunitySecurityComponentVersion_3_43D4;
import org.neo4j.server.security.systemgraph.versions.CommunitySecurityComponentVersion_4_50;
import org.neo4j.server.security.systemgraph.versions.KnownCommunitySecurityComponentVersion;
import org.neo4j.server.security.systemgraph.versions.NoCommunitySecurityComponentVersion;
import org.neo4j.util.VisibleForTesting;

/**
 * This component contains the users of the dbms.
 * Each user is represented by a node in the system database with the label :User and properties for username, credentials, passwordChangeRequired and status.
 * The schema is the same in both community and enterprise (even if status is an enterprise-only feature).
 */
public class UserSecurityGraphComponent extends AbstractSystemGraphComponent {
    private final KnownSystemComponentVersions<KnownCommunitySecurityComponentVersion>
            knownUserSecurityComponentVersions =
                    new KnownSystemComponentVersions<>(new NoCommunitySecurityComponentVersion());
    private final Log debugLog;

    public UserSecurityGraphComponent(
            UserRepository initialPasswordRepo,
            Config config,
            LogProvider debugLogProvider,
            AbstractSecurityLog securityLog) {
        super(config);
        this.debugLog = debugLogProvider.getLog(UserSecurityGraphComponent.class);

        KnownCommunitySecurityComponentVersion version1 =
                new CommunitySecurityComponentVersion_1_40(debugLog, securityLog, initialPasswordRepo);
        KnownCommunitySecurityComponentVersion version2 =
                new CommunitySecurityComponentVersion_2_41(debugLog, securityLog, initialPasswordRepo, version1);
        KnownCommunitySecurityComponentVersion version3 =
                new CommunitySecurityComponentVersion_3_43D4(debugLog, securityLog, initialPasswordRepo, version2);
        KnownCommunitySecurityComponentVersion version4 =
                new CommunitySecurityComponentVersion_4_50(debugLog, securityLog, initialPasswordRepo, version3);

        knownUserSecurityComponentVersions.add(version1);
        knownUserSecurityComponentVersions.add(version2);
        knownUserSecurityComponentVersions.add(version3);
        knownUserSecurityComponentVersions.add(version4);
    }

    @Override
    public String componentName() {
        return SECURITY_USER_COMPONENT;
    }

    @Override
    public Status detect(Transaction tx) {
        return knownUserSecurityComponentVersions
                .detectCurrentComponentVersion(tx)
                .getStatus();
    }

    @Override
    public void initializeSystemGraphModel(Transaction tx, GraphDatabaseService systemDb) throws Exception {
        KnownCommunitySecurityComponentVersion componentBeforeInit =
                knownUserSecurityComponentVersions.detectCurrentComponentVersion(tx);
        debugLog.info(String.format(
                "Initializing system graph model for component '%s' with version %d and status %s",
                SECURITY_USER_COMPONENT, componentBeforeInit.version, componentBeforeInit.getStatus()));
        initializeLatestSystemGraph(tx);
        KnownCommunitySecurityComponentVersion componentAfterInit =
                knownUserSecurityComponentVersions.detectCurrentComponentVersion(tx);
        debugLog.info(String.format(
                "After initialization of system graph model component '%s' have version %d and status %s",
                SECURITY_USER_COMPONENT, componentAfterInit.version, componentAfterInit.getStatus()));
    }

    @Override
    public void initializeSystemGraphConstraints(Transaction tx) {
        initializeSystemGraphConstraint(tx, USER_LABEL, "name");
        initializeSystemGraphConstraint(tx, USER_LABEL, USER_ID);
    }

    private void initializeLatestSystemGraph(Transaction tx) throws Exception {
        KnownCommunitySecurityComponentVersion latest = knownUserSecurityComponentVersions.latestComponentVersion();
        debugLog.debug(
                String.format("Latest version of component '%s' is %s", SECURITY_USER_COMPONENT, latest.version));
        latest.setupUsers(tx);
        latest.setVersionProperty(tx, latest.version);
    }

    @VisibleForTesting
    @Override
    public void postInitialization(GraphDatabaseService system, boolean wasInitialized) throws Exception {
        try (Transaction tx = system.beginTx()) {
            KnownCommunitySecurityComponentVersion component =
                    knownUserSecurityComponentVersions.detectCurrentComponentVersion(tx);
            debugLog.info(String.format(
                    "Performing postInitialization step for component '%s' with version %d and status %s",
                    SECURITY_USER_COMPONENT, component.version, component.getStatus()));

            // Do not need to setup initial password when initialized, because that is already done by the
            // initialization code in `setupUsers`
            if (!wasInitialized) {

                debugLog.info(
                        String.format("Updating the initial password in component '%s'", SECURITY_USER_COMPONENT));
                component.updateInitialUserPassword(tx);
                tx.commit();
            }
        }
    }

    @Override
    public void upgradeToCurrent(GraphDatabaseService system) throws Exception {
        KnownCommunitySecurityComponentVersion currentVersion;
        try (TransactionImpl tx = (TransactionImpl) system.beginTx();
                KernelTransaction.Revertable ignore =
                        tx.kernelTransaction().overrideWith(SecurityContext.AUTH_DISABLED)) {
            currentVersion = knownUserSecurityComponentVersions.detectCurrentComponentVersion(tx);
            tx.commit();
        }

        debugLog.debug(String.format(
                "Trying to upgrade component '%s' with version %d and status %s to latest version",
                SECURITY_USER_COMPONENT, currentVersion.version, currentVersion.getStatus()));
        if (currentVersion.version == UNKNOWN_VERSION) {
            debugLog.debug("The current version does not have a security graph, doing a full initialization");
            SystemGraphComponent.executeWithFullAccess(system, this::initializeLatestSystemGraph);
            SystemGraphComponent.executeWithFullAccess(system, this::initializeSystemGraphConstraints);
        } else if (currentVersion.migrationSupported()) {
            debugLog.info("Upgrading security graph to latest version");
            SystemGraphComponent.executeWithFullAccess(system, tx -> knownUserSecurityComponentVersions
                    .latestComponentVersion()
                    .upgradeSecurityGraph(tx, currentVersion.version));
            SystemGraphComponent.executeWithFullAccess(system, tx -> knownUserSecurityComponentVersions
                    .latestComponentVersion()
                    .upgradeSecurityGraphSchema(tx, currentVersion.version));
        } else {
            throw currentVersion.unsupported();
        }
    }

    public KnownCommunitySecurityComponentVersion findSecurityGraphComponentVersion(ComponentVersion version) {
        return knownUserSecurityComponentVersions.findComponentVersion(version);
    }
}
