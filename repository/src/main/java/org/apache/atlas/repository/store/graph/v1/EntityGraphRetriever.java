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
package org.apache.atlas.repository.store.graph.v1;

import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.annotation.GraphTransaction;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.instance.AtlasClassification;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntitiesWithExtInfo;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntityExtInfo;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntityWithExtInfo;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.AtlasObjectId;
import org.apache.atlas.model.instance.AtlasStruct;
import org.apache.atlas.model.typedef.AtlasStructDef.AtlasAttributeDef;
import org.apache.atlas.repository.Constants;
import org.apache.atlas.repository.graph.GraphHelper;
import org.apache.atlas.repository.graphdb.AtlasEdge;
import org.apache.atlas.repository.graphdb.AtlasEdgeDirection;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.type.AtlasArrayType;
import org.apache.atlas.type.AtlasEntityType;
import org.apache.atlas.type.AtlasMapType;
import org.apache.atlas.type.AtlasStructType;
import org.apache.atlas.type.AtlasStructType.AtlasAttribute;
import org.apache.atlas.type.AtlasType;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.atlas.type.AtlasTypeUtil;
import org.apache.atlas.utils.AtlasEntityUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import static org.apache.atlas.model.typedef.AtlasBaseTypeDef.*;
import static org.apache.atlas.repository.graph.GraphHelper.EDGE_LABEL_PREFIX;
import static org.apache.atlas.type.AtlasStructType.AtlasAttribute.encodePropertyKey;

@Component
public final class EntityGraphRetriever {
    private static final Logger LOG = LoggerFactory.getLogger(EntityGraphRetriever.class);

    private static final String NAME           = "name";
    private static final String QUALIFIED_NAME = "qualifiedName";
    private static final String MAP_VALUE_FORMAT = "%s.%s";

    private static final GraphHelper graphHelper = GraphHelper.getInstance();

    private final AtlasTypeRegistry typeRegistry;

    @Inject
    public EntityGraphRetriever(AtlasTypeRegistry typeRegistry) {
        this.typeRegistry = typeRegistry;
    }

    public AtlasEntity toAtlasEntity(String guid) throws AtlasBaseException {
        return toAtlasEntity(getEntityVertex(guid));
    }

    public AtlasEntity toAtlasEntity(AtlasObjectId objId) throws AtlasBaseException {
        return toAtlasEntity(getEntityVertex(objId));
    }

    public AtlasEntity toAtlasEntity(AtlasVertex entityVertex) throws AtlasBaseException {
        return mapVertexToAtlasEntity(entityVertex, null);
    }

    public AtlasEntityWithExtInfo toAtlasEntityWithExtInfo(String guid) throws AtlasBaseException {
        return toAtlasEntityWithExtInfo(guid, false);
    }

    public AtlasEntityWithExtInfo toAtlasEntityWithExtInfo(String guid, boolean isMinExtInfo) throws AtlasBaseException {
        return toAtlasEntityWithExtInfo(getEntityVertex(guid), isMinExtInfo);
    }

    public AtlasEntityWithExtInfo toAtlasEntityWithExtInfo(AtlasObjectId objId) throws AtlasBaseException {
        return toAtlasEntityWithExtInfo(getEntityVertex(objId));
    }

    public AtlasEntityWithExtInfo toAtlasEntityWithExtInfo(AtlasVertex entityVertex) throws AtlasBaseException {
        return toAtlasEntityWithExtInfo(entityVertex, false);
    }

    public AtlasEntityWithExtInfo toAtlasEntityWithExtInfo(AtlasVertex entityVertex, boolean isMinExtInfo) throws AtlasBaseException {
        AtlasEntityExtInfo     entityExtInfo = new AtlasEntityExtInfo();
        AtlasEntity            entity        = mapVertexToAtlasEntity(entityVertex, entityExtInfo, isMinExtInfo);
        AtlasEntityWithExtInfo ret           = new AtlasEntityWithExtInfo(entity, entityExtInfo);

        ret.compact();

        return ret;
    }

