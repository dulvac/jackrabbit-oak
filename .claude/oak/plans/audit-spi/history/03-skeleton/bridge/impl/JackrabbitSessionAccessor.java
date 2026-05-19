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
package org.apache.jackrabbit.oak.audit.bridge.impl;

import javax.jcr.Session;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Internal testable seam for {@link Session} → {@code sessionId}
 * resolution. Package-private; the bridge depends on it via constructor
 * injection. Production binding: {@link DefaultJackrabbitSessionAccessor};
 * tests bind a Mockito stub returning whatever sessionId they want without
 * spinning up a real Oak repository.
 * <p>
 * Replaces the need for any {@code SessionRootResolver} pattern (turing's
 * task #6 ask). By returning only a {@link String}, this seam keeps
 * {@code org.apache.jackrabbit.oak.api.Root} out of the bridge's unit
 * test surface entirely — no mock {@code Root}, no mock
 * {@code ContentSession}, no mock {@code AuthInfo}.
 */
interface JackrabbitSessionAccessor {

    /**
     * Extracts the internal session id from the given JCR session.
     *
     * @param session the session to inspect, non-null.
     * @return the opaque session id (the AuditBuffer key, identical to
     *         {@code ContentSession.toString()} on the production path),
     *         or {@code null} when the session is not an Oak-backed
     *         {@code JackrabbitSession}.
     */
    @Nullable
    String getSessionId(@NotNull Session session);
}
