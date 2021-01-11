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

package org.apache.atlas.repository.graph;

import com.google.common.annotations.VisibleForTesting;
import org.apache.atlas.ApplicationProperties;
import org.apache.atlas.AtlasException;
import org.apache.atlas.GraphTransactionInterceptor;
import org.apache.atlas.RequestContext;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntity.Status;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.repository.Constants;
import org.apache.atlas.repository.RepositoryException;
import org.apache.atlas.repository.graphdb.AtlasEdge;
import org.apache.atlas.repository.graphdb.AtlasEdgeDirection;
import org.apache.atlas.repository.graphdb.AtlasElement;
import org.apache.atlas.repository.graphdb.AtlasGraph;
import org.apache.atlas.repository.graphdb.AtlasGraphQuery;
import org.apache.atlas.repository.graphdb.AtlasVertex;
import org.apache.atlas.repository.store.graph.v1.AtlasGraphUtilsV1;
import org.apache.atlas.type.AtlasType;
import org.apache.atlas.typesystem.IReferenceableInstance;
import org.apache.atlas.typesystem.ITypedInstance;
import org.apache.atlas.typesystem.ITypedReferenceableInstance;
import org.apache.atlas.typesystem.Referenceable;
import org.apache.atlas.typesystem.exception.EntityNotFoundException;
import org.apache.atlas.typesystem.exception.TypeNotFoundException;
import org.apache.atlas.typesystem.json.InstanceSerialization;
import org.apache.atlas.typesystem.persistence.Id;
import org.apache.atlas.typesystem.persistence.ReferenceableInstance;
import org.apache.atlas.typesystem.types.AttributeInfo;
import org.apache.atlas.typesystem.types.ClassType;
import org.apache.atlas.typesystem.types.DataTypes;
import org.apache.atlas.typesystem.types.DataTypes.TypeCategory;
import org.apache.atlas.typesystem.types.HierarchicalType;
import org.apache.atlas.typesystem.types.IDataType;
import org.apache.atlas.typesystem.types.Multiplicity;
import org.apache.atlas.typesystem.types.TypeSystem;
import org.apache.atlas.typesystem.types.ValueConversionException;
import org.apache.atlas.typesystem.types.utils.TypesUtil;
import org.apache.atlas.util.AttributeValueMap;
import org.apache.atlas.util.IndexedInstance;
import org.apache.atlas.utils.ParamChecker;
import org.apache.commons.collections.CollectionUtils;
import org.codehaus.jettison.json.JSONArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;


/**
 * Utility class for graph operations.
 */
public final class GraphHelper {

    private static final Logger LOG = LoggerFactory.getLogger(GraphHelper.class);
    public static final String EDGE_LABEL_PREFIX = "__";

    private static final TypeSystem typeSystem = TypeSystem.getInstance();

    public static final String RETRY_COUNT = "atlas.graph.storage.num.retries";
    public static final String RETRY_DELAY = "atlas.graph.storage.retry.sleeptime.ms";

    private static volatile GraphHelper INSTANCE;

    private final AtlasGraph                 graph;
    private final GraphToTypedInstanceMapper graphToInstanceMapper;

    private static int maxRetries;
    public static long retrySleepTimeMillis;

    @VisibleForTesting
    GraphHelper(AtlasGraph graph) {
        this.graph                 = graph;
        this.graphToInstanceMapper = new GraphToTypedInstanceMapper(graph);

        try {
            maxRetries = ApplicationProperties.get().getInt(RETRY_COUNT, 3);
            retrySleepTimeMillis = ApplicationProperties.get().getLong(RETRY_DELAY, 1000);
        } catch (AtlasException e) {
            LOG.error("Could not load configuration. Setting to default value for " + RETRY_COUNT, e);
        }
    }

    public static GraphHelper getInstance() {
        if ( INSTANCE == null) {
            synchronized (GraphHelper.class) {
                if (INSTANCE == null) {
                    INSTANCE = new GraphHelper(AtlasGraphProvider.getGraphInstance());
                }
            }
        }
        return INSTANCE;
    }

    @VisibleForTesting
    static GraphHelper getInstance(AtlasGraph graph) {
        if ( INSTANCE == null) {
            synchronized (GraphHelper.class) {
                if (INSTANCE == null) {
                    INSTANCE = new GraphHelper(graph);
                }
            }
        }
        return INSTANCE;
    }


    public AtlasVertex createVertexWithIdentity(ITypedReferenceableInstance typedInstance, Set<String> superTypeNames) {
        final String guid = UUID.randomUUID().toString();

        final AtlasVertex vertexWithIdentity = createVertexWithoutIdentity(typedInstance.getTypeName(),
                new Id(guid, 0, typedInstance.getTypeName()), superTypeNames);

        // add identity
        AtlasGraphUtilsV1.setEncodedProperty(vertexWithIdentity, Constants.GUID_PROPERTY_KEY, guid);

        // add version information
        AtlasGraphUtilsV1.setEncodedProperty(vertexWithIdentity, Constants.VERSION_PROPERTY_KEY, typedInstance.getId().version);

        return vertexWithIdentity;
    }

