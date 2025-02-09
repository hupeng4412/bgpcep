/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.protocol.bgp.rib.impl.config;

import static java.util.Objects.requireNonNull;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.FluentFuture;
import io.netty.util.concurrent.Future;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.checkerframework.checker.lock.qual.GuardedBy;
import org.opendaylight.mdsal.binding.api.RpcProviderService;
import org.opendaylight.mdsal.common.api.CommitInfo;
import org.opendaylight.protocol.bgp.openconfig.spi.BGPTableTypeRegistryConsumer;
import org.opendaylight.protocol.bgp.parser.BgpExtendedMessageUtil;
import org.opendaylight.protocol.bgp.parser.spi.MultiprotocolCapabilitiesUtil;
import org.opendaylight.protocol.bgp.parser.spi.RevisedErrorHandlingSupport;
import org.opendaylight.protocol.bgp.rib.impl.BGPPeer;
import org.opendaylight.protocol.bgp.rib.impl.BgpPeerUtil;
import org.opendaylight.protocol.bgp.rib.impl.spi.BGPDispatcher;
import org.opendaylight.protocol.bgp.rib.impl.spi.BGPPeerRegistry;
import org.opendaylight.protocol.bgp.rib.impl.spi.BGPSessionPreferences;
import org.opendaylight.protocol.bgp.rib.impl.spi.RIB;
import org.opendaylight.protocol.bgp.rib.spi.state.BGPPeerState;
import org.opendaylight.protocol.bgp.rib.spi.state.BGPPeerStateConsumer;
import org.opendaylight.protocol.concepts.KeyMapping;
import org.opendaylight.protocol.util.Ipv4Util;
import org.opendaylight.yang.gen.v1.http.openconfig.net.yang.bgp.multiprotocol.rev151009.bgp.common.afi.safi.list.AfiSafi;
import org.opendaylight.yang.gen.v1.http.openconfig.net.yang.bgp.rev151009.bgp.neighbor.group.AfiSafis;
import org.opendaylight.yang.gen.v1.http.openconfig.net.yang.bgp.rev151009.bgp.neighbor.group.Config;
import org.opendaylight.yang.gen.v1.http.openconfig.net.yang.bgp.rev151009.bgp.neighbors.Neighbor;
import org.opendaylight.yang.gen.v1.http.openconfig.net.yang.bgp.rev151009.bgp.peer.group.PeerGroup;
import org.opendaylight.yang.gen.v1.http.openconfig.net.yang.bgp.rev151009.bgp.top.Bgp;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.AsNumber;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.IpAddress;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.PortNumber;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.open.message.BgpParameters;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.open.message.bgp.parameters.OptionalCapabilities;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.open.message.bgp.parameters.OptionalCapabilitiesBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.open.message.bgp.parameters.optional.capabilities.CParametersBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.open.message.bgp.parameters.optional.capabilities.c.parameters.As4BytesCapabilityBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.BgpTableType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.CParameters1;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.CParameters1Builder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.mp.capabilities.AddPathCapabilityBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.mp.capabilities.MultiprotocolCapabilityBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.mp.capabilities.add.path.capability.AddressFamilies;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.openconfig.extensions.rev180329.NeighborPeerGroupConfig;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.rib.rev180329.PeerRole;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.rib.rev180329.rib.TablesKey;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.types.rev180329.ClusterIdentifier;
import org.opendaylight.yangtools.yang.binding.InstanceIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BgpPeer implements PeerBean, BGPPeerStateConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(BgpPeer.class);

    private final RpcProviderService rpcRegistry;
    @GuardedBy("this")
    private Neighbor currentConfiguration;
    @GuardedBy("this")
    private BgpPeerSingletonService bgpPeerSingletonService;

    public BgpPeer(final RpcProviderService rpcRegistry) {
        this.rpcRegistry = rpcRegistry;
    }

    private static List<OptionalCapabilities> getBgpCapabilities(final AfiSafis afiSafis, final RIB rib,
                                                          final BGPTableTypeRegistryConsumer tableTypeRegistry) {
        final List<OptionalCapabilities> caps = new ArrayList<>();
        caps.add(new OptionalCapabilitiesBuilder().setCParameters(new CParametersBuilder().setAs4BytesCapability(
                new As4BytesCapabilityBuilder().setAsNumber(rib.getLocalAs()).build()).build()).build());

        caps.add(new OptionalCapabilitiesBuilder()
                .setCParameters(BgpExtendedMessageUtil.EXTENDED_MESSAGE_CAPABILITY).build());
        caps.add(new OptionalCapabilitiesBuilder()
                .setCParameters(MultiprotocolCapabilitiesUtil.RR_CAPABILITY).build());

        final List<AfiSafi> afiSafi = OpenConfigMappingUtil.getAfiSafiWithDefault(afiSafis, false);
        final List<AddressFamilies> addPathCapability = OpenConfigMappingUtil
                .toAddPathCapability(afiSafi, tableTypeRegistry);
        if (!addPathCapability.isEmpty()) {
            caps.add(new OptionalCapabilitiesBuilder()
                    .setCParameters(new CParametersBuilder().addAugmentation(CParameters1.class,
                            new CParameters1Builder().setAddPathCapability(
                                    new AddPathCapabilityBuilder()
                                            .setAddressFamilies(addPathCapability).build()).build()).build()).build());
        }

        final List<BgpTableType> tableTypes = OpenConfigMappingUtil.toTableTypes(afiSafi, tableTypeRegistry);
        for (final BgpTableType tableType : tableTypes) {
            if (!rib.getLocalTables().contains(tableType)) {
                LOG.info("RIB instance does not list {} in its local tables. Incoming data will be dropped.",
                    tableType);
            }

            caps.add(new OptionalCapabilitiesBuilder().setCParameters(
                    new CParametersBuilder().addAugmentation(CParameters1.class,
                            new CParameters1Builder().setMultiprotocolCapability(
                                    new MultiprotocolCapabilityBuilder(tableType).build()).build()).build()).build());
        }
        return caps;
    }

    private static Optional<byte[]> getPassword(final KeyMapping key) {
        if (key != null) {
            return Optional.of(Iterables.getOnlyElement(key.values()));
        }
        return Optional.empty();
    }

    @Override
    public synchronized void start(final RIB rib, final Neighbor neighbor, final InstanceIdentifier<Bgp> bgpIid,
            final PeerGroupConfigLoader peerGroupLoader, final BGPTableTypeRegistryConsumer tableTypeRegistry) {
        Preconditions.checkState(this.bgpPeerSingletonService == null,
                "Previous peer instance was not closed.");

        this.bgpPeerSingletonService = new BgpPeerSingletonService(rib, neighbor, bgpIid, peerGroupLoader,
                tableTypeRegistry);
        this.currentConfiguration = neighbor;
    }

    @Override
    public synchronized void restart(final RIB rib, final InstanceIdentifier<Bgp> bgpIid,
            final PeerGroupConfigLoader peerGroupLoader, final BGPTableTypeRegistryConsumer tableTypeRegistry) {
        Preconditions.checkState(this.currentConfiguration != null);
        start(rib, this.currentConfiguration, bgpIid, peerGroupLoader, tableTypeRegistry);
    }

    @Override
    public void close() {
        // No-op
    }

    @Override
    public synchronized void instantiateServiceInstance() {
        if (this.bgpPeerSingletonService != null) {
            this.bgpPeerSingletonService.instantiateServiceInstance();
        }
    }

    @Override
    public synchronized FluentFuture<? extends CommitInfo> closeServiceInstance() {
        if (this.bgpPeerSingletonService != null) {
            final FluentFuture<? extends CommitInfo> fut = this.bgpPeerSingletonService.closeServiceInstance();
            this.bgpPeerSingletonService = null;
            return fut;
        }
        return CommitInfo.emptyFluentFuture();
    }

    @Override
    public synchronized Boolean containsEqualConfiguration(final Neighbor neighbor) {
        if (this.currentConfiguration == null) {
            return false;
        }
        final AfiSafis actAfiSafi = this.currentConfiguration.getAfiSafis();
        final AfiSafis extAfiSafi = neighbor.getAfiSafis();
        final List<AfiSafi> actualSafi = actAfiSafi != null ? actAfiSafi.getAfiSafi() : Collections.emptyList();
        final List<AfiSafi> extSafi = extAfiSafi != null ? extAfiSafi.getAfiSafi() : Collections.emptyList();
        return actualSafi.containsAll(extSafi) && extSafi.containsAll(actualSafi)
                && Objects.equals(this.currentConfiguration.getConfig(), neighbor.getConfig())
                && Objects.equals(this.currentConfiguration.getNeighborAddress(), neighbor.getNeighborAddress())
                && Objects.equals(this.currentConfiguration.getAddPaths(), neighbor.getAddPaths())
                && Objects.equals(this.currentConfiguration.getApplyPolicy(), neighbor.getApplyPolicy())
                && Objects.equals(this.currentConfiguration.getAsPathOptions(), neighbor.getAsPathOptions())
                && Objects.equals(this.currentConfiguration.getEbgpMultihop(), neighbor.getEbgpMultihop())
                && Objects.equals(this.currentConfiguration.getGracefulRestart(), neighbor.getGracefulRestart())
                && Objects.equals(this.currentConfiguration.getErrorHandling(), neighbor.getErrorHandling())
                && Objects.equals(this.currentConfiguration.getLoggingOptions(), neighbor.getLoggingOptions())
                && Objects.equals(this.currentConfiguration.getRouteReflector(), neighbor.getRouteReflector())
                && Objects.equals(this.currentConfiguration.getState(), neighbor.getState())
                && Objects.equals(this.currentConfiguration.getTimers(), neighbor.getTimers())
                && Objects.equals(this.currentConfiguration.getTransport(), neighbor.getTransport());
    }

    @Override
    public synchronized BGPPeerState getPeerState() {
        if (this.bgpPeerSingletonService == null) {
            return null;
        }
        return this.bgpPeerSingletonService.getPeerState();
    }

    synchronized void removePeer(final BGPPeerRegistry bgpPeerRegistry) {
        if (BgpPeer.this.currentConfiguration != null) {
            bgpPeerRegistry.removePeer(BgpPeer.this.currentConfiguration.getNeighborAddress());
        }
    }

    private final class BgpPeerSingletonService implements BGPPeerStateConsumer {
        private final boolean activeConnection;
        private final BGPDispatcher dispatcher;
        private final InetSocketAddress inetAddress;
        private final int retryTimer;
        private final KeyMapping keys;
        private final InetSocketAddress localAddress;
        private final BGPPeer bgpPeer;
        private final IpAddress neighborAddress;
        private final BGPSessionPreferences prefs;
        private Future<Void> connection;
        private boolean isServiceInstantiated;
        private final List<OptionalCapabilities> finalCapabilities;
        private final int gracefulRestartTimer;
        private final RevisedErrorHandlingSupport errorHandling;


        private BgpPeerSingletonService(final RIB rib, final Neighbor neighbor, final InstanceIdentifier<Bgp> bgpIid,
                final PeerGroupConfigLoader peerGroupLoader, final BGPTableTypeRegistryConsumer tableTypeRegistry) {
            this.neighborAddress = neighbor.getNeighborAddress();

            PeerGroup peerGroup = null;
            String peerGroupName = null;
            final Config neighborConfig = neighbor.getConfig();
            if (neighborConfig != null) {
                final NeighborPeerGroupConfig pgConfig = neighborConfig.augmentation(NeighborPeerGroupConfig.class);
                if (pgConfig != null) {
                    peerGroupName = StringUtils.substringBetween(pgConfig.getPeerGroup(), "=\"", "\"");
                    peerGroup = peerGroupLoader.getPeerGroup(bgpIid, peerGroupName);
                }
            }
            final AfiSafis afisSafis;
            if (peerGroup != null && peerGroup.getAfiSafis() != null) {
                afisSafis = peerGroup.getAfiSafis();
            } else {
                afisSafis = requireNonNull(neighbor.getAfiSafis(), "Missing mandatory AFIs/SAFIs");
            }

            final Set<TablesKey> afiSafisAdvertized = OpenConfigMappingUtil
                    .toTableKey(afisSafis.getAfiSafi(), tableTypeRegistry);
            final PeerRole role = OpenConfigMappingUtil.toPeerRole(neighbor, peerGroup);

            final ClusterIdentifier clusterId = OpenConfigMappingUtil
                    .getNeighborClusterIdentifier(neighbor.getRouteReflector(), peerGroup);
            final int hold = OpenConfigMappingUtil.getHoldTimer(neighbor, peerGroup);
            this.gracefulRestartTimer = OpenConfigMappingUtil.getGracefulRestartTimer(neighbor,
                    peerGroup, hold);
            final Set<TablesKey> gracefulTables = GracefulRestartUtil.getGracefulTables(afisSafis.getAfiSafi(),
                    tableTypeRegistry);
            final Map<TablesKey, Integer> llGracefulTimers = GracefulRestartUtil
                    .getLlGracefulTimers(afisSafis.getAfiSafi(), tableTypeRegistry);
            this.finalCapabilities = getBgpCapabilities(afisSafis, rib, tableTypeRegistry);
            final List<BgpParameters> bgpParameters = getInitialBgpParameters(gracefulTables, llGracefulTimers);
            final KeyMapping keyMapping = OpenConfigMappingUtil.getNeighborKey(neighbor);
            final IpAddress neighborLocalAddress = OpenConfigMappingUtil.getLocalAddress(neighbor.getTransport());
            final AsNumber globalAs = rib.getLocalAs();
            final AsNumber neighborRemoteAs = OpenConfigMappingUtil
                    .getRemotePeerAs(neighbor.getConfig(), peerGroup, globalAs);
            final AsNumber neighborLocalAs;
            if (role == PeerRole.Ebgp) {
                neighborLocalAs = OpenConfigMappingUtil.getLocalPeerAs(neighbor.getConfig(), globalAs);
            } else {
                neighborLocalAs = globalAs;
            }

            this.errorHandling = OpenConfigMappingUtil.getRevisedErrorHandling(role, peerGroup, neighbor);
            this.bgpPeer = new BGPPeer(tableTypeRegistry, this.neighborAddress, peerGroupName, rib, role, clusterId,
                    neighborLocalAs, BgpPeer.this.rpcRegistry, afiSafisAdvertized, gracefulTables, llGracefulTimers,
                    BgpPeer.this);
            this.prefs = new BGPSessionPreferences(neighborLocalAs, hold, rib.getBgpIdentifier(),
                    neighborRemoteAs, bgpParameters, getPassword(keyMapping));
            this.activeConnection = OpenConfigMappingUtil.isActive(neighbor, peerGroup);
            this.retryTimer = OpenConfigMappingUtil.getRetryTimer(neighbor, peerGroup);
            this.dispatcher = rib.getDispatcher();

            final PortNumber port = OpenConfigMappingUtil.getPort(neighbor, peerGroup);
            this.inetAddress = Ipv4Util.toInetSocketAddress(this.neighborAddress, port);
            if (neighborLocalAddress != null) {
                this.localAddress = Ipv4Util.toInetSocketAddress(neighborLocalAddress, port);
            } else {
                this.localAddress = null;
            }
            this.keys = keyMapping;
        }

        private List<BgpParameters> getInitialBgpParameters(final Set<TablesKey> gracefulTables,
                                                            final Map<TablesKey, Integer> llGracefulTimers) {
            final Set<BgpPeerUtil.LlGracefulRestartDTO> llGracefulRestarts = llGracefulTimers.entrySet().stream()
                    .map(entry -> new BgpPeerUtil.LlGracefulRestartDTO(entry.getKey(), entry.getValue(), false))
                    .collect(Collectors.toSet());
            return Collections.singletonList(
                    GracefulRestartUtil.getGracefulBgpParameters(this.finalCapabilities, gracefulTables,
                            Collections.emptySet(), gracefulRestartTimer, false, llGracefulRestarts));
        }

        private synchronized void instantiateServiceInstance() {
            this.isServiceInstantiated = true;
            LOG.info("Peer instantiated {}", this.neighborAddress);
            this.bgpPeer.instantiateServiceInstance();
            this.dispatcher.getBGPPeerRegistry().addPeer(this.neighborAddress, this.bgpPeer, this.prefs);
            if (this.activeConnection) {
                this.connection = this.dispatcher.createReconnectingClient(this.inetAddress, this.localAddress,
                        this.retryTimer, this.keys);
            }
        }

        private synchronized FluentFuture<? extends CommitInfo> closeServiceInstance() {
            if (!this.isServiceInstantiated) {
                LOG.info("Peer {} already closed", this.neighborAddress);
                return CommitInfo.emptyFluentFuture();
            }
            LOG.info("Close Peer {}", this.neighborAddress);
            this.isServiceInstantiated = false;
            if (this.connection != null) {
                this.connection.cancel(true);
                this.connection = null;
            }
            final FluentFuture<? extends CommitInfo> future = this.bgpPeer.close();
            removePeer(this.dispatcher.getBGPPeerRegistry());
            return future;
        }

        @Override
        public BGPPeerState getPeerState() {
            return this.bgpPeer.getPeerState();
        }
    }

    public synchronized List<OptionalCapabilities> getBgpFixedCapabilities() {
        return this.bgpPeerSingletonService.finalCapabilities;
    }

    public synchronized int getGracefulRestartTimer() {
        return this.bgpPeerSingletonService.gracefulRestartTimer;
    }

    public synchronized Optional<RevisedErrorHandlingSupport> getErrorHandling() {
        return Optional.ofNullable(this.bgpPeerSingletonService.errorHandling);
    }
}
