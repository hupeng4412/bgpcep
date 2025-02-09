/*
 * Copyright (c) 2014 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.protocol.bgp.rib.impl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.google.common.collect.Lists;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.opendaylight.mdsal.common.api.LogicalDatastoreType;
import org.opendaylight.protocol.bgp.parser.BGPDocumentedException;
import org.opendaylight.protocol.bgp.parser.BGPError;
import org.opendaylight.protocol.bgp.parser.impl.message.update.LocalPreferenceAttributeParser;
import org.opendaylight.protocol.bgp.rib.spi.RibSupportUtils;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.IpAddress;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Ipv4Address;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Ipv4Prefix;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.inet.rev180329.ipv4.routes.ipv4.routes.Ipv4Route;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.KeepaliveBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.Open;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.OpenBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.UpdateBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.open.message.BgpParameters;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.open.message.BgpParametersBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.open.message.bgp.parameters.OptionalCapabilitiesBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.open.message.bgp.parameters.optional.capabilities.CParametersBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.path.attributes.AttributesBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.path.attributes.attributes.AsPath;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.path.attributes.attributes.AsPathBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.path.attributes.attributes.LocalPref;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.path.attributes.attributes.LocalPrefBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.path.attributes.attributes.Origin;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.path.attributes.attributes.OriginBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.update.message.Nlri;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.update.message.NlriBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.update.message.WithdrawnRoutes;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.update.message.WithdrawnRoutesBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.Attributes2;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.Attributes2Builder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.CParameters1;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.CParameters1Builder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.RouteRefreshBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.mp.capabilities.MultiprotocolCapabilityBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.update.attributes.MpUnreachNlriBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.rib.rev180329.ApplicationRibId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.rib.rev180329.PeerRole;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.rib.rev180329.bgp.rib.rib.LocRib;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.rib.rev180329.rib.Tables;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.types.rev180329.BgpOrigin;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.types.rev180329.Ipv4AddressFamily;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.types.rev180329.Ipv6AddressFamily;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.types.rev180329.UnicastSubsequentAddressFamily;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.types.rev180329.next.hop.CNextHop;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.types.rev180329.next.hop.c.next.hop.Ipv4NextHopCaseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.types.rev180329.next.hop.c.next.hop.ipv4.next.hop._case.Ipv4NextHopBuilder;
import org.opendaylight.yangtools.yang.binding.Notification;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.api.schema.tree.ModificationType;

public class PeerTest extends AbstractRIBTestSetup {

    private final IpAddress neighborAddress = new IpAddress(new Ipv4Address("127.0.0.1"));
    private ApplicationPeer peer;
    private BGPSessionImpl session;
    private Map<YangInstanceIdentifier, NormalizedNode<?, ?>> routes;
    private BGPPeer classic;

    @Override
    @Before
    public void setUp() throws Exception {
        super.setUp();
        this.routes = new HashMap<>();
        overrideMockedBehaviour();
    }

    private void overrideMockedBehaviour() {
        doAnswer(invocation -> {
            final Object[] args = invocation.getArguments();
            final NormalizedNode<?, ?> node = (NormalizedNode<?, ?>) args[2];
            if (node.getIdentifier().getNodeType().equals(Ipv4Route.QNAME) || node.getNodeType().equals(PREFIX_QNAME)) {
                this.routes.put((YangInstanceIdentifier) args[1], node);
            }
            return args[1];
        }).when(getTransaction()).put(eq(LogicalDatastoreType.OPERATIONAL),
                any(YangInstanceIdentifier.class), any(NormalizedNode.class));

        doAnswer(invocation -> {
            final Object[] args = invocation.getArguments();
            this.routes.remove(args[1]);
            return args[1];
        }).when(getTransaction()).delete(eq(LogicalDatastoreType.OPERATIONAL), any(YangInstanceIdentifier.class));
    }

    @Test
    public void testAppPeer() {
        final Ipv4Prefix first = new Ipv4Prefix("127.0.0.2/32");
        final Ipv4Prefix second = new Ipv4Prefix("127.0.0.1/32");
        final Ipv4Prefix third = new Ipv4Prefix("127.0.0.3/32");
        this.peer = new ApplicationPeer(this.tableRegistry,
                new ApplicationRibId(this.neighborAddress.getIpv4Address().getValue()),
                this.neighborAddress.getIpv4Address(), getRib());
        this.peer.instantiateServiceInstance(null, null);
        final YangInstanceIdentifier base = getRib().getYangRibId().node(LocRib.QNAME)
                .node(Tables.QNAME).node(RibSupportUtils.toYangTablesKey(KEY));
        this.peer.onDataTreeChanged(ipv4Input(base, ModificationType.WRITE, first, second, third));
        assertEquals(3, this.routes.size());

        this.peer.onDataTreeChanged(ipv4Input(base, ModificationType.DELETE, third));
        assertEquals(2, this.routes.size());
    }

    @Test
    public void testClassicPeer() throws Exception {
        this.classic = AbstractAddPathTest.configurePeer(this.tableRegistry, this.neighborAddress.getIpv4Address(),
                getRib(), null, PeerRole.Ibgp, new StrictBGPPeerRegistry());
        this.classic.instantiateServiceInstance();
        this.mockSession();
        assertEquals(this.neighborAddress.getIpv4Address().getValue(), this.classic.getName());
        this.classic.onSessionUp(this.session);
        Assert.assertArrayEquals(new byte[]{1, 1, 1, 1}, this.classic.getRawIdentifier());
        assertEquals("BGPPeer{name=127.0.0.1, tables=[TablesKey{_afi=interface org.opendaylight.yang.gen.v1"
                        + ".urn.opendaylight.params.xml.ns.yang.bgp.types.rev180329.Ipv4AddressFamily,"
                        + " _safi=interface org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.types"
                        + ".rev180329.UnicastSubsequentAddressFamily}]}",
                this.classic.toString());

        final Nlri n1 = new NlriBuilder().setPrefix(new Ipv4Prefix("8.0.1.0/28")).build();
        final Nlri n2 = new NlriBuilder().setPrefix(new Ipv4Prefix("127.0.0.1/32")).build();
        final Nlri n3 = new NlriBuilder().setPrefix(new Ipv4Prefix("2.2.2.2/24")).build();
        final List<Nlri> nlris = Lists.newArrayList(n1, n2, n3);
        final UpdateBuilder ub = new UpdateBuilder();
        ub.setNlri(nlris);
        final Origin origin = new OriginBuilder().setValue(BgpOrigin.Igp).build();
        final AsPath asPath = new AsPathBuilder().setSegments(Collections.emptyList()).build();
        final CNextHop nextHop = new Ipv4NextHopCaseBuilder().setIpv4NextHop(new Ipv4NextHopBuilder()
                .setGlobal(new Ipv4Address("127.0.0.1")).build()).build();
        final AttributesBuilder ab = new AttributesBuilder();
        ub.setAttributes(ab.setOrigin(origin).setAsPath(asPath).setCNextHop(nextHop).build());
        try {
            this.classic.onMessage(this.session, ub.build());
            fail();
        } catch (final BGPDocumentedException e) {
            assertEquals(BGPError.MANDATORY_ATTR_MISSING_MSG + "LOCAL_PREF", e.getMessage());
            assertEquals(BGPError.WELL_KNOWN_ATTR_MISSING.getCode(), e.getError().getCode());
            assertEquals(BGPError.WELL_KNOWN_ATTR_MISSING.getSubcode(), e.getError().getSubcode());
            assertArrayEquals(new byte[]{LocalPreferenceAttributeParser.TYPE}, e.getData());
        }
        assertEquals(0, this.routes.size());

        final LocalPref localPref = new LocalPrefBuilder().setPref((long) 100).build();
        ub.setAttributes(ab.setLocalPref(localPref).build());
        this.classic.onMessage(this.session, ub.build());
        assertEquals(3, this.routes.size());

        //create new peer so that it gets advertized routes from RIB
        final BGPPeer testingPeer = AbstractAddPathTest.configurePeer(this.tableRegistry,
            this.neighborAddress.getIpv4Address(), getRib(), null, PeerRole.Ibgp, new StrictBGPPeerRegistry());
        testingPeer.instantiateServiceInstance();
        testingPeer.onSessionUp(this.session);
        assertEquals(3, this.routes.size());

        final Nlri n11 = new NlriBuilder().setPrefix(new Ipv4Prefix("8.0.1.0/28")).build();
        final Nlri n22 = new NlriBuilder().setPrefix(new Ipv4Prefix("8.0.1.16/28")).build();
        final List<Nlri> nlris2 = Lists.newArrayList(n11, n22);
        ub.setNlri(nlris2);
        final WithdrawnRoutes w1 = new WithdrawnRoutesBuilder().setPrefix(new Ipv4Prefix("8.0.1.0/28")).build();
        final WithdrawnRoutes w2 = new WithdrawnRoutesBuilder().setPrefix(new Ipv4Prefix("127.0.0.1/32")).build();
        final WithdrawnRoutes w3 = new WithdrawnRoutesBuilder().setPrefix(new Ipv4Prefix("2.2.2.2/24")).build();
        final List<WithdrawnRoutes> wrs = Lists.newArrayList(w1, w2, w3);
        ub.setWithdrawnRoutes(wrs);
        this.classic.onMessage(this.session, ub.build());
        assertEquals(2, this.routes.size());
        this.classic.onMessage(this.session, new KeepaliveBuilder().build());
        this.classic.onMessage(this.session, new UpdateBuilder().setAttributes(
                new AttributesBuilder().addAugmentation(
                        Attributes2.class,
                        new Attributes2Builder().setMpUnreachNlri(
                                new MpUnreachNlriBuilder()
                                        .setAfi(IPV4_AFI)
                                        .setSafi(SAFI)
                                        .build()).build()).build()).build());
        this.classic.onMessage(this.session, new RouteRefreshBuilder().setAfi(IPV4_AFI).setSafi(SAFI).build());
        this.classic.onMessage(this.session, new RouteRefreshBuilder()
                .setAfi(Ipv6AddressFamily.class)
                .setSafi(SAFI).build());
        assertEquals(2, this.routes.size());
        this.classic.releaseConnection();
    }

    private void mockSession() {
        final EventLoop eventLoop = mock(EventLoop.class);
        final Channel channel = mock(Channel.class);
        final ChannelPipeline pipeline = mock(ChannelPipeline.class);
        doReturn(null).when(eventLoop).schedule(any(Runnable.class), any(long.class), any(TimeUnit.class));
        doReturn(eventLoop).when(channel).eventLoop();
        doReturn(Boolean.TRUE).when(channel).isWritable();
        doReturn(null).when(channel).close();
        doReturn(pipeline).when(channel).pipeline();
        doCallRealMethod().when(channel).toString();
        doReturn(pipeline).when(pipeline).addLast(any(ChannelHandler.class));
        doReturn(new DefaultChannelPromise(channel)).when(channel).writeAndFlush(any(Notification.class));
        doReturn(new InetSocketAddress("localhost", 12345)).when(channel).remoteAddress();
        doReturn(new InetSocketAddress("localhost", 12345)).when(channel).localAddress();
        final List<BgpParameters> params = Lists.newArrayList(new BgpParametersBuilder().setOptionalCapabilities(
                Lists.newArrayList(new OptionalCapabilitiesBuilder().setCParameters(
                        new CParametersBuilder().addAugmentation(
                                CParameters1.class, new CParameters1Builder().setMultiprotocolCapability(
                                        new MultiprotocolCapabilityBuilder()
                                                .setAfi(Ipv4AddressFamily.class)
                                                .setSafi(UnicastSubsequentAddressFamily.class)
                                                .build()).build()).build()).build())).build());
        final Open openObj = new OpenBuilder()
                .setBgpIdentifier(new Ipv4Address("1.1.1.1"))
                .setHoldTimer(50)
                .setMyAsNumber(72)
                .setBgpParameters(params).build();
        this.session = new BGPSessionImpl(this.classic, channel, openObj, 30, null);
        this.session.setChannelExtMsgCoder(openObj);
    }
}
