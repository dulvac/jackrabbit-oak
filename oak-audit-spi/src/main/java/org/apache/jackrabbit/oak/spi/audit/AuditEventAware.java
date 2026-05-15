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
package org.apache.jackrabbit.oak.spi.audit;

import org.osgi.annotation.versioning.ConsumerType;

/**
 * Optional marker interface for components that produce audit events at
 * their API call sites. Implementing this interface is not required —
 * callers may instead use the static {@link AuditEvents#record} façade.
 * <p>
 * The marker exists so that tooling and tests can discover
 * audit-emitting bundles via OSGi service lookup or classpath scanning
 * (e.g. {@code ServiceTracker<AuditEventAware>}). The marker carries no
 * methods: it does not, on its own, expose which {@link AuditEvent}
 * types a component emits. Tools that need to enumerate types either
 * scan source for {@code AuditEvents.record(...)} call sites or rely on
 * documentation in the component's own Javadoc.
 */
@ConsumerType
public interface AuditEventAware {
    // intentionally empty — marker only.
}
