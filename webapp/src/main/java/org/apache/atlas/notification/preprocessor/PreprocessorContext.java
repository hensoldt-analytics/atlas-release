/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.notification.preprocessor;

import org.apache.atlas.kafka.AtlasKafkaMessage;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntitiesWithExtInfo;
import org.apache.atlas.model.instance.AtlasObjectId;
import org.apache.atlas.notification.hook.HookNotification;
import org.apache.atlas.notification.hook.HookNotification.HookNotificationMessage;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.apache.atlas.notification.preprocessor.EntityPreprocessor.QNAME_SEP_CLUSTER_NAME;
import static org.apache.atlas.notification.preprocessor.EntityPreprocessor.QNAME_SEP_ENTITY_NAME;


public class PreprocessorContext {
    private static final Logger LOG = LoggerFactory.getLogger(PreprocessorContext.class);

    public enum PreprocessAction { NONE, IGNORE, PRUNE }

    private final AtlasKafkaMessage<HookNotificationMessage> kafkaMessage;
    private final AtlasEntitiesWithExtInfo                   entitiesWithExtInfo;
    private final List<Pattern>                              hiveTablesToIgnore;
    private final List<Pattern>                              hiveTablesToPrune;
    private final Map<String, PreprocessAction>              hiveTablesCache;
    private final List<String>                               hiveDummyDatabasesToIgnore;
    private final List<String>                               hiveDummyTablesToIgnore;
    private final List<String>                               hiveTablePrefixesToIgnore;
    private final boolean                                    isHivePreProcessEnabled;
    private final Set<String>                                ignoredEntities        = new HashSet<>();
    private final Set<String>                                prunedEntities         = new HashSet<>();
    private final Set<String>                                referredEntitiesToMove = new HashSet<>();

    public PreprocessorContext(AtlasKafkaMessage<HookNotificationMessage> kafkaMessage, List<Pattern> hiveTablesToIgnore, List<Pattern> hiveTablesToPrune, Map<String, PreprocessAction> hiveTablesCache, List<String> hiveDummyDatabasesToIgnore, List<String> hiveDummyTablesToIgnore, List<String> hiveTablePrefixesToIgnore) {
        this.kafkaMessage                  = kafkaMessage;
        this.hiveTablesToIgnore            = hiveTablesToIgnore;
        this.hiveTablesToPrune             = hiveTablesToPrune;
        this.hiveTablesCache               = hiveTablesCache;
        this.hiveDummyDatabasesToIgnore    = hiveDummyDatabasesToIgnore;
        this.hiveDummyTablesToIgnore       = hiveDummyTablesToIgnore;
        this.hiveTablePrefixesToIgnore     = hiveTablePrefixesToIgnore;

        final HookNotificationMessage  message = kafkaMessage.getMessage();

        switch (message.getType()) {
            case ENTITY_CREATE_V2:
                entitiesWithExtInfo = ((HookNotification.EntityCreateRequestV2) message).getEntities();
            break;

            case ENTITY_FULL_UPDATE_V2:
                entitiesWithExtInfo = ((HookNotification.EntityUpdateRequestV2) message).getEntities();
            break;

            default:
                entitiesWithExtInfo = null;
            break;
        }

        this.isHivePreProcessEnabled = !hiveTablesToIgnore.isEmpty() || !hiveTablesToPrune.isEmpty() || !hiveDummyDatabasesToIgnore.isEmpty() || !hiveDummyTablesToIgnore.isEmpty() || !hiveTablePrefixesToIgnore.isEmpty();
    }

    public AtlasKafkaMessage<HookNotificationMessage> getKafkaMessage() {
        return kafkaMessage;
    }

    public long getKafkaMessageOffset() {
        return kafkaMessage.getOffset();
    }

    public int getKafkaPartition() {
        return kafkaMessage.getPartition();
    }

    public boolean isHivePreprocessEnabled() {
        return isHivePreProcessEnabled;
    }

    public List<AtlasEntity> getEntities() {
        return entitiesWithExtInfo != null ? entitiesWithExtInfo.getEntities() : null;
    }

    public Map<String, AtlasEntity> getReferredEntities() {
        return entitiesWithExtInfo != null ? entitiesWithExtInfo.getReferredEntities() : null;
    }

    public AtlasEntity getEntity(String guid) {
        return entitiesWithExtInfo != null && guid != null ? entitiesWithExtInfo.getEntity(guid) : null;
    }

    public Set<String> getIgnoredEntities() { return ignoredEntities; }

    public Set<String> getPrunedEntities() { return prunedEntities; }

