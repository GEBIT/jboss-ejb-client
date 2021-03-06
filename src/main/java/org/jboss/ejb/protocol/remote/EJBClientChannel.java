/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015, Red Hat, Inc., and individual contributors
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

package org.jboss.ejb.protocol.remote;

import static java.lang.Math.min;
import static org.xnio.IoUtils.safeClose;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.InflaterInputStream;

import javax.ejb.CreateException;
import javax.ejb.EJBException;
import javax.ejb.NoSuchEJBException;

import org.jboss.ejb.client.Affinity;
import org.jboss.ejb.client.AttachmentKey;
import org.jboss.ejb.client.AttachmentKeys;
import org.jboss.ejb.client.EJBClientInvocationContext;
import org.jboss.ejb.client.EJBLocator;
import org.jboss.ejb.client.EJBReceiverInvocationContext;
import org.jboss.ejb.client.SessionID;
import org.jboss.ejb.client.StatefulEJBLocator;
import org.jboss.ejb.client.StatelessEJBLocator;
import org.jboss.ejb.client.TransactionID;
import org.jboss.marshalling.ByteInput;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.remoting3.Attachments;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.CloseHandler;
import org.jboss.remoting3.Connection;
import org.jboss.remoting3.MessageInputStream;
import org.jboss.remoting3.MessageOutputStream;
import org.jboss.remoting3._private.IntIndexHashMap;
import org.jboss.remoting3._private.IntIndexMap;
import org.jboss.remoting3._private.IntIndexer;
import org.jboss.remoting3.util.StreamUtils;
import org.xnio.FutureResult;
import org.xnio.IoFuture;
import org.xnio.OptionMap;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
@SuppressWarnings("deprecation")
class EJBClientChannel {

    static final byte[] RIVER_BYTES = new byte[] {
        5, (byte) 'r', (byte) 'i', (byte) 'v', (byte) 'e', (byte) 'r'
    };

    private final MarshallerFactory marshallerFactory;

    private final Channel channel;
    private final int version;

    private final IntIndexMap<Invocation> invocationMap = new IntIndexHashMap<Invocation>(Invocation.INDEXER);

    private final AtomicInteger messageCount = new AtomicInteger();
    private final MarshallingConfiguration configuration;
    private volatile boolean closed;

    EJBClientChannel(final Channel channel, final int version) {
        this.channel = channel;
        this.version = version;
        marshallerFactory = Marshalling.getProvidedMarshallerFactory("river");
        MarshallingConfiguration configuration = new MarshallingConfiguration();
        if (version < 3) {
            configuration.setClassTable(ProtocolV1ClassTable.INSTANCE);
            configuration.setObjectTable(ProtocolV1ObjectTable.INSTANCE);
            configuration.setVersion(2);
        } else {
            configuration.setClassTable(ProtocolV3ClassTable.INSTANCE);
            configuration.setObjectTable(ProtocolV3ObjectTable.INSTANCE);
            configuration.setVersion(4);
        }
        this.configuration = configuration;
    }

