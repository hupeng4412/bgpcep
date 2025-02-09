/*
 * Copyright (c) 2015 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.protocol.bgp.mode.impl.base;

import static java.util.Objects.requireNonNull;
import static org.opendaylight.protocol.bgp.parser.spi.PathIdUtil.NON_PATH_ID_VALUE;

import com.google.common.base.MoreObjects.ToStringHelper;
import org.eclipse.jdt.annotation.NonNull;
import org.opendaylight.protocol.bgp.mode.api.BestPathState;
import org.opendaylight.protocol.bgp.mode.spi.AbstractBestPath;
import org.opendaylight.protocol.bgp.rib.spi.RouterId;

final class BaseBestPath extends AbstractBestPath {
    private final @NonNull RouterId routerId;

    BaseBestPath(final @NonNull RouterId routerId, final @NonNull BestPathState state) {
        super(state);
        this.routerId = requireNonNull(routerId);
    }

    @Override
    public RouterId getRouterId() {
        return this.routerId;
    }

    @Override
    public long getPathId() {
        return NON_PATH_ID_VALUE;
    }

    @Override
    protected ToStringHelper addToStringAttributes(final ToStringHelper toStringHelper) {
        return super.addToStringAttributes(toStringHelper.add("routerId", this.routerId));
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + this.routerId.hashCode();
        result = prime * result + this.state.hashCode();
        return result;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof BaseBestPath)) {
            return false;
        }
        final BaseBestPath other = (BaseBestPath) obj;
        if (!this.routerId.equals(other.routerId)) {
            return false;
        }
        if (!this.state.equals(other.state)) {
            return false;
        }
        return true;
    }
}
