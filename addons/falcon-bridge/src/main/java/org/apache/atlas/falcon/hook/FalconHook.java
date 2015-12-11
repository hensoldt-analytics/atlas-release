/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.atlas.falcon.hook;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.sun.jersey.api.client.ClientResponse;
import org.apache.atlas.ApplicationProperties;
import org.apache.atlas.AtlasClient;
import org.apache.atlas.AtlasServiceException;
import org.apache.atlas.falcon.model.FalconDataModelGenerator;
import org.apache.atlas.falcon.model.FalconDataTypes;
import org.apache.atlas.hive.bridge.HiveMetaStoreBridge;
import org.apache.atlas.hive.model.HiveDataModelGenerator;
import org.apache.atlas.hive.model.HiveDataTypes;
import org.apache.atlas.notification.NotificationInterface;
import org.apache.atlas.notification.NotificationModule;
import org.apache.atlas.typesystem.Referenceable;
import org.apache.atlas.typesystem.json.InstanceSerialization;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang.StringUtils;
import org.apache.falcon.atlas.Util.EventUtil;
import org.apache.falcon.atlas.event.FalconEvent;
import org.apache.falcon.atlas.publisher.FalconEventPublisher;
import org.apache.falcon.entity.CatalogStorage;
import org.apache.falcon.entity.FeedHelper;
import org.apache.falcon.entity.store.ConfigurationStore;
import org.apache.falcon.entity.v0.EntityType;
import org.apache.falcon.entity.v0.feed.CatalogTable;
import org.apache.falcon.entity.v0.feed.Feed;
import org.apache.falcon.entity.v0.process.Cluster;
import org.apache.falcon.entity.v0.process.Input;
import org.apache.falcon.entity.v0.process.Output;
import org.apache.falcon.entity.v0.process.Process;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.security.UserGroupInformation;
import org.codehaus.jettison.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Falcon hook sends lineage information to the Atlas Service.
 */