    public void processInvocation(final EJBReceiverInvocationContext receiverContext) {
        int id;
        MethodInvocation invocation;
        if (closed) {
            receiverContext.requestCancelled();
            return;
        }
        final IntIndexMap<Invocation> invocationMap = this.invocationMap;
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        do {
            do {
                id = random.nextInt() & 0xffff;
            } while (invocationMap.containsKey(id));
            invocation = new MethodInvocation(id, receiverContext);
        } while (invocationMap.putIfAbsent(invocation) != null);
        if (closed) {
            invocationMap.remove(invocation);
            receiverContext.requestCancelled();
            return;
        }
        final EJBClientInvocationContext invocationContext = receiverContext.getClientInvocationContext();
        final EJBLocator<?> locator = invocationContext.getLocator();
        try (DelegatingMessageOutputStream out = getMessageBlocking()) {
            try {
                out.write(Protocol.INVOCATION_REQUEST);
                out.writeShort(id);

                Marshaller marshaller = getMarshaller();
                marshaller.start(out);

                final Method invokedMethod = invocationContext.getInvokedMethod();
                final Object[] parameters = invocationContext.getParameters();

                if (version < 3) {
                    // method name as UTF string
                    out.writeUTF(invokedMethod.getName());

                    // write the method signature as UTF string
                    out.writeUTF(invocationContext.getMethodSignatureString());

                    // protocol 1 & 2 redundant locator objects
                    marshaller.writeObject(locator.getAppName());
                    marshaller.writeObject(locator.getModuleName());
                    marshaller.writeObject(locator.getDistinctName());
                    marshaller.writeObject(locator.getBeanName());
                } else {
                    // write method locator
                    marshaller.writeObject(invocationContext.getMethodLocator());
                }
                // write the invocation locator itself
                marshaller.writeObject(locator);

                // and the parameters
                if (parameters != null && parameters.length > 0) {
                    for (final Object methodParam : parameters) {
                        marshaller.writeObject(methodParam);
                    }
                }

                // now, attachments
                // we write out the private (a.k.a JBoss specific) attachments as well as public invocation context data
                // (a.k.a user application specific data)
                final Map<AttachmentKey<?>, ?> privateAttachments = invocationContext.getAttachments();
                final Map<String, Object> contextData = invocationContext.getContextData();

                // write the attachment count which is the sum of invocation context data + 1 (since we write
                // out the private attachments under a single key with the value being the entire attachment map)
                int totalAttachments = contextData.size();
                final boolean hasPrivateAttachments = !privateAttachments.isEmpty();
                if (hasPrivateAttachments) {
                    totalAttachments++;
                }
                if (version >= 3) {
                    // Just write the attachments.
                    PackedInteger.writePackedInteger(marshaller, totalAttachments);

                    for (Map.Entry<String, Object> invocationContextData : contextData.entrySet()) {
                        marshaller.writeObject(invocationContextData.getKey());
                        marshaller.writeObject(invocationContextData.getValue());
                    }

                    if (hasPrivateAttachments) {
                        // now write out the JBoss specific attachments under a single key and the value will be the
                        // entire map of JBoss specific attachments
                        marshaller.writeObject(EJBClientInvocationContext.PRIVATE_ATTACHMENTS_KEY);
                        marshaller.writeObject(privateAttachments);
                    }
                } else {
                    // Note: The code here is just for backward compatibility of 1.x and 2.x versions of EJB client project.
                    // Attach legacy transaction ID, if there is an active txn.

                    final boolean txIdAttachmentPresent = privateAttachments.containsKey(AttachmentKeys.TRANSACTION_ID_KEY);
                    if (txIdAttachmentPresent) {
                        // we additionally add/duplicate the transaction id under a different attachment key
                        // to preserve backward compatibility. This is here just for 1.0.x backward compatibility
                        totalAttachments++;
                    }
                    // backward compatibility code block for transaction id ends here.

                    PackedInteger.writePackedInteger(marshaller, totalAttachments);
                    // write out public (application specific) context data
                    for (Map.Entry<String, Object> invocationContextData : contextData.entrySet()) {
                        marshaller.writeObject(invocationContextData.getKey());
                        marshaller.writeObject(invocationContextData.getValue());
                    }
                    if (hasPrivateAttachments) {
                        // now write out the JBoss specific attachments under a single key and the value will be the
                        // entire map of JBoss specific attachments
                        marshaller.writeObject(EJBClientInvocationContext.PRIVATE_ATTACHMENTS_KEY);
                        marshaller.writeObject(privateAttachments);
                    }

                    // Note: The code here is just for backward compatibility of 1.0.x version of EJB client project
                    // against AS7 7.1.x releases. Discussion here https://github.com/jbossas/jboss-ejb-client/pull/11#issuecomment-6573863
                    if (txIdAttachmentPresent) {
                        // we additionally add/duplicate the transaction id under a different attachment key
                        // to preserve backward compatibility. This is here just for 1.0.x backward compatibility
                        marshaller.writeObject(TransactionID.PRIVATE_DATA_KEY);
                        // This transaction id attachment duplication *won't* cause increase in EJB protocol message payload
                        // since we rely on JBoss Marshalling to use back references for the same transaction id object being
                        // written out
                        marshaller.writeObject(privateAttachments.get(AttachmentKeys.TRANSACTION_ID_KEY));
                    }
                    // backward compatibility code block for transaction id ends here.
                }

                // finished
                marshaller.finish();
            } catch (IOException e) {
                out.cancel();
                throw e;
            }
        } catch (IOException e) {
            receiverContext.resultReady(new EJBReceiverInvocationContext.ResultProducer.Failed(new EJBException(e.getMessage(), e)));
            return;
        }
    }