    public AtlasVertex createVertexWithoutIdentity(String typeName, Id typedInstanceId, Set<String> superTypeNames) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Creating AtlasVertex for type {} id {}", typeName,
                      typedInstanceId != null ? typedInstanceId._getId() : null);
        }

        final AtlasVertex vertexWithoutIdentity = graph.addVertex();

        // add type information
        AtlasGraphUtilsV1.setEncodedProperty(vertexWithoutIdentity, Constants.ENTITY_TYPE_PROPERTY_KEY, typeName);


        // add super types
        for (String superTypeName : superTypeNames) {
            AtlasGraphUtilsV1.addEncodedProperty(vertexWithoutIdentity, Constants.SUPER_TYPES_PROPERTY_KEY, superTypeName);
        }

        // add state information
        AtlasGraphUtilsV1.setEncodedProperty(vertexWithoutIdentity, Constants.STATE_PROPERTY_KEY, Id.EntityState.ACTIVE.name());

        // add timestamp information
        AtlasGraphUtilsV1.setEncodedProperty(vertexWithoutIdentity, Constants.TIMESTAMP_PROPERTY_KEY, RequestContext.get().getRequestTime());
        AtlasGraphUtilsV1.setEncodedProperty(vertexWithoutIdentity, Constants.MODIFICATION_TIMESTAMP_PROPERTY_KEY, RequestContext.get().getRequestTime());
        AtlasGraphUtilsV1.setEncodedProperty(vertexWithoutIdentity, Constants.CREATED_BY_KEY, RequestContext.get().getUser());
        AtlasGraphUtilsV1.setEncodedProperty(vertexWithoutIdentity, Constants.MODIFIED_BY_KEY, RequestContext.get().getUser());

        return vertexWithoutIdentity;
    }

    private AtlasEdge addEdge(AtlasVertex fromVertex, AtlasVertex toVertex, String edgeLabel) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Adding edge for {} -> label {} -> {}", string(fromVertex), edgeLabel, string(toVertex));
        }

        AtlasEdge edge = graph.addEdge(fromVertex, toVertex, edgeLabel);

        AtlasGraphUtilsV1.setEncodedProperty(edge, Constants.STATE_PROPERTY_KEY, Id.EntityState.ACTIVE.name());
        AtlasGraphUtilsV1.setEncodedProperty(edge, Constants.TIMESTAMP_PROPERTY_KEY, RequestContext.get().getRequestTime());
        AtlasGraphUtilsV1.setEncodedProperty(edge, Constants.MODIFICATION_TIMESTAMP_PROPERTY_KEY, RequestContext.get().getRequestTime());
        AtlasGraphUtilsV1.setEncodedProperty(edge, Constants.CREATED_BY_KEY, RequestContext.get().getUser());
        AtlasGraphUtilsV1.setEncodedProperty(edge, Constants.MODIFIED_BY_KEY, RequestContext.get().getUser());

        if (LOG.isDebugEnabled()) {
            LOG.debug("Added {}", string(edge));
        }

        return edge;
    }

    public AtlasEdge getOrCreateEdge(AtlasVertex outVertex, AtlasVertex inVertex, String edgeLabel) throws RepositoryException {
        for (int numRetries = 0; numRetries < maxRetries; numRetries++) {
            try {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Running edge creation attempt {}", numRetries);
                }

                Iterator<AtlasEdge> edges = getAdjacentEdgesByLabel(inVertex, AtlasEdgeDirection.IN, edgeLabel);

                while (edges.hasNext()) {
                    AtlasEdge edge = edges.next();
                    if (edge.getOutVertex().equals(outVertex)) {
                        Id.EntityState edgeState = getState(edge);
                        if (edgeState == null || edgeState == Id.EntityState.ACTIVE) {
                            return edge;
                        }
                    }
                }

                return addEdge(outVertex, inVertex, edgeLabel);
            } catch (Exception e) {
                LOG.warn(String.format("Exception while trying to create edge from %s to %s with label %s. Retrying",
                        vertexString(outVertex), vertexString(inVertex), edgeLabel), e);
                if (numRetries == (maxRetries - 1)) {
                    LOG.error("Max retries exceeded for edge creation {} {} {} ", outVertex, inVertex, edgeLabel, e);
                    throw new RepositoryException("Edge creation failed after retries", e);
                }

                try {
                    LOG.info("Retrying with delay of {} ms ", retrySleepTimeMillis);
                    Thread.sleep(retrySleepTimeMillis);
                } catch(InterruptedException ie) {
                    LOG.warn("Retry interrupted during edge creation ");
                    throw new RepositoryException("Retry interrupted during edge creation", ie);
                }
            }
        }
        return null;
    }

    public AtlasEdge getEdgeByEdgeId(AtlasVertex outVertex, String edgeLabel, String edgeId) {
        if (edgeId == null) {
            return null;
        }
        return graph.getEdge(edgeId);

        //TODO get edge id is expensive. Use this logic. But doesn't work for now
        /**
        Iterable<AtlasEdge> edges = outVertex.getEdges(Direction.OUT, edgeLabel);
        for (AtlasEdge edge : edges) {
            if (edge.getObjectId().toString().equals(edgeId)) {
                return edge;
            }
        }
        return null;
         **/
    }

    /**
     * Args of the format prop1, key1, prop2, key2...
     * Searches for a AtlasVertex with prop1=key1 && prop2=key2
     * @param args
     * @return AtlasVertex with the given property keys
     * @throws EntityNotFoundException
     */
    public AtlasVertex findVertex(Object... args) throws EntityNotFoundException {
        AtlasGraphQuery query = graph.query();
        for (int i = 0 ; i < args.length; i+=2) {
            query = query.has((String) args[i], args[i+1]);
        }

        Iterator<AtlasVertex> results = query.vertices().iterator();
        // returning one since entityType, qualifiedName should be unique
        AtlasVertex vertex = results.hasNext() ? results.next() : null;

        if (vertex == null) {
            String conditionStr = getConditionString(args);
            if (LOG.isDebugEnabled()) {
                LOG.debug("Could not find a vertex with {}", conditionStr);
            }
            throw new EntityNotFoundException("Could not find an entity in the repository with " + conditionStr);
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Found a vertex {} with {}", string(vertex), getConditionString(args));
            }
        }

        return vertex;
    }

    //In some cases of parallel APIs, the edge is added, but get edge by label doesn't return the edge. ATLAS-1104
    //So traversing all the edges
    public Iterator<AtlasEdge> getAdjacentEdgesByLabel(AtlasVertex instanceVertex, AtlasEdgeDirection direction, final String edgeLabel) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Finding edges for {} with label {}", string(instanceVertex), edgeLabel);
        }

        if(instanceVertex != null && edgeLabel != null) {
            final Iterator<AtlasEdge> iterator = instanceVertex.getEdges(direction).iterator();
            return new Iterator<AtlasEdge>() {
                private AtlasEdge edge = null;

                @Override
                public boolean hasNext() {
                    while (edge == null && iterator.hasNext()) {
                        AtlasEdge localEdge = iterator.next();
                        if (localEdge.getLabel().equals(edgeLabel)) {
                            edge = localEdge;
                        }
                    }
                    return edge != null;
                }

                @Override
                public AtlasEdge next() {
                    if (hasNext()) {
                        AtlasEdge localEdge = edge;
                        edge = null;
                        return localEdge;
                    }
                    return null;
                }

                @Override
                public void remove() {
                    throw new IllegalStateException("Not handled");
                }
            };
        }
        return null;
    }

    public Iterator<AtlasEdge> getOutGoingEdgesByLabel(AtlasVertex instanceVertex, String edgeLabel) {
        return getAdjacentEdgesByLabel(instanceVertex, AtlasEdgeDirection.OUT, edgeLabel);
    }

    /**
     * Returns the active edge for the given edge label.
     * If the vertex is deleted and there is no active edge, it returns the latest deleted edge
     * @param vertex
     * @param edgeLabel
     * @return
     */
    public AtlasEdge getEdgeForLabel(AtlasVertex vertex, String edgeLabel) {
        Iterator<AtlasEdge> iterator = getAdjacentEdgesByLabel(vertex, AtlasEdgeDirection.OUT, edgeLabel);
        AtlasEdge latestDeletedEdge = null;
        long latestDeletedEdgeTime = Long.MIN_VALUE;

        while (iterator != null && iterator.hasNext()) {
            AtlasEdge edge = iterator.next();
            Id.EntityState edgeState = getState(edge);
            if (edgeState == null || edgeState == Id.EntityState.ACTIVE) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Found {}", string(edge));
                }

                return edge;
            } else {
                Long modificationTime = edge.getProperty(Constants.MODIFICATION_TIMESTAMP_PROPERTY_KEY, Long.class);
                if (modificationTime != null && modificationTime >= latestDeletedEdgeTime) {
                    latestDeletedEdgeTime = modificationTime;
                    latestDeletedEdge = edge;
                }
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Found {}", latestDeletedEdge == null ? "null" : string(latestDeletedEdge));
        }

        //If the vertex is deleted, return latest deleted edge
        return latestDeletedEdge;
    }

    public static String vertexString(final AtlasVertex vertex) {
        StringBuilder properties = new StringBuilder();
        for (String propertyKey : vertex.getPropertyKeys()) {
            Collection<?> propertyValues = vertex.getPropertyValues(propertyKey, Object.class);
            properties.append(propertyKey).append("=").append(propertyValues.toString()).append(", ");
        }

        return "v[" + vertex.getIdForDisplay() + "], Properties[" + properties + "]";
    }

    public static String edgeString(final AtlasEdge edge) {
        return "e[" + edge.getLabel() + "], [" + edge.getOutVertex() + " -> " + edge.getLabel() + " -> "
                + edge.getInVertex() + "]";
    }


    /**
     * Remove the specified edge from the graph.
     *
     * @param edge
     */
    public void removeEdge(AtlasEdge edge) {
        String edgeString = null;

        if (LOG.isDebugEnabled()) {
            edgeString = string(edge);

            LOG.debug("Removing {}", edgeString);
        }

        graph.removeEdge(edge);

        if (LOG.isDebugEnabled()) {
            LOG.info("Removed {}", edgeString);
        }
    }

    /**
     * Remove the specified AtlasVertex from the graph.
     *
     * @param vertex
     */
    public void removeVertex(AtlasVertex vertex) {
        String vertexString = null;

        if (LOG.isDebugEnabled()) {
            vertexString = string(vertex);

            LOG.debug("Removing {}", vertexString);
        }

        graph.removeVertex(vertex);

        if (LOG.isDebugEnabled()) {
            LOG.info("Removed {}", vertexString);
        }
    }

    public AtlasVertex getVertexForGUID(String guid) throws EntityNotFoundException {
        return findVertex(Constants.GUID_PROPERTY_KEY, guid);
    }


    /**
     * Finds the Vertices that correspond to the given property values.  Property
     * values that are not found in the graph will not be in the map.
     *
     *  @return propertyValue to AtlasVertex map with the result.
     */
    public Map<String, AtlasVertex> getVerticesForPropertyValues(String property, List<String> values) {

        if(values.isEmpty()) {
            return Collections.emptyMap();
        }
        Collection<String> nonNullValues = new HashSet<>(values.size());

        for(String value : values) {
            if(value != null) {
                nonNullValues.add(value);
            }
        }

        //create graph query that finds vertices with the guids
        AtlasGraphQuery query = graph.query();
        query.in(property, nonNullValues);
        Iterable<AtlasVertex> results = query.vertices();

        Map<String, AtlasVertex> result = new HashMap<>(values.size());
        //Process the result, using the guidToIndexMap to figure out where
        //each vertex should go in the result list.
        for(AtlasVertex vertex : results) {
            if(vertex.exists()) {
                String propertyValue = vertex.getProperty(property, String.class);
                if(LOG.isDebugEnabled()) {
                    LOG.debug("Found a vertex {} with {} =  {}", string(vertex), property, propertyValue);
                }
                result.put(propertyValue, vertex);
            }
        }
        return result;
    }


    /**
     * Finds the Vertices that correspond to the given GUIDs.  GUIDs
     * that are not found in the graph will not be in the map.
     *
     *  @return GUID to AtlasVertex map with the result.
     */
    public Map<String, AtlasVertex> getVerticesForGUIDs(List<String> guids) {

        return getVerticesForPropertyValues(Constants.GUID_PROPERTY_KEY, guids);
    }

    public static String getQualifiedNameForMapKey(String prefix, String key) {
        return prefix + "." + key;
    }

    public static String getQualifiedFieldName(ITypedInstance typedInstance, AttributeInfo attributeInfo) throws AtlasException {
        IDataType dataType = typeSystem.getDataType(IDataType.class, typedInstance.getTypeName());
        return getQualifiedFieldName(dataType, attributeInfo.name);
    }

    public static String getQualifiedFieldName(IDataType dataType, String attributeName) throws AtlasException {
        return dataType.getTypeCategory() == DataTypes.TypeCategory.STRUCT ? dataType.getName() + "." + attributeName
                // else class or trait
                : ((HierarchicalType) dataType).getQualifiedName(attributeName);
    }

    public static String getTraitLabel(String typeName, String attrName) {
        return attrName;
    }

    public static List<String> getTraitNames(AtlasVertex<?,?> entityVertex) {
        ArrayList<String> traits = new ArrayList<>();
        Collection<String> propertyValues = entityVertex.getPropertyValues(Constants.TRAIT_NAMES_PROPERTY_KEY, String.class);
        for(String value : propertyValues) {
            traits.add(value);
        }
        return traits;
    }

    public static List<String> getSuperTypeNames(AtlasVertex<?,?> entityVertex) {
        ArrayList<String>  superTypes     = new ArrayList<>();
        Collection<String> propertyValues = entityVertex.getPropertyValues(Constants.SUPER_TYPES_PROPERTY_KEY, String.class);

        if (CollectionUtils.isNotEmpty(propertyValues)) {
            for(String value : propertyValues) {
                superTypes.add(value);
            }
        }

        return superTypes;
    }

    public static String getEdgeLabel(ITypedInstance typedInstance, AttributeInfo aInfo) throws AtlasException {
        IDataType dataType = typeSystem.getDataType(IDataType.class, typedInstance.getTypeName());
        return getEdgeLabel(dataType, aInfo);
    }

    public static String getEdgeLabel(IDataType dataType, AttributeInfo aInfo) throws AtlasException {
        return GraphHelper.EDGE_LABEL_PREFIX + getQualifiedFieldName(dataType, aInfo.name);
    }

    public static Id getIdFromVertex(String dataTypeName, AtlasVertex vertex) {
        return new Id(getGuid(vertex),
                getVersion(vertex), dataTypeName,
                getStateAsString(vertex));
    }

    public static Id getIdFromVertex(AtlasVertex vertex) {
        return getIdFromVertex(getTypeName(vertex), vertex);
    }

    public static String getGuid(AtlasVertex vertex) {
        Object vertexId = vertex.getId();

        String ret = GraphTransactionInterceptor.getVertexGuidFromCache(vertexId);

        if (ret == null) {
            ret = vertex.<String>getProperty(Constants.GUID_PROPERTY_KEY, String.class);

            GraphTransactionInterceptor.addToVertexGuidCache(vertexId, ret);
        }

        return ret;
    }

    public static String getTypeName(AtlasVertex instanceVertex) {
        return instanceVertex.getProperty(Constants.ENTITY_TYPE_PROPERTY_KEY, String.class);
    }

    public static Id.EntityState getState(AtlasElement element) {
        String state = getStateAsString(element);
        return state == null ? null : Id.EntityState.valueOf(state);
    }

    public static Integer getVersion(AtlasElement element) {
        return element.getProperty(Constants.VERSION_PROPERTY_KEY, Integer.class);
    }



    public static String getStateAsString(AtlasElement element) {
        return element.getProperty(Constants.STATE_PROPERTY_KEY, String.class);
    }

    public static Status getStatus(AtlasVertex vertex) {
        Object vertexId = vertex.getId();

        Status ret = GraphTransactionInterceptor.getVertexStateFromCache(vertexId);

        if (ret == null) {
            ret = (getState(vertex) == Id.EntityState.DELETED) ? Status.DELETED : Status.ACTIVE;

            GraphTransactionInterceptor.addToVertexStateCache(vertexId, ret);
        }

        return ret;
    }

    public static Status getStatus(AtlasEdge edge) {
        Object edgeId = edge.getId();
        Status ret = GraphTransactionInterceptor.getEdgeStateFromCache(edgeId);

        if (ret == null) {
            ret = (getState(edge) == Id.EntityState.DELETED) ? Status.DELETED : Status.ACTIVE;

            GraphTransactionInterceptor.addToEdgeStateCache(edgeId, ret);
        }

        return ret;
    }

    //Added conditions in fetching system attributes to handle test failures in GremlinTest where these properties are not set
    public static String getCreatedByAsString(AtlasElement element){
        return element.getProperty(Constants.CREATED_BY_KEY, String.class);
    }

    public static String getModifiedByAsString(AtlasElement element){
        return element.getProperty(Constants.MODIFIED_BY_KEY, String.class);
    }

    public static long getCreatedTime(AtlasElement element){
        return element.getProperty(Constants.TIMESTAMP_PROPERTY_KEY, Long.class);
    }

    public static long getModifiedTime(AtlasElement element){
        return element.getProperty(Constants.MODIFICATION_TIMESTAMP_PROPERTY_KEY, Long.class);
    }

    /**
     * For the given type, finds an unique attribute and checks if there is an existing instance with the same
     * unique value
     *
     * @param classType
     * @param instance
     * @return
     * @throws AtlasException
     */
    public AtlasVertex getVertexForInstanceByUniqueAttribute(ClassType classType, IReferenceableInstance instance)
        throws AtlasException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Checking if there is an instance with the same unique attributes for instance {}", instance.toShortString());
        }

        AtlasVertex result = null;
        for (AttributeInfo attributeInfo : classType.fieldMapping().fields.values()) {
            if (attributeInfo.isUnique) {
                String propertyKey = getQualifiedFieldName(classType, attributeInfo.name);
                try {
                    result = findVertex(propertyKey, instance.get(attributeInfo.name),
                            Constants.ENTITY_TYPE_PROPERTY_KEY, classType.getName(),
                            Constants.STATE_PROPERTY_KEY, Id.EntityState.ACTIVE.name());
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Found vertex by unique attribute : {}={}", propertyKey, instance.get(attributeInfo.name));
                    }
                } catch (EntityNotFoundException e) {
                    //Its ok if there is no entity with the same unique value
                }
            }
        }

        return result;
    }

    /**
     * Finds vertices that match at least one unique attribute of the instances specified.  The AtlasVertex at a given index in the result corresponds
     * to the IReferencableInstance at that same index that was passed in.  The number of elements in the resultant list is guaranteed to match the
     * number of instances that were passed in.  If no vertex is found for a given instance, that entry will be null in the resultant list.
     *
     *
     * @param classType
     * @param instancesForClass
     * @return
     * @throws AtlasException
     */
    public List<AtlasVertex> getVerticesForInstancesByUniqueAttribute(ClassType classType, List<? extends IReferenceableInstance> instancesForClass) throws AtlasException {

        //For each attribute, need to figure out what values to search for and which instance(s)
        //those values correspond to.
        Map<String, AttributeValueMap> map = new HashMap<String, AttributeValueMap>();

        for (AttributeInfo attributeInfo : classType.fieldMapping().fields.values()) {
            if (attributeInfo.isUnique) {
                String propertyKey = getQualifiedFieldName(classType, attributeInfo.name);
                AttributeValueMap mapForAttribute = new AttributeValueMap();
                for(int idx = 0; idx < instancesForClass.size(); idx++) {
                    IReferenceableInstance instance = instancesForClass.get(idx);
                    Object value = instance.get(attributeInfo.name);
                    mapForAttribute.put(value, instance, idx);
                }
                map.put(propertyKey, mapForAttribute);
            }
        }

        AtlasVertex[] result = new AtlasVertex[instancesForClass.size()];
        if(map.isEmpty()) {
            //no unique attributes
            return Arrays.asList(result);
        }

        //construct gremlin query
        AtlasGraphQuery query = graph.query();

        query.has(Constants.ENTITY_TYPE_PROPERTY_KEY, classType.getName());
        query.has(Constants.STATE_PROPERTY_KEY,Id.EntityState.ACTIVE.name());

        List<AtlasGraphQuery> orChildren = new ArrayList<AtlasGraphQuery>();


        //build up an or expression to find vertices which match at least one of the unique attribute constraints
        //For each unique attributes, we add a within clause to match vertices that have a value of that attribute
        //that matches the value in some instance.
        for(Map.Entry<String, AttributeValueMap> entry : map.entrySet()) {
            AtlasGraphQuery orChild = query.createChildQuery();
            String propertyName = entry.getKey();
            AttributeValueMap valueMap = entry.getValue();
            Set<Object> values = valueMap.getAttributeValues();
            if(values.size() == 1) {
                orChild.has(propertyName, values.iterator().next());
            }
            else if(values.size() > 1) {
                orChild.in(propertyName, values);
            }
            orChildren.add(orChild);
        }

        if(orChildren.size() == 1) {
            AtlasGraphQuery child = orChildren.get(0);
            query.addConditionsFrom(child);
        }
        else if(orChildren.size() > 1) {
            query.or(orChildren);
        }

        Iterable<AtlasVertex> queryResult = query.vertices();


        for(AtlasVertex matchingVertex : queryResult) {
            Collection<IndexedInstance> matches = getInstancesForVertex(map, matchingVertex);
            for(IndexedInstance wrapper : matches) {
                result[wrapper.getIndex()]= matchingVertex;
            }
        }
        return Arrays.asList(result);
    }

    //finds the instance(s) that correspond to the given vertex
    private Collection<IndexedInstance> getInstancesForVertex(Map<String, AttributeValueMap> map, AtlasVertex foundVertex) {

        //loop through the unique attributes.  For each attribute, check to see if the vertex property that
        //corresponds to that attribute has a value from one or more of the instances that were passed in.

        for(Map.Entry<String, AttributeValueMap> entry : map.entrySet()) {

            String propertyName = entry.getKey();
            AttributeValueMap valueMap = entry.getValue();

            Object vertexValue = foundVertex.getProperty(propertyName, Object.class);

            Collection<IndexedInstance> instances = valueMap.get(vertexValue);
            if(instances != null && instances.size() > 0) {
                //return first match.  Let the underling graph determine if this is a problem
                //(i.e. if the other unique attributes change be changed safely to match what
                //the user requested).
                return instances;
            }
            //try another attribute
        }
        return Collections.emptyList();
    }

    /**
     * Guid and AtlasVertex combo
     */
    public static class VertexInfo {
        private final AtlasEntityHeader entity;
        private final AtlasVertex       vertex;

        public VertexInfo(AtlasEntityHeader entity, AtlasVertex vertex) {
            this.entity = entity;
            this.vertex = vertex;
        }

        public String getGuid() {
            return entity.getGuid();
        }
        public AtlasVertex getVertex() {
            return vertex;
        }
        public String getTypeName() {
            return entity.getTypeName();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            VertexInfo that = (VertexInfo) o;
            return Objects.equals(entity, that.entity) &&
                    Objects.equals(vertex, that.vertex);
        }

        @Override
        public int hashCode() {
            return Objects.hash(entity, vertex);
        }
    }

    /**
     * Get the GUIDs and vertices for all composite entities owned/contained by the specified root entity AtlasVertex.
     * The graph is traversed from the root entity through to the leaf nodes of the containment graph.
     *
     * @param entityVertex the root entity vertex
     * @return set of VertexInfo for all composite entities
     * @throws AtlasException
     */
    public Set<VertexInfo> getCompositeVertices(AtlasVertex entityVertex) throws AtlasException {
        Set<VertexInfo> result = new HashSet<>();
        Stack<AtlasVertex> vertices = new Stack<>();
        vertices.push(entityVertex);
        while (vertices.size() > 0) {
            AtlasVertex        vertex   = vertices.pop();
            AtlasEntityHeader  entity   = toAtlasEntityHeader(vertex);
            String             typeName = entity.getTypeName();
            AtlasEntity.Status state    = entity.getStatus();

            if (state == Status.DELETED) {
                //If the reference vertex is marked for deletion, skip it
                continue;
            }

            result.add(new VertexInfo(entity, vertex));

            ClassType classType = typeSystem.getDataType(ClassType.class, typeName);

            for (AttributeInfo attributeInfo : classType.fieldMapping().fields.values()) {
                if (!attributeInfo.isComposite) {
                    continue;
                }
                String edgeLabel = GraphHelper.getEdgeLabel(classType, attributeInfo);
                switch (attributeInfo.dataType().getTypeCategory()) {
                    case CLASS:
                        AtlasEdge edge = getEdgeForLabel(vertex, edgeLabel);
                        if (edge != null && GraphHelper.getState(edge) == Id.EntityState.ACTIVE) {
                            AtlasVertex compositeVertex = edge.getInVertex();
                            vertices.push(compositeVertex);
                        }
                        break;
                    case ARRAY:
                        IDataType elementType = ((DataTypes.ArrayType) attributeInfo.dataType()).getElemType();
                        DataTypes.TypeCategory elementTypeCategory = elementType.getTypeCategory();
                        if (elementTypeCategory != TypeCategory.CLASS) {
                            continue;
                        }
                        Iterator<AtlasEdge> edges = getOutGoingEdgesByLabel(vertex, edgeLabel);
                        if (edges != null) {
                            while (edges.hasNext()) {
                                edge = edges.next();
                                if (edge != null && GraphHelper.getState(edge) == Id.EntityState.ACTIVE) {
                                    AtlasVertex compositeVertex = edge.getInVertex();
                                    vertices.push(compositeVertex);
                                }
                            }
                        }
                        break;
                    case MAP:
                        DataTypes.MapType mapType = (DataTypes.MapType) attributeInfo.dataType();
                        DataTypes.TypeCategory valueTypeCategory = mapType.getValueType().getTypeCategory();
                        if (valueTypeCategory != TypeCategory.CLASS) {
                            continue;
                        }
                        String propertyName = GraphHelper.getQualifiedFieldName(classType, attributeInfo.name);
                        List<String> keys = vertex.getProperty(propertyName, List.class);
                        if (keys != null) {
                            for (String key : keys) {
                                String mapEdgeLabel = GraphHelper.getQualifiedNameForMapKey(edgeLabel, key);
                                edge = getEdgeForLabel(vertex, mapEdgeLabel);
                                if (edge != null && GraphHelper.getState(edge) == Id.EntityState.ACTIVE) {
                                    AtlasVertex compositeVertex = edge.getInVertex();
                                    vertices.push(compositeVertex);
                                }
                            }
                        }
                        break;
                    default:
                }
            }
        }
        return result;
    }

    public static ITypedReferenceableInstance[] deserializeClassInstances(TypeSystem typeSystem, String entityInstanceDefinition)
    throws AtlasException {
        try {
            JSONArray referableInstances = new JSONArray(entityInstanceDefinition);
            ITypedReferenceableInstance[] instances = new ITypedReferenceableInstance[referableInstances.length()];
            for (int index = 0; index < referableInstances.length(); index++) {
                Referenceable entityInstance =
                        InstanceSerialization.fromJsonReferenceable(referableInstances.getString(index), true);
                ITypedReferenceableInstance typedInstrance = getTypedReferenceableInstance(typeSystem, entityInstance);
                instances[index] = typedInstrance;
            }
            return instances;
        } catch(ValueConversionException | TypeNotFoundException  e) {
            throw e;
        } catch (Exception e) {  // exception from deserializer
            LOG.error("Unable to deserialize json={}", entityInstanceDefinition, e);
            throw new IllegalArgumentException("Unable to deserialize json", e);
        }
    }

    public static ITypedReferenceableInstance getTypedReferenceableInstance(TypeSystem typeSystem, Referenceable entityInstance)
            throws AtlasException {
        final String entityTypeName = ParamChecker.notEmpty(entityInstance.getTypeName(), "Entity type cannot be null");

        ClassType entityType = typeSystem.getDataType(ClassType.class, entityTypeName);

        //Both assigned id and values are required for full update
        //classtype.convert() will remove values if id is assigned. So, set temp id, convert and
        // then replace with original id
        Id origId = entityInstance.getId();
        entityInstance.replaceWithNewId(new Id(entityInstance.getTypeName()));
        ITypedReferenceableInstance typedInstrance = entityType.convert(entityInstance, Multiplicity.REQUIRED);
        ((ReferenceableInstance)typedInstrance).replaceWithNewId(origId);
        return typedInstrance;
    }

    public static boolean isReference(IDataType type) {

        return type.getTypeCategory() == DataTypes.TypeCategory.STRUCT ||
                type.getTypeCategory() == DataTypes.TypeCategory.CLASS;

    }

    public static boolean isInternalType(AtlasVertex vertex) {
        return vertex != null && isInternalType(getTypeName(vertex));
    }

    public static boolean isInternalType(String typeName) {
        return typeName != null && typeName.startsWith(Constants.INTERNAL_PROPERTY_KEY_PREFIX);
    }

    public static void setArrayElementsProperty(IDataType elementType, AtlasVertex instanceVertex, String propertyName, List<Object> values) {
        String actualPropertyName = AtlasGraphUtilsV1.encodePropertyKey(propertyName);
        if(GraphHelper.isReference(elementType)) {
            setListPropertyFromElementIds(instanceVertex, actualPropertyName, (List)values);
        }
        else {
            AtlasGraphUtilsV1.setEncodedProperty(instanceVertex, actualPropertyName, values);
        }
    }

    public static void setMapValueProperty(IDataType elementType, AtlasVertex instanceVertex, String propertyName, Object value) {
        String actualPropertyName = AtlasGraphUtilsV1.encodePropertyKey(propertyName);
        if(GraphHelper.isReference(elementType)) {
            instanceVertex.setPropertyFromElementId(actualPropertyName, (AtlasEdge)value);
        }
        else {
            instanceVertex.setProperty(actualPropertyName, value);
        }
    }

    public static Object getMapValueProperty(IDataType elementType, AtlasVertex instanceVertex, String propertyName) {
        String actualPropertyName = AtlasGraphUtilsV1.encodePropertyKey(propertyName);
        if(GraphHelper.isReference(elementType)) {
            return instanceVertex.getProperty(actualPropertyName, AtlasEdge.class);
        }
        else {
            return instanceVertex.getProperty(actualPropertyName, Object.class);
        }
    }

    public static Object getMapValueProperty(AtlasType elementType, AtlasVertex instanceVertex, String propertyName) {
        String vertexPropertyName = AtlasGraphUtilsV1.encodePropertyKey(propertyName);

        if (AtlasGraphUtilsV1.isReference(elementType)) {
            return instanceVertex.getProperty(vertexPropertyName, AtlasEdge.class);
        } else {
            return instanceVertex.getProperty(vertexPropertyName, Object.class);
        }
    }

    // newly added
    public static List<Object> getArrayElementsProperty(AtlasType elementType, AtlasVertex instanceVertex, String propertyName) {
        String encodedPropertyName = AtlasGraphUtilsV1.encodePropertyKey(propertyName);
        if(AtlasGraphUtilsV1.isReference(elementType)) {
            return (List)instanceVertex.getListProperty(encodedPropertyName, AtlasEdge.class);
        }
        else {
            return (List)instanceVertex.getListProperty(encodedPropertyName);
        }
    }

    public static List<Object> getArrayElementsProperty(IDataType elementType, AtlasVertex instanceVertex, String propertyName) {
        String actualPropertyName = AtlasGraphUtilsV1.encodePropertyKey(propertyName);
        if(GraphHelper.isReference(elementType)) {
            return (List)instanceVertex.getListProperty(actualPropertyName, AtlasEdge.class);
        }
        else {
            return (List)instanceVertex.getListProperty(actualPropertyName);
        }
    }

    public static void dumpToLog(final AtlasGraph<?,?> graph) {
        LOG.debug("*******************Graph Dump****************************");
        LOG.debug("Vertices of {}", graph);
        for (AtlasVertex vertex : graph.getVertices()) {
            LOG.debug(vertexString(vertex));
        }

        LOG.debug("Edges of {}", graph);
        for (AtlasEdge edge : graph.getEdges()) {
            LOG.debug(edgeString(edge));
        }
        LOG.debug("*******************Graph Dump****************************");
    }

    public static String string(ITypedReferenceableInstance instance) {
        return String.format("entity[type=%s guid=%s]", instance.getTypeName(), instance.getId()._getId());
    }

    public static String string(AtlasVertex<?,?> vertex) {
        if(vertex == null) {
            return "vertex[null]";
        } else {
            if (LOG.isDebugEnabled()) {
                return getVertexDetails(vertex);
            } else {
                return String.format("vertex[id=%s]", vertex.getIdForDisplay());
            }
        }
    }

    public static String getVertexDetails(AtlasVertex<?,?> vertex) {

        return String.format("vertex[id=%s type=%s guid=%s]", vertex.getIdForDisplay(), getTypeName(vertex),
                getGuid(vertex));
    }


    public static String string(AtlasEdge<?,?> edge) {
        if(edge == null) {
            return "edge[null]";
        } else {
            if (LOG.isDebugEnabled()) {
                return getEdgeDetails(edge);
            } else {
                return String.format("edge[id=%s]", edge.getIdForDisplay());
            }
        }
    }

    public static String getEdgeDetails(AtlasEdge<?,?> edge) {

        return String.format("edge[id=%s label=%s from %s -> to %s]", edge.getIdForDisplay(), edge.getLabel(),
                string(edge.getOutVertex()), string(edge.getInVertex()));
    }


    public Object getVertexId(String guid) throws EntityNotFoundException {
        AtlasVertex instanceVertex   = getVertexForGUID(guid);
        Object      instanceVertexId = instanceVertex.getId();

        return instanceVertexId;
    }

    public static AttributeInfo getAttributeInfoForSystemAttributes(String field) {
        if (field.equals(Constants.STATE_PROPERTY_KEY) || field.equals(Constants.GUID_PROPERTY_KEY) || field.equals(Constants.CREATED_BY_KEY) || field.equals(Constants.MODIFIED_BY_KEY)) {
            return TypesUtil.newAttributeInfo(field, DataTypes.STRING_TYPE);
        } else if (field.equals(Constants.TIMESTAMP_PROPERTY_KEY) || field.equals(Constants.MODIFICATION_TIMESTAMP_PROPERTY_KEY)) {
            return TypesUtil.newAttributeInfo(field, DataTypes.DATE_TYPE);
        }

        return null;
    }

    public static boolean elementExists(AtlasElement v) {
        return v != null && v.exists();
    }

    public static void setListPropertyFromElementIds(AtlasVertex<?, ?> instanceVertex, String propertyName,
            List<AtlasElement> elements) {
        instanceVertex.setPropertyFromElementsIds(propertyName, elements);
    }

    public static void setPropertyFromElementId(AtlasVertex<?, ?> instanceVertex, String propertyName,
            AtlasElement value) {
        String actualPropertyName = AtlasGraphUtilsV1.encodePropertyKey(propertyName);
        instanceVertex.setPropertyFromElementId(actualPropertyName, value);

    }

    public static void setListProperty(AtlasVertex instanceVertex, String propertyName, ArrayList<String> value) throws AtlasException {
        String actualPropertyName = AtlasGraphUtilsV1.encodePropertyKey(propertyName);
        instanceVertex.setListProperty(actualPropertyName, value);
    }

    public static List<String> getListProperty(AtlasVertex instanceVertex, String propertyName) {
        String actualPropertyName = AtlasGraphUtilsV1.encodePropertyKey(propertyName);
        return instanceVertex.getListProperty(actualPropertyName);
    }


    private String getConditionString(Object[] args) {
        StringBuilder condition = new StringBuilder();

        for (int i = 0; i < args.length; i+=2) {
            condition.append(args[i]).append(" = ").append(args[i+1]).append(", ");
        }

        return condition.toString();
    }

    private AtlasEntityHeader toAtlasEntityHeader(AtlasVertex vertex) {
        AtlasEntityHeader ret = new AtlasEntityHeader();

        ret.setGuid(GraphHelper.getGuid(vertex));
        ret.setTypeName(GraphHelper.getTypeName(vertex));
        ret.setStatus(GraphHelper.getStatus(vertex));

        try {
            ClassType      classType = typeSystem.getDataType(ClassType.class, ret.getTypeName());
            ITypedInstance entity    = classType.createInstance();

            for (AttributeInfo attributeInfo : classType.fieldMapping().fields.values()) {
                if (attributeInfo.isUnique || "clusterName".equals(attributeInfo.name)) {
                    try {
                        Object attrValue = graphToInstanceMapper.mapVertexToAttribute(vertex, entity, attributeInfo);

                        if (attrValue != null) {
                            ret.setAttribute(attributeInfo.name, attrValue);
                        }
                    } catch (AtlasException excp) {
                        LOG.error("Failed to get uniqueAttribute value: entityGuid={}, attrName={}", ret.getGuid(), attributeInfo.name, excp);
                    }
                }
            }
        } catch (AtlasException excp) {
            LOG.error("Failed to create instance of type {}", ret.getTypeName(), excp);
        }

        return ret;
    }
}
