/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



package org.gradle.messaging.dispatch

import org.gradle.api.Action
import org.gradle.util.MultithreadedTestCase
import org.junit.Test
import static org.junit.Assert.*

class TcpConnectorTest extends MultithreadedTestCase {
    @Test
    public void canConnectToServer() {
        TcpOutgoingConnector outgoingConnector = new TcpOutgoingConnector(getClass().classLoader)
        TcpIncomingConnector incomingConnector = new TcpIncomingConnector(executor, getClass().classLoader)

        Action action = { syncAt(1) } as Action
        incomingConnector.accept(action)

        def connection = outgoingConnector.connect(incomingConnector.getLocalAddress())
        assertNotNull(connection)
        run { syncAt(1) }

        incomingConnector.requestStop()
    }
}