    private Marshaller getMarshaller() throws IOException {
        return marshallerFactory.createMarshaller(configuration);
    }

    public <T> StatefulEJBLocator<T> openSession(final StatelessEJBLocator<T> statelessLocator) throws Exception {
        int id;
        SessionOpenInvocation<T> invocation;
        final IntIndexMap<Invocation> invocationMap = this.invocationMap;
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        do {
            do {
                id = random.nextInt() & 0xffff;
            } while (invocationMap.containsKey(id));
            invocation = new SessionOpenInvocation<T>(id, statelessLocator);
        } while (invocationMap.putIfAbsent(invocation) != null);
        try (DelegatingMessageOutputStream out = getMessageBlocking()) {
            out.write(Protocol.OPEN_SESSION_REQUEST);
            out.writeShort(id);
            writeRawIdentifier(statelessLocator, out);
        } catch (IOException e) {
            CreateException createException = new CreateException(e.getMessage());
            createException.initCause(e);
            throw createException;
        }
        // await the response
        return invocation.getResult();
    }

    private static <T> void writeRawIdentifier(final EJBLocator<T> statelessLocator, final DelegatingMessageOutputStream out) throws IOException {
        final String appName = statelessLocator.getAppName();
        out.writeUTF(appName == null ? "" : appName);
        out.writeUTF(statelessLocator.getModuleName());
        out.writeUTF(statelessLocator.getBeanName());
        final String distinctName = statelessLocator.getDistinctName();
        out.writeUTF(distinctName == null ? "" : distinctName);
    }

    public static IoFuture<EJBClientChannel> fromFuture(final Connection connection) {
        final Attachments attachments = connection.getAttachments();
        Future future = attachments.getAttachment(KEY);
        if (future == null) {
            Future appearing = attachments.attachIfAbsent(KEY, future = new Future(connection));
            if (appearing != null) {
                // discard constructed Future
                future = appearing;
            }
        }
        return future.acquire().getIoFuture();
    }

    public static EJBClientChannel from(final Connection connection) throws IOException {
        try {
            return fromFuture(connection).getInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }
    }

    DelegatingMessageOutputStream getMessageBlocking() throws IOException {
        int oldVal;
        do {
            oldVal = messageCount.get();
            if (oldVal == 0) {
                synchronized (messageCount) {
                    do {
                        try {
                            messageCount.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException();
                        }
                        oldVal = messageCount.get();
                    } while (oldVal == 0);
                }
            }
        } while (! messageCount.compareAndSet(oldVal, oldVal - 1));
        return new DelegatingMessageOutputStream(channel.writeMessage());
    }

    static class CachedLocator {
        private final EJBLocator<?> locator;
        private final int locatorId;

        CachedLocator(final EJBLocator<?> locator, final int locatorId) {
            this.locator = locator;
            this.locatorId = locatorId;
        }

        EJBLocator<?> getLocator() {
            return locator;
        }

        int getLocatorId() {
            return locatorId;
        }
    }

    abstract static class Invocation {
        static final IntIndexer<Invocation> INDEXER = new IntIndexer<Invocation>() {
            public int getKey(final Invocation argument) {
                return argument.getIndex();
            }
        };

        private final int index;

        protected Invocation(final int index) {
            this.index = index;
        }

        int getIndex() {
            return index;
        }

        abstract void handleResponse(int id, MessageInputStream inputStream);

        abstract void handleClosed();
    }

    final class SessionOpenInvocation<T> extends Invocation {

        private final StatelessEJBLocator<T> statelessLocator;
        private int id;
        private MessageInputStream inputStream;

        protected SessionOpenInvocation(final int index, final StatelessEJBLocator<T> statelessLocator) {
            super(index);
            this.statelessLocator = statelessLocator;
        }

        void handleResponse(final int id, final MessageInputStream inputStream) {
            synchronized (this) {
                this.id = id;
                this.inputStream = inputStream;
                notifyAll();
            }
        }

        void handleClosed() {
            synchronized (this) {
                this.id = -1;
                notifyAll();
            }
        }