public class FalconHook extends FalconEventPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(FalconHook.class);

    public static final String CONF_PREFIX = "atlas.hook.falcon.";
    private static final String MIN_THREADS = CONF_PREFIX + "minThreads";
    private static final String MAX_THREADS = CONF_PREFIX + "maxThreads";
    private static final String KEEP_ALIVE_TIME = CONF_PREFIX + "keepAliveTime";
    public static final String QUEUE_SIZE = CONF_PREFIX + "queueSize";
    public static final String CONF_SYNC = CONF_PREFIX + "synchronous";

    public static final String HOOK_NUM_RETRIES = CONF_PREFIX + "numRetries";

    public static final String ATLAS_ENDPOINT = "atlas.rest.address";

    private static  AtlasClient atlasClient;

    // wait time determines how long we wait before we exit the jvm on
    // shutdown. Pending requests after that will not be sent.
    private static final int WAIT_TIME = 3;
    private static ExecutorService executor;

    private static final int minThreadsDefault = 5;
    private static final int maxThreadsDefault = 5;
    private static final long keepAliveTimeDefault = 10;
    private static final int queueSizeDefault = 10000;

    private static Configuration atlasProperties;
    @Inject
    private static NotificationInterface notifInterface;

    public static boolean typesRegistered = false;

    private static boolean sync;

    private static ConfigurationStore STORE;

    static {
        try {
            atlasProperties = ApplicationProperties.get();

            // initialize the async facility to process hook calls. We don't
            // want to do this inline since it adds plenty of overhead for the query.
            int minThreads = atlasProperties.getInt(MIN_THREADS, minThreadsDefault);
            int maxThreads = atlasProperties.getInt(MAX_THREADS, maxThreadsDefault);
            long keepAliveTime = atlasProperties.getLong(KEEP_ALIVE_TIME, keepAliveTimeDefault);
            int queueSize = atlasProperties.getInt(QUEUE_SIZE, queueSizeDefault);
            sync = atlasProperties.getBoolean(CONF_SYNC, false);

            executor = new ThreadPoolExecutor(minThreads, maxThreads, keepAliveTime, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<Runnable>(queueSize),
                    new ThreadFactoryBuilder().setNameFormat("Atlas Logger %d").build());

            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    try {
                        executor.shutdown();
                        executor.awaitTermination(WAIT_TIME, TimeUnit.SECONDS);
                        executor = null;
                    } catch (InterruptedException ie) {
                        LOG.info("Interrupt received in shutdown.");
                    }
                    // shutdown client
                }
            });
            atlasClient = new AtlasClient(atlasProperties.getString(ATLAS_ENDPOINT),
                    EventUtil.getUgi(), EventUtil.getUgi().getShortUserName());

            STORE = ConfigurationStore.get();
        } catch (Exception e) {
            LOG.info("Caught exception initializing the falcon hook.", e);
        }

        Injector injector = Guice.createInjector(new NotificationModule());
        notifInterface = injector.getInstance(NotificationInterface.class);

        LOG.info("Created Atlas Hook for Falcon");
    }

    @Override
    public void publish(final Data data) throws Exception {
        final FalconEvent event = data.getEvent();
        if (sync) {
            fireAndForget(event);
        } else {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        fireAndForget(event);
                    } catch (Throwable e) {
                        LOG.info("Atlas hook failed", e);
                    }
                }
            });
        }
    }

    private void fireAndForget(FalconEvent event) throws Exception {
        LOG.info("Entered Atlas hook for Falcon hook operation {}", event.getOperation());

        if (!typesRegistered) {
            registerFalconDataModel();
            typesRegistered = true;
        }

        notifyEntity(createEntities(event));
    }

    private List<Referenceable> createEntities(FalconEvent event) throws Exception {
        switch (event.getOperation()) {
            case ADD_PROCESS:
                return createProcessInstance((Process) event.getEntity(), event.getUser(), event.getTimestamp());
        }

        return null;
    }

    private void notifyEntity(Collection<Referenceable> entities) {
        if (entities == null || entities.size() == 0) {
            return;
        }
        JSONArray entitiesArray = new JSONArray();
        for (Referenceable entity : entities) {
            String entityJson = InstanceSerialization.toJson(entity, true);
            entitiesArray.put(entityJson);
        }
        notifyEntity(entitiesArray);
    }


    /**
     * Notify atlas of the entity through message. The entity can be a complex entity with reference to other entities.
     * De-duping of entities is done on server side depending on the unique attribute on the
     *
     * @param entities entitiies to add
     */
    private void notifyEntity(JSONArray entities) {
        int maxRetries = atlasProperties.getInt(HOOK_NUM_RETRIES, 3);
        String message = entities.toString();

        int numRetries = 0;
        while (true) {
            try {
                notifInterface.send(NotificationInterface.NotificationType.HOOK, message);
                return;
            } catch (Exception e) {
                numRetries++;
                if (numRetries < maxRetries) {
                    LOG.debug("Failed to notify atlas for entity {}. Retrying", message, e);
                } else {
                    LOG.error("Failed to notify atlas for entity {} after {} retries. Quitting", message,
                            maxRetries, e);
                }
            }
        }
    }


    /**
     +     * Creates process entity
     +     *
     +     * @param event process entity event
     +     * @return process instance reference
     +     */
    public List<Referenceable> createProcessInstance(Process process, String user, long timestamp) throws Exception {
        LOG.info("Creating process Instance : {}", process.getName());

        // The requirement is for each cluster, create a process entity with name
        // clustername.processname
        List<Referenceable> entities = new ArrayList<>();

        if (process.getClusters() != null) {

            for (Cluster processCluster : process.getClusters().getClusters()) {
                org.apache.falcon.entity.v0.cluster.Cluster cluster = STORE.get(EntityType.CLUSTER, processCluster.getName());

                List<Referenceable> inputs = new ArrayList<>();
                if (process.getInputs() != null) {
                    for (Input input : process.getInputs().getInputs()) {
                        List<Referenceable> clusterInputs = getInputOutputEntity(cluster, input.getFeed());
                        entities.addAll(clusterInputs);
                        inputs.add(clusterInputs.get(clusterInputs.size() -1 ));
                    }
                }

                List<Referenceable> outputs = new ArrayList<>();
                if (process.getOutputs() != null) {
                    for (Output output : process.getOutputs().getOutputs()) {
                        List<Referenceable> clusterOutputs = getInputOutputEntity(cluster, output.getFeed());
                        entities.addAll(clusterOutputs);
                        outputs.add(clusterOutputs.get(clusterOutputs.size() -1 ));
                    }
                }

                if (!inputs.isEmpty() || !outputs.isEmpty()) {
                    Referenceable processEntity = new Referenceable(FalconDataTypes.FALCON_PROCESS_ENTITY.getName());
                    processEntity.set(FalconDataModelGenerator.NAME, String.format("%s@%s", process.getName(),
                            cluster.getName()));
                    processEntity.set(FalconDataModelGenerator.PROCESS_NAME, process.getName());

                    processEntity.set(FalconDataModelGenerator.TIMESTAMP, timestamp);
                    if (!inputs.isEmpty()) {
                        processEntity.set(FalconDataModelGenerator.INPUTS, inputs);
                    }
                    if (!outputs.isEmpty()) {
                        processEntity.set(FalconDataModelGenerator.OUTPUTS, outputs);
                    }
                    processEntity.set(FalconDataModelGenerator.USER, user);

                    if (StringUtils.isNotEmpty(process.getTags())) {
                        processEntity.set(FalconDataModelGenerator.TAGS,
                                EventUtil.convertKeyValueStringToMap(process.getTags()));
                    }
                    entities.add(processEntity);
                }

            }
        }

        return entities;
    }

    private List<Referenceable> getInputOutputEntity(org.apache.falcon.entity.v0.cluster.Cluster cluster, String feedName) throws Exception {
        Feed feed = STORE.get(EntityType.FEED, feedName);
        org.apache.falcon.entity.v0.feed.Cluster feedCluster = FeedHelper.getCluster(feed, cluster.getName());

        final CatalogTable table = getTable(feedCluster, feed);
        if (table != null) {
            CatalogStorage storage = new CatalogStorage(cluster, table);
            return createHiveTableInstance(cluster.getName(), storage.getDatabase().toLowerCase(),
                    storage.getTable().toLowerCase());
        }

        return null;
    }

    private CatalogTable getTable(org.apache.falcon.entity.v0.feed.Cluster cluster, Feed feed) {
        // check if table is overridden in cluster
        if (cluster.getTable() != null) {
            return cluster.getTable();
        }

        return feed.getTable();
    }

    private Referenceable createHiveDatabaseInstance(String clusterName, String dbName)
            throws Exception {
        Referenceable dbRef = new Referenceable(HiveDataTypes.HIVE_DB.getName());
        dbRef.set(HiveDataModelGenerator.CLUSTER_NAME, clusterName);
        dbRef.set(HiveDataModelGenerator.NAME, dbName);
        dbRef.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME,
                HiveMetaStoreBridge.getDBQualifiedName(clusterName, dbName));
        return dbRef;
    }

    private List<Referenceable> createHiveTableInstance(String clusterName, String dbName, String tableName) throws Exception {
        List<Referenceable> entities = new ArrayList<>();
        Referenceable dbRef = createHiveDatabaseInstance(clusterName, dbName);
        entities.add(dbRef);

        Referenceable tableRef = new Referenceable(HiveDataTypes.HIVE_TABLE.getName());
        tableRef.set(HiveDataModelGenerator.NAME,
                HiveMetaStoreBridge.getTableQualifiedName(clusterName, dbName, tableName));
        tableRef.set(HiveDataModelGenerator.TABLE_NAME, tableName);
        tableRef.set(HiveDataModelGenerator.DB, dbRef);
        entities.add(tableRef);

        return entities;
    }

    public synchronized void registerFalconDataModel() throws Exception {
        if (isDataModelAlreadyRegistered()) {
            LOG.info("Falcon data model is already registered!");
            return;
        }

        HiveMetaStoreBridge hiveMetaStoreBridge = new HiveMetaStoreBridge(new HiveConf(), atlasProperties,
                UserGroupInformation.getCurrentUser().getShortUserName(), UserGroupInformation.getCurrentUser());
        hiveMetaStoreBridge.registerHiveDataModel();

        FalconDataModelGenerator dataModelGenerator = new FalconDataModelGenerator();
        LOG.info("Registering Falcon data model");
        atlasClient.createType(dataModelGenerator.getModelAsJson());
    }

    private boolean isDataModelAlreadyRegistered() throws Exception {
        try {
            atlasClient.getType(FalconDataTypes.FALCON_PROCESS_ENTITY.getName());
            LOG.info("Hive data model is already registered!");
            return true;
        } catch(AtlasServiceException ase) {
            if (ase.getStatus() == ClientResponse.Status.NOT_FOUND) {
                return false;
            }
            throw ase;
        }
    }
}

