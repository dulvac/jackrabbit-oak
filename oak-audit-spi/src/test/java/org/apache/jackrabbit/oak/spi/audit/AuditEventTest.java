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

import java.util.Collections;

import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AuditEventTest {

    @Test
    public void defaultGetPayloadReturnsEmptyMap() {
        // Impl that does NOT override getPayload() — exercises the default method body.
        AuditEvent e = new AuditEvent() {
            @Override public @NotNull String getDomain() { return "test"; }
            @Override public @NotNull String getType() { return "t"; }
            @Override public long getTimestamp() { return 0L; }
        };
        assertEquals(Collections.emptyMap(), e.getPayload());
    }
}