        StatefulEJBLocator<T> getResult() throws Exception {
            Exception e;
            try (ResponseMessageInputStream response = removeInvocationResult()) {
                final int id = response.getId();
                switch (id) {
                    case Protocol.OPEN_SESSION_RESPONSE: {
                        final Affinity affinity;
                        int size = StreamUtils.readPackedUnsignedInt32(response);
                        byte[] bytes = new byte[size];
                        response.readFully(bytes);
                        // todo: pool unmarshallers?  use very small instance count config?
                        try (final Unmarshaller unmarshaller = createUnmarshaller()) {
                            unmarshaller.start(response);
                            affinity = unmarshaller.readObject(Affinity.class);
                            unmarshaller.finish();
                        }
                        response.close();
                        return new StatefulEJBLocator<T>(statelessLocator, SessionID.createSessionID(bytes), affinity);
                    }
                    case Protocol.APPLICATION_EXCEPTION: {
                        try (final Unmarshaller unmarshaller = createUnmarshaller()) {
                            unmarshaller.start(response);
                            e = unmarshaller.readObject(Exception.class);
                            unmarshaller.finish();
                            if (version < 3) {
                                // drain off attachments so the server doesn't complain
                                while (response.read() != -1) {
                                    response.skip(Long.MAX_VALUE);
                                }
                            }
                        }
                        // todo: glue stack traces
                        break;
                    }
                    case Protocol.NO_SUCH_EJB: {
                        final String message = response.readUTF();
                        throw new NoSuchEJBException(message);
                    }
                    case Protocol.EJB_NOT_STATEFUL: {
                        final String message = response.readUTF();
                        // todo: I don't think this is the best exception type for this case...
                        throw new IllegalArgumentException(message);
                    }
                    default: {
                        throw new EJBException("Invalid EJB creation response (id " + id + ")");
                    }
                }
            } catch (ClassNotFoundException | IOException ex) {
                throw new EJBException("Failed to read session create response", ex);
            }
            // throw the application exception outside of the try block
            throw e;
        }

