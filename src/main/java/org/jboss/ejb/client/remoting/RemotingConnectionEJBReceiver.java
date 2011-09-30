/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.ejb.client.remoting;

import java.lang.reflect.UndeclaredThrowableException;
import java.security.PrivateKey;
import java.util.concurrent.CancellationException;
import org.jboss.ejb.client.EJBClientInvocationContext;
import org.jboss.ejb.client.EJBReceiver;
import org.jboss.ejb.client.EJBReceiverContext;
import org.jboss.ejb.client.EJBReceiverInvocationContext;
import org.jboss.ejb.client.NoSessionID;
import org.jboss.ejb.client.SessionID;
import org.jboss.logging.Logger;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.CloseHandler;
import org.jboss.remoting3.Connection;
import org.xnio.FutureResult;
import org.xnio.IoFuture;
import org.xnio.OptionMap;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class RemotingConnectionEJBReceiver extends EJBReceiver<RemotingAttachments> {

    private static final Logger logger = Logger.getLogger(RemotingConnectionEJBReceiver.class);

    private final Connection connection;

    private final Map<EJBReceiverContext, ChannelAssociation> channelAssociation = new IdentityHashMap<EJBReceiverContext, ChannelAssociation>();

    // TODO: The version and the marshalling strategy shouldn't be hardcoded here
    private final byte clientProtocolVersion = 0x00;
    private final String clientMarshallingStrategy = "river";

    /**
     * Construct a new instance.
     *
     * @param connection the connection to associate with
     */
    public RemotingConnectionEJBReceiver(final Connection connection) {
        this.connection = connection;
    }

    @Override
    public void associate(final EJBReceiverContext context) {
        final CountDownLatch versionHandshakeLatch = new CountDownLatch(1);
        final VersionReceiver versionReceiver = new VersionReceiver(versionHandshakeLatch, this.clientProtocolVersion, this.clientMarshallingStrategy);
        final IoFuture<Channel> futureChannel = connection.openChannel("jboss.ejb", OptionMap.EMPTY);
        futureChannel.addNotifier(new IoFuture.HandlingNotifier<Channel, EJBReceiverContext>() {
            public void handleCancelled(final EJBReceiverContext context) {
                context.close();
            }

            public void handleFailed(final IOException exception, final EJBReceiverContext context) {
                // todo: log?
                context.close();
            }

            public void handleDone(final Channel channel, final EJBReceiverContext context) {
                channel.addCloseHandler(new CloseHandler<Channel>() {
                    public void handleClose(final Channel closed, final IOException exception) {
                        context.close();
                    }
                });
                // receive version message from server
                channel.receiveMessage(versionReceiver);
            }
        }, context);

        try {
            // wait for the handshake to complete
            // TODO: Think about externalizing this timeout
            final boolean successfulHandshake = versionHandshakeLatch.await(5, TimeUnit.SECONDS);
            if (successfulHandshake) {
                final Channel compatibleChannel = versionReceiver.getCompatibleChannel();
                final ChannelAssociation channelAssociation = new ChannelAssociation(this, context, compatibleChannel, this.clientProtocolVersion, this.clientMarshallingStrategy);
                synchronized (this.channelAssociation) {
                    this.channelAssociation.put(context, channelAssociation);
                }
                logger.info("Successful version handshake completed for receiver context " + context + " on channel " + compatibleChannel);
            } else {
                // no version handshake done. close the context
                logger.info("Version handshake not completed for recevier context " + context + " by receiver " + this + " . Closing the receiver context");
                context.close();
            }
        } catch (InterruptedException e) {
            context.close();
        }
    }

    @Override
    public void processInvocation(final EJBClientInvocationContext<RemotingAttachments> clientInvocationContext, final EJBReceiverInvocationContext ejbReceiverInvocationContext) throws Exception {
        ChannelAssociation channelAssociation;
        synchronized (this.channelAssociation) {
            channelAssociation = this.channelAssociation.get(ejbReceiverInvocationContext.getEjbReceiverContext());
        }
        if (channelAssociation == null) {
            throw new IllegalStateException("EJB receiver " + this + " is not yet ready to process invocations for receiver context " + ejbReceiverInvocationContext);
        }
        final MethodInvocationMessageWriter messageWriter = new MethodInvocationMessageWriter(this, this.clientProtocolVersion, this.clientMarshallingStrategy);
        final Channel channel = channelAssociation.getChannel();
        final DataOutputStream dataOutputStream = new DataOutputStream(channel.writeMessage());
        final short invocationId = channelAssociation.getNextInvocationId();
        try {
            messageWriter.writeMessage(dataOutputStream, invocationId, clientInvocationContext);
        } finally {
            dataOutputStream.close();
        }
        channelAssociation.receiveResponse(invocationId, ejbReceiverInvocationContext);
    }

    @Override
    public SessionID openSession(final EJBReceiverContext receiverContext, final String appName, final String moduleName, final String distinctName, final String beanName) throws Exception {
        // todo
        return NoSessionID.INSTANCE;
    }

    public void verify(final String appName, final String moduleName, final String distinctName, final String beanName) throws Exception {
    }

    public RemotingAttachments createReceiverSpecific() {
        return new RemotingAttachments();
    }

    void onModuleAvailable(final String appName, final String moduleName, final String distinctName) {
        logger.info("Received module availability message for appName: " + appName + " moduleName: " + moduleName + " distinctName: " + distinctName);

        this.registerModule(appName, moduleName, distinctName);
    }

}