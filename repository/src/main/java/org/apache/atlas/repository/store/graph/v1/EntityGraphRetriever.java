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

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.atlas.AtlasErrorCode;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.TimeBoundary;
import org.apache.atlas.model.glossary.enums.AtlasTermAssignmentStatus;
import org.apache.atlas.model.glossary.relations.AtlasTermAssignmentHeader;
import org.apache.atlas.model.instance.AtlasClassification;
import org.apache.atlas.model.instance.AtlasClassification.PropagationState;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntitiesWithExtInfo;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntityExtInfo;
import org.apache.atlas.model.instance.AtlasEntity.AtlasEntityWithExtInfo;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.AtlasObjectId;
import org.apache.atlas.model.instance.AtlasRelatedObjectId;
import org.apache.atlas.model.instance.AtlasRelationship;
import org.apache.atlas.model.instance.AtlasRelationship.AtlasRelationshipWithExtInfo;
import org.apache.atlas.model.instance.AtlasStruct;
import org.apache.atlas.model.typedef.AtlasRelationshipDef;
import org.apache.atlas.model.typedef.AtlasRelationshipDef.PropagateTags;
import org.apache.atlas.model.typedef.AtlasRelationshipEndDef;
import org.apache.atlas.model.typedef.AtlasStructDef.AtlasAttributeDef;
import org.apache.atlas.repository.Constants;
import org.apache.atlas.repository.graph.GraphHelper;
import org.apache.atlas.repository.graphdb.AtlasEdge;
import org.apache.atlas.repository.graphdb.AtlasEdgeDirection;
import org.apache.atlas.repository.graphdb.AtlasElement;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.type.AtlasArrayType;
import org.apache.atlas.type.AtlasClassificationType;
import org.apache.atlas.type.AtlasEntityType;
import org.apache.atlas.type.AtlasMapType;
import org.apache.atlas.type.AtlasRelationshipType;
import org.apache.atlas.type.AtlasStructType;
import org.apache.atlas.type.AtlasStructType.AtlasAttribute;
import org.apache.atlas.type.AtlasType;
import org.apache.atlas.type.AtlasTypeRegistry;
import org.apache.atlas.type.AtlasTypeUtil;
import org.apache.atlas.utils.AtlasJson;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.atlas.glossary.GlossaryUtils.*;
import static org.apache.atlas.model.instance.AtlasClassification.PropagationState.ACTIVE;
import static org.apache.atlas.model.instance.AtlasClassification.PropagationState.DELETED;
import static org.apache.atlas.model.typedef.AtlasBaseTypeDef.*;
import static org.apache.atlas.model.typedef.AtlasRelationshipDef.PropagateTags.ONE_TO_TWO;
import static org.apache.atlas.repository.Constants.CLASSIFICATION_ENTITY_GUID;
import static org.apache.atlas.repository.Constants.CLASSIFICATION_LABEL;
import static org.apache.atlas.repository.Constants.CLASSIFICATION_VALIDITY_PERIODS_KEY;
import static org.apache.atlas.repository.Constants.TERM_ASSIGNMENT_LABEL;
import static org.apache.atlas.repository.graph.GraphHelper.EDGE_LABEL_PREFIX;
import static org.apache.atlas.repository.graph.GraphHelper.addToPropagatedTraitNames;
import static org.apache.atlas.repository.graph.GraphHelper.getAdjacentEdgesByLabel;
import static org.apache.atlas.repository.graph.GraphHelper.getAllClassificationEdges;
import static org.apache.atlas.repository.graph.GraphHelper.getAllTraitNames;
import static org.apache.atlas.repository.graph.GraphHelper.getAssociatedEntityVertex;
import static org.apache.atlas.repository.graph.GraphHelper.getBlockedClassificationIds;
import static org.apache.atlas.repository.graph.GraphHelper.getClassificationEdge;
import static org.apache.atlas.repository.graph.GraphHelper.getClassificationEdgeState;
import static org.apache.atlas.repository.graph.GraphHelper.getClassificationVertices;
import static org.apache.atlas.repository.graph.GraphHelper.getGuid;
import static org.apache.atlas.repository.graph.GraphHelper.getIncomingEdgesByLabel;
import static org.apache.atlas.repository.graph.GraphHelper.getOutGoingEdgesByLabel;
import static org.apache.atlas.repository.graph.GraphHelper.getPropagateTags;
import static org.apache.atlas.repository.graph.GraphHelper.getPropagatedClassificationEdge;
import static org.apache.atlas.repository.graph.GraphHelper.getPropagationEnabledClassificationVertices;
import static org.apache.atlas.repository.graph.GraphHelper.getRelationshipGuid;
import static org.apache.atlas.repository.graph.GraphHelper.getTypeName;
import static org.apache.atlas.repository.graph.GraphHelper.isPropagatedClassificationEdge;
import static org.apache.atlas.repository.graph.GraphHelper.isPropagationEnabled;
import static org.apache.atlas.repository.graph.GraphHelper.removeFromPropagatedTraitNames;
import static org.apache.atlas.repository.store.graph.v1.AtlasGraphUtilsV1.getIdFromVertex;
import static org.apache.atlas.type.AtlasStructType.AtlasAttribute.AtlasRelationshipEdgeDirection;
import static org.apache.atlas.type.AtlasStructType.AtlasAttribute.AtlasRelationshipEdgeDirection.BOTH;
import static org.apache.atlas.type.AtlasStructType.AtlasAttribute.AtlasRelationshipEdgeDirection.IN;
import static org.apache.atlas.type.AtlasStructType.AtlasAttribute.AtlasRelationshipEdgeDirection.OUT;