        private ResponseMessageInputStream removeInvocationResult() {
            MessageInputStream mis;
            int id;
            try {
                synchronized (this) {
                    for (; ; ) {
                        id = this.id;
                        if (inputStream != null) {
                            mis = inputStream;
                            inputStream = null;
                            break;
                        }
                        if (id == -1) {
                            throw new EJBException("Connection closed");
                        }
                        wait();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EJBException("Session creation interrupted");
            } finally {
                invocationMap.remove(this);
            }
            return new ResponseMessageInputStream(mis, id);
        }
    }

    private Unmarshaller createUnmarshaller() throws IOException {
        return marshallerFactory.createUnmarshaller(configuration);
    }

    final class MethodInvocation extends Invocation {
        private final EJBReceiverInvocationContext receiverInvocationContext;

        protected MethodInvocation(final int index, final EJBReceiverInvocationContext receiverInvocationContext) {
            super(index);
            this.receiverInvocationContext = receiverInvocationContext;
        }

        void handleResponse(final int id, final MessageInputStream inputStream) {
            switch (id) {
                case Protocol.COMPRESSED_INVOCATION_MESSAGE: {
                    invocationMap.remove(this);
                    receiverInvocationContext.resultReady(new MethodCallResultProducer(new InflaterInputStream(inputStream), id));
                    break;
                }
                case Protocol.INVOCATION_RESPONSE: {
                    invocationMap.remove(this);
                    receiverInvocationContext.resultReady(new MethodCallResultProducer(inputStream, id));
                    break;
                }
                case Protocol.APPLICATION_EXCEPTION: {
                    invocationMap.remove(this);
                    receiverInvocationContext.resultReady(new ExceptionResultProducer(inputStream, id));
                    break;
                }
                case Protocol.NO_SUCH_EJB: {
                    invocationMap.remove(this);
                    try {
                        final String message = inputStream.readUTF();
                        receiverInvocationContext.resultReady(new EJBReceiverInvocationContext.ResultProducer.Failed(new NoSuchEJBException(message)));
                    } catch (IOException e) {
                        receiverInvocationContext.resultReady(new EJBReceiverInvocationContext.ResultProducer.Failed(new EJBException("Failed to read 'No such EJB' response", e)));
                    } finally {
                        safeClose(inputStream);
                    }
                    break;
                }
                case Protocol.NO_SUCH_METHOD: {
                    invocationMap.remove(this);
                    try {
                        final String message = inputStream.readUTF();
                        // todo: I don't think this is the best exception type for this case...
                        receiverInvocationContext.resultReady(new EJBReceiverInvocationContext.ResultProducer.Failed(new IllegalArgumentException(message)));
                    } catch (IOException e) {
                        receiverInvocationContext.resultReady(new EJBReceiverInvocationContext.ResultProducer.Failed(new EJBException("Failed to read 'No such EJB method' response", e)));
                    } finally {
                        safeClose(inputStream);
                    }
                    break;
                }
                case Protocol.SESSION_NOT_ACTIVE: {
                    invocationMap.remove(this);
                    try {
                        final String message = inputStream.readUTF();
                        receiverInvocationContext.resultReady(new EJBReceiverInvocationContext.ResultProducer.Failed(new EJBException(message)));
                    } catch (IOException e) {
                        receiverInvocationContext.resultReady(new EJBReceiverInvocationContext.ResultProducer.Failed(new EJBException("Failed to read 'Session not active' response", e)));
                    } finally {
                        safeClose(inputStream);
                    }
                    break;
                }
                case Protocol.EJB_NOT_STATEFUL: {
                    invocationMap.remove(this);
                    try {
                        final String message = inputStream.readUTF();
                        receiverInvocationContext.resultReady(new EJBReceiverInvocationContext.ResultProducer.Failed(new EJBException(message)));
                    } catch (IOException e) {
                        receiverInvocationContext.resultReady(new EJBReceiverInvocationContext.ResultProducer.Failed(new EJBException("Failed to read 'EJB not stateful' response", e)));
                    } finally {
                        safeClose(inputStream);
                    }
                    break;
                }
                case Protocol.PROCEED_ASYNC_RESPONSE: {
                    safeClose(inputStream);
                    receiverInvocationContext.proceedAsynchronously();
                    break;
                }
                default: {
                    invocationMap.remove(this);
                    safeClose(inputStream);
                    receiverInvocationContext.resultReady(new EJBReceiverInvocationContext.ResultProducer.Failed(new EJBException("Unknown protocol response")));
                    break;
                }
            }
        }

        void handleClosed() {
            closed = true;
            for (Invocation invocation : invocationMap) {
                invocation.handleClosed();
            }
        }

        private class MethodCallResultProducer implements EJBReceiverInvocationContext.ResultProducer {

            private final InputStream inputStream;
            private final int id;

            public MethodCallResultProducer(final InputStream inputStream, final int id) {
                this.inputStream = inputStream;
                this.id = id;
            }

            public Object getResult() throws Exception {
                final ResponseMessageInputStream response = new ResponseMessageInputStream(inputStream, id);
                Object result;
                try (final Unmarshaller unmarshaller = createUnmarshaller()) {
                    unmarshaller.start(response);
                    result = unmarshaller.readObject();
                    int attachments = unmarshaller.readUnsignedByte();
                    for (int i = 0; i < attachments; i ++) {
                        String key = unmarshaller.readObject(String.class);
                        if (key.equals(Affinity.WEAK_AFFINITY_CONTEXT_KEY)) {
                            receiverInvocationContext.getClientInvocationContext().putAttachment(AttachmentKeys.WEAK_AFFINITY, unmarshaller.readObject(Affinity.class));
                        } else {
                            // discard
                            unmarshaller.readObject();
                        }
                    }
                    unmarshaller.finish();
                } catch (IOException | ClassNotFoundException ex) {
                    throw new EJBException("Failed to read response", ex);
                }
                return result;
            }

            public void discardResult() {
                safeClose(inputStream);
            }
        }

        private class ExceptionResultProducer implements EJBReceiverInvocationContext.ResultProducer {

            private final InputStream inputStream;
            private final int id;

            public ExceptionResultProducer(final InputStream inputStream, final int id) {
                this.inputStream = inputStream;
                this.id = id;
            }

            public Object getResult() throws Exception {
                final ResponseMessageInputStream response = new ResponseMessageInputStream(inputStream, id);
                Exception e;
                try (final Unmarshaller unmarshaller = createUnmarshaller()) {
                    unmarshaller.start(response);
                    e = unmarshaller.readObject(Exception.class);
                    if (version < 3) {
                        // discard attachment data, if any
                        int attachments = unmarshaller.readUnsignedByte();
                        for (int i = 0; i < attachments; i ++) {
                            unmarshaller.readObject();
                            unmarshaller.readObject();
                        }
                    }
                    unmarshaller.finish();
                } catch (IOException | ClassNotFoundException ex) {
                    throw new EJBException("Failed to read response", ex);
                }
                // todo: glue stack traces
                throw e;
            }

            public void discardResult() {
                safeClose(inputStream);
            }
        }
    }

    @SuppressWarnings("serial")
    static final class Future extends AtomicReference<FutureResult<EJBClientChannel>> {

        private static final IoFuture.Notifier<? super Channel, FutureResult<EJBClientChannel>> NOTIFIER = new IoFuture.HandlingNotifier<Channel, FutureResult<EJBClientChannel>>() {
            public void handleCancelled(final FutureResult<EJBClientChannel> attachment) {
                attachment.setCancelled();
            }

            public void handleFailed(final IOException exception, final FutureResult<EJBClientChannel> attachment) {
                attachment.setException(exception);
            }

            public void handleDone(final Channel channel, final FutureResult<EJBClientChannel> futureResult) {
                // now perform opening negotiation: receive server greeting
                channel.receiveMessage(new Channel.Receiver() {
                    public void handleError(final Channel channel, final IOException error) {
                        futureResult.setException(error);
                    }

                    public void handleEnd(final Channel channel) {
                        futureResult.setCancelled();
                    }

                    public void handleMessage(final Channel channel, final MessageInputStream message) {
                        // receive message body
                        try {
                            final int version = min(3, StreamUtils.readInt8(message));
                            // drain the rest of the message because it's just garbage really
                            message.skip(Long.MAX_VALUE);
                            while (message.read() != -1) {}
                            // send back result
                            try (MessageOutputStream out = channel.writeMessage()) {
                                out.write(version);
                                out.write(RIVER_BYTES);
                            }
                            futureResult.setResult(new EJBClientChannel(channel, version));
                            // done!
                        } catch (final IOException e) {
                            channel.closeAsync();
                            channel.addCloseHandler(new CloseHandler<Channel>() {
                                public void handleClose(final Channel closed, final IOException exception) {
                                    futureResult.setException(e);
                                }
                            });
                        }
                    }
                });
            }
        };

        private final Connection connection;

        Future(final Connection connection) {
            this.connection = connection;
        }

        FutureResult<EJBClientChannel> acquire() {
            FutureResult<EJBClientChannel> result = get();
            if (result == null) {
                result = new FutureResult<>();
                if (! compareAndSet(null, result)) {
                    return get();
                }
                final IoFuture<Channel> channel = connection.openChannel("ejb", OptionMap.EMPTY);
                channel.addNotifier(NOTIFIER, result);
            }
            return result;
        }
    }

    static final Attachments.Key<Future> KEY = new Attachments.Key<>(Future.class);

    class DelegatingMessageOutputStream extends MessageOutputStream implements ByteOutput {

        private final AtomicBoolean closed;
        private final MessageOutputStream delegate;

        DelegatingMessageOutputStream(final MessageOutputStream delegate) {
            this.delegate = delegate;
            closed = new AtomicBoolean();
        }

        public void flush() throws IOException {
            delegate.flush();
        }

        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                if (messageCount.getAndIncrement() == 0) {
                    synchronized (messageCount) {
                        messageCount.notifyAll();
                    }
                }
            }
            delegate.close();
        }

        public MessageOutputStream cancel() {
            delegate.cancel();
            return this;
        }

        public void write(final int b) throws IOException {
            delegate.write(b);
        }

        public void write(final byte[] b, final int off, final int len) throws IOException {
            delegate.write(b, off, len);
        }
    }

    static class ResponseMessageInputStream extends MessageInputStream implements ByteInput {
        private final InputStream delegate;
        private final int id;

        ResponseMessageInputStream(final InputStream delegate, final int id) {
            this.delegate = delegate;
            this.id = id;
        }

        public int read() throws IOException {
            return delegate.read();
        }

        public int read(final byte[] b, final int off, final int len) throws IOException {
            return delegate.read(b, off, len);
        }

        public long skip(final long n) throws IOException {
            return delegate.skip(n);
        }

        public void close() throws IOException {
            delegate.close();
        }

        public int getId() {
            return id;
        }
    }
}
