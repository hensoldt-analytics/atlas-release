/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.atlas.web.rest;

import com.google.inject.Inject;
import org.apache.atlas.exception.AtlasBaseException;
import org.apache.atlas.model.SearchFilter;
import org.apache.atlas.model.typedef.AtlasBaseTypeDef;
import org.apache.atlas.model.typedef.AtlasClassificationDef;
import org.apache.atlas.model.typedef.AtlasEntityDef;
import org.apache.atlas.model.typedef.AtlasEnumDef;
import org.apache.atlas.model.typedef.AtlasStructDef;
import org.apache.atlas.model.typedef.AtlasTypeDefHeader;
import org.apache.atlas.model.typedef.AtlasTypesDef;
import org.apache.atlas.store.AtlasTypeDefStore;
import org.apache.atlas.type.AtlasTypeUtil;
import org.apache.atlas.web.util.Servlets;
import org.apache.http.annotation.Experimental;

import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import java.util.List;
import java.util.Set;


@Path("v2/types")
@Singleton
public class TypesREST {

    private final AtlasTypeDefStore typeDefStore;


    @Inject
    public TypesREST(AtlasTypeDefStore typeDefStore) {
        this.typeDefStore = typeDefStore;
    }


    @GET
    @Path("/typedef/name/{name}")
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public AtlasBaseTypeDef getTypeDefByName(@PathParam("name") String name) throws AtlasBaseException {
        AtlasBaseTypeDef ret = typeDefStore.getByName(name);

        return ret;
    }

    @GET
    @Path("/typedef/guid/{guid}")
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public AtlasBaseTypeDef getTypeDefByGuid(@PathParam("guid") String guid) throws AtlasBaseException {
        AtlasBaseTypeDef ret = typeDefStore.getByGuid(guid);

        return ret;
    }

    /**
     * Bulk retrieval API for all type definitions returned as a list of minimal information header
     * @return List of AtlasTypeDefHeader {@link AtlasTypeDefHeader}
     * @throws AtlasBaseException
     */
    @GET
    @Path("/typedefs/headers")
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public List<AtlasTypeDefHeader> getTypeDefHeaders(@Context HttpServletRequest httpServletRequest) throws AtlasBaseException {
        SearchFilter searchFilter = getSearchFilter(httpServletRequest);

        AtlasTypesDef searchTypesDef = typeDefStore.searchTypesDef(searchFilter);

        return AtlasTypeUtil.toTypeDefHeader(searchTypesDef);
    }

    /**
     * Bulk retrieval API for retrieving all type definitions in Atlas
     * @return A composite wrapper object with lists of all type definitions
     * @throws Exception
     */
    @GET
    @Path("/typedefs")
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public AtlasTypesDef getAllTypeDefs(@Context HttpServletRequest httpServletRequest) throws AtlasBaseException {
        SearchFilter searchFilter = getSearchFilter(httpServletRequest);

        AtlasTypesDef typesDef = typeDefStore.searchTypesDef(searchFilter);

        return typesDef;
    }

    @GET
    @Path("/enumdef/name/{name}")
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public AtlasEnumDef getEnumDefByName(@PathParam("name") String name) throws AtlasBaseException {
        AtlasEnumDef ret = typeDefStore.getEnumDefByName(name);

        return ret;
    }

    @GET
    @Path("/enumdef/guid/{guid}")
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public AtlasEnumDef getEnumDefByGuid(@PathParam("guid") String guid) throws AtlasBaseException {
        AtlasEnumDef ret = typeDefStore.getEnumDefByGuid(guid);

        return ret;
    }


    @GET
    @Path("/structdef/name/{name}")
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public AtlasStructDef getStructDefByName(@PathParam("name") String name) throws AtlasBaseException {
        AtlasStructDef ret = typeDefStore.getStructDefByName(name);

        return ret;
    }

    @GET
    @Path("/structdef/guid/{guid}")
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public AtlasStructDef getStructDefByGuid(@PathParam("guid") String guid) throws AtlasBaseException {
        AtlasStructDef ret = typeDefStore.getStructDefByGuid(guid);

        return ret;
    }

    @GET
    @Path("/classificationdef/name/{name}")
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public AtlasClassificationDef getClassificationDefByName(@PathParam("name") String name) throws AtlasBaseException {
        AtlasClassificationDef ret = typeDefStore.getClassificationDefByName(name);

        return ret;
    }

    @GET
    @Path("/classificationdef/guid/{guid}")
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public AtlasClassificationDef getClassificationDefByGuid(@PathParam("guid") String guid) throws AtlasBaseException {
        AtlasClassificationDef ret = typeDefStore.getClassificationDefByGuid(guid);

        return ret;
    }


    @GET
    @Path("/entitydef/name/{name}")
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public AtlasEntityDef getEntityDefByName(@PathParam("name") String name) throws AtlasBaseException {
        AtlasEntityDef ret = typeDefStore.getEntityDefByName(name);

        return ret;
    }

    @GET
    @Path("/entitydef/guid/{guid}")
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public AtlasEntityDef getEntityDefByGuid(@PathParam("guid") String guid) throws AtlasBaseException {
        AtlasEntityDef ret = typeDefStore.getEntityDefByGuid(guid);

        return ret;
    }

    /******************************************************************/
    /** Bulk API operations                                          **/
    /******************************************************************/

    /**
     * Bulk create APIs for all atlas type definitions, only new definitions will be created.
     * Any changes to the existing definitions will be discarded
     * @param typesDef A composite wrapper object with corresponding lists of the type definition
     * @return A composite wrapper object with lists of type definitions that were successfully
     * created
     * @throws Exception
     */
    @POST
    @Path("/typedefs")
    @Consumes(Servlets.JSON_MEDIA_TYPE)
    @Produces(Servlets.JSON_MEDIA_TYPE)
    public AtlasTypesDef createAtlasTypeDefs(final AtlasTypesDef typesDef) throws AtlasBaseException {
        AtlasTypesDef ret = typeDefStore.createTypesDef(typesDef);

        return ret;
    }

    /**
     * Bulk update API for all types, changes detected in the type definitions would be persisted
     * @param typesDef A composite object that captures all type definition changes
     * @return A composite object with lists of type definitions that were updated
     * @throws Exception
     */
    @PUT
    @Path("/typedefs")
    @Consumes(Servlets.JSON_MEDIA_TYPE)
    @Produces(Servlets.JSON_MEDIA_TYPE)
    @Experimental
    public AtlasTypesDef updateAtlasTypeDefs(final AtlasTypesDef typesDef) throws AtlasBaseException {
        AtlasTypesDef ret = typeDefStore.updateTypesDef(typesDef);

        return ret;
    }

    /**
     * Bulk delete API for all types
     * @param typesDef A composite object that captures all types to be deleted
     * @throws Exception
     */
    @DELETE
    @Path("/typedefs")
    @Consumes(Servlets.JSON_MEDIA_TYPE)
    @Produces(Servlets.JSON_MEDIA_TYPE)
    @Experimental
    public void deleteAtlasTypeDefs(final AtlasTypesDef typesDef) throws AtlasBaseException {
        typeDefStore.deleteTypesDef(typesDef);
    }

    /**
     * Populate a SearchFilter on the basis of the Query Parameters
     * @return
     */
    private SearchFilter getSearchFilter(HttpServletRequest httpServletRequest) {
        SearchFilter ret = new SearchFilter();
        Set<String> keySet = httpServletRequest.getParameterMap().keySet();
        for (String key : keySet) {
            ret.setParam(String.valueOf(key), String.valueOf(httpServletRequest.getParameter(key)));
        }

        return ret;
    }}
