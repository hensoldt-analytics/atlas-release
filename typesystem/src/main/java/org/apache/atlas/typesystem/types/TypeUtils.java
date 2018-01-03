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

package org.apache.atlas.typesystem.types;

import com.google.common.collect.ImmutableList;
import org.apache.atlas.AtlasException;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TypeUtils {

    public static final String NAME_REGEX = "[a-zA-z][a-zA-Z0-9_]*";
    public static final Pattern NAME_PATTERN = Pattern.compile(NAME_REGEX);
    private static final String ARRAY_TYPE_NAME_PREFIX = "array<";
    private static final String ARRAY_TYPE_NAME_SUFFIX = ">";
    private static final String MAP_TYPE_NAME_PREFIX   = "map<";
    private static final String MAP_TYPE_NAME_SUFFIX   = ">";
    private static final String MAP_TYPE_KEY_VAL_SEP   = ",";

    public static void outputVal(String val, Appendable buf, String prefix) throws AtlasException {
        try {
            buf.append(prefix).append(val);
        } catch (IOException ie) {
            throw new AtlasException(ie);
        }
    }

    public static String parseAsArrayType(String typeName) {
        String ret = null;

        if (typeName.startsWith(ARRAY_TYPE_NAME_PREFIX) && typeName.endsWith(ARRAY_TYPE_NAME_SUFFIX)) {
            int    startIdx        = ARRAY_TYPE_NAME_PREFIX.length();
            int    endIdx          = typeName.length() - ARRAY_TYPE_NAME_SUFFIX.length();
            String elementTypeName = typeName.substring(startIdx, endIdx);

            ret = elementTypeName;
        }

        return ret;
    }

    public static String[] parseAsMapType(String typeName) {
        String[] ret = null;

        if (typeName.startsWith(MAP_TYPE_NAME_PREFIX) && typeName.endsWith(MAP_TYPE_NAME_SUFFIX)) {
            int      startIdx      = MAP_TYPE_NAME_PREFIX.length();
            int      endIdx        = typeName.length() - MAP_TYPE_NAME_SUFFIX.length();
            String[] keyValueTypes = typeName.substring(startIdx, endIdx).split(MAP_TYPE_KEY_VAL_SEP, 2);
            String   keyTypeName   = keyValueTypes.length > 0 ? keyValueTypes[0] : null;
            String   valueTypeName = keyValueTypes.length > 1 ? keyValueTypes[1] : null;

            ret = new String[] { keyTypeName, valueTypeName };
        }

        return ret;
    }

    public static Map<AttributeInfo, List<String>> buildAttrInfoToNameMap(FieldMapping f) {
        Map<AttributeInfo, List<String>> b = new HashMap();
        for (Map.Entry<String, AttributeInfo> e : f.fields.entrySet()) {
            List<String> names = b.get(e.getValue());
            if (names == null) {
                names = new ArrayList<>();
                b.put(e.getValue(), names);
            }
            names.add(e.getKey());
        }
        return b;
    }

    public static class Pair<L, R> {
        public L left;
        public R right;

        public Pair(L left, R right) {
            this.left = left;
            this.right = right;
        }

        public static <L, R> Pair<L, R> of(L left, R right) {
            return new Pair<>(left, right);
        }

        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }

            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            Pair p = (Pair)o;

            return Objects.equals(left, p.left) && Objects.equals(right, p.right);
        }

        public int hashCode() { return Objects.hash(left, right); }
    }

    /**
     * Validates that the old field mapping can be replaced with new field mapping
     * @param oldFieldMapping
     * @param newFieldMapping
     */
    public static void validateUpdate(FieldMapping oldFieldMapping, FieldMapping newFieldMapping)
            throws TypeUpdateException {
        Map<String, AttributeInfo> newFields = newFieldMapping.fields;
        for (AttributeInfo attribute : oldFieldMapping.fields.values()) {
            if (newFields.containsKey(attribute.name)) {
                AttributeInfo newAttribute = newFields.get(attribute.name);
                //If old attribute is also in new definition, only allowed change is multiplicity change from REQUIRED to OPTIONAL
                if (!newAttribute.equals(attribute)) {
                    if (attribute.multiplicity == Multiplicity.REQUIRED
                            && newAttribute.multiplicity == Multiplicity.OPTIONAL) {
                        continue;
                    } else {
                        throw new TypeUpdateException("Attribute " + attribute.name + " can't be updated");
                    }
                }

            } else {
                //If old attribute is missing in new definition, return false as attributes can't be deleted
                throw new TypeUpdateException("Old Attribute " + attribute.name + " is missing");
            }
        }

        //Only new attributes
        Set<String> newAttributes = new HashSet<>(ImmutableList.copyOf(newFields.keySet()));
        newAttributes.removeAll(oldFieldMapping.fields.keySet());
        for (String attributeName : newAttributes) {
            AttributeInfo newAttribute = newFields.get(attributeName);
            //New required attribute can't be added
            if (newAttribute.multiplicity == Multiplicity.REQUIRED) {
                throw new TypeUpdateException("Can't add required attribute " + attributeName);
            }
        }
    }
}
