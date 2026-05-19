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

import org.apache.jackrabbit.api.JackrabbitSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Production binding for {@link JackrabbitSessionAccessor}. Casts the
 * supplied {@link Session} to {@link JackrabbitSession} and invokes the
 * new {@code getInternalSessionId()} default method approved in
 * {@code 07-bridge-design.md} §5.
 * <p>
 * The new method on {@link JackrabbitSession} is the only public-surface
 * widening required by the bridge in {@code oak-jackrabbit-api}:
 * <pre>{@code
 * // oak-jackrabbit-api: JackrabbitSession
 * /**
 *  * Returns the underlying Oak session identifier. Format and stability
 *  * are internal Oak detail; the value is intended only for
 *  * audit-bridge integration. Consumers other than the audit bridge
 *  * should not call this method, and the format may change between
 *  * Oak versions.
 *  *
 *  * Implementations backed by Oak return ContentSession.toString().
 *  * The default implementation throws UnsupportedOperationException
 *  * for non-Oak Session implementations.
 *  *\/
 * @NotNull
 * default String getInternalSessionId() {
 *     throw new UnsupportedOperationException(
 *         "getInternalSessionId is implemented only by Oak-backed sessions.");
 * }
 *
 * // oak-jcr: SessionImpl
 * @Override @NotNull
 * public String getInternalSessionId() {
 *     return sd.getContentSession().toString();
 * }
 * }</pre>
 * <p>
 * Returning {@code null} (rather than throwing) when the session is not
 * a {@link JackrabbitSession} lets the caller
 * ({@code AuditEventEmitterImpl.recordOnCommit}) throw a clean
 * {@link IllegalArgumentException} with forensic context rather than a
 * naked {@link ClassCastException}.
 * <p>
 * If the cast succeeds but {@code getInternalSessionId()} throws
 * {@link UnsupportedOperationException} (i.e. a non-Oak
 * {@link JackrabbitSession} implementation that did not override the
 * default), the exception propagates — that scenario is genuinely
 * exceptional and the caller surfaces it as a hard failure rather than
 * masking it as a foreign-session no-op.
 */
final class DefaultJackrabbitSessionAccessor implements JackrabbitSessionAccessor {

    @Override
    @Nullable
    public String getSessionId(@NotNull Session session) {
        if (session instanceof JackrabbitSession) {
            return ((JackrabbitSession) session).getInternalSessionId();
        }
        return null;
    }
}