    public Set<String> getReferredEntitiesToMove() { return referredEntitiesToMove; }

    public PreprocessAction getPreprocessActionForHiveDb(String dbName) {
        PreprocessAction ret = PreprocessAction.NONE;

        if (dbName != null) {
            for (String dummyDbName : hiveDummyDatabasesToIgnore) {
                if (StringUtils.equalsIgnoreCase(dbName, dummyDbName)) {
                    ret = PreprocessAction.IGNORE;

                    break;
                }
            }
        }

        return ret;
    }

    public PreprocessAction getPreprocessActionForHiveTable(String qualifiedName) {
        PreprocessAction ret = PreprocessAction.NONE;

        if (qualifiedName != null) {
            if (CollectionUtils.isNotEmpty(hiveTablesToIgnore) || CollectionUtils.isNotEmpty(hiveTablesToPrune)) {
                ret = hiveTablesCache.get(qualifiedName);

                if (ret == null) {
                    if (isMatch(qualifiedName, hiveTablesToIgnore)) {
                        ret = PreprocessAction.IGNORE;
                    } else if (isMatch(qualifiedName, hiveTablesToPrune)) {
                        ret = PreprocessAction.PRUNE;
                    } else {
                        ret = PreprocessAction.NONE;
                    }

                    hiveTablesCache.put(qualifiedName, ret);
                }
            }

            if (ret != PreprocessAction.IGNORE && (CollectionUtils.isNotEmpty(hiveDummyTablesToIgnore) || CollectionUtils.isNotEmpty(hiveTablePrefixesToIgnore))) {
                String tblName = getHiveTableNameFromQualifiedName(qualifiedName);

                if (tblName != null) {
                    for (String dummyTblName : hiveDummyTablesToIgnore) {
                        if (StringUtils.equalsIgnoreCase(tblName, dummyTblName)) {
                            ret = PreprocessAction.IGNORE;

                            break;
                        }
                    }

                    if (ret != PreprocessAction.IGNORE) {
                        for (String tableNamePrefix : hiveTablePrefixesToIgnore) {
                            if (StringUtils.startsWithIgnoreCase(tblName, tableNamePrefix)) {
                                ret = PreprocessAction.IGNORE;

                                break;
                            }
                        }
                    }
                }

                if (ret != PreprocessAction.IGNORE && CollectionUtils.isNotEmpty(hiveDummyDatabasesToIgnore)) {
                    String dbName = getHiveDbNameFromQualifiedName(qualifiedName);

                    if (dbName != null) {
                        for (String dummyDbName : hiveDummyDatabasesToIgnore) {
                            if (StringUtils.equalsIgnoreCase(dbName, dummyDbName)) {
                                ret = PreprocessAction.IGNORE;

                                break;
                            }
                        }
                    }
                }
            }
        }

        return ret;
    }

    public boolean isIgnoredEntity(String guid) {
        return guid != null ? ignoredEntities.contains(guid) : false;
    }

    public boolean isPrunedEntity(String guid) {
        return guid != null ? prunedEntities.contains(guid) : false;
    }

    public void addToIgnoredEntities(AtlasEntity entity) {
        if (!ignoredEntities.contains(entity.getGuid())) {
            ignoredEntities.add(entity.getGuid());

            LOG.info("ignored entity: typeName={}, qualifiedName={}. topic-offset={}, partition={}", entity.getTypeName(), EntityPreprocessor.getQualifiedName(entity), getKafkaMessageOffset(), getKafkaPartition());
        }
    }

    public void addToPrunedEntities(AtlasEntity entity) {
        if (!prunedEntities.contains(entity.getGuid())) {
            prunedEntities.add(entity.getGuid());

            LOG.info("pruned entity: typeName={}, qualifiedName={} topic-offset={}, partition={}", entity.getTypeName(), EntityPreprocessor.getQualifiedName(entity), getKafkaMessageOffset(), getKafkaPartition());
        }
    }

    public void addToIgnoredEntities(String guid) {
        if (guid != null) {
            ignoredEntities.add(guid);
        }
    }

    public void addToPrunedEntities(String guid) {
        if (guid != null) {
            prunedEntities.add(guid);
        }
    }

    public void addToReferredEntitiesToMove(String guid) {
        if (guid != null) {
            referredEntitiesToMove.add(guid);
        }
    }

    public void addToIgnoredEntities(Object obj) {
        collectGuids(obj, ignoredEntities);
    }

    public void addToPrunedEntities(Object obj) {
        collectGuids(obj, prunedEntities);
    }

