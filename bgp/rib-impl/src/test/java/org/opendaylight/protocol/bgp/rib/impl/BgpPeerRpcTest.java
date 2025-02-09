/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.protocol.bgp.rib.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.opendaylight.protocol.bgp.rib.spi.PeerRPCs;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.peer.rpc.rev180329.PeerRef;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.peer.rpc.rev180329.ResetSessionInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.peer.rpc.rev180329.ResetSessionInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.peer.rpc.rev180329.ResetSessionOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.peer.rpc.rev180329.RestartGracefullyInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.peer.rpc.rev180329.RestartGracefullyInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.peer.rpc.rev180329.RestartGracefullyOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.peer.rpc.rev180329.RouteRefreshRequestInput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.peer.rpc.rev180329.RouteRefreshRequestInputBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.peer.rpc.rev180329.RouteRefreshRequestOutput;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.rib.rev180329.rib.TablesKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.types.rev180329.Ipv4AddressFamily;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.types.rev180329.Ipv6AddressFamily;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.types.rev180329.SubsequentAddressFamily;
import org.opendaylight.yangtools.yang.binding.Notification;
import org.opendaylight.yangtools.yang.common.RpcResult;

@RunWith(MockitoJUnitRunner.StrictStubs.class)
public final class BgpPeerRpcTest {
    @Mock
    private BGPSessionImpl session;
    @Mock
    private PeerRPCs peerRpcs;
    @Mock
    private PeerRef peer;
    @Mock
    private ChannelFuture future;
    private BgpPeerRpc rpc;

    @Before
    public void setUp() throws InterruptedException, ExecutionException {
        this.rpc = new BgpPeerRpc(this.peerRpcs, this.session,
                Collections.singleton(new TablesKey(Ipv4AddressFamily.class, SubsequentAddressFamily.class)));
        final ChannelOutputLimiter limiter = new ChannelOutputLimiter(this.session);

        doReturn(limiter).when(this.session).getLimiter();
        doReturn(this.future).when(this.session).writeAndFlush(any(Notification.class));

        doReturn(true).when(this.future).isSuccess();
        doAnswer(invocation -> {
            GenericFutureListener<ChannelFuture> listener = invocation.getArgument(0);
            listener.operationComplete(this.future);
            return null;
        }).when(this.future).addListener(any());
    }

    @Test
    public void testRouteRefreshRequestSuccessRequest() throws InterruptedException, ExecutionException {
        final RouteRefreshRequestInput input = new RouteRefreshRequestInputBuilder()
                .setAfi(Ipv4AddressFamily.class)
                .setSafi(SubsequentAddressFamily.class)
                .setPeerRef(this.peer).build();
        final Future<RpcResult<RouteRefreshRequestOutput>> result = this.rpc.routeRefreshRequest(input);
        assertTrue(result.get().getErrors().isEmpty());
    }

    @Test
    public void testRouteRefreshRequestFailedRequest() throws InterruptedException, ExecutionException {
        final RouteRefreshRequestInput input = new RouteRefreshRequestInputBuilder()
                .setAfi(Ipv6AddressFamily.class)
                .setSafi(SubsequentAddressFamily.class)
                .setPeerRef(this.peer).build();
        final Future<RpcResult<RouteRefreshRequestOutput>> result = this.rpc.routeRefreshRequest(input);
        assertEquals(1, result.get().getErrors().size());
        assertEquals("Failed to send Route Refresh message due to unsupported address families.",
                result.get().getErrors().iterator().next().getMessage());
    }

    @Test
    public void testResetSessionRequestSuccessRequest() throws InterruptedException, ExecutionException {
        doReturn(Futures.immediateFuture(null)).when(this.peerRpcs).releaseConnection();
        final ResetSessionInput input = new ResetSessionInputBuilder()
                .setPeerRef(this.peer).build();
        final Future<RpcResult<ResetSessionOutput>> result = this.rpc.resetSession(input);
        assertTrue(result.get().getErrors().isEmpty());
    }

    @Test
    public void testRestartGracefullyRequestFailedRequest() throws ExecutionException, InterruptedException {
        final long referraltimerSeconds = 10L;
        doReturn(new SimpleSessionListener().restartGracefully(referraltimerSeconds))
                .when(this.peerRpcs).restartGracefully(referraltimerSeconds);
        final RestartGracefullyInput input = new RestartGracefullyInputBuilder()
                .setSelectionDeferralTime(referraltimerSeconds)
                .build();
        final ListenableFuture<RpcResult<RestartGracefullyOutput>> result = this.rpc.restartGracefully(input);
        assertTrue(!result.get().getErrors().isEmpty());
    }
}
