/*
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.protocol.framework;

import io.netty.channel.Channel;

import java.util.Timer;

public final class SimpleSessionFactory implements ProtocolSessionFactory<SimpleSession> {
	private final int maximumMessageSize;

	public SimpleSessionFactory(final int maximumMessageSize) {
		this.maximumMessageSize = maximumMessageSize;
	}

	@Override
	public SimpleSession getProtocolSession(final SessionParent parent, final Timer timer, final ProtocolConnection connection,
			final int sessionId, final Channel channel) {
		return new SimpleSession(connection, parent, this.maximumMessageSize);
	}
}
