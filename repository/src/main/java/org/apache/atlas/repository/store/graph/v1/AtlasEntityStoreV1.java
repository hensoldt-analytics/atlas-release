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
package org.apache.atlas.repository.store.graph.v1;


import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.GraphTransactionInterceptor;
import org.apache.atlas.RequestContextV1;
import org.apache.atlas.annotation.GraphTransaction;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.metrics.Metrics.MetricRecorder;
import org.apache.atlas.model.instance.AtlasCheckStateRequest;
import org.apache.atlas.model.instance.AtlasCheckStateResult;
import org.apache.atlas.model.instance.AtlasClassification;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntitiesWithExtInfo;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntityWithExtInfo;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.AtlasObjectId;
import org.apache.atlas.model.instance.EntityMutationResponse;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.repository.store.graph.AtlasEntityStore;
import org.apache.atlas.repository.store.graph.EntityGraphDiscovery;
import org.apache.atlas.repository.store.graph.EntityGraphDiscoveryContext;
import org.apache.atlas.type.AtlasClassificationType;
import org.apache.atlas.type.AtlasEntityType;
import org.apache.atlas.type.AtlasStructType.AtlasAttribute;
import org.apache.atlas.type.AtlasType;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.atlas.type.AtlasTypeUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.apache.atlas.model.instance.EntityMutations.EntityOperation.DELETE;
import static org.apache.atlas.model.instance.EntityMutations.EntityOperation.UPDATE;


@Component
public class AtlasEntityStoreV1 implements AtlasEntityStore {
    private static final Logger LOG = LoggerFactory.getLogger(AtlasEntityStoreV1.class);

    private final DeleteHandlerDelegateV1   deleteDelegate;
    private final AtlasTypeRegistry         typeRegistry;
    private final AtlasEntityChangeNotifier entityChangeNotifier;
    private final EntityGraphMapper         entityGraphMapper;
    private final EntityGraphRetriever      entityRetriever;

    @Inject
    public AtlasEntityStoreV1(DeleteHandlerDelegateV1 deleteDelegate, AtlasTypeRegistry typeRegistry,
                              AtlasEntityChangeNotifier entityChangeNotifier, EntityGraphMapper entityGraphMapper, EntityGraphRetriever entityRetriever) {
        this.deleteDelegate       = deleteDelegate;
        this.typeRegistry         = typeRegistry;
        this.entityChangeNotifier = entityChangeNotifier;
        this.entityGraphMapper    = entityGraphMapper;
        this.entityRetriever      = entityRetriever;
    }

    @Override
    @GraphTransaction
    public AtlasEntityWithExtInfo getById(String guid) throws AtlasBaseException {
        return getById(guid, false);
    }

