/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.atlas;

import org.apache.atlas.metrics.Metrics;
import org.apache.atlas.metrics.Metrics.MetricRecorder;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.store.DeleteType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RequestContextV1 {
    private static final Logger METRICS = LoggerFactory.getLogger("METRICS");

    private static final ThreadLocal<RequestContextV1> CURRENT_CONTEXT = new ThreadLocal<>();
    private static final Set<RequestContextV1>         ACTIVE_REQUESTS = new HashSet<>();

    private final long                           requestTime         = System.currentTimeMillis();
    private final Map<String, AtlasEntityHeader> updatedEntities     = new HashMap<>();
    private final Map<String, AtlasEntityHeader> deletedEntities     = new HashMap<>();
    private final Map<String, AtlasEntity>       entityCacheV2       = new HashMap<>();
    private final Metrics                        metrics             = new Metrics();
    private       List<EntityGuidPair>           entityGuidInRequest = null;

    private String     user;
    private DeleteType deleteType = DeleteType.DEFAULT;

    private RequestContextV1() {
    }

    //To handle gets from background threads where createContext() is not called
    //createContext called for every request in the filter
    public static RequestContextV1 get() {
        RequestContextV1 ret = CURRENT_CONTEXT.get();

        if (ret == null) {
            ret = new RequestContextV1();
            CURRENT_CONTEXT.set(ret);

            synchronized (ACTIVE_REQUESTS) {
                ACTIVE_REQUESTS.add(ret);
            }
        }

        return ret;
    }

    public static void clear() {
        RequestContextV1 instance = CURRENT_CONTEXT.get();

        if (instance != null) {
            instance.clearCache();

            synchronized (ACTIVE_REQUESTS) {
                ACTIVE_REQUESTS.remove(instance);
            }
        }

        CURRENT_CONTEXT.remove();
    }

    public void clearCache() {
        this.updatedEntities.clear();
        this.deletedEntities.clear();
        this.entityCacheV2.clear();

        if (this.entityGuidInRequest != null) {
            this.entityGuidInRequest.clear();
        }
 
        if (METRICS.isDebugEnabled() && !metrics.isEmpty()) {
            METRICS.debug(metrics.toString());
        }

        metrics.clear();
    }

    public static int getActiveRequestsCount() {
        return ACTIVE_REQUESTS.size();
    }

    public static long earliestActiveRequestTime() {
        long ret = System.currentTimeMillis();

        synchronized (ACTIVE_REQUESTS) {
            for (RequestContextV1 context : ACTIVE_REQUESTS) {
                if (ret > context.getRequestTime()) {
                    ret = context.getRequestTime();
                }
            }
        }

        return ret;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public DeleteType getDeleteType() { return deleteType; }

    public void setDeleteType(DeleteType deleteType) { this.deleteType = (deleteType == null) ? DeleteType.DEFAULT : deleteType; }

    public void recordEntityUpdate(AtlasEntityHeader entity) {
        if (entity != null && entity.getGuid() != null) {
            updatedEntities.put(entity.getGuid(), entity);
        }
    }

    public void recordEntityDelete(AtlasEntityHeader entity) {
        if (entity != null && entity.getGuid() != null) {
            deletedEntities.put(entity.getGuid(), entity);
        }
    }

    /**
     * Adds the specified instance to the cache
     *
     */
    public void cache(AtlasEntity entity) {
        if (entity != null && entity.getGuid() != null) {
            entityCacheV2.put(entity.getGuid(), entity);
        }
    }

    public Collection<AtlasEntityHeader> getUpdatedEntities() {
        return updatedEntities.values();
    }

    public Collection<AtlasEntityHeader> getDeletedEntities() {
        return deletedEntities.values();
    }

    /**
     * Checks if an instance with the given guid is in the cache for this request.  Either returns the instance
     * or null if it is not in the cache.
     *
     * @param guid the guid to find
     * @return Either the instance or null if it is not in the cache.
     */
    public AtlasEntity getInstanceV2(String guid) {
        return entityCacheV2.get(guid);
    }

    public long getRequestTime() {
        return requestTime;
    }

    public boolean isUpdatedEntity(String guid) {
        return updatedEntities.containsKey(guid);
    }

    public boolean isDeletedEntity(String guid) {
        return deletedEntities.containsKey(guid);
    }

    public MetricRecorder startMetricRecord(String name) { return metrics.getMetricRecorder(name); }

    public void endMetricRecord(MetricRecorder recorder) { metrics.recordMetric(recorder); }

    public static Metrics getMetrics() {
        return get().metrics;
    }

    public void recordEntityGuidUpdate(AtlasEntity entity, String guidInRequest) {
        if (entityGuidInRequest == null) {
            entityGuidInRequest = new ArrayList<>();
        }

        entityGuidInRequest.add(new EntityGuidPair(entity, guidInRequest));
    }

    public void resetEntityGuidUpdates() {
        if (entityGuidInRequest != null) {
            for (EntityGuidPair entityGuidPair : entityGuidInRequest) {
                entityGuidPair.resetEntityGuid();
            }
        }
    }

    public class EntityGuidPair {
        private final AtlasEntity entity;
        private final String      guid;

        public EntityGuidPair(AtlasEntity entity, String guid) {
            this.entity = entity;
            this.guid   = guid;
        }

        public void resetEntityGuid() {
            entity.setGuid(guid);
        }
    }
}