    public AtlasEntitiesWithExtInfo toAtlasEntitiesWithExtInfo(List<String> guids) throws AtlasBaseException {
        return toAtlasEntitiesWithExtInfo(guids, false);
    }

    public AtlasEntitiesWithExtInfo toAtlasEntitiesWithExtInfo(List<String> guids, boolean isMinExtInfo) throws AtlasBaseException {
        AtlasEntitiesWithExtInfo ret = new AtlasEntitiesWithExtInfo();

        for (String guid : guids) {
            AtlasVertex vertex = getEntityVertex(guid);

            AtlasEntity entity = mapVertexToAtlasEntity(vertex, ret, isMinExtInfo);

            ret.addEntity(entity);
        }

        ret.compact();

        return ret;
    }

    public AtlasEntityHeader toAtlasEntityHeader(String guid) throws AtlasBaseException {
        return toAtlasEntityHeader(getEntityVertex(guid));
    }

    public AtlasEntityHeader toAtlasEntityHeader(AtlasVertex entityVertex) throws AtlasBaseException {
        return toAtlasEntityHeader(entityVertex, Collections.<String>emptySet());
    }

    public AtlasVertex getEntityVertex(String guid) throws AtlasBaseException {
        AtlasVertex ret = AtlasGraphUtilsV1.findByGuid(guid);

        if (ret == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
        }

        return ret;
    }

    public Map<String, Object> getEntityUniqueAttribute(AtlasVertex entityVertex) throws AtlasBaseException {
        Map<String, Object> ret        = null;
        String              typeName   = AtlasGraphUtilsV1.getTypeName(entityVertex);
        AtlasEntityType     entityType = typeRegistry.getEntityTypeByName(typeName);

        if (entityType != null && MapUtils.isNotEmpty(entityType.getUniqAttributes())) {
            for (AtlasAttribute attribute : entityType.getUniqAttributes().values()) {
                Object val = mapVertexToAttribute(entityVertex, attribute, null, false);

                if (val != null) {
                    if (ret == null) {
                        ret = new HashMap<>();
                    }

                    ret.put(attribute.getName(), val);
                }
            }
        }

        return ret;
    }

    private AtlasVertex getEntityVertex(AtlasObjectId objId) throws AtlasBaseException {
        AtlasVertex ret = null;

        if (! AtlasTypeUtil.isValid(objId)) {
            throw new AtlasBaseException(AtlasErrorCode.INVALID_OBJECT_ID, objId.toString());
        }

        if (AtlasTypeUtil.isAssignedGuid(objId)) {
            ret = AtlasGraphUtilsV1.findByGuid(objId.getGuid());
        } else {
            AtlasEntityType     entityType     = typeRegistry.getEntityTypeByName(objId.getTypeName());
            Map<String, Object> uniqAttributes = objId.getUniqueAttributes();

            ret = AtlasGraphUtilsV1.getVertexByUniqueAttributes(entityType, uniqAttributes);
        }

        if (ret == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, objId.toString());
        }