    @Override
    @GraphTransaction
    public AtlasEntityWithExtInfo getById(String guid, boolean isMinExtInfo) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> getById({}, {})", guid, isMinExtInfo);
        }

        AtlasEntityWithExtInfo ret = entityRetriever.toAtlasEntityWithExtInfo(guid, isMinExtInfo);

        if (ret == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== getById({}, {}): {}", guid, isMinExtInfo, ret);
        }

        return ret;
    }

    @Override
    @GraphTransaction
    public AtlasEntityHeader getHeaderById(String guid) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> getHeaderById({})", guid);
        }

        AtlasEntityHeader ret = entityRetriever.toAtlasEntityHeader(guid);

        if (ret == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== getHeaderById({}): {}", guid, ret);
        }

        return ret;
    }

    @Override
    @GraphTransaction
    public AtlasEntitiesWithExtInfo getByIds(List<String> guids) throws AtlasBaseException {
        return getByIds(guids, false);
    }

    @Override
    @GraphTransaction
    public AtlasEntitiesWithExtInfo getByIds(List<String> guids, boolean isMinExtInfo) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> getByIds({}, {})", guids, isMinExtInfo);
        }

        AtlasEntitiesWithExtInfo ret = entityRetriever.toAtlasEntitiesWithExtInfo(guids, isMinExtInfo);

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== getByIds({}, {}): {}", guids, isMinExtInfo, ret);
        }

        return ret;
    }

    @Override
    @GraphTransaction
    public AtlasEntityWithExtInfo getByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> uniqAttributes)
            throws AtlasBaseException {
        return getByUniqueAttributes(entityType, uniqAttributes, false);
    }

    @Override
    @GraphTransaction
    public AtlasEntityWithExtInfo getByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> uniqAttributes, boolean isMinExtInfo)
            throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> getByUniqueAttribute({}, {})", entityType.getTypeName(), uniqAttributes);
        }

        AtlasVertex entityVertex = AtlasGraphUtilsV1.getVertexByUniqueAttributes(entityType, uniqAttributes);

        AtlasEntityWithExtInfo ret = entityRetriever.toAtlasEntityWithExtInfo(entityVertex, isMinExtInfo);

        if (ret == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_BY_UNIQUE_ATTRIBUTE_NOT_FOUND, entityType.getTypeName(),
                    uniqAttributes.toString());
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== getByUniqueAttribute({}, {}): {}", entityType.getTypeName(), uniqAttributes, ret);
        }

        return ret;
    }

    /**
     * Check state of entities in the store
     * @param request AtlasCheckStateRequest
     * @return AtlasCheckStateResult
     * @throws AtlasBaseException
     */
    @Override
    @GraphTransaction
    public AtlasCheckStateResult checkState(AtlasCheckStateRequest request) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> checkState({})", request);
        }

        EntityStateChecker entityStateChecker = new EntityStateChecker(typeRegistry);

        AtlasCheckStateResult ret = entityStateChecker.checkState(request);

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== checkState({}, {})", request, ret);
        }

        return ret;
    }

    private EntityMutationResponse createOrUpdate(EntityStream entityStream, boolean isPartialUpdate, boolean replaceClassifications) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> createOrUpdate()");
        }

        if (entityStream == null || !entityStream.hasNext()) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "no entities to create/update.");
        }

        MetricRecorder metric = RequestContextV1.get().startMetricRecord("createOrUpdate");

        // Create/Update entities
        EntityMutationContext context = preCreateOrUpdate(entityStream, entityGraphMapper, isPartialUpdate);

        // for existing entities, skip update if incoming entity doesn't have any change
        if (CollectionUtils.isNotEmpty(context.getUpdatedEntities())) {
            MetricRecorder checkForUnchangedEntities = RequestContextV1.get().startMetricRecord("checkForUnchangedEntities");

            List<AtlasEntity> entitiesToSkipUpdate = null;

            for (AtlasEntity entity : context.getUpdatedEntities()) {
                String          guid       = entity.getGuid();
                AtlasVertex     vertex     = context.getVertex(guid);
                AtlasEntityType entityType = typeRegistry.getEntityTypeByName(entity.getTypeName());
                boolean         hasUpdates = false;

                if (!hasUpdates) {
                    hasUpdates = entity.getStatus() == AtlasEntity.Status.DELETED; // entity status could be updated during import
                }

                if (!hasUpdates) {
                    for (AtlasAttribute attribute : entityType.getAllAttributes().values()) {
                        if (!entity.getAttributes().containsKey(attribute.getName())) {  // if value is not provided, current value will not be updated
                            continue;
                        }

                        Object currVal = entityRetriever.getEntityAttribute(vertex, attribute);
                        Object newVal  = entity.getAttribute(attribute.getName());

                        if (!attribute.getAttributeType().areEqualValues(currVal, newVal, context.getGuidAssignments())) {
                            hasUpdates = true;

                            if (LOG.isDebugEnabled()) {
                                LOG.debug("found attribute update: entity(guid={}, typeName={}), attrName={}, currValue={}, newValue={}", guid, entity.getTypeName(), attribute.getName(), currVal, newVal);
                            }

                            break;
                        }
                    }
                }

                // if classifications are to be replaced, then skip updates only when no change in classifications
                if (!hasUpdates && replaceClassifications) {
                    List<AtlasClassification> newVal  = entity.getClassifications();
                    List<AtlasClassification> currVal = entityRetriever.getClassifications(vertex);

                    if (!Objects.equals(currVal, newVal)) {
                        hasUpdates = true;

                        if (LOG.isDebugEnabled()) {
                            LOG.debug("found classifications update: entity(guid={}, typeName={}), currValue={}, newValue={}", guid, entity.getTypeName(), currVal, newVal);
                        }
                    }
                }

                if (!hasUpdates) {
                    if (entitiesToSkipUpdate == null) {
                        entitiesToSkipUpdate = new ArrayList<>();
                    }

                    if (LOG.isDebugEnabled()) {
                        LOG.debug("skipping unchanged entity: {}", entity);
                    }

                    entitiesToSkipUpdate.add(entity);
                }
            }

            if (entitiesToSkipUpdate != null) {
                context.getUpdatedEntities().removeAll(entitiesToSkipUpdate);
            }

            RequestContextV1.get().endMetricRecord(checkForUnchangedEntities);
        }

        EntityMutationResponse ret = entityGraphMapper.mapAttributesAndClassifications(context, isPartialUpdate, replaceClassifications);

        ret.setGuidAssignments(context.getGuidAssignments());

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== createOrUpdate()");
        }

        // Notify the change listeners
        entityChangeNotifier.onEntitiesMutated(ret, entityStream instanceof EntityImportStream);

        RequestContextV1.get().endMetricRecord(metric);

        return ret;
    }

    @Override
    @GraphTransaction
    public EntityMutationResponse createOrUpdate(EntityStream entityStream, boolean isPartialUpdate) throws AtlasBaseException {
        return createOrUpdate(entityStream, isPartialUpdate, false);
    }

    @Override
    @GraphTransaction
    public EntityMutationResponse createOrUpdateForImport(EntityStream entityStream) throws AtlasBaseException {
        return createOrUpdate(entityStream, false, true);
    }

    @Override
    @GraphTransaction
    public EntityMutationResponse updateEntity(AtlasObjectId objectId, AtlasEntityWithExtInfo updatedEntityInfo, boolean isPartialUpdate) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> updateEntity({}, {})", objectId, updatedEntityInfo);
        }

        if (objectId == null || updatedEntityInfo == null || updatedEntityInfo.getEntity() == null) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "null entity-id/entity");
        }

        final String guid;

        if (AtlasTypeUtil.isAssignedGuid(objectId.getGuid())) {
            guid = objectId.getGuid();
        } else {
            AtlasEntityType entityType = typeRegistry.getEntityTypeByName(objectId.getTypeName());

            if (entityType == null) {
                throw new AtlasBaseException(AtlasErrorCode.UNKNOWN_TYPENAME, objectId.getTypeName());
            }

            guid = AtlasGraphUtilsV1.getGuidByUniqueAttributes(entityType, objectId.getUniqueAttributes());
        }

        AtlasEntity entity = updatedEntityInfo.getEntity();

        entity.setGuid(guid);

        return createOrUpdate(new AtlasEntityStream(updatedEntityInfo), isPartialUpdate, false);
    }


    @Override
    @GraphTransaction
    public EntityMutationResponse updateByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> uniqAttributes,
                                                           AtlasEntityWithExtInfo updatedEntityInfo) throws AtlasBaseException {

        if (LOG.isDebugEnabled()) {
            LOG.debug("==> updateByUniqueAttributes({}, {})", entityType.getTypeName(), uniqAttributes);
        }

        if (updatedEntityInfo == null || updatedEntityInfo.getEntity() == null) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "no entity to update.");
        }

        String guid = AtlasGraphUtilsV1.getGuidByUniqueAttributes(entityType, uniqAttributes);

        AtlasEntity entity = updatedEntityInfo.getEntity();

        entity.setGuid(guid);

        return createOrUpdate(new AtlasEntityStream(updatedEntityInfo), true, false);
    }

    @Override
    @GraphTransaction
    public EntityMutationResponse updateEntityAttributeByGuid(String guid, String attrName, Object attrValue)
            throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> updateEntityAttributeByGuid({}, {}, {})", guid, attrName, attrValue);
        }

        AtlasEntityWithExtInfo entityInfo = getById(guid);

        if (entityInfo == null || entityInfo.getEntity() == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
        }

        AtlasEntity     entity     = entityInfo.getEntity();
        AtlasEntityType entityType = (AtlasEntityType) typeRegistry.getType(entity.getTypeName());
        AtlasAttribute  attr       = entityType.getAttribute(attrName);

        if (attr == null) {
            throw new AtlasBaseException(AtlasErrorCode.UNKNOWN_ATTRIBUTE, attrName, entity.getTypeName());
        }

        AtlasType   attrType     = attr.getAttributeType();
        AtlasEntity updateEntity = new AtlasEntity();

        updateEntity.setGuid(guid);
        updateEntity.setTypeName(entity.getTypeName());

        switch (attrType.getTypeCategory()) {
            case PRIMITIVE:
                updateEntity.setAttribute(attrName, attrValue);
                break;
            case OBJECT_ID_TYPE:
                AtlasObjectId objId;

                if (attrValue instanceof String) {
                    objId = new AtlasObjectId((String) attrValue, attr.getAttributeDef().getTypeName());
                } else {
                    objId = (AtlasObjectId) attrType.getNormalizedValue(attrValue);
                }

                updateEntity.setAttribute(attrName, objId);
                break;

            default:
                throw new AtlasBaseException(AtlasErrorCode.ATTRIBUTE_UPDATE_NOT_SUPPORTED, attrName, attrType.getTypeName());
        }

        return createOrUpdate(new AtlasEntityStream(updateEntity), true, false);
    }

    @Override
    @GraphTransaction
    public EntityMutationResponse deleteById(final String guid) throws AtlasBaseException {

        if (StringUtils.isEmpty(guid)) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
        }

        // Retrieve vertices for requested guids.
        AtlasVertex vertex = AtlasGraphUtilsV1.findByGuid(guid);

        Collection<AtlasVertex> deletionCandidates = new ArrayList<>();

        if (vertex != null) {
            deletionCandidates.add(vertex);
        } else {
            if (LOG.isDebugEnabled()) {
                // Entity does not exist - treat as non-error, since the caller
                // wanted to delete the entity and it's already gone.
                LOG.debug("Deletion request ignored for non-existent entity with guid " + guid);
            }
        }

        EntityMutationResponse ret = deleteVertices(deletionCandidates);

        // Notify the change listeners
        entityChangeNotifier.onEntitiesMutated(ret, false);

        return ret;
    }

    @Override
    @GraphTransaction
    public EntityMutationResponse deleteByIds(final List<String> guids) throws AtlasBaseException {
        if (CollectionUtils.isEmpty(guids)) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "Guid(s) not specified");
        }

        Collection<AtlasVertex> deletionCandidates = new ArrayList<>();

        for (String guid : guids) {
            // Retrieve vertices for requested guids.
            AtlasVertex vertex = AtlasGraphUtilsV1.findByGuid(guid);

            if (vertex != null) {
                deletionCandidates.add(vertex);
            } else {
                if (LOG.isDebugEnabled()) {
                    // Entity does not exist - treat as non-error, since the caller
                    // wanted to delete the entity and it's already gone.
                    LOG.debug("Deletion request ignored for non-existent entity with guid " + guid);
                }
            }
        }

        if (deletionCandidates.isEmpty()) {
            LOG.info("No deletion candidate entities were found for guids %s", guids);
        }

        EntityMutationResponse ret = deleteVertices(deletionCandidates);

        // Notify the change listeners
        entityChangeNotifier.onEntitiesMutated(ret, false);

        return ret;
    }

    @Override
    @GraphTransaction
    public EntityMutationResponse deleteByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> uniqAttributes)
            throws AtlasBaseException {

        if (MapUtils.isEmpty(uniqAttributes)) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_BY_UNIQUE_ATTRIBUTE_NOT_FOUND, uniqAttributes.toString());
        }

        final AtlasVertex vertex = AtlasGraphUtilsV1.findByUniqueAttributes(entityType, uniqAttributes);
        Collection<AtlasVertex> deletionCandidates = new ArrayList<>();

        if (vertex != null) {
            deletionCandidates.add(vertex);
        } else {
            if (LOG.isDebugEnabled()) {
                // Entity does not exist - treat as non-error, since the caller
                // wanted to delete the entity and it's already gone.
                LOG.debug("Deletion request ignored for non-existent entity with uniqueAttributes " + uniqAttributes);
            }
        }

        EntityMutationResponse ret = deleteVertices(deletionCandidates);

        // Notify the change listeners
        entityChangeNotifier.onEntitiesMutated(ret, false);

        return ret;
    }

    @Override
    @GraphTransaction
    public String getGuidByUniqueAttributes(AtlasEntityType entityType, Map<String, Object> uniqAttributes) throws AtlasBaseException{
        return AtlasGraphUtilsV1.getGuidByUniqueAttributes(entityType, uniqAttributes);
    }

    @Override
    @GraphTransaction
    public void addClassifications(final String guid, final List<AtlasClassification> classifications) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Adding classifications={} to entity={}", classifications, guid);
        }

        if (StringUtils.isEmpty(guid)) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "Guid(s) not specified");
        }
        if (CollectionUtils.isEmpty(classifications)) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "classifications(s) not specified");
        }

        AtlasVertex entityVertex = AtlasGraphUtilsV1.findByGuid(guid);

        if (entityVertex == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
        }

        String                entityType = AtlasGraphUtilsV1.getTypeName(entityVertex);
        EntityMutationContext context    = new EntityMutationContext();

        context.cacheEntity(guid, entityVertex, typeRegistry.getEntityTypeByName(entityType));

        GraphTransactionInterceptor.lockObjectAndReleasePostCommit(guid);
        for (AtlasClassification classification : classifications) {
            validateAndNormalize(classification);
        }

        // validate if entity, not already associated with classifications
        validateEntityAssociations(guid, classifications);

        entityGraphMapper.addClassifications(context, guid, classifications);

        // notify listeners on classification addition
        entityChangeNotifier.onClassificationAddedToEntity(guid, classifications);
    }

    @Override
    @GraphTransaction
    public void updateClassifications(String guid, List<AtlasClassification> newClassifications) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Updating classifications={} for entity={}", newClassifications, guid);
        }

        if (StringUtils.isEmpty(guid)) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "Guid not specified");
        }

        if (CollectionUtils.isEmpty(newClassifications)) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "classifications(s) not specified");
        }

        AtlasVertex entityVertex = AtlasGraphUtilsV1.findByGuid(guid);

        if (entityVertex == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
        }

        String                entityType = AtlasGraphUtilsV1.getTypeName(entityVertex);
        EntityMutationContext context    = new EntityMutationContext();

        context.cacheEntity(guid, entityVertex, typeRegistry.getEntityTypeByName(entityType));

        GraphTransactionInterceptor.lockObjectAndReleasePostCommit(guid);
        List<AtlasClassification> updatedClassifications = new ArrayList<>();

        for (AtlasClassification newClassification : newClassifications) {
            String              classificationName = newClassification.getTypeName();
            AtlasClassification oldClassification  = getClassification(guid, classificationName);

            if (oldClassification == null) {
                throw new AtlasBaseException(AtlasErrorCode.CLASSIFICATION_NOT_FOUND, classificationName);
            }

            validateAndNormalizeForUpdate(newClassification);

            Map<String, Object> newAttrs = newClassification.getAttributes();

            if (MapUtils.isNotEmpty(newAttrs)) {
                for (String attrName : newAttrs.keySet()) {
                    oldClassification.setAttribute(attrName, newAttrs.get(attrName));
                }
            }

            entityGraphMapper.updateClassification(context, guid, oldClassification);

            updatedClassifications.add(oldClassification);
        }

        // notify listeners on update to classifications
        entityChangeNotifier.onClassificationUpdatedToEntity(guid, updatedClassifications);
    }

    @Override
    @GraphTransaction
    public void addClassification(final List<String> guids, final AtlasClassification classification) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Adding classification={} to entities={}", classification, guids);
        }

        if (CollectionUtils.isEmpty(guids)) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "Guid(s) not specified");
        }
        if (classification == null) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "classification not specified");
        }

        GraphTransactionInterceptor.lockObjectAndReleasePostCommit(guids);

        validateAndNormalize(classification);

        List<AtlasClassification> classifications = Collections.singletonList(classification);
        EntityMutationContext context = new EntityMutationContext();

        for (String guid : guids) {
            AtlasVertex entityVertex = AtlasGraphUtilsV1.findByGuid(guid);

            if (entityVertex == null) {
                throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
            }

            String entityType = AtlasGraphUtilsV1.getTypeName(entityVertex);

            context.cacheEntity(guid, entityVertex, typeRegistry.getEntityTypeByName(entityType));

            // validate if entity, not already associated with classifications
            validateEntityAssociations(guid, classifications);

            entityGraphMapper.addClassifications(context, guid, classifications);

            // notify listeners on classification addition
            entityChangeNotifier.onClassificationAddedToEntity(guid, classifications);
        }

    }

    @Override
    @GraphTransaction
    public void deleteClassifications(final String guid, final List<String> classificationNames) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Deleting classifications={} from entity={}", classificationNames, guid);
        }

        if (StringUtils.isEmpty(guid)) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "Guid(s) not specified");
        }
        if (CollectionUtils.isEmpty(classificationNames)) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "classifications(s) not specified");
        }

        AtlasVertex entityVertex = AtlasGraphUtilsV1.findByGuid(guid);

        if (entityVertex == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
        }

        String                entityType = AtlasGraphUtilsV1.getTypeName(entityVertex);
        EntityMutationContext context    = new EntityMutationContext();

        context.cacheEntity(guid, entityVertex, typeRegistry.getEntityTypeByName(entityType));

        GraphTransactionInterceptor.lockObjectAndReleasePostCommit(guid);

        entityGraphMapper.deleteClassifications(context, guid, classificationNames);

        // notify listeners on classification deletion
        entityChangeNotifier.onClassificationDeletedFromEntity(guid, classificationNames);
    }

    @Override
    @GraphTransaction
    public List<AtlasClassification> getClassifications(String guid) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Getting classifications for entities={}", guid);
        }

        EntityGraphRetriever graphRetriever = new EntityGraphRetriever(typeRegistry);
        return graphRetriever.getClassifications(guid);
    }

    @Override
    @GraphTransaction
    public AtlasClassification getClassification(String guid, String classificationName) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Getting classifications for entities={}", guid);
        }

        EntityGraphRetriever graphRetriever = new EntityGraphRetriever(typeRegistry);
        return graphRetriever.getClassification(guid, classificationName);
    }

    private EntityMutationContext preCreateOrUpdate(EntityStream entityStream, EntityGraphMapper entityGraphMapper, boolean isPartialUpdate) throws AtlasBaseException {
        MetricRecorder metric = RequestContextV1.get().startMetricRecord("preCreateOrUpdate");

        EntityGraphDiscovery        graphDiscoverer  = new AtlasEntityGraphDiscoveryV1(typeRegistry, entityStream);
        EntityGraphDiscoveryContext discoveryContext = graphDiscoverer.discoverEntities();
        EntityMutationContext       context          = new EntityMutationContext(discoveryContext);
        RequestContextV1            requestContext   = RequestContextV1.get();

        for (String guid : discoveryContext.getReferencedGuids()) {
            AtlasEntity entity = entityStream.getByGuid(guid);

            if (entity != null) {
                AtlasVertex vertex = getResolvedEntityVertex(discoveryContext, entity);

                if (vertex != null) {
                    // entity would be null if guid is not in the stream but referenced by an entity in the stream
                    if (!isPartialUpdate) {
                        graphDiscoverer.validateAndNormalize(entity);
                    } else {
                        graphDiscoverer.validateAndNormalizeForUpdate(entity);
                    }

                    AtlasEntityType entityType = typeRegistry.getEntityTypeByName(entity.getTypeName());
                    String          guidVertex = AtlasGraphUtilsV1.getIdFromVertex(vertex);

                    if (!StringUtils.equals(guidVertex, guid)) { // if entity was found by unique attribute
                        entity.setGuid(guidVertex);

                        requestContext.recordEntityGuidUpdate(entity, guid);
                    }

                    context.addUpdated(guid, entity, entityType, vertex);
                } else {
                    graphDiscoverer.validateAndNormalize(entity);

                    AtlasEntityType entityType = typeRegistry.getEntityTypeByName(entity.getTypeName());

                    //Create vertices which do not exist in the repository
                    if ((entityStream instanceof EntityImportStream) && AtlasTypeUtil.isAssignedGuid(entity.getGuid())) {
                        vertex = entityGraphMapper.createVertexWithGuid(entity, entity.getGuid());
                    } else {
                        vertex = entityGraphMapper.createVertex(entity);
                    }

                    discoveryContext.addResolvedGuid(guid, vertex);

                    discoveryContext.addResolvedIdByUniqAttribs(getAtlasObjectId(entity), vertex);

                    String generatedGuid = AtlasGraphUtilsV1.getIdFromVertex(vertex);

                    entity.setGuid(generatedGuid);

                    requestContext.recordEntityGuidUpdate(entity, guid);

                    context.addCreated(guid, entity, entityType, vertex);
                }

                // during import, update the system attributes
                if (entityStream instanceof EntityImportStream) {
                    AtlasEntity.Status newStatus = entity.getStatus();

                    if (newStatus != null) {
                        AtlasEntity.Status currStatus = AtlasGraphUtilsV1.getState(vertex);

                        if (currStatus == AtlasEntity.Status.ACTIVE && newStatus == AtlasEntity.Status.DELETED) {
                            if (LOG.isDebugEnabled()) {
                                LOG.debug("entity-delete via import - guid={}", guid);
                            }

                            context.addEntityToDelete(vertex);
                        } else if (currStatus == AtlasEntity.Status.DELETED && newStatus == AtlasEntity.Status.ACTIVE) {
                            LOG.warn("attempt to activate deleted entity (guid={}). Ignored", guid);

                            entity.setStatus(currStatus);
                        }
                    }

                    entityGraphMapper.updateSystemAttributes(vertex, entity);
                }
            }
        }

        RequestContextV1.get().endMetricRecord(metric);

        return context;
    }

    private AtlasVertex getResolvedEntityVertex(EntityGraphDiscoveryContext context, AtlasEntity entity) throws AtlasBaseException {
        AtlasVertex ret = context.getResolvedEntityVertex(entity.getGuid());

        if (ret == null) {
            ret = context.getResolvedEntityVertex(getAtlasObjectId(entity));

            if (ret != null) {
                context.addResolvedGuid(entity.getGuid(), ret);
            }
        }

        return ret;
    }

    private AtlasObjectId getAtlasObjectId(AtlasEntity entity) {
        AtlasObjectId ret = entityGraphMapper.toAtlasObjectId(entity);

        if (ret != null && MapUtils.isNotEmpty(ret.getUniqueAttributes())) {
            // if uniqueAttributes is not empty, reset guid to null.
            ret.setGuid(null);
        }

        return ret;
    }

    private EntityMutationResponse deleteVertices(Collection<AtlasVertex> deletionCandidates) throws AtlasBaseException {
        EntityMutationResponse response = new EntityMutationResponse();
        deleteDelegate.getHandlerV1().deleteEntities(deletionCandidates);
        RequestContextV1 req = RequestContextV1.get();
        for (AtlasEntityHeader entity : req.getDeletedEntities()) {
            response.addEntity(DELETE, entity);
        }

        for (AtlasEntityHeader entity : req.getUpdatedEntities()) {
            response.addEntity(UPDATE, entity);
        }

        return response;
    }

    private void validateAndNormalize(AtlasClassification classification) throws AtlasBaseException {
        AtlasClassificationType type = typeRegistry.getClassificationTypeByName(classification.getTypeName());

        if (type == null) {
            throw new AtlasBaseException(AtlasErrorCode.CLASSIFICATION_NOT_FOUND, classification.getTypeName());
        }

        List<String> messages = new ArrayList<>();

        type.validateValue(classification, classification.getTypeName(), messages);

        if (!messages.isEmpty()) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, messages);
        }

        type.getNormalizedValue(classification);
    }

    private void validateAndNormalizeForUpdate(AtlasClassification classification) throws AtlasBaseException {
        AtlasClassificationType type = typeRegistry.getClassificationTypeByName(classification.getTypeName());

        if (type == null) {
            throw new AtlasBaseException(AtlasErrorCode.CLASSIFICATION_NOT_FOUND, classification.getTypeName());
        }

        List<String> messages = new ArrayList<>();

        type.validateValueForUpdate(classification, classification.getTypeName(), messages);

        if (!messages.isEmpty()) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, messages);
        }

        type.getNormalizedValueForUpdate(classification);
    }

    /**
     * Validate if classification is not already associated with the entities
     *
     * @param guid            unique entity id
     * @param classifications list of classifications to be associated
     */
    private void validateEntityAssociations(String guid, List<AtlasClassification> classifications) throws AtlasBaseException {
        List<String> entityClassifications = getClassificationNames(guid);

        for (AtlasClassification classification : classifications) {
            String newClassification = classification.getTypeName();

            if (CollectionUtils.isNotEmpty(entityClassifications) && entityClassifications.contains(newClassification)) {
                throw new AtlasBaseException(AtlasErrorCode.INVALID_PARAMETERS, "entity: " + guid +
                        ", already associated with classification: " + newClassification);
            }
        }
    }

    private List<String> getClassificationNames(String guid) throws AtlasBaseException {
        List<String>              ret             = null;
        List<AtlasClassification> classifications = getClassifications(guid);

        if (CollectionUtils.isNotEmpty(classifications)) {
            ret = new ArrayList<>();

            for (AtlasClassification classification : classifications) {
                ret.add(classification.getTypeName());
            }
        }

        return ret;
    }
}
