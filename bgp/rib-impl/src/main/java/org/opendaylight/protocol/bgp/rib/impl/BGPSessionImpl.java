/*
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.protocol.bgp.rib.impl;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.ScheduledFuture;
import java.io.IOException;
import java.nio.channels.NonWritableChannelException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.checkerframework.checker.lock.qual.GuardedBy;
import org.opendaylight.protocol.bgp.parser.AsNumberUtil;
import org.opendaylight.protocol.bgp.parser.BGPDocumentedException;
import org.opendaylight.protocol.bgp.parser.BGPError;
import org.opendaylight.protocol.bgp.parser.BgpExtendedMessageUtil;
import org.opendaylight.protocol.bgp.parser.BgpTableTypeImpl;
import org.opendaylight.protocol.bgp.parser.GracefulRestartUtil;
import org.opendaylight.protocol.bgp.parser.spi.MultiPathSupport;
import org.opendaylight.protocol.bgp.parser.spi.PeerConstraint;
import org.opendaylight.protocol.bgp.parser.spi.pojo.MultiPathSupportImpl;
import org.opendaylight.protocol.bgp.rib.impl.spi.BGPMessagesListener;
import org.opendaylight.protocol.bgp.rib.impl.spi.BGPPeerRegistry;
import org.opendaylight.protocol.bgp.rib.impl.spi.BGPSessionPreferences;
import org.opendaylight.protocol.bgp.rib.impl.state.BGPSessionStateImpl;
import org.opendaylight.protocol.bgp.rib.impl.state.BGPSessionStateProvider;
import org.opendaylight.protocol.bgp.rib.spi.BGPSession;
import org.opendaylight.protocol.bgp.rib.spi.BGPSessionListener;
import org.opendaylight.protocol.bgp.rib.spi.BGPTerminationReason;
import org.opendaylight.protocol.bgp.rib.spi.State;
import org.opendaylight.protocol.bgp.rib.spi.state.BGPSessionState;
import org.opendaylight.protocol.bgp.rib.spi.state.BGPTimersState;
import org.opendaylight.protocol.bgp.rib.spi.state.BGPTransportState;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.AsNumber;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.ietf.inet.types.rev130715.Ipv4Address;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.Keepalive;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.KeepaliveBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.Notify;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.NotifyBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.Open;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.Update;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.open.message.BgpParameters;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.open.message.bgp.parameters.OptionalCapabilities;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.message.rev180329.open.message.bgp.parameters.optional.capabilities.CParameters;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.BgpTableType;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.CParameters1;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.MpCapabilities;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.RouteRefresh;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.mp.capabilities.AddPathCapability;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.mp.capabilities.GracefulRestartCapability;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.mp.capabilities.LlGracefulRestartCapability;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.mp.capabilities.MultiprotocolCapability;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.multiprotocol.rev180329.mp.capabilities.add.path.capability.AddressFamilies;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.bgp.rib.rev180329.rib.TablesKey;
import org.opendaylight.yangtools.yang.binding.ChildOf;
import org.opendaylight.yangtools.yang.binding.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@VisibleForTesting
public class BGPSessionImpl extends SimpleChannelInboundHandler<Notification> implements BGPSession,
        BGPSessionStateProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BGPSessionImpl.class);

    private static final Notification KEEP_ALIVE = new KeepaliveBuilder().build();

    private static final int KA_TO_DEADTIMER_RATIO = 3;

    private static final String EXTENDED_MSG_DECODER = "EXTENDED_MSG_DECODER";

    static final String END_OF_INPUT = "End of input detected. Close the session.";

    /**
     * System.nanoTime value about when was sent the last message.
     */
    @VisibleForTesting
    private long lastMessageSentAt;

    /**
     * System.nanoTime value about when was received the last message.
     */
    private long lastMessageReceivedAt;

    private final BGPSessionListener listener;

    private final BGPSynchronization sync;

    private int kaCounter = 0;

    private final Channel channel;

    @GuardedBy("this")
    private State state = State.OPEN_CONFIRM;

    private final Set<BgpTableType> tableTypes;
    private final List<AddressFamilies> addPathTypes;
    private final int holdTimerValue;
    private final int keepAlive;
    private final AsNumber asNumber;
    private final Ipv4Address bgpId;
    private final BGPPeerRegistry peerRegistry;
    private final ChannelOutputLimiter limiter;
    private final BGPSessionStateImpl sessionState;
    private final GracefulRestartCapability gracefulCapability;
    private final LlGracefulRestartCapability llGracefulCapability;
    private boolean terminationReasonNotified;

    public BGPSessionImpl(final BGPSessionListener listener, final Channel channel, final Open remoteOpen,
            final BGPSessionPreferences localPreferences, final BGPPeerRegistry peerRegistry) {
        this(listener, channel, remoteOpen, localPreferences.getHoldTime(), peerRegistry);
    }

    public BGPSessionImpl(final BGPSessionListener listener, final Channel channel, final Open remoteOpen,
            final int localHoldTimer, final BGPPeerRegistry peerRegistry) {
        this.listener = requireNonNull(listener);
        this.channel = requireNonNull(channel);
        this.limiter = new ChannelOutputLimiter(this);
        this.channel.pipeline().addLast(this.limiter);
        this.holdTimerValue = remoteOpen.getHoldTimer() < localHoldTimer ? remoteOpen.getHoldTimer() : localHoldTimer;
        LOG.info("BGP HoldTimer new value: {}", this.holdTimerValue);
        this.keepAlive = this.holdTimerValue / KA_TO_DEADTIMER_RATIO;
        this.asNumber = AsNumberUtil.advertizedAsNumber(remoteOpen);
        this.peerRegistry = peerRegistry;
        this.sessionState = new BGPSessionStateImpl();

        final Set<TablesKey> tts = new HashSet<>();
        final Set<BgpTableType> tats = new HashSet<>();
        final List<AddressFamilies> addPathCapabilitiesList = new ArrayList<>();
        final List<BgpParameters> bgpParameters = remoteOpen.getBgpParameters();
        if (bgpParameters != null) {
            for (final BgpParameters param : bgpParameters) {
                for (final OptionalCapabilities optCapa : param.getOptionalCapabilities()) {
                    final CParameters cParam = optCapa.getCParameters();
                    final CParameters1 cParam1 = cParam.augmentation(CParameters1.class);
                    if (cParam1 != null) {
                        final MultiprotocolCapability multi = cParam1.getMultiprotocolCapability();
                        if (multi != null) {
                            final TablesKey tt = new TablesKey(multi.getAfi(), multi.getSafi());
                            LOG.trace("Added table type to sync {}", tt);
                            tts.add(tt);
                            tats.add(new BgpTableTypeImpl(tt.getAfi(), tt.getSafi()));
                        } else {
                            final AddPathCapability addPathCap = cParam1.getAddPathCapability();
                            if (addPathCap != null) {
                                addPathCapabilitiesList.addAll(addPathCap.getAddressFamilies());
                            }
                        }
                    }
                }
            }
            this.gracefulCapability = findSingleCapability(bgpParameters, "Graceful Restart",
                CParameters1::getGracefulRestartCapability).orElse(GracefulRestartUtil.EMPTY_GR_CAPABILITY);
            this.llGracefulCapability = findSingleCapability(bgpParameters, "Long-lived Graceful Restart",
                CParameters1::getLlGracefulRestartCapability).orElse(GracefulRestartUtil.EMPTY_LLGR_CAPABILITY);
        } else {
            this.gracefulCapability = GracefulRestartUtil.EMPTY_GR_CAPABILITY;
            this.llGracefulCapability = GracefulRestartUtil.EMPTY_LLGR_CAPABILITY;
        }

        this.sync = new BGPSynchronization(this.listener, tts);
        this.tableTypes = tats;
        this.addPathTypes = addPathCapabilitiesList;

        if (!this.addPathTypes.isEmpty()) {
            addDecoderConstraint(MultiPathSupport.class,
                MultiPathSupportImpl.createParserMultiPathSupport(this.addPathTypes));
        }

        if (this.holdTimerValue != 0) {
            channel.eventLoop().schedule(this::handleHoldTimer, this.holdTimerValue, TimeUnit.SECONDS);
            channel.eventLoop().schedule(this::handleKeepaliveTimer, this.keepAlive, TimeUnit.SECONDS);
        }
        this.bgpId = remoteOpen.getBgpIdentifier();
        this.sessionState.advertizeCapabilities(this.holdTimerValue, channel.remoteAddress(), channel.localAddress(),
                this.tableTypes, bgpParameters);
    }

    private static <T extends ChildOf<MpCapabilities>> Optional<T> findSingleCapability(
            final List<BgpParameters> bgpParameters, final String name, final Function<CParameters1, T> extractor) {
        final List<T> found = new ArrayList<>(1);
        for (BgpParameters bgpParams : bgpParameters) {
            for (OptionalCapabilities optCapability : bgpParams.nonnullOptionalCapabilities()) {
                final CParameters cparam = optCapability.getCParameters();
                if (cparam != null) {
                    final CParameters1 augment = cparam.augmentation(CParameters1.class);
                    if (augment != null) {
                        final T capa = extractor.apply(augment);
                        if (capa != null) {
                            found.add(capa);
                        }
                    }
                }
            }
        }

        final Set<T> set = ImmutableSet.copyOf(found);
        switch (set.size()) {
            case 0:
                LOG.debug("{} capability not advertised.", name);
                return Optional.empty();
            case 1:
                return Optional.of(found.get(0));
            default:
                LOG.warn("Multiple instances of {} capability advertised: {}, ignoring.", name, set);
                return Optional.empty();
        }
    }

    /**
     * Set the extend message coder for current channel.
     * The reason for separating this part from constructor is, in #channel.pipeline().replace(..), the
     * invokeChannelRead() will be invoked after the original message coder handler got removed. And there
     * is chance that before the session instance is fully initiated (constructor returns), a KeepAlive
     * message arrived already in the channel buffer. Thus #AbstractBGPSessionNegotiator.handleMessage(..)
     * gets invoked again and a deadlock is caused.  A BGP final state machine error will happen as BGP
     * negotiator is still in OPEN_SENT state as the session constructor hasn't returned yet.
     */
    public synchronized void setChannelExtMsgCoder(final Open remoteOpen) {
        final boolean enableExMess = BgpExtendedMessageUtil.advertizedBgpExtendedMessageCapability(remoteOpen);
        if (enableExMess) {
            this.channel.pipeline().replace(BGPMessageHeaderDecoder.class, EXTENDED_MSG_DECODER,
                    BGPMessageHeaderDecoder.getExtendedBGPMessageHeaderDecoder());
        }
    }

    @Override
    public synchronized void close() {
        if (this.state != State.IDLE) {
            if (!this.terminationReasonNotified) {
                this.writeAndFlush(new NotifyBuilder().setErrorCode(BGPError.CEASE.getCode())
                        .setErrorSubcode(BGPError.CEASE.getSubcode()).build());
            }
            this.closeWithoutMessage();
        }
    }

    /**
     * Handles incoming message based on their type.
     *
     * @param msg incoming message
     */
    synchronized void handleMessage(final Notification msg) {
        if (this.state == State.IDLE) {
            return;
        }
        try {
            // Update last reception time
            this.lastMessageReceivedAt = System.nanoTime();

            if (msg instanceof Open) {
                // Open messages should not be present here
                this.terminate(new BGPDocumentedException(null, BGPError.FSM_ERROR));
            } else if (msg instanceof Notify) {
                final Notify notify = (Notify) msg;
                // Notifications are handled internally
                LOG.info("Session closed because Notification message received: {} / {}, data={}",
                        notify.getErrorCode(),
                        notify.getErrorSubcode(),
                        notify.getData() != null ? ByteBufUtil.hexDump(notify.getData()) : null);
                notifyTerminationReasonAndCloseWithoutMessage(notify.getErrorCode(), notify.getErrorSubcode());
            } else if (msg instanceof Keepalive) {
                // Keepalives are handled internally
                LOG.trace("Received KeepAlive message.");
                this.kaCounter++;
                if (this.kaCounter >= 2) {
                    this.sync.kaReceived();
                }
            } else if (msg instanceof RouteRefresh) {
                this.listener.onMessage(this, msg);
            } else if (msg instanceof Update) {
                this.listener.onMessage(this, msg);
                this.sync.updReceived((Update) msg);
            } else {
                LOG.warn("Ignoring unhandled message: {}.", msg.getClass());
            }

            this.sessionState.messageReceived(msg);
        } catch (final BGPDocumentedException e) {
            this.terminate(e);
        }
    }

    private synchronized void notifyTerminationReasonAndCloseWithoutMessage(
            final Short errorCode,
            final Short errorSubcode) {
        this.terminationReasonNotified = true;
        this.closeWithoutMessage();
        this.listener.onSessionTerminated(this, new BGPTerminationReason(
                BGPError.forValue(errorCode, errorSubcode)));
    }

    synchronized void endOfInput() {
        if (this.state == State.UP) {
            LOG.info(END_OF_INPUT);
            this.listener.onSessionDown(this, new IOException(END_OF_INPUT));
        }
    }

    @GuardedBy("this")
    private ChannelFuture writeEpilogue(final ChannelFuture future, final Notification msg) {
        future.addListener((ChannelFutureListener) f -> {
            if (!f.isSuccess()) {
                LOG.warn("Failed to send message {} to socket {}", msg, BGPSessionImpl.this.channel, f.cause());
            } else {
                LOG.trace("Message {} sent to socket {}", msg, BGPSessionImpl.this.channel);
            }
        });
        this.lastMessageSentAt = System.nanoTime();
        this.sessionState.messageSent(msg);
        return future;
    }

    void flush() {
        this.channel.flush();
    }

    @SuppressWarnings("checkstyle:illegalCatch")
    synchronized void write(final Notification msg) {
        try {
            writeEpilogue(this.channel.write(msg), msg);
        } catch (final Exception e) {
            LOG.warn("Message {} was not sent.", msg, e);
        }
    }

    synchronized ChannelFuture writeAndFlush(final Notification msg) {
        if (isWritable()) {
            return writeEpilogue(this.channel.writeAndFlush(msg), msg);
        }
        return this.channel.newFailedFuture(new NonWritableChannelException());
    }

    @Override
    public synchronized void closeWithoutMessage() {
        if (this.state == State.IDLE) {
            return;
        }
        LOG.info("Closing session: {}", this);
        this.channel.close().addListener((ChannelFutureListener) future
            -> Preconditions.checkArgument(future.isSuccess(), "Channel failed to close: %s", future.cause()));
        this.state = State.IDLE;
        removePeerSession();
        this.sessionState.setSessionState(this.state);
    }

    /**
     * Closes BGP session from the parent with given reason. A message needs to be sent, but parent doesn't have to be
     * modified, because he initiated the closing. (To prevent concurrent modification exception).
     *
     * @param cause BGPDocumentedException
     */
    @VisibleForTesting
    synchronized void terminate(final BGPDocumentedException cause) {
        final BGPError error = cause.getError();
        final byte[] data = cause.getData();
        final NotifyBuilder builder = new NotifyBuilder().setErrorCode(error.getCode())
                .setErrorSubcode(error.getSubcode());
        if (data != null && data.length != 0) {
            builder.setData(data);
        }
        this.writeAndFlush(builder.build());
        notifyTerminationReasonAndCloseWithoutMessage(error.getCode(), error.getSubcode());
    }

    private void removePeerSession() {
        if (this.peerRegistry != null) {
            this.peerRegistry.removePeerSession(StrictBGPPeerRegistry.getIpAddress(this.channel.remoteAddress()));
        }
    }

    /**
     * If HoldTimer expires, the session ends. If a message (whichever) was received during this period, the HoldTimer
     * will be rescheduled by HOLD_TIMER_VALUE + the time that has passed from the start of the HoldTimer to the time at
     * which the message was received. If the session was closed by the time this method starts to execute (the session
     * state will become IDLE), then rescheduling won't occur.
     */
    private synchronized void handleHoldTimer() {
        if (this.state == State.IDLE) {
            return;
        }

        final long ct = System.nanoTime();
        final long nextHold = this.lastMessageReceivedAt + TimeUnit.SECONDS.toNanos(this.holdTimerValue);

        if (ct >= nextHold) {
            LOG.debug("HoldTimer expired. {}", new Date());
            this.terminate(new BGPDocumentedException(BGPError.HOLD_TIMER_EXPIRED));
        } else {
            this.channel.eventLoop().schedule(this::handleHoldTimer, nextHold - ct, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * If KeepAlive Timer expires, sends KeepAlive message. If a message (whichever) was send during this period, the
     * KeepAlive Timer will be rescheduled by KEEP_ALIVE_TIMER_VALUE + the time that has passed from the start of the
     * KeepAlive timer to the time at which the message was sent. If the session was closed by the time this method
     * starts to execute (the session state will become IDLE), that rescheduling won't occur.
     */
    private synchronized void handleKeepaliveTimer() {
        if (this.state == State.IDLE) {
            return;
        }

        final long ct = System.nanoTime();
        final long keepNanos = TimeUnit.SECONDS.toNanos(this.keepAlive);
        long nextKeepalive = this.lastMessageSentAt + keepNanos;

        if (ct >= nextKeepalive) {
            final ChannelFuture future = this.writeAndFlush(KEEP_ALIVE);
            LOG.debug("Enqueued session {} keepalive as {}", this, future);
            nextKeepalive = ct + keepNanos;
            if (LOG.isDebugEnabled()) {
                future.addListener(compl -> LOG.debug("Session {} keepalive completed as {}", this, compl));
            }
        } else {
            LOG.debug("Skipping keepalive on session {}", this);
        }

        final long nextNanos = nextKeepalive - ct;
        LOG.debug("Scheduling next keepalive on {} in {} nanos", this, nextNanos);
        this.channel.eventLoop().schedule(this::handleKeepaliveTimer, nextNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public final String toString() {
        return addToStringAttributes(MoreObjects.toStringHelper(this)).toString();
    }

    protected ToStringHelper addToStringAttributes(final ToStringHelper toStringHelper) {
        toStringHelper.add("channel", this.channel);
        toStringHelper.add("state", this.getState());
        return toStringHelper;
    }

    @Override
    public Set<BgpTableType> getAdvertisedTableTypes() {
        return this.tableTypes;
    }

    @Override
    public List<AddressFamilies> getAdvertisedAddPathTableTypes() {
        return this.addPathTypes;
    }

    @Override
    public GracefulRestartCapability getAdvertisedGracefulRestartCapability() {
        return this.gracefulCapability;
    }

    @Override
    public LlGracefulRestartCapability getAdvertisedLlGracefulRestartCapability() {
        return this.llGracefulCapability;
    }

    @VisibleForTesting
    @SuppressWarnings("checkstyle:illegalCatch")
    synchronized void sessionUp() {
        this.state = State.UP;
        try {
            this.sessionState.setSessionState(this.state);
            this.listener.onSessionUp(this);
        } catch (final Exception e) {
            handleException(e);
            throw e;
        }
    }

    public synchronized State getState() {
        return this.state;
    }

    @Override
    public final Ipv4Address getBgpId() {
        return this.bgpId;
    }

    @Override
    public final AsNumber getAsNumber() {
        return this.asNumber;
    }

    private synchronized boolean isWritable() {
        return this.channel != null && this.channel.isWritable();
    }

    public ChannelOutputLimiter getLimiter() {
        return this.limiter;
    }

    @Override
    @SuppressWarnings("checkstyle:illegalCatch")
    public final void channelInactive(final ChannelHandlerContext ctx) {
        LOG.debug("Channel {} inactive.", ctx.channel());
        this.endOfInput();

        try {
            super.channelInactive(ctx);
        } catch (final Exception e) {
            throw new IllegalStateException("Failed to delegate channel inactive event on channel " + ctx.channel(), e);
        }
    }

    @Override
    protected final void channelRead0(final ChannelHandlerContext ctx, final Notification msg) {
        LOG.debug("Message was received: {}", msg);
        this.handleMessage(msg);
    }

    @Override
    public final void handlerAdded(final ChannelHandlerContext ctx) {
        this.sessionUp();
    }

    @Override
    public synchronized void exceptionCaught(final ChannelHandlerContext ctx, final Throwable cause) {
        handleException(cause);
    }

    /**
     * Handle exception occurred in the BGP session. The session in error state should be closed
     * properly so that it can be restored later.
     */
    @VisibleForTesting
    void handleException(final Throwable cause) {
        LOG.warn("BGP session encountered error", cause);
        final Throwable docCause = cause.getCause();
        if (docCause instanceof BGPDocumentedException) {
            this.terminate((BGPDocumentedException) docCause);
        } else {
            this.terminate(new BGPDocumentedException(BGPError.CEASE));
        }
    }

    @Override
    public BGPSessionState getBGPSessionState() {
        return this.sessionState;
    }

    @Override
    public BGPTimersState getBGPTimersState() {
        return this.sessionState;
    }

    @Override
    public BGPTransportState getBGPTransportState() {
        return this.sessionState;
    }

    @Override
    public void registerMessagesCounter(final BGPMessagesListener bgpMessagesListener) {
        this.sessionState.registerMessagesCounter(bgpMessagesListener);
    }

    @Override
    public <T extends PeerConstraint> void addDecoderConstraint(final Class<T> constraintClass, final T constraint) {
        this.channel.pipeline().get(BGPByteToMessageDecoder.class).addDecoderConstraint(constraintClass, constraint);
    }

    @Override
    public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
        return this.channel.eventLoop().schedule(command, delay, unit);
    }
}