    public void moveRegisteredReferredEntities() {
        List<AtlasEntity>        entities         = getEntities();
        Map<String, AtlasEntity> referredEntities = getReferredEntities();

        if (entities != null && referredEntities != null && !referredEntitiesToMove.isEmpty()) {
            AtlasEntity firstEntity = entities.isEmpty() ? null : entities.get(0);

            for (String guid : referredEntitiesToMove) {
                AtlasEntity entity = referredEntities.remove(guid);

                if (entity != null) {
                    entities.add(entity);

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("moved referred entity: typeName={}, qualifiedName={}. topic-offset={}, partition={}", entity.getTypeName(), EntityPreprocessor.getQualifiedName(entity), kafkaMessage.getOffset(), kafkaMessage.getPartition());
                    }
                }
            }

            if (firstEntity != null) {
                LOG.info("moved {} referred-entities to end of entities-list (firstEntity:typeName={}, qualifiedName={}). topic-offset={}, partition={}", referredEntitiesToMove.size(), firstEntity.getTypeName(), EntityPreprocessor.getQualifiedName(firstEntity), kafkaMessage.getOffset(), kafkaMessage.getPartition());
            } else {
                LOG.info("moved {} referred-entities to entities-list. topic-offset={}, partition={}", referredEntitiesToMove.size(), kafkaMessage.getOffset(), kafkaMessage.getPartition());
            }

            referredEntitiesToMove.clear();
        }
    }

    public String getHiveTableNameFromQualifiedName(String qualifiedName) {
        String ret      = null;
        int    idxStart = qualifiedName.indexOf(QNAME_SEP_ENTITY_NAME) + 1;

        if (idxStart != 0 && qualifiedName.length() > idxStart) {
            int idxEnd = qualifiedName.indexOf(QNAME_SEP_CLUSTER_NAME, idxStart);

            if (idxEnd != -1) {
                ret = qualifiedName.substring(idxStart, idxEnd);
            }
        }

        return ret;
    }

    public String getHiveDbNameFromQualifiedName(String qualifiedName) {
        String ret    = null;
        int    idxEnd = qualifiedName.indexOf(QNAME_SEP_ENTITY_NAME); // db.table@cluster, db.table.column@cluster

        if (idxEnd == -1) {
            idxEnd = qualifiedName.indexOf(QNAME_SEP_CLUSTER_NAME); // db@cluster
        }

        if (idxEnd != -1) {
            ret = qualifiedName.substring(0, idxEnd);
        }

        return ret;
    }

    public String getTypeName(Object obj) {
        Object ret = null;

        if (obj instanceof AtlasObjectId) {
            ret = ((AtlasObjectId) obj).getTypeName();
        } else if (obj instanceof Map) {
            ret = ((Map) obj).get(AtlasObjectId.KEY_TYPENAME);
        } else if (obj instanceof AtlasEntity) {
            ret = ((AtlasEntity) obj).getTypeName();
        } else if (obj instanceof AtlasEntity.AtlasEntityWithExtInfo) {
            ret = ((AtlasEntity.AtlasEntityWithExtInfo) obj).getEntity().getTypeName();
        }

        return ret != null ? ret.toString() : null;
    }

    public String getGuid(Object obj) {
        Object ret = null;

        if (obj instanceof AtlasObjectId) {
            ret = ((AtlasObjectId) obj).getGuid();
        } else if (obj instanceof Map) {
            ret = ((Map) obj).get(AtlasObjectId.KEY_GUID);
        } else if (obj instanceof AtlasEntity) {
            ret = ((AtlasEntity) obj).getGuid();
        } else if (obj instanceof AtlasEntity.AtlasEntityWithExtInfo) {
            ret = ((AtlasEntity.AtlasEntityWithExtInfo) obj).getEntity().getGuid();
        }

        return ret != null ? ret.toString() : null;
    }


    private boolean isMatch(String name, List<Pattern> patterns) {
        boolean ret = false;

        for (Pattern p : patterns) {
            if (p.matcher(name).matches()) {
                ret = true;

                break;
            }
        }

        return ret;
    }

    private void collectGuids(Object obj, Set<String> guids) {
        if (obj != null) {
            if (obj instanceof Collection) {
                Collection objList = (Collection) obj;

                for (Object objElem : objList) {
                    collectGuid(objElem, guids);
                }
            } else {
                collectGuid(obj, guids);
            }
        }
    }

    private void collectGuid(Object obj, Set<String> guids) {
        String guid = getGuid(obj);

        if (guid != null) {
            guids.add(guid);
        }
    }
}
