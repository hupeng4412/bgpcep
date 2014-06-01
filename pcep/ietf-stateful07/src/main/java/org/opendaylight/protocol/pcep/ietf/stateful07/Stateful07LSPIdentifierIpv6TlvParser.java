/*
 * Copyright (c) 2013 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.protocol.pcep.ietf.stateful07;

import io.netty.buffer.ByteBuf;

import org.opendaylight.protocol.concepts.Ipv6Util;
import org.opendaylight.protocol.pcep.impl.tlv.TlvUtil;
import org.opendaylight.protocol.pcep.spi.PCEPDeserializerException;
import org.opendaylight.protocol.pcep.spi.TlvParser;
import org.opendaylight.protocol.pcep.spi.TlvSerializer;
import org.opendaylight.protocol.util.ByteArray;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.pcep.ietf.stateful.rev131222.lsp.identifiers.tlv.LspIdentifiers;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.pcep.ietf.stateful.rev131222.lsp.identifiers.tlv.LspIdentifiersBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.pcep.ietf.stateful.rev131222.lsp.identifiers.tlv.lsp.identifiers.AddressFamily;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.pcep.ietf.stateful.rev131222.lsp.identifiers.tlv.lsp.identifiers.address.family.Ipv6Case;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.pcep.ietf.stateful.rev131222.lsp.identifiers.tlv.lsp.identifiers.address.family.Ipv6CaseBuilder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.pcep.ietf.stateful.rev131222.lsp.identifiers.tlv.lsp.identifiers.address.family.ipv6._case.Ipv6;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.pcep.ietf.stateful.rev131222.lsp.identifiers.tlv.lsp.identifiers.address.family.ipv6._case.Ipv6Builder;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.pcep.types.rev131005.Tlv;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.rsvp.rev130820.Ipv6ExtendedTunnelId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.rsvp.rev130820.LspId;
import org.opendaylight.yang.gen.v1.urn.opendaylight.params.xml.ns.yang.rsvp.rev130820.TunnelId;

/**
 * Parser for {@link LspIdentifiers}
 */
public final class Stateful07LSPIdentifierIpv6TlvParser implements TlvParser, TlvSerializer {

	public static final int TYPE = 19;

	private static final int IP6_F_LENGTH = 16;
	private static final int EX_TUNNEL_ID6_F_LENGTH = 16;

	private static final int LSP_ID_F_LENGTH = 2;
	private static final int TUNNEL_ID_F_LENGTH = 2;

	private static final int V6_LENGTH = 52;

	@Override
	public LspIdentifiers parseTlv(final ByteBuf buffer) throws PCEPDeserializerException {
		if (buffer == null) {
			return null;
		}
		if (buffer.readableBytes() != V6_LENGTH) {
			throw new IllegalArgumentException("Length " + buffer.readableBytes() + " does not match LSP Identifiers Ipv6 tlv length.");
		}
		final Ipv6Builder builder = new Ipv6Builder();
		builder.setIpv6TunnelSenderAddress(Ipv6Util.addressForBytes(ByteArray.readBytes(buffer, IP6_F_LENGTH)));
		final LspId lspId = new LspId((long) buffer.readUnsignedShort());
		final TunnelId tunnelId = new TunnelId(buffer.readUnsignedShort());
		builder.setIpv6ExtendedTunnelId(new Ipv6ExtendedTunnelId(Ipv6Util.addressForBytes(ByteArray.readBytes(buffer, EX_TUNNEL_ID6_F_LENGTH))));
		builder.setIpv6TunnelEndpointAddress(Ipv6Util.addressForBytes(ByteArray.readBytes(buffer, IP6_F_LENGTH)));
		final AddressFamily afi = new Ipv6CaseBuilder().setIpv6(builder.build()).build();
		return new LspIdentifiersBuilder().setAddressFamily(afi).setLspId(lspId).setTunnelId(tunnelId).build();
	}

	@Override
	public byte[] serializeTlv(final Tlv tlv) {
		if (tlv == null) {
			throw new IllegalArgumentException("LspIdentifiersTlv is mandatory.");
		}
		final byte[] bytes = new byte[V6_LENGTH];
		int offset = 0;
		final LspIdentifiers lsp = (LspIdentifiers) tlv;
		final Ipv6 ipv6 = ((Ipv6Case) lsp.getAddressFamily()).getIpv6();
		ByteArray.copyWhole(Ipv6Util.bytesForAddress(ipv6.getIpv6TunnelSenderAddress()), bytes, offset);
		offset += IP6_F_LENGTH;
		ByteArray.copyWhole(ByteArray.longToBytes(lsp.getLspId().getValue(), LSP_ID_F_LENGTH), bytes, offset);
		offset += LSP_ID_F_LENGTH;
		ByteArray.copyWhole(ByteArray.intToBytes(lsp.getTunnelId().getValue(), TUNNEL_ID_F_LENGTH), bytes, offset);
		offset += TUNNEL_ID_F_LENGTH;
		ByteArray.copyWhole(Ipv6Util.bytesForAddress(ipv6.getIpv6ExtendedTunnelId()), bytes, offset);
		offset += EX_TUNNEL_ID6_F_LENGTH;
		ByteArray.copyWhole(Ipv6Util.bytesForAddress(ipv6.getIpv6TunnelEndpointAddress()), bytes, offset);
		return TlvUtil.formatTlv(TYPE, bytes);
	}
}
