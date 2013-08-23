/*
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.protocol.pcep;

import io.netty.util.concurrent.Future;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.opendaylight.protocol.framework.ProtocolServer;
import org.opendaylight.protocol.framework.ReconnectStrategy;

/**
 * Dispatcher class for creating servers and clients.
 */
public interface PCEPDispatcher {

	/**
	 * Creates server. Each server needs three factories to pass their instances to client sessions.
	 * @param address to be bound with the server
	 * @param listenerFactory to create listeners for clients
	 * @param proposalFactory to create proposed open objects for clients
	 * @param checkerFactory to create session characteristics checker for clients
	 * @return instance of PCEPServer
	 * @throws IOException if some IO error occurred
	 */
	public Future<ProtocolServer> createServer(final InetSocketAddress address, final PCEPConnectionFactory connectionFactory) throws IOException;

	/**
	 * Creates a client. Needs to be started via the start method.
	 * @param connection PCEP connection settings
	 * @param strategy Reconnection strategy to be used for TCP-level connection
	 * @throws IOException if some IO error occurred
	 */
	public Future<? extends PCEPSession> createClient(PCEPConnection connection, final ReconnectStrategy strategy) throws IOException;

	/**
	 * Sets the limit of maximum unknown messages per minute. If not set by the user, default is 5 messages/minute.
	 * @param limit maximum unknown messages per minute
	 */
	public void setMaxUnknownMessages(final int limit);
}