public final class EntityGraphRetriever {
    private static final Logger LOG = LoggerFactory.getLogger(EntityGraphRetriever.class);

    private static final String TERM_RELATION_NAME = "__AtlasGlossarySemanticAssignment";
    private static final String GLOSSARY_TERM_DISPLAY_NAME_ATTR = "__AtlasGlossaryTerm.displayName";

    private final String NAME           = "name";
    private final String DISPLAY_NAME   = "displayName";
    private final String DESCRIPTION    = "description";
    private final String OWNER          = "owner";
    private final String CREATE_TIME    = "createTime";
    private final String QUALIFIED_NAME = "qualifiedName";

    private static final TypeReference<List<TimeBoundary>> TIME_BOUNDARIES_LIST_TYPE = new TypeReference<List<TimeBoundary>>() {};
    private static final GraphHelper graphHelper = GraphHelper.getInstance();

    private final AtlasTypeRegistry typeRegistry;

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
        return toAtlasEntityWithExtInfo(getEntityVertex(guid));
    }

    public AtlasEntityWithExtInfo toAtlasEntityWithExtInfo(AtlasObjectId objId) throws AtlasBaseException {
        return toAtlasEntityWithExtInfo(getEntityVertex(objId));
    }

    public AtlasEntityWithExtInfo toAtlasEntityWithExtInfo(AtlasVertex entityVertex) throws AtlasBaseException {
        AtlasEntityExtInfo     entityExtInfo = new AtlasEntityExtInfo();
        AtlasEntity            entity        = mapVertexToAtlasEntity(entityVertex, entityExtInfo);
        AtlasEntityWithExtInfo ret           = new AtlasEntityWithExtInfo(entity, entityExtInfo);

        ret.compact();

        return ret;
    }

    public AtlasEntitiesWithExtInfo toAtlasEntitiesWithExtInfo(List<String> guids) throws AtlasBaseException {
        AtlasEntitiesWithExtInfo ret = new AtlasEntitiesWithExtInfo();

        for (String guid : guids) {
            AtlasVertex vertex = getEntityVertex(guid);

            AtlasEntity entity = mapVertexToAtlasEntity(vertex, ret);

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

    public AtlasEntityHeader toAtlasEntityHeader(AtlasVertex atlasVertex, Set<String> attributes) throws AtlasBaseException {
        return atlasVertex != null ? mapVertexToAtlasEntityHeader(atlasVertex, attributes) : null;
    }

    public AtlasEntityHeader toAtlasEntityHeaderWithClassifications(String guid) throws AtlasBaseException {
        return toAtlasEntityHeaderWithClassifications(getEntityVertex(guid), Collections.emptySet());
    }

    public AtlasEntityHeader toAtlasEntityHeaderWithClassifications(AtlasVertex entityVertex) throws AtlasBaseException {
        return toAtlasEntityHeaderWithClassifications(entityVertex, Collections.emptySet());
    }

    public AtlasEntityHeader toAtlasEntityHeaderWithClassifications(AtlasVertex entityVertex, Set<String> attributes) throws AtlasBaseException {
        AtlasEntityHeader ret = toAtlasEntityHeader(entityVertex, attributes);

        ret.setClassifications(getAllClassifications(entityVertex));

        return ret;
    }

    public AtlasEntityHeader toAtlasEntityHeader(AtlasEntity entity) {
        AtlasEntityHeader ret        = null;
        String            typeName   = entity.getTypeName();
        AtlasEntityType   entityType = typeRegistry.getEntityTypeByName(typeName);

        if (entityType != null) {
            Map<String, Object> uniqueAttributes = new HashMap<>();

            for (AtlasAttribute attribute : entityType.getUniqAttributes().values()) {
                Object attrValue = entity.getAttribute(attribute.getName());

                if (attrValue != null) {
                    uniqueAttributes.put(attribute.getName(), attrValue);
                }
            }

            ret = new AtlasEntityHeader(entity.getTypeName(), entity.getGuid(), uniqueAttributes);

            if (CollectionUtils.isNotEmpty(entity.getClassifications())) {
                List<AtlasClassification> classifications     = new ArrayList<>(entity.getClassifications().size());
                List<String>              classificationNames = new ArrayList<>(entity.getClassifications().size());

                for (AtlasClassification classification : entity.getClassifications()) {
                    classifications.add(classification);
                    classificationNames.add(classification.getTypeName());
                }

                ret.setClassifications(classifications);
                ret.setClassificationNames(classificationNames);
            }

            if (CollectionUtils.isNotEmpty(entity.getMeanings())) {
                ret.setMeanings(entity.getMeanings());
                ret.setMeaningNames(entity.getMeanings().stream().map(AtlasTermAssignmentHeader::getDisplayText).collect(Collectors.toList()));
            }
        }

        return ret;
    }

    public AtlasObjectId toAtlasObjectId(AtlasVertex entityVertex) throws AtlasBaseException {
        AtlasObjectId   ret        = null;
        String          typeName   = entityVertex.getProperty(Constants.TYPE_NAME_PROPERTY_KEY, String.class);
        AtlasEntityType entityType = typeRegistry.getEntityTypeByName(typeName);

        if (entityType != null) {
            Map<String, Object> uniqueAttributes = new HashMap<>();

            for (AtlasAttribute attribute : entityType.getUniqAttributes().values()) {
                Object attrValue = getVertexAttribute(entityVertex, attribute);

                if (attrValue != null) {
                    uniqueAttributes.put(attribute.getName(), attrValue);
                }
            }

            ret = new AtlasObjectId(entityVertex.getProperty(Constants.GUID_PROPERTY_KEY, String.class), typeName, uniqueAttributes);
        }

        return ret;
    }

    public AtlasClassification toAtlasClassification(AtlasVertex classificationVertex) throws AtlasBaseException {
        AtlasClassification ret = new AtlasClassification(getTypeName(classificationVertex));

        ret.setEntityGuid(AtlasGraphUtilsV1.getProperty(classificationVertex, CLASSIFICATION_ENTITY_GUID, String.class));
        ret.setPropagate(isPropagationEnabled(classificationVertex));

        String strValidityPeriods = AtlasGraphUtilsV1.getProperty(classificationVertex, CLASSIFICATION_VALIDITY_PERIODS_KEY, String.class);

        if (strValidityPeriods != null) {
            ret.setValidityPeriods(AtlasJson.fromJson(strValidityPeriods, TIME_BOUNDARIES_LIST_TYPE));
        }

        mapAttributes(classificationVertex, ret, null);

        return ret;
    }

    public AtlasVertex getReferencedEntityVertex(AtlasEdge edge, AtlasRelationshipEdgeDirection relationshipDirection, AtlasVertex parentVertex) throws AtlasBaseException {
        AtlasVertex entityVertex = null;

        if (relationshipDirection == OUT) {
            entityVertex = edge.getInVertex();
        } else if (relationshipDirection == IN) {
            entityVertex = edge.getOutVertex();
        } else if (relationshipDirection == BOTH){
            // since relationship direction is BOTH, edge direction can be inward or outward
            // compare with parent entity vertex and pick the right reference vertex
            if (StringUtils.equals(GraphHelper.getGuid(parentVertex), GraphHelper.getGuid(edge.getOutVertex()))) {
                entityVertex = edge.getInVertex();
            } else {
                entityVertex = edge.getOutVertex();
            }
        }

        return entityVertex;
    }

    public AtlasVertex getEntityVertex(String guid) throws AtlasBaseException {
        AtlasVertex ret = AtlasGraphUtilsV1.findByGuid(guid);

        if (ret == null) {
            throw new AtlasBaseException(AtlasErrorCode.INSTANCE_GUID_NOT_FOUND, guid);
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
        String      guid   = getGuid(entityVertex);
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

            mapAttributes(entityVertex, entity, entityExtInfo);

            mapRelationshipAttributes(entityVertex, entity);

            mapClassifications(entityVertex, entity);
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
        ret.setClassificationNames(getAllTraitNames(entityVertex));

        List<AtlasTermAssignmentHeader> termAssignmentHeaders = mapAssignedTerms(entityVertex);
        ret.setMeanings(termAssignmentHeaders);
        ret.setMeaningNames(termAssignmentHeaders.stream().map(AtlasTermAssignmentHeader::getDisplayText).collect(Collectors.toList()));

        AtlasEntityType entityType = typeRegistry.getEntityTypeByName(typeName);

        if (entityType != null) {
            for (AtlasAttribute uniqueAttribute : entityType.getUniqAttributes().values()) {
                Object attrValue = getVertexAttribute(entityVertex, uniqueAttribute);

                if (attrValue != null) {
                    ret.setAttribute(uniqueAttribute.getName(), attrValue);
                }
            }

            Object name        = getVertexAttribute(entityVertex, entityType.getAttribute(NAME));
            Object description = getVertexAttribute(entityVertex, entityType.getAttribute(DESCRIPTION));
            Object owner       = getVertexAttribute(entityVertex, entityType.getAttribute(OWNER));
            Object createTime  = getVertexAttribute(entityVertex, entityType.getAttribute(CREATE_TIME));
            Object displayText = name != null ? name : ret.getAttribute(QUALIFIED_NAME);

            ret.setAttribute(NAME, name);
            ret.setAttribute(DESCRIPTION, description);
            ret.setAttribute(OWNER, owner);
            ret.setAttribute(CREATE_TIME, createTime);

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

        entity.setGuid(getGuid(entityVertex));
        entity.setTypeName(getTypeName(entityVertex));
        entity.setStatus(GraphHelper.getStatus(entityVertex));
        entity.setVersion(GraphHelper.getVersion(entityVertex));

        entity.setCreatedBy(GraphHelper.getCreatedByAsString(entityVertex));
        entity.setUpdatedBy(GraphHelper.getModifiedByAsString(entityVertex));

        entity.setCreateTime(new Date(GraphHelper.getCreatedTime(entityVertex)));
        entity.setUpdateTime(new Date(GraphHelper.getModifiedTime(entityVertex)));

        return entity;
    }

    private void mapAttributes(AtlasVertex entityVertex, AtlasStruct struct, AtlasEntityExtInfo entityExtInfo) throws AtlasBaseException {
        AtlasType objType = typeRegistry.getType(struct.getTypeName());

        if (!(objType instanceof AtlasStructType)) {
            throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_INVALID, struct.getTypeName());
        }

        AtlasStructType structType = (AtlasStructType) objType;

        for (AtlasAttribute attribute : structType.getAllAttributes().values()) {
            Object attrValue = mapVertexToAttribute(entityVertex, attribute, entityExtInfo);

            struct.setAttribute(attribute.getName(), attrValue);
        }
    }

    public List<AtlasClassification> getAllClassifications(AtlasVertex entityVertex) throws AtlasBaseException {
        List<AtlasClassification> ret   = new ArrayList<>();
        Iterable                  edges = entityVertex.query().direction(AtlasEdgeDirection.OUT).label(CLASSIFICATION_LABEL).edges();

        if (edges != null) {
            Iterator<AtlasEdge> iterator = edges.iterator();

            while (iterator.hasNext()) {
                AtlasEdge edge = iterator.next();

                if (edge != null) {
                    ret.add(toAtlasClassification(edge.getInVertex()));
                }
            }
        }

        return ret;
    }

    public List<AtlasTermAssignmentHeader> mapAssignedTerms(AtlasVertex entityVertex) throws AtlasBaseException {
        List<AtlasTermAssignmentHeader> ret = new ArrayList<>();

        Iterable edges = entityVertex.query().direction(AtlasEdgeDirection.IN).label(TERM_ASSIGNMENT_LABEL).edges();

        if (edges != null) {
            for (final AtlasEdge edge : (Iterable<AtlasEdge>) edges) {
                if (edge != null && GraphHelper.getStatus(edge) != AtlasEntity.Status.DELETED) {
                    ret.add(toTermAssignmentHeader(edge));
                }
            }
        }

        return ret;
    }

    private AtlasTermAssignmentHeader toTermAssignmentHeader(final AtlasEdge edge) {
        AtlasTermAssignmentHeader ret = new AtlasTermAssignmentHeader();

        AtlasVertex termVertex = edge.getOutVertex();

        String guid = GraphHelper.getGuid(termVertex);
        if (guid != null) {
            ret.setTermGuid(guid);
        }

        String relationGuid = edge.getProperty(Constants.RELATIONSHIP_GUID_PROPERTY_KEY, String.class);
        if (relationGuid != null) {
            ret.setRelationGuid(relationGuid);
        }

        Object displayName = GraphHelper.getProperty(termVertex, GLOSSARY_TERM_DISPLAY_NAME_ATTR);
        if (displayName instanceof String) {
            ret.setDisplayText((String) displayName);
        }

        String description = edge.getProperty(TERM_ASSIGNMENT_ATTR_DESCRIPTION, String.class);
        if (description != null) {
            ret.setDescription(description);
        }

        String expression    = edge.getProperty(TERM_ASSIGNMENT_ATTR_EXPRESSION, String.class);
        if (expression != null) {
            ret.setExpression(expression);
        }

        String status = edge.getProperty(TERM_ASSIGNMENT_ATTR_STATUS, String.class);
        if (status != null) {
            AtlasTermAssignmentStatus assignmentStatus = AtlasTermAssignmentStatus.valueOf(status);
            ret.setStatus(assignmentStatus);
        }

        Integer confidence = edge.getProperty(TERM_ASSIGNMENT_ATTR_CONFIDENCE, Integer.class);
        if (confidence != null) {
            ret.setConfidence(confidence);
        }

        String createdBy = edge.getProperty(TERM_ASSIGNMENT_ATTR_CREATED_BY, String.class);
        if (createdBy != null) {
            ret.setCreatedBy(createdBy);
        }

        String steward = edge.getProperty(TERM_ASSIGNMENT_ATTR_STEWARD, String.class);
        if (steward != null) {
            ret.setSteward(steward);
        }

        String source = edge.getProperty(TERM_ASSIGNMENT_ATTR_SOURCE, String.class);
        if (source != null) {
            ret.setSource(source);
        }

        return ret;
    }

    private void mapClassifications(AtlasVertex entityVertex, AtlasEntity entity) throws AtlasBaseException {
        List<AtlasEdge> edges = getAllClassificationEdges(entityVertex);

        if (CollectionUtils.isNotEmpty(edges)) {
            List<AtlasClassification> allClassifications                 = new ArrayList<>();
            List<AtlasClassification> propagationDisabledClassifications = new ArrayList<>();

            for (AtlasEdge edge : edges) {
                PropagationState edgeState            = getClassificationEdgeState(edge);
                AtlasVertex      classificationVertex = edge.getInVertex();

                if (edgeState == ACTIVE) {
                    allClassifications.add(toAtlasClassification(classificationVertex));
                } else if (edgeState == DELETED && isPropagatedClassificationEdge(edge)) {
                    propagationDisabledClassifications.add(toAtlasClassification(classificationVertex));
                }
            }

            entity.setClassifications(allClassifications);
            entity.setPropagationDisabledClassifications(propagationDisabledClassifications);
        }
    }

    private Object mapVertexToAttribute(AtlasVertex entityVertex, AtlasAttribute attribute, AtlasEntityExtInfo entityExtInfo) throws AtlasBaseException {
        Object    ret                = null;
        AtlasType attrType           = attribute.getAttributeType();
        String    vertexPropertyName = attribute.getQualifiedName();
        String    edgeLabel          = EDGE_LABEL_PREFIX + vertexPropertyName;
        boolean   isOwnedAttribute   = attribute.isOwnedRef();
        AtlasRelationshipEdgeDirection edgeDirection = attribute.getRelationshipEdgeDirection();

        if (LOG.isDebugEnabled()) {
            LOG.debug("Mapping vertex {} to atlas entity {}.{}", entityVertex, attribute.getDefinedInDef().getName(), attribute.getName());
        }

        switch (attrType.getTypeCategory()) {
            case PRIMITIVE:
                ret = mapVertexToPrimitive(entityVertex, vertexPropertyName, attribute.getAttributeDef());
                break;
            case ENUM:
                ret = GraphHelper.getProperty(entityVertex, vertexPropertyName);
                break;
            case STRUCT:
                ret = mapVertexToStruct(entityVertex, edgeLabel, null, entityExtInfo);
                break;
            case OBJECT_ID_TYPE:
                ret = mapVertexToObjectId(entityVertex, edgeLabel, null, entityExtInfo, isOwnedAttribute, edgeDirection);
                break;
            case ARRAY:
                ret = mapVertexToArray(entityVertex, (AtlasArrayType) attrType, vertexPropertyName, entityExtInfo, isOwnedAttribute, edgeDirection);
                break;
            case MAP:
                ret = mapVertexToMap(entityVertex, (AtlasMapType) attrType, vertexPropertyName, entityExtInfo, isOwnedAttribute, edgeDirection);
                break;
            case CLASSIFICATION:
                // do nothing
                break;
        }

        return ret;
    }

    private Map<String, Object> mapVertexToMap(AtlasVertex entityVertex, AtlasMapType atlasMapType, final String propertyName,
                                               AtlasEntityExtInfo entityExtInfo, boolean isOwnedAttribute,
                                               AtlasRelationshipEdgeDirection edgeDirection) throws AtlasBaseException {

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

            Object mapValue = mapVertexToCollectionEntry(entityVertex, mapValueType, keyValue, edgeLabel,
                                                         entityExtInfo, isOwnedAttribute, edgeDirection);

            if (mapValue != null) {
                ret.put(mapKey, mapValue);
            }
        }

        return ret;
    }

    private List<Object> mapVertexToArray(AtlasVertex entityVertex, AtlasArrayType arrayType, String propertyName,
                                          AtlasEntityExtInfo entityExtInfo, boolean isOwnedAttribute,
                                          AtlasRelationshipEdgeDirection edgeDirection)  throws AtlasBaseException {

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
            // When internal types are deleted, sometimes the collection type attribute will contain a null value
            // Graph layer does erroneous mapping of the null element, hence avoiding the processing of the null element
            if (element == null) {
                LOG.debug("Skipping null arrayElement");
                continue;
            }

            Object arrValue = mapVertexToCollectionEntry(entityVertex, arrayElementType, element, edgeLabel,
                                                         entityExtInfo, isOwnedAttribute, edgeDirection);

            if (arrValue != null) {
                arrValues.add(arrValue);
            }
        }

        return arrValues;
    }

    private Object mapVertexToCollectionEntry(AtlasVertex entityVertex, AtlasType arrayElement, Object value,
                                              String edgeLabel, AtlasEntityExtInfo entityExtInfo, boolean isOwnedAttribute,
                                              AtlasRelationshipEdgeDirection edgeDirection) throws AtlasBaseException {
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
                ret = mapVertexToStruct(entityVertex, edgeLabel, (AtlasEdge) value, entityExtInfo);
                break;

            case OBJECT_ID_TYPE:
                ret = mapVertexToObjectId(entityVertex, edgeLabel, (AtlasEdge) value, entityExtInfo, isOwnedAttribute, edgeDirection);
                break;

            default:
                break;
        }

        return ret;
    }

    public Object mapVertexToPrimitive(AtlasElement entityVertex, final String vertexPropertyName, AtlasAttributeDef attrDef) {
        Object ret = null;

        if (GraphHelper.getSingleValuedProperty(entityVertex, vertexPropertyName, Object.class) == null) {
            return null;
        }

        switch (attrDef.getTypeName().toLowerCase()) {
            case ATLAS_TYPE_STRING:
                ret = GraphHelper.getSingleValuedProperty(entityVertex, vertexPropertyName, String.class);
                break;
            case ATLAS_TYPE_SHORT:
                ret = GraphHelper.getSingleValuedProperty(entityVertex, vertexPropertyName, Short.class);
                break;
            case ATLAS_TYPE_INT:
                ret = GraphHelper.getSingleValuedProperty(entityVertex, vertexPropertyName, Integer.class);
                break;
            case ATLAS_TYPE_BIGINTEGER:
                ret = GraphHelper.getSingleValuedProperty(entityVertex, vertexPropertyName, BigInteger.class);
                break;
            case ATLAS_TYPE_BOOLEAN:
                ret = GraphHelper.getSingleValuedProperty(entityVertex, vertexPropertyName, Boolean.class);
                break;
            case ATLAS_TYPE_BYTE:
                ret = GraphHelper.getSingleValuedProperty(entityVertex, vertexPropertyName, Byte.class);
                break;
            case ATLAS_TYPE_LONG:
                ret = GraphHelper.getSingleValuedProperty(entityVertex, vertexPropertyName, Long.class);
                break;
            case ATLAS_TYPE_FLOAT:
                ret = GraphHelper.getSingleValuedProperty(entityVertex, vertexPropertyName, Float.class);
                break;
            case ATLAS_TYPE_DOUBLE:
                ret = GraphHelper.getSingleValuedProperty(entityVertex, vertexPropertyName, Double.class);
                break;
            case ATLAS_TYPE_BIGDECIMAL:
                ret = GraphHelper.getSingleValuedProperty(entityVertex, vertexPropertyName, BigDecimal.class);
                break;
            case ATLAS_TYPE_DATE:
                ret = new Date(GraphHelper.getSingleValuedProperty(entityVertex, vertexPropertyName, Long.class));
                break;
            default:
                break;
        }

        return ret;
    }

    private AtlasObjectId mapVertexToObjectId(AtlasVertex entityVertex, String edgeLabel, AtlasEdge edge,
                                              AtlasEntityExtInfo entityExtInfo, boolean isOwnedAttribute,
                                              AtlasRelationshipEdgeDirection edgeDirection) throws AtlasBaseException {
        AtlasObjectId ret = null;

        if (edge == null) {
            edge = graphHelper.getEdgeForLabel(entityVertex, edgeLabel, edgeDirection);
        }

        if (GraphHelper.elementExists(edge)) {
            AtlasVertex referenceVertex = edge.getInVertex();

            if (StringUtils.equals(getIdFromVertex(referenceVertex), getIdFromVertex(entityVertex))) {
                referenceVertex = edge.getOutVertex();
            }

            if (referenceVertex != null) {
                if (entityExtInfo != null && isOwnedAttribute) {
                    AtlasEntity entity = mapVertexToAtlasEntity(referenceVertex, entityExtInfo);

                    if (entity != null) {
                        ret = AtlasTypeUtil.getAtlasObjectId(entity);
                    }
                } else {
                    ret = new AtlasObjectId(getGuid(referenceVertex), getTypeName(referenceVertex));
                }
            }
        }

        return ret;
    }

    private AtlasStruct mapVertexToStruct(AtlasVertex entityVertex, String edgeLabel, AtlasEdge edge, AtlasEntityExtInfo entityExtInfo) throws AtlasBaseException {
        AtlasStruct ret = null;

        if (edge == null) {
            edge = graphHelper.getEdgeForLabel(entityVertex, edgeLabel);
        }

        if (GraphHelper.elementExists(edge)) {
            final AtlasVertex referenceVertex = edge.getInVertex();
            ret = new AtlasStruct(getTypeName(referenceVertex));

            mapAttributes(referenceVertex, ret, entityExtInfo);
        }

        return ret;
    }

    private Object getVertexAttribute(AtlasVertex vertex, AtlasAttribute attribute) throws AtlasBaseException {
        return vertex != null && attribute != null ? mapVertexToAttribute(vertex, attribute, null) : null;
    }

    private void mapRelationshipAttributes(AtlasVertex entityVertex, AtlasEntity entity) throws AtlasBaseException {
        AtlasEntityType entityType = typeRegistry.getEntityTypeByName(entity.getTypeName());

        if (entityType == null) {
            throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_INVALID, entity.getTypeName());
        }

        for (AtlasAttribute attribute : entityType.getRelationshipAttributes().values()) {
            Object attrValue = mapVertexToRelationshipAttribute(entityVertex, entityType, attribute);

            entity.setRelationshipAttribute(attribute.getName(), attrValue);
        }
    }

    private Object mapVertexToRelationshipAttribute(AtlasVertex entityVertex, AtlasEntityType entityType, AtlasAttribute attribute) throws AtlasBaseException {
        Object               ret             = null;
        AtlasRelationshipDef relationshipDef = graphHelper.getRelationshipDef(entityVertex, entityType, attribute.getName());

        if (relationshipDef == null) {
            throw new AtlasBaseException(AtlasErrorCode.RELATIONSHIPDEF_INVALID, "relationshipDef is null");
        }

        AtlasRelationshipEndDef endDef1         = relationshipDef.getEndDef1();
        AtlasRelationshipEndDef endDef2         = relationshipDef.getEndDef2();
        AtlasEntityType         endDef1Type     = typeRegistry.getEntityTypeByName(endDef1.getType());
        AtlasEntityType         endDef2Type     = typeRegistry.getEntityTypeByName(endDef2.getType());
        AtlasRelationshipEndDef attributeEndDef = null;

        if (endDef1Type.isTypeOrSuperTypeOf(entityType.getTypeName()) && StringUtils.equals(endDef1.getName(), attribute.getName())) {
            attributeEndDef = endDef1;
        } else if (endDef2Type.isTypeOrSuperTypeOf(entityType.getTypeName()) && StringUtils.equals(endDef2.getName(), attribute.getName())) {
            attributeEndDef = endDef2;
        }

        if (attributeEndDef == null) {
            throw new AtlasBaseException(AtlasErrorCode.RELATIONSHIPDEF_INVALID, relationshipDef.toString());
        }

        switch (attributeEndDef.getCardinality()) {
            case SINGLE:
                ret = mapRelatedVertexToObjectId(entityVertex, attribute);
                break;

            case LIST:
            case SET:
                ret = mapRelationshipArrayAttribute(entityVertex, attribute);
                break;
        }

        return ret;
    }

    private AtlasObjectId mapRelatedVertexToObjectId(AtlasVertex entityVertex, AtlasAttribute attribute) throws AtlasBaseException {
        AtlasEdge edge = graphHelper.getEdgeForLabel(entityVertex, attribute.getRelationshipEdgeLabel(), attribute.getRelationshipEdgeDirection());

        return mapVertexToRelatedObjectId(entityVertex, edge);
    }

    private List<AtlasRelatedObjectId> mapRelationshipArrayAttribute(AtlasVertex entityVertex, AtlasAttribute attribute) throws AtlasBaseException {
        List<AtlasRelatedObjectId> ret   = new ArrayList<>();
        Iterator<AtlasEdge>        edges = null;

        if (attribute.getRelationshipEdgeDirection() == IN) {
            edges = getIncomingEdgesByLabel(entityVertex, attribute.getRelationshipEdgeLabel());
        } else if (attribute.getRelationshipEdgeDirection() == OUT) {
            edges = getOutGoingEdgesByLabel(entityVertex, attribute.getRelationshipEdgeLabel());
        } else if (attribute.getRelationshipEdgeDirection() == BOTH) {
            edges = getAdjacentEdgesByLabel(entityVertex, AtlasEdgeDirection.BOTH, attribute.getRelationshipEdgeLabel());
        }

        if (edges != null) {
            while (edges.hasNext()) {
                AtlasEdge relationshipEdge = edges.next();

                AtlasRelatedObjectId relatedObjectId = mapVertexToRelatedObjectId(entityVertex, relationshipEdge);

                ret.add(relatedObjectId);
            }
        }

        return ret;
    }

    private AtlasRelatedObjectId mapVertexToRelatedObjectId(AtlasVertex entityVertex, AtlasEdge edge) throws AtlasBaseException {
        AtlasRelatedObjectId ret = null;

        if (GraphHelper.elementExists(edge)) {
            AtlasVertex referenceVertex = edge.getInVertex();

            if (StringUtils.equals(getIdFromVertex(referenceVertex), getIdFromVertex(entityVertex))) {
                referenceVertex = edge.getOutVertex();
            }

            if (referenceVertex != null) {
                String            entityTypeName = getTypeName(referenceVertex);
                String            entityGuid     = getGuid(referenceVertex);
                AtlasRelationship relationship   = mapEdgeToAtlasRelationship(edge);

                ret = new AtlasRelatedObjectId(entityGuid, entityTypeName,
                                               relationship.getGuid(), relationship.getStatus(),
                                               new AtlasStruct(relationship.getTypeName(), relationship.getAttributes()));

                Object displayText = getDisplayText(referenceVertex, entityTypeName);

                if (displayText != null) {
                    ret.setDisplayText(displayText.toString());
                }
            }
        }

        return ret;
    }

    private Object getDisplayText(AtlasVertex entityVertex, String entityTypeName) throws AtlasBaseException {
        AtlasEntityType entityType = typeRegistry.getEntityTypeByName(entityTypeName);
        Object          ret        = null;

        if (entityType != null) {
            ret = getVertexAttribute(entityVertex, entityType.getAttribute(NAME));

            if (ret == null) {
                ret = getVertexAttribute(entityVertex, entityType.getAttribute(DISPLAY_NAME));
            }

            if (ret == null) {
                ret = getVertexAttribute(entityVertex, entityType.getAttribute(QUALIFIED_NAME));
            }
        }

        return ret;
    }

    public AtlasRelationship mapEdgeToAtlasRelationship(AtlasEdge edge) throws AtlasBaseException {
        return mapEdgeToAtlasRelationship(edge, false).getRelationship();
    }

    public AtlasRelationshipWithExtInfo mapEdgeToAtlasRelationshipWithExtInfo(AtlasEdge edge) throws AtlasBaseException {
        return mapEdgeToAtlasRelationship(edge, true);
    }

    public AtlasRelationshipWithExtInfo mapEdgeToAtlasRelationship(AtlasEdge edge, boolean extendedInfo) throws AtlasBaseException {
        AtlasRelationshipWithExtInfo ret = new AtlasRelationshipWithExtInfo();

        mapSystemAttributes(edge, ret, extendedInfo);

        mapAttributes(edge, ret);

        return ret;
    }

    private AtlasRelationship.AtlasRelationshipWithExtInfo mapSystemAttributes(AtlasEdge edge, AtlasRelationshipWithExtInfo relationshipWithExtInfo, boolean extendedInfo) throws AtlasBaseException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Mapping system attributes for relationship");
        }

        AtlasRelationship relationship = relationshipWithExtInfo.getRelationship();

        if (relationship == null) {
            relationship = new AtlasRelationship();

            relationshipWithExtInfo.setRelationship(relationship);
        }

        relationship.setGuid(getRelationshipGuid(edge));
        relationship.setTypeName(getTypeName(edge));

        relationship.setCreatedBy(GraphHelper.getCreatedByAsString(edge));
        relationship.setUpdatedBy(GraphHelper.getModifiedByAsString(edge));

        relationship.setCreateTime(new Date(GraphHelper.getCreatedTime(edge)));
        relationship.setUpdateTime(new Date(GraphHelper.getModifiedTime(edge)));

        Long version = GraphHelper.getVersion(edge);

        if (version == null) {
            version = Long.valueOf(1L);
        }

        relationship.setVersion(version);
        relationship.setStatus(GraphHelper.getEdgeStatus(edge));

        AtlasVertex end1Vertex = edge.getOutVertex();
        AtlasVertex end2Vertex = edge.getInVertex();

        relationship.setEnd1(new AtlasObjectId(getGuid(end1Vertex), getTypeName(end1Vertex)));
        relationship.setEnd2(new AtlasObjectId(getGuid(end2Vertex), getTypeName(end2Vertex)));

        relationship.setLabel(edge.getLabel());
        relationship.setPropagateTags(getPropagateTags(edge));

        if (extendedInfo) {
            addToReferredEntities(relationshipWithExtInfo, end1Vertex);
            addToReferredEntities(relationshipWithExtInfo, end2Vertex);
        }

        // set propagated and blocked propagated classifications
        readClassificationsFromEdge(edge, relationshipWithExtInfo, extendedInfo);

        return relationshipWithExtInfo;
    }

    private void readClassificationsFromEdge(AtlasEdge edge, AtlasRelationshipWithExtInfo relationshipWithExtInfo, boolean extendedInfo) throws AtlasBaseException {
        List<AtlasVertex>         classificationVertices    = getClassificationVertices(edge);
        List<String>              blockedClassificationIds  = getBlockedClassificationIds(edge);
        List<AtlasClassification> propagatedClassifications = new ArrayList<>();
        List<AtlasClassification> blockedClassifications    = new ArrayList<>();
        AtlasRelationship         relationship              = relationshipWithExtInfo.getRelationship();

        for (AtlasVertex classificationVertex : classificationVertices) {
            String              classificationId = classificationVertex.getIdForDisplay();
            AtlasClassification classification   = toAtlasClassification(classificationVertex);
            String              entityGuid       = classification.getEntityGuid();

            if (blockedClassificationIds.contains(classificationId)) {
                blockedClassifications.add(classification);
            } else {
                propagatedClassifications.add(classification);
            }

            // add entity headers to referred entities
            if (extendedInfo) {
                addToReferredEntities(relationshipWithExtInfo, entityGuid);
            }
        }

        relationship.setPropagatedClassifications(propagatedClassifications);
        relationship.setBlockedPropagatedClassifications(blockedClassifications);
    }

    private void addToReferredEntities(AtlasRelationshipWithExtInfo relationshipWithExtInfo, String guid) throws AtlasBaseException {
        if (!relationshipWithExtInfo.referredEntitiesContains(guid)) {
            addToReferredEntities(relationshipWithExtInfo, getEntityVertex(guid));
        }
    }

    private void addToReferredEntities(AtlasRelationshipWithExtInfo relationshipWithExtInfo, AtlasVertex entityVertex) throws AtlasBaseException {
        String entityGuid = getGuid(entityVertex);

        if (!relationshipWithExtInfo.referredEntitiesContains(entityGuid)) {
            relationshipWithExtInfo.addReferredEntity(entityGuid, toAtlasEntityHeader(entityVertex));
        }
    }

    private void mapAttributes(AtlasEdge edge, AtlasRelationshipWithExtInfo relationshipWithExtInfo) throws AtlasBaseException {
        AtlasRelationship relationship = relationshipWithExtInfo.getRelationship();
        AtlasType         objType      = typeRegistry.getType(relationship.getTypeName());

        if (!(objType instanceof AtlasRelationshipType)) {
            throw new AtlasBaseException(AtlasErrorCode.TYPE_NAME_INVALID, relationship.getTypeName());
        }

        AtlasRelationshipType relationshipType = (AtlasRelationshipType) objType;

        for (AtlasAttribute attribute : relationshipType.getAllAttributes().values()) {
            // mapping only primitive attributes
            Object attrValue = mapVertexToPrimitive(edge, attribute.getQualifiedName(), attribute.getAttributeDef());

            relationship.setAttribute(attribute.getName(), attrValue);
        }
    }
}