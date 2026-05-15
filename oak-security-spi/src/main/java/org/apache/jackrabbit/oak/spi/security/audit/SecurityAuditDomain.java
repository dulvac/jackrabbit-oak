/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.spi.security.audit;

/**
 * Domain constants for security audit events. Future domains (e.g.
 * indexing, query, blob) will define their own constants alongside this
 * one in the same {@code audit} subpackage.
 */
public final class SecurityAuditDomain {

    /**
     * Domain name for events produced by Oak security modules
     * (user management, ACLs, principal management, tokens, etc.).
     */
    public static final String NAME = "security";

    private SecurityAuditDomain() {
        // constants class
    }
}