        return ret;
    }

    private AtlasEntity mapVertexToAtlasEntity(AtlasVertex entityVertex, AtlasEntityExtInfo entityExtInfo) throws AtlasBaseException {
        return mapVertexToAtlasEntity(entityVertex, entityExtInfo, false);
    }

    private AtlasEntity mapVertexToAtlasEntity(AtlasVertex entityVertex, AtlasEntityExtInfo entityExtInfo, boolean isMinExtInfo) throws AtlasBaseException {
        String      guid   = GraphHelper.getGuid(entityVertex);
        AtlasEntity entity = entityExtInfo != null ? entityExtInfo.getEntity(guid) : null;

        if (entity == null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Mapping graph vertex to atlas entity for guid {}", guid);
            }

            entity = new AtlasEntity();

            if (entityExtInfo != null) {
                entityExtInfo.addReferredEntity(guid, entity);
            }

            mapSystemAttributes(entityVertex, entity);

            mapAttributes(entityVertex, entity, entityExtInfo, isMinExtInfo);

            mapClassifications(entityVertex, entity);
        }

        return entity;
    }

    private AtlasEntity mapVertexToAtlasEntityMin(AtlasVertex entityVertex, AtlasEntityExtInfo entityExtInfo) throws AtlasBaseException {
        return mapVertexToAtlasEntityMin(entityVertex, entityExtInfo, null);
    }

    private AtlasEntity mapVertexToAtlasEntityMin(AtlasVertex entityVertex, AtlasEntityExtInfo entityExtInfo, Set<String> attributes) throws AtlasBaseException {
        String      guid   = GraphHelper.getGuid(entityVertex);
        AtlasEntity entity = entityExtInfo != null ? entityExtInfo.getEntity(guid) : null;

        if (entity == null) {
            entity = new AtlasEntity();

            if (entityExtInfo != null) {
                entityExtInfo.addReferredEntity(guid, entity);
            }

            mapSystemAttributes(entityVertex, entity);

            mapClassifications(entityVertex, entity);

            AtlasEntityType entityType = typeRegistry.getEntityTypeByName(entity.getTypeName());

            if (entityType != null) {
                for (AtlasAttribute attribute : entityType.getMinInfoAttributes().values()) {
                    Object attrValue = getVertexAttribute(entityVertex, attribute);

                    if (attrValue != null) {
                        entity.setAttribute(attribute.getName(), attrValue);
                    }
                }
            }
        }

        return entity;
    }

    private AtlasEntityHeader mapVertexToAtlasEntityHeader(AtlasVertex entityVertex) throws AtlasBaseException {
        return mapVertexToAtlasEntityHeader(entityVertex, Collections.<String>emptySet());
    }

    private AtlasEntityHeader mapVertexToAtlasEntityHeader(AtlasVertex entityVertex, Set<String> attributes) throws AtlasBaseException {
        AtlasEntityHeader ret = new AtlasEntityHeader();

        String typeName = entityVertex.getProperty(Constants.TYPE_NAME_PROPERTY_KEY, String.class);
        String guid     = entityVertex.getProperty(Constants.GUID_PROPERTY_KEY, String.class);

        ret.setTypeName(typeName);
        ret.setGuid(guid);
        ret.setStatus(GraphHelper.getStatus(entityVertex));
        ret.setClassificationNames(GraphHelper.getTraitNames(entityVertex));

        AtlasEntityType entityType = typeRegistry.getEntityTypeByName(typeName);

        if (entityType != null) {
            for (AtlasAttribute headerAttribute : entityType.getHeaderAttributes().values()) {
                Object attrValue = getVertexAttribute(entityVertex, headerAttribute);

                if (attrValue != null) {
                    ret.setAttribute(headerAttribute.getName(), attrValue);
                }
            }

            Object name        = ret.getAttribute(NAME);
            Object displayText = name != null ? name : ret.getAttribute(QUALIFIED_NAME);

            if (displayText != null) {
                ret.setDisplayText(displayText.toString());
            }

            if (CollectionUtils.isNotEmpty(attributes)) {
                for (String attrName : attributes) {
                    String nonQualifiedAttrName = toNonQualifiedName(attrName);
                    if (ret.hasAttribute(attrName)) {
                        continue;
                    }

                    Object attrValue = getVertexAttribute(entityVertex, entityType.getAttribute(nonQualifiedAttrName));

                    if (attrValue != null) {
                        ret.setAttribute(nonQualifiedAttrName, attrValue);
                    }
                }
            }
        }

        return ret;
    }

    private String toNonQualifiedName(String attrName) {
        String ret;
        if (attrName.contains(".")) {
            String[] attributeParts = attrName.split("\\.");
            ret = attributeParts[attributeParts.length - 1];
        } else {
            ret = attrName;
        }
        return ret;
    }

    private AtlasEntity mapSystemAttributes(AtlasVertex entityVertex, AtlasEntity entity) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Mapping system attributes for type {}", entity.getTypeName());
        }

        entity.setGuid(GraphHelper.getGuid(entityVertex));
        entity.setTypeName(GraphHelper.getTypeName(entityVertex));
        entity.setStatus(GraphHelper.getStatus(entityVertex));
        entity.setVersion(GraphHelper.getVersion(entityVertex).longValue());

        entity.setCreatedBy(GraphHelper.getCreatedByAsString(entityVertex));
        entity.setUpdatedBy(GraphHelper.getModifiedByAsString(entityVertex));

        entity.setCreateTime(new Date(GraphHelper.getCreatedTime(entityVertex)));
        entity.setUpdateTime(new Date(GraphHelper.getModifiedTime(entityVertex)));

        return entity;
    }

    private void mapAttributes(AtlasVertex entityVertex, AtlasStruct struct, AtlasEntityExtInfo entityExtInfo) throws AtlasBaseException {
        mapAttributes(entityVertex, struct, entityExtInfo, false);
    }

    private void mapAttributes(AtlasVertex entityVertex, AtlasStruct struct, AtlasEntityExtInfo entityExtInfo, boolean isMinExtInfo) throws AtlasBaseException {
        AtlasType objType = typeRegistry.getType(struct.getTypeName());

        if (!(objType instanceof AtlasStructType)) {
            throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_INVALID, struct.getTypeName());
        }

        AtlasStructType structType = (AtlasStructType) objType;

        for (AtlasAttribute attribute : structType.getAllAttributes().values()) {
            Object attrValue = mapVertexToAttribute(entityVertex, attribute, entityExtInfo, isMinExtInfo);

            struct.setAttribute(attribute.getName(), attrValue);
        }
    }

    public List<AtlasClassification> getClassifications(String guid) throws AtlasBaseException {

        AtlasVertex instanceVertex = AtlasGraphUtilsV1.findByGuid(guid);
        if (instanceVertex == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
        }

        return getClassifications(instanceVertex, null);
    }

    public List<AtlasClassification> getClassifications(AtlasVertex instanceVertex) throws AtlasBaseException {
        return getClassifications(instanceVertex, null);
    }

    public AtlasClassification getClassification(String guid, String classificationName) throws AtlasBaseException {

        AtlasVertex instanceVertex = AtlasGraphUtilsV1.findByGuid(guid);
        if (instanceVertex == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
        }

        List<AtlasClassification> classifications = getClassifications(instanceVertex, classificationName);

        if(CollectionUtils.isEmpty(classifications)) {
            throw new AtlasBaseException(AtlasErrorCode.CLASSIFICATION_NOT_FOUND, classificationName);
        }

        return classifications.get(0);
    }

    public AtlasClassification getClassification(AtlasVertex instanceVertex, String classificationName) throws AtlasBaseException {

        AtlasClassification ret = null;
        if (LOG.isDebugEnabled()) {
            LOG.debug("mapping classification {} to atlas entity", classificationName);
        }

        Iterable<AtlasEdge> edges = instanceVertex.getEdges(AtlasEdgeDirection.OUT, classificationName);
        AtlasEdge           edge  = (edges != null && edges.iterator().hasNext()) ? edges.iterator().next() : null;

        if (edge != null) {
            ret = new AtlasClassification(classificationName);
            mapAttributes(edge.getInVertex(), ret, null);
        }

        return ret;
    }


    private List<AtlasClassification> getClassifications(AtlasVertex instanceVertex, String classificationNameFilter) throws AtlasBaseException {
        List<AtlasClassification> classifications = new ArrayList<>();
        List<String> classificationNames = GraphHelper.getTraitNames(instanceVertex);

        if (CollectionUtils.isNotEmpty(classificationNames)) {
            for (String classificationName : classificationNames) {
                AtlasClassification classification;
                if (StringUtils.isNotEmpty(classificationNameFilter)) {
                    if (classificationName.equals(classificationNameFilter)) {
                        classification = getClassification(instanceVertex, classificationName);
                        classifications.add(classification);
                        return classifications;
                    }
                } else {
                    classification = getClassification(instanceVertex, classificationName);
                    classifications.add(classification);
                }
            }


            if (StringUtils.isNotEmpty(classificationNameFilter)) {
                //Should not reach here if classification present
                throw new AtlasBaseException(AtlasErrorCode.CLASSIFICATION_NOT_FOUND, classificationNameFilter);
            }
        }
        return classifications;
    }

    private void mapClassifications(AtlasVertex entityVertex, AtlasEntity entity) throws AtlasBaseException {
        final List<AtlasClassification> classifications = getClassifications(entityVertex, null);
        entity.setClassifications(classifications);
    }

    private Object mapVertexToAttribute(AtlasVertex entityVertex, AtlasAttribute attribute, AtlasEntityExtInfo entityExtInfo, boolean isMinExtInfo) throws AtlasBaseException {
        Object    ret              = null;
        AtlasType attrType         = attribute.getAttributeType();
        String    qualifiedName    = attribute.getQualifiedName();
        String    edgeLabel        = EDGE_LABEL_PREFIX + qualifiedName;
        boolean   isOwnedAttribute = attribute.isOwnedRef();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Mapping vertex {} to atlas entity {}.{}", entityVertex, attribute.getDefinedInDef().getName(), attribute.getName());
        }

        switch (attrType.getTypeCategory()) {
            case PRIMITIVE:
                ret = mapVertexToPrimitive(entityVertex, attribute.getVertexPropertyName(), attribute.getAttributeDef());
                break;
            case ENUM:
                ret = AtlasGraphUtilsV1.getEncodedProperty(entityVertex, attribute.getVertexPropertyName(), Object.class);
                break;
            case STRUCT:
                ret = mapVertexToStruct(entityVertex, edgeLabel, null, entityExtInfo, isMinExtInfo);
                break;
            case OBJECT_ID_TYPE:
                if(attribute.getAttributeDef().isSoftReferenced()) {
                    ret = mapVertexToObjectIdForSoftRef(entityVertex, attribute, entityExtInfo, isMinExtInfo);
                } else {
                    ret = mapVertexToObjectId(entityVertex, edgeLabel, null, entityExtInfo, isOwnedAttribute, isMinExtInfo);
                }
                break;
            case ARRAY:
                if(attribute.getAttributeDef().isSoftReferenced()) {
                    ret = mapVertexToArrayForSoftRef(entityVertex, attribute, entityExtInfo, isMinExtInfo);
                } else {
                    ret = mapVertexToArray(entityVertex, (AtlasArrayType) attrType, qualifiedName, entityExtInfo, isOwnedAttribute, isMinExtInfo);
                }
                break;
            case MAP:
                if(attribute.getAttributeDef().isSoftReferenced()) {
                    ret = mapVertexToMapForSoftRef(entityVertex, attribute, entityExtInfo, isMinExtInfo);
                } else {
                    ret = mapVertexToMap(entityVertex, (AtlasMapType) attrType, qualifiedName, entityExtInfo, isOwnedAttribute, isMinExtInfo);
                }
                break;
            case CLASSIFICATION:
                // do nothing
                break;
        }

        return ret;
    }

    private Map<String, AtlasObjectId> mapVertexToMapForSoftRef(AtlasVertex entityVertex, AtlasAttribute attribute, AtlasEntityExtInfo entityExtInfo, boolean isMinExtInfo) {
        Map<String, AtlasObjectId> ret     = null;
        List                       mapKeys = entityVertex.getListProperty(attribute.getVertexPropertyName());

        if (CollectionUtils.isNotEmpty(mapKeys)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Mapping map attribute {} for vertex {}", attribute.getVertexPropertyName(), entityVertex);
            }

            ret = new HashMap<>(mapKeys.size());

            for (Object mapKey : mapKeys) {
                String        keyPropertyName = String.format(MAP_VALUE_FORMAT, attribute.getVertexPropertyName(), encodePropertyKey(Objects.toString(mapKey)));
                AtlasObjectId mapValue        = getAtlasObjectIdFromSoftRefFormat(entityVertex.getProperty(keyPropertyName, String.class), attribute, entityExtInfo, isMinExtInfo);

                if (mapValue != null) {
                    ret.put(Objects.toString(mapKey), mapValue);
                }
            }
        }

        return ret;
    }

    private List<AtlasObjectId> mapVertexToArrayForSoftRef(AtlasVertex entityVertex, AtlasAttribute attribute, AtlasEntityExtInfo entityExtInfo, boolean isMinExtInfo) {
        List<AtlasObjectId> ret  = null;
        List                list = entityVertex.getListProperty(attribute.getVertexPropertyName());

        if (CollectionUtils.isNotEmpty(list)) {
            ret = new ArrayList<>();

            for (Object o : list) {
                AtlasObjectId objectId = getAtlasObjectIdFromSoftRefFormat(Objects.toString(o), attribute, entityExtInfo, isMinExtInfo);

                if (objectId != null) {
                    ret.add(objectId);
                }
            }
        }

        return ret;
    }

    private AtlasObjectId mapVertexToObjectIdForSoftRef(AtlasVertex entityVertex, AtlasAttribute attribute, AtlasEntityExtInfo entityExtInfo, boolean isMinExtInfo) {
        String rawValue = AtlasGraphUtilsV1.getEncodedProperty(entityVertex, attribute.getVertexPropertyName(), String.class);

        return StringUtils.isNotEmpty(rawValue) ? getAtlasObjectIdFromSoftRefFormat(rawValue, attribute, entityExtInfo, isMinExtInfo) : null;
    }

    private AtlasObjectId getAtlasObjectIdFromSoftRefFormat(String rawValue, AtlasAttribute attribute, AtlasEntityExtInfo entityExtInfo, boolean isMinExtInfo) {
        AtlasObjectId ret = AtlasEntityUtil.parseSoftRefValue(rawValue);

        if (ret != null) {
            if (entityExtInfo != null && attribute.isOwnedRef()) {
                try {
                    AtlasVertex referenceVertex = getEntityVertex(ret.getGuid());

                    if (referenceVertex != null) {
                        final AtlasEntity entity;

                        if (isMinExtInfo) {
                            entity = mapVertexToAtlasEntityMin(referenceVertex, entityExtInfo);
                        } else {
                            entity = mapVertexToAtlasEntity(referenceVertex, entityExtInfo);
                        }

                        if (entity != null) {
                            ret = toAtlasObjectId(entity);
                        }
                    }
                } catch (AtlasBaseException excp) {
                    LOG.info("failed to retrieve soft-referenced entity(typeName={}, guid={}); errorCode={}. Ignoring", ret.getTypeName(), ret.getGuid(), excp.getAtlasErrorCode());
                }

            }
        }

        return ret;
    }

    private Map<String, Object> mapVertexToMap(AtlasVertex entityVertex, AtlasMapType atlasMapType, final String propertyName,
                                               AtlasEntityExtInfo entityExtInfo, boolean isOwnedAttribute, boolean isMinExtInfo) throws AtlasBaseException {
        List<String> mapKeys = GraphHelper.getListProperty(entityVertex, propertyName);

        if (CollectionUtils.isEmpty(mapKeys)) {
            return null;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Mapping map attribute {} for vertex {}", atlasMapType.getTypeName(), entityVertex);
        }

        Map<String, Object> ret          = new HashMap<>(mapKeys.size());
        AtlasType           mapValueType = atlasMapType.getValueType();

        for (String mapKey : mapKeys) {
            final String keyPropertyName = propertyName + "." + mapKey;
            final String edgeLabel       = EDGE_LABEL_PREFIX + keyPropertyName;
            final Object keyValue        = GraphHelper.getMapValueProperty(mapValueType, entityVertex, keyPropertyName);

            Object mapValue = mapVertexToCollectionEntry(entityVertex, mapValueType, keyValue, edgeLabel, entityExtInfo, isOwnedAttribute, isMinExtInfo);
            if (mapValue != null) {
                ret.put(mapKey, mapValue);
            }
        }

        return ret;
    }

    private List<Object> mapVertexToArray(AtlasVertex entityVertex, AtlasArrayType arrayType, String propertyName,
                                          AtlasEntityExtInfo entityExtInfo, boolean isOwnedAttribute, boolean isMinExtInfo) throws AtlasBaseException {
        AtlasType    arrayElementType = arrayType.getElementType();
        List<Object> arrayElements    = GraphHelper.getArrayElementsProperty(arrayElementType, entityVertex, propertyName);

        if (CollectionUtils.isEmpty(arrayElements)) {
            return null;
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Mapping array attribute {} for vertex {}", arrayElementType.getTypeName(), entityVertex);
        }

        List   arrValues = new ArrayList(arrayElements.size());
        String edgeLabel = EDGE_LABEL_PREFIX + propertyName;

        for (Object element : arrayElements) {
            Object arrValue = mapVertexToCollectionEntry(entityVertex, arrayElementType, element,
                                                         edgeLabel, entityExtInfo, isOwnedAttribute, isMinExtInfo);

            if (arrValue != null) {
                arrValues.add(arrValue);
            }
        }

        return arrValues;
    }

    private Object mapVertexToCollectionEntry(AtlasVertex entityVertex, AtlasType arrayElement, Object value, String edgeLabel,
                                              AtlasEntityExtInfo entityExtInfo, boolean isOwnedAttribute, boolean isMinExtInfo) throws AtlasBaseException {
        Object ret = null;

        switch (arrayElement.getTypeCategory()) {
            case PRIMITIVE:
            case ENUM:
            case ARRAY:
            case MAP:
                ret = value;
                break;

            case CLASSIFICATION:
                break;

            case STRUCT:
                ret = mapVertexToStruct(entityVertex, edgeLabel, (AtlasEdge) value, entityExtInfo, isMinExtInfo);
                break;

            case OBJECT_ID_TYPE:
                ret = mapVertexToObjectId(entityVertex, edgeLabel, (AtlasEdge) value, entityExtInfo, isOwnedAttribute, isMinExtInfo);
                break;

            default:
                break;
        }

        return ret;
    }

    private Object mapVertexToPrimitive(AtlasVertex entityVertex, final String vertexPropertyName, AtlasAttributeDef attrDef) {
        Object ret = null;

        if (AtlasGraphUtilsV1.getEncodedProperty(entityVertex, vertexPropertyName, Object.class) == null) {
            return null;
        }

        switch (attrDef.getTypeName().toLowerCase()) {
            case ATLAS_TYPE_STRING:
                ret = AtlasGraphUtilsV1.getEncodedProperty(entityVertex, vertexPropertyName, String.class);
                break;
            case ATLAS_TYPE_SHORT:
                ret = AtlasGraphUtilsV1.getEncodedProperty(entityVertex, vertexPropertyName, Short.class);
                break;
            case ATLAS_TYPE_INT:
                ret = AtlasGraphUtilsV1.getEncodedProperty(entityVertex, vertexPropertyName, Integer.class);
                break;
            case ATLAS_TYPE_BIGINTEGER:
                ret = AtlasGraphUtilsV1.getEncodedProperty(entityVertex, vertexPropertyName, BigInteger.class);
                break;
            case ATLAS_TYPE_BOOLEAN:
                ret = AtlasGraphUtilsV1.getEncodedProperty(entityVertex, vertexPropertyName, Boolean.class);
                break;
            case ATLAS_TYPE_BYTE:
                ret = AtlasGraphUtilsV1.getEncodedProperty(entityVertex, vertexPropertyName, Byte.class);
                break;
            case ATLAS_TYPE_LONG:
                ret = AtlasGraphUtilsV1.getEncodedProperty(entityVertex, vertexPropertyName, Long.class);
                break;
            case ATLAS_TYPE_FLOAT:
                ret = AtlasGraphUtilsV1.getEncodedProperty(entityVertex, vertexPropertyName, Float.class);
                break;
            case ATLAS_TYPE_DOUBLE:
                ret = AtlasGraphUtilsV1.getEncodedProperty(entityVertex, vertexPropertyName, Double.class);
                break;
            case ATLAS_TYPE_BIGDECIMAL:
                ret = AtlasGraphUtilsV1.getEncodedProperty(entityVertex, vertexPropertyName, BigDecimal.class);
                break;
            case ATLAS_TYPE_DATE:
                ret = new Date(AtlasGraphUtilsV1.getEncodedProperty(entityVertex, vertexPropertyName, Long.class));
                break;
            default:
                break;
        }

        return ret;
    }

    private AtlasObjectId mapVertexToObjectId(AtlasVertex entityVertex, String edgeLabel, AtlasEdge edge,
                                              AtlasEntityExtInfo entityExtInfo, boolean isOwnedAttribute, boolean isMinExtInfo) throws AtlasBaseException {
        AtlasObjectId ret = null;

        if (edge == null) {
            edge = graphHelper.getEdgeForLabel(entityVertex, edgeLabel);
        }

        if (GraphHelper.elementExists(edge)) {
            final AtlasVertex referenceVertex = edge.getInVertex();

            if (referenceVertex != null) {
                if (entityExtInfo != null && isOwnedAttribute) {
                    final AtlasEntity entity;

                    if (isMinExtInfo) {
                        entity = mapVertexToAtlasEntityMin(referenceVertex, entityExtInfo);
                    } else {
                        entity = mapVertexToAtlasEntity(referenceVertex, entityExtInfo);
                    }

                    if (entity != null) {
                        ret = AtlasTypeUtil.getAtlasObjectId(entity);
                    }
                } else {
                    ret = new AtlasObjectId(GraphHelper.getGuid(referenceVertex), GraphHelper.getTypeName(referenceVertex));
                }
            }
        }

        return ret;
    }

    private AtlasStruct mapVertexToStruct(AtlasVertex entityVertex, String edgeLabel, AtlasEdge edge, AtlasEntityExtInfo entityExtInfo, boolean isMinExtInfo) throws AtlasBaseException {
        AtlasStruct ret = null;

        if (edge == null) {
            edge = graphHelper.getEdgeForLabel(entityVertex, edgeLabel);
        }

        if (GraphHelper.elementExists(edge)) {
            final AtlasVertex referenceVertex = edge.getInVertex();
            ret = new AtlasStruct(GraphHelper.getTypeName(referenceVertex));

            mapAttributes(referenceVertex, ret, entityExtInfo, isMinExtInfo);
        }

        return ret;
    }

    private Object getVertexAttribute(AtlasVertex vertex, AtlasAttribute attribute) throws AtlasBaseException {
        return vertex != null && attribute != null ? mapVertexToAttribute(vertex, attribute, null, false) : null;
    }

    public AtlasEntityHeader toAtlasEntityHeader(AtlasVertex atlasVertex, Set<String> attributes) throws AtlasBaseException {
        return atlasVertex != null ? mapVertexToAtlasEntityHeader(atlasVertex, attributes) : null;
    }

    public AtlasObjectId toAtlasObjectId(AtlasEntity entity) {
        AtlasObjectId   ret        = null;
        AtlasEntityType entityType = typeRegistry.getEntityTypeByName(entity.getTypeName());

        if (entityType != null) {
            Map<String, Object> uniqueAttributes = new HashMap<>();

            for (String attributeName : entityType.getUniqAttributes().keySet()) {
                Object attrValue = entity.getAttribute(attributeName);

                if (attrValue != null) {
                    uniqueAttributes.put(attributeName, attrValue);
                }
            }

            ret = new AtlasObjectId(entity.getGuid(), entity.getTypeName(), uniqueAttributes);
        }

        return ret;
    }
}
