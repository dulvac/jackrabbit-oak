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

import org.jetbrains.annotations.NotNull;
import org.osgi.annotation.versioning.ProviderType;

/**
 * OSGi service for emitting audit events. Consumed via {@code @Reference}:
 * <pre>{@code
 * @Reference private AuditEventEmitter audit;
 *
 * void onSomething() {
 *     if (audit.isEnabledFor("aem.content")) {
 *         audit.emit(new MyEvent(...));
 *     }
 * }
 * }</pre>
 * <p>
 * The emitter dispatches synchronously on the calling thread to all
 * listeners registered for the event's domain. Not tied to any commit;
 * not buffered; not rolled back on failure.
 * <p>
 * Listeners are invoked under per-listener try/catch isolation: one
 * listener throwing does not prevent others from running, and exceptions
 * are logged but never propagate back to the caller.
 * <p>
 * <strong>Trust model:</strong> any bundle that resolves this service can
 * emit any event for any domain. The event payload reflects the emitting
 * bundle's claim; Oak does not verify it. See
 * {@link AuditEventListener} class-level Javadoc for the listener-side
 * trust contract.
 */
@ProviderType
public interface AuditEventEmitter {

    /**
     * Dispatches the event to all listeners registered for the event's
     * domain. Synchronous on the calling thread.
     *
     * @param event the event to dispatch, non-null.
     */
    void emit(@NotNull AuditEvent event);

    /**
     * Returns {@code true} when at least one listener is registered for
     * the given domain. Callers should gate event allocation with this
     * method on hot paths.
     *
     * @param domain the domain to check, non-null.
     */
    boolean isEnabledFor(@NotNull String domain);
}
