package com.github.prabhuprabhakaran.jsockets.nio;

import java.beans.*;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class NioServer {

    private final static Logger LOGGER = Logger.getLogger(NioServer.class.getName());

    public final static String INPUT_BUFFER_SIZE_PROP = "bufferSize";

    public final static String OUTPUT_BUFFER_SIZE_PROP = "bufferSize";
    private final static int BUFFER_SIZE_DEFAULT = 4096;
    private int inputBufferSize = BUFFER_SIZE_DEFAULT;
    private int outputBufferSize = BUFFER_SIZE_DEFAULT;

    public static enum State {

        STARTING, STARTED, STOPPING, STOPPED
    };
    private State currentState = State.STOPPED;

    public final static String STATE_PROP = "state";

    public final static String LAST_EXCEPTION_PROP = "lastException";
    private Throwable lastException;
    private final Collection<NioServer.Listener> listeners = new LinkedList<NioServer.Listener>();
    private NioServer.Listener[] cachedListeners = null;
    private final NioServer.Event event = new NioServer.Event(this);
    private final PropertyChangeSupport propSupport = new PropertyChangeSupport(this);
    private ThreadFactory threadFactory;
    private Thread ioThread;
    private Selector selector;

    public final static String TCP_BINDINGS_PROP = "tcpBindings";

    public final static String UDP_BINDINGS_PROP = "udpBindings";

    public final static String SINGLE_TCP_PORT_PROP = "singleTcpPort";

    public final static String SINGLE_UDP_PORT_PROP = "singleUdpPort";
    private final Map<SocketAddress, SelectionKey> tcpBindings = new HashMap<SocketAddress, SelectionKey>();
    private final Map<SocketAddress, SelectionKey> udpBindings = new HashMap<SocketAddress, SelectionKey>();
    private final Map<SocketAddress, String> multicastGroups = new HashMap<SocketAddress, String>();
    private final Set<SocketAddress> pendingTcpAdds = new HashSet<SocketAddress>();
    private final Set<SocketAddress> pendingUdpAdds = new HashSet<SocketAddress>();
    private final Map<SocketAddress, SelectionKey> pendingTcpRemoves = new HashMap<SocketAddress, SelectionKey>();
    private final Map<SocketAddress, SelectionKey> pendingUdpRemoves = new HashMap<SocketAddress, SelectionKey>();
    private final Map<SelectionKey, Boolean> pendingTcpNotifyOnWritable = new HashMap<SelectionKey, Boolean>();
    private final Map<SelectionKey, ByteBuffer> leftoverForReading = new HashMap<SelectionKey, ByteBuffer>();
    private final Map<SelectionKey, ByteBuffer> leftoverForWriting = new HashMap<SelectionKey, ByteBuffer>();
    private final Set<SelectionKey> closeAfterWriting = new HashSet<SelectionKey>();

    public NioServer() {
    }

    public NioServer(ThreadFactory factory) {
        this.threadFactory = factory;
    }

    private static boolean knownState(Buffer buff, CharSequence seq) {
        boolean dotFound = false;
        boolean undFound = false;
        boolean rFound = false;
        boolean PFound = false;
        boolean LFound = false;
        boolean endFound = false;

        assert seq.charAt(0) == '[' : seq + " Expected '[' at beginning: " + seq.charAt(0);
        assert seq.charAt(seq.length() - 1) == ']' : seq + " Expected ']': " + seq.charAt(seq.length() - 1);
        for (int i = 1; i < seq.length(); i++) {
            char c = seq.charAt(i);
            switch (c) {
                case '.':
                    assert !(PFound && !LFound) : seq + " Between 'P' and 'L' should be 'r': " + c;
                    assert !undFound : seq + " Should not mix '.' and '_'";
                    dotFound = true;
                    break;

                case '_':
                    assert !(PFound && !LFound) : seq + " Between 'P' and 'L' should be 'r': " + c;
                    assert !dotFound : seq + " Should not mix '.' and '_'";
                    undFound = true;
                    break;

                case 'r':
                    assert PFound && !LFound : seq + " Should only see 'r' between 'P' and 'L'";
                    rFound = true;
                    break;

                case 'P':
                    assert !PFound : seq + " Too many P's";
                    if (undFound) {
                        assert buff.position() > 0 : seq + " Expected position to be positive: " + buff;
                    } else if (!dotFound) {
                        assert buff.position() == 0 : seq + " Expected position to be zero: " + buff;
                    }
                    PFound = true;
                    dotFound = false;
                    undFound = false;
                    break;

                case 'L':
                    assert PFound : seq + " 'L' should not come before 'P'";
                    assert !LFound : seq + " Too many L's";
                    if (rFound) {
                        assert buff.position() <= buff.limit() : seq + " Expected possible space between position and limit: " + buff;
                    } else {
                        assert buff.position() == buff.limit() : seq + " Expected position to equal limit: " + buff;
                    }
                    LFound = true;
                    rFound = false;
                    break;

                case ']':
                    assert PFound && LFound : seq + " 'P' and 'L' not found before ']'";
                    assert !endFound : seq + " Too many ]'s";
                    if (undFound) {
                        assert buff.limit() < buff.capacity() : seq + " Expected space between limit and capacity: " + buff;
                    } else if (!dotFound) {
                        assert buff.limit() == buff.capacity() : seq + " Expected limit to equal capacity: " + buff;
                    }
                    endFound = true;
                    dotFound = false;
                    undFound = false;
                    break;
                default:
                    assert false : seq + " Unexpected character: " + c;
            }
        }
        return true;
    }

    public synchronized void start() {
        if (this.currentState == State.STOPPED) {
            assert ioThread == null : ioThread;

            Runnable run = new Runnable() {
                @Override
                public void run() {
                    runServer();
                    ioThread = null;
                    setState(State.STOPPED);
                }
            };

            if (this.threadFactory != null) {
                this.ioThread = this.threadFactory.newThread(run);
            } else {
                this.ioThread = new Thread(run, this.getClass().getName());
            }

            setState(State.STARTING);
            this.ioThread.start();
        }
    }

    public synchronized void stop() {
        if (this.currentState == State.STARTED || this.currentState == State.STARTING) {
            setState(State.STOPPING);
            if (this.selector != null) {
                this.selector.wakeup();
            }
        }
    }

    public synchronized State getState() {
        return this.currentState;
    }

    protected synchronized void setState(State state) {
        State oldVal = this.currentState;
        this.currentState = state;
        firePropertyChange(STATE_PROP, oldVal, state);
    }

    public synchronized void reset() {
        switch (this.currentState) {
            case STARTED:
                this.addPropertyChangeListener(STATE_PROP, new PropertyChangeListener() {
                    public void propertyChange(PropertyChangeEvent evt) {
                        State newState = (State) evt.getNewValue();
                        if (newState == State.STOPPED) {
                            NioServer server = (NioServer) evt.getSource();
                            server.removePropertyChangeListener(STATE_PROP, this);
                            server.start();
                        }
                    }
                });
                stop();
                break;
        }
    }

    protected void runServer() {
        try {

            ByteBuffer inBuff = ByteBuffer.allocateDirect(this.inputBufferSize);
            ByteBuffer outBuff = ByteBuffer.allocateDirect(this.outputBufferSize);

            synchronized (this) {
                this.pendingTcpAdds.addAll(this.tcpBindings.keySet());
                this.pendingUdpAdds.addAll(this.udpBindings.keySet());
            }

            setState(State.STARTED);
            while (runLoopCheck()) {

                if (this.selector.select() <= 0) {
                    LOGGER.debug("selector.select() <= 0");
                    Thread.sleep(100);
                }

                synchronized (this) {
                    if (this.currentState == State.STOPPING) {
                        try {
                            Set<SelectionKey> keys = this.selector.keys();
                            for (SelectionKey key : this.selector.keys()) {
                                key.channel().close();
                                key.cancel();
                            }
                        } catch (IOException exc) {
                            fireExceptionNotification(exc);
                            LOGGER.log(
                                    Level.FATAL,
                                    "An error occurred while closing the server. "
                                    + "This try{may have left the server in an undefined state.",
                                    exc);
                        } finally {
                            continue;
                        }
                    }
                }

                if (this.inputBufferSize != inBuff.capacity()) {
                    assert this.inputBufferSize >= 0 : this.inputBufferSize;
                    inBuff = ByteBuffer.allocateDirect(this.inputBufferSize);
                }

                if (this.outputBufferSize != outBuff.capacity()) {
                    assert this.outputBufferSize >= 0 : this.outputBufferSize;
                    outBuff = ByteBuffer.allocateDirect(this.outputBufferSize);
                }

                Set<SelectionKey> keys = this.selector.selectedKeys();
                if (LOGGER.isEnabledFor(Level.DEBUG)) {
                    LOGGER.debug("Keys: " + keys);
                }

                Iterator<SelectionKey> iter = keys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();

                    try {

                        if (key.isAcceptable()) {
                            handleAccept(key, outBuff);
                        } else if (key.isReadable()) {
                            handleRead(key, inBuff, outBuff);
                        } else if (key.isWritable()) {
                            handleWrite(key, inBuff, outBuff);
                        }

                    } catch (IOException exc) {
                        LOGGER.warn("Encountered an error with a connection: " + exc.getMessage());
                        fireExceptionNotification(exc);
                        key.channel().close();
                    }
                }
            }

        } catch (Exception exc) {
            synchronized (this) {
                if (this.currentState == State.STOPPING) {
                    try {
                        this.selector.close();
                        this.selector = null;
                        LOGGER.info("Server closed normally.");
                    } catch (IOException exc2) {
                        this.lastException = exc2;
                        LOGGER.log(
                                Level.FATAL,
                                "An error occurred while closing the server. "
                                + "This may have left the server in an undefined state.",
                                exc2);
                        fireExceptionNotification(exc2);
                    }
                } else {
                    LOGGER.log(Level.WARN, "Server closed unexpectedly: " + exc.getMessage(), exc);
                }
            }
            fireExceptionNotification(exc);
        } finally {
            setState(State.STOPPING);
            if (this.selector != null) {
                try {
                    this.selector.close();
                } catch (IOException exc2) {
                    LOGGER.log(
                            Level.FATAL,
                            "An error occurred while closing the server. "
                            + "This may have left the server in an undefined state.",
                            exc2);
                    fireExceptionNotification(exc2);
                }
            }

            this.selector = null;
        }
    }

    private synchronized boolean runLoopCheck() throws IOException {

        if (this.currentState == State.STOPPING) {
            LOGGER.debug("Stopping server by request.");
            assert this.selector != null;
            this.selector.close();
        } else if (this.selector == null) {
            this.selector = Selector.open();
        }

        if (!this.selector.isOpen()) {
            return false;
        }

        for (SocketAddress addr : this.pendingTcpAdds) {
            LOGGER.debug("Binding TCP: " + addr);
            ServerSocketChannel sc = ServerSocketChannel.open();
            sc.socket().bind(addr);
            sc.configureBlocking(false);
            SelectionKey acceptKey = sc.register(
                    this.selector, SelectionKey.OP_ACCEPT);
            this.tcpBindings.put(addr, acceptKey);
        }
        this.pendingTcpAdds.clear();

        for (SocketAddress addr : this.pendingUdpAdds) {
            LOGGER.debug("Binding UDP: " + addr);
            DatagramChannel dc = DatagramChannel.open();
            dc.socket().bind(addr);
            dc.configureBlocking(false);
            SelectionKey acceptKey = dc.register(
                    this.selector, SelectionKey.OP_READ);
            this.udpBindings.put(addr, acceptKey);

            String group = this.multicastGroups.get(addr);
            if (group != null && addr instanceof InetSocketAddress) {
                int port = ((InetSocketAddress) addr).getPort();
                InetSocketAddress groupAddr = new InetSocketAddress(group, port);

                try {

                    @SuppressWarnings(value = "unchecked")
                    Constructor<? extends DatagramSocketImpl> c
                            = (Constructor<? extends DatagramSocketImpl>) Class.forName("java.net.PlainDatagramSocketImpl").getDeclaredConstructor();
                    c.setAccessible(true);
                    DatagramSocketImpl socketImpl = c.newInstance();
                    Field channelFd = Class.forName("sun.nio.ch.DatagramChannelImpl").getDeclaredField("fd");
                    channelFd.setAccessible(true);
                    Field socketFd = DatagramSocketImpl.class.getDeclaredField("fd");
                    socketFd.setAccessible(true);
                    socketFd.set(socketImpl, channelFd.get(dc));
                    try {
                        Method m = DatagramSocketImpl.class.getDeclaredMethod("joinGroup", SocketAddress.class, NetworkInterface.class);
                        m.setAccessible(true);
                        m.invoke(socketImpl, groupAddr, null);
                    } catch (Exception e) {
                        throw e;
                    } finally {

                        socketFd.set(socketImpl, null);
                    }
                } catch (Exception ex) {
                    LOGGER.warn("Experimental feature failed. Could not join multicast group: " + ex.getMessage());
                    fireExceptionNotification(ex);
                }

            }
        }
        this.pendingUdpAdds.clear();

        for (Map.Entry<SocketAddress, SelectionKey> e : this.pendingTcpRemoves.entrySet()) {
            SelectionKey key = e.getValue();
            if (key != null) {
                key.channel().close();
                key.cancel();
            }
        }
        this.pendingTcpRemoves.clear();

        for (Map.Entry<SocketAddress, SelectionKey> e : this.pendingUdpRemoves.entrySet()) {
            SelectionKey key = e.getValue();
            if (key != null) {
                key.channel().close();
                key.cancel();
            }
        }
        this.pendingUdpRemoves.clear();

        for (Map.Entry<SelectionKey, Boolean> e : this.pendingTcpNotifyOnWritable.entrySet()) {
            SelectionKey key = e.getKey();
            if (key != null && key.isValid()) {
                int ops = e.getKey().interestOps();
                if (e.getValue()) {
                    ops |= SelectionKey.OP_WRITE;
                } else {
                    ops &= ~SelectionKey.OP_WRITE;
                }
                e.getKey().interestOps(ops);
            }
        }
        this.pendingTcpNotifyOnWritable.clear();

        return true;
    }

    private void handleAccept(SelectionKey accKey, ByteBuffer outBuff) throws IOException {
        assert accKey.isAcceptable() : accKey.readyOps();
        assert selector.isOpen();

        SelectableChannel sc = accKey.channel();
        assert sc instanceof ServerSocketChannel : sc;

        ServerSocketChannel ch = (ServerSocketChannel) accKey.channel();
        SocketChannel incoming = null;
        while ((incoming = ch.accept()) != null) {
            incoming.configureBlocking(false);
            SelectionKey incomingReadKey = incoming.register(
                    this.selector,
                    SelectionKey.OP_READ | SelectionKey.OP_WRITE);

            outBuff.clear().flip();

            fireNewConnection(incomingReadKey, outBuff);

            if (outBuff.remaining() > 0) {
                ByteBuffer leftover = ByteBuffer.allocateDirect(outBuff.remaining());
                leftover.put(outBuff).flip();
                assert knownState(leftover, "[PrrL..]");
                this.leftoverForWriting.put(incomingReadKey, leftover);
                this.setNotifyOnWritable(incomingReadKey, true);
            }

            if (LOGGER.isEnabledFor(Level.DEBUG)) {
                LOGGER.debug("  " + incoming + ", key: " + incomingReadKey);
            }
        }

    }

    private void handleRead(SelectionKey key, ByteBuffer inBuff, ByteBuffer outBuff) throws IOException {

        SelectableChannel sc = key.channel();
        inBuff.clear();
        assert knownState(inBuff, "[PrrrL]");

        if (sc instanceof SocketChannel) {

            SocketChannel client = (SocketChannel) key.channel();
            ByteBuffer leftoverR = this.leftoverForReading.get(key);

            if (leftoverR != null && leftoverR.remaining() > 0) {
                assert knownState(leftoverR, "[..PrrL..]");

                if (leftoverR.remaining() <= inBuff.remaining()) {
                    inBuff.put(leftoverR);
                    assert knownState(inBuff, "[..PrrL]");

                } else {
                    while (leftoverR.hasRemaining() && inBuff.hasRemaining()) {
                        inBuff.put(leftoverR.get());
                    }
                    assert knownState(inBuff, "[..PL]");
                }
            }

            if (client.read(inBuff) == -1) {
                key.cancel();
                client.close();
                cleanupClosedConnection(key);
                fireConnectionClosed(key);
                if (LOGGER.isEnabledFor(Level.DEBUG)) {
                    LOGGER.debug("Connection closed: " + key);
                }

            } else {

                inBuff.flip();
                outBuff.clear().flip();
                assert knownState(inBuff, "[PrrL..]");
                assert knownState(outBuff, "[PL...]");

                fireTcpDataReceived(key, inBuff, outBuff);

                if (outBuff.remaining() > 0) {
                    ByteBuffer leftoverW = this.leftoverForWriting.get(key);
                    if (leftoverW == null) {
                        leftoverW = ByteBuffer.allocateDirect(outBuff.remaining());
                    } else {
                        assert knownState(outBuff, "[..PrrL..]");
                        if (outBuff.remaining()
                                > leftoverW.capacity() - leftoverW.limit()) {

                            ByteBuffer temp = ByteBuffer.allocateDirect(
                                    leftoverW.limit() - leftoverW.position()
                                    + outBuff.remaining());

                            temp.put(leftoverW).flip();
                            leftoverW = temp;
                            assert knownState(leftoverW, "[PrrL__]");

                        }
                        assert knownState(leftoverW, "[PrrL..]");
                        leftoverW.position(leftoverW.limit());
                        leftoverW.limit(leftoverW.capacity());
                        assert knownState(leftoverW, "[..PrrL]");
                    }
                    assert knownState(leftoverW, "[..PrrL]");
                    assert knownState(outBuff, "[..PrrL..]");
                    leftoverW.put(outBuff).flip();

                    assert knownState(leftoverW, "[PrrL..]");
                    assert knownState(outBuff, "[..PL..]");

                    this.leftoverForWriting.put(key, leftoverW);
                    this.setNotifyOnWritable(key, true);
                }

                if (inBuff.remaining() > 0) {
                    if (leftoverR == null
                            || inBuff.remaining() > leftoverR.capacity()) {
                        leftoverR = ByteBuffer.allocateDirect(inBuff.remaining());
                    }
                    assert knownState(inBuff, "[..PrrL..]");
                    leftoverR.clear();
                    leftoverR.put(inBuff).flip();
                    assert knownState(leftoverR, "[PrrL..]");

                }
                this.leftoverForReading.put(key, leftoverR);
            }
        } else if (sc instanceof DatagramChannel) {
            DatagramChannel dc = (DatagramChannel) sc;
            SocketAddress remote = null;
            while ((remote = dc.receive(inBuff)) != null) {
                inBuff.flip();
                outBuff.clear().flip();
                key.attach(inBuff);
                fireUdpDataReceived(key, inBuff, outBuff, remote);

                if (outBuff.hasRemaining()) {
                    dc.send(outBuff, remote);
                }
            }
        }

    }

    private void handleWrite(SelectionKey key, ByteBuffer inBuff, ByteBuffer outBuff) throws IOException {

        SocketChannel ch = (SocketChannel) key.channel();

        ByteBuffer leftover = this.leftoverForWriting.get(key);
        if (leftover != null && leftover.remaining() > 0) {
            assert knownState(leftover, "[PrrL..]");

            ch.write(leftover);

            if (!leftover.hasRemaining()) {
                leftover.clear().flip();
            }
        }

        if (leftover == null || !leftover.hasRemaining()) {
            outBuff.clear().flip();

            fireTcpReadyToWrite(key, outBuff);

            if (outBuff.hasRemaining()) {
                ch.write(outBuff);
            } else {
                this.setNotifyOnWritable(key, false);
            }

            if (outBuff.hasRemaining()) {
                if (leftover == null
                        || outBuff.remaining() > leftover.capacity()) {
                    leftover = ByteBuffer.allocateDirect(outBuff.remaining());
                }
                leftover.clear();
                assert knownState(outBuff, "[..PrrL..]");
                leftover.put(outBuff).flip();
                assert knownState(leftover, "[PrrL..]");
            }
            this.leftoverForWriting.put(key, leftover);
        }

        if (this.closeAfterWriting.contains(key)
                && (leftover == null || !leftover.hasRemaining())) {
            ch.close();
        }

    }

    private void cleanupClosedConnection(SelectionKey key) {

        this.pendingTcpNotifyOnWritable.remove(key);
        this.leftoverForReading.remove(key);
        this.leftoverForWriting.remove(key);
        this.closeAfterWriting.remove(key);
        this.pendingTcpNotifyOnWritable.remove(key);

    }

    public synchronized void setNotifyOnWritable(SelectionKey key, boolean notify) {

        if (key == null) {
            throw new NullPointerException("Cannot set notifications for null key.");
        }
        this.pendingTcpNotifyOnWritable.put(key, notify);
        if (this.selector != null) {
            this.selector.wakeup();
        }
    }

    public synchronized void closeAfterWriting(SelectionKey key) {
        this.closeAfterWriting.add(key);
    }

    public synchronized int getInputBufferSize() {
        return this.inputBufferSize;
    }

    public synchronized void setInputBufferSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("New buffer size must be positive: " + size);
        }

        int oldVal = this.inputBufferSize;
        this.inputBufferSize = size;
        if (this.selector != null) {
            this.selector.wakeup();
        }

        firePropertyChange(INPUT_BUFFER_SIZE_PROP, oldVal, size);
    }

    public synchronized int getOutputBufferSize() {
        return this.outputBufferSize;
    }

    public synchronized void setOutputBufferSize(int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("New buffer size must be positive: " + size);
        }

        int oldVal = this.outputBufferSize;
        this.outputBufferSize = size;
        if (this.selector != null) {
            this.selector.wakeup();
        }

        firePropertyChange(OUTPUT_BUFFER_SIZE_PROP, oldVal, size);
    }

    public synchronized void send(SocketChannel socket, String data) {
        if (!data.endsWith("\n")) {
            data = data.concat("\n");
        }
        SelectionKey keyFor = socket.keyFor(selector);
        ByteBuffer wrap = ByteBuffer.wrap(data.getBytes());
        leftoverForWriting.put(keyFor, wrap);
        int interestOps = keyFor.interestOps();
        interestOps |= SelectionKey.OP_WRITE;
        keyFor.interestOps(interestOps);
        this.selector.wakeup();
    }

    public synchronized void send(SelectionKey keyFor, String data) {
        if (!data.endsWith("\n")) {
            data = data.concat("\n");
        }
        ByteBuffer wrap = ByteBuffer.wrap(data.getBytes());
        leftoverForWriting.put(keyFor, wrap);
        int interestOps = keyFor.interestOps();
        interestOps |= SelectionKey.OP_WRITE;
        keyFor.interestOps(interestOps);
        this.selector.wakeup();
    }

    public synchronized void send(SocketChannel socket, ByteBuffer pbBuffer) {

        SelectionKey keyFor = socket.keyFor(selector);

        leftoverForWriting.put(keyFor, pbBuffer);
        int interestOps = keyFor.interestOps();
        interestOps |= SelectionKey.OP_WRITE;
        keyFor.interestOps(interestOps);
        this.selector.wakeup();
    }

    public synchronized void send(SelectionKey keyFor, ByteBuffer pbBuffer) {

        leftoverForWriting.put(keyFor, pbBuffer);
        int interestOps = keyFor.interestOps();
        interestOps |= SelectionKey.OP_WRITE;
        keyFor.interestOps(interestOps);
        this.selector.wakeup();
    }

    public synchronized NioServer addTcpBinding(SocketAddress addr) {
        Set<SocketAddress> oldVal = this.getTcpBindings();
        this.tcpBindings.put(addr, null);
        Set<SocketAddress> newVal = this.getTcpBindings();
        this.pendingTcpAdds.add(addr);
        this.pendingTcpRemoves.remove(addr);

        if (this.selector != null) {
            this.selector.wakeup();
        }
        firePropertyChange(TCP_BINDINGS_PROP, oldVal, newVal);
        return this;
    }

    public synchronized NioServer removeTcpBinding(SocketAddress addr) {
        Set<SocketAddress> oldVal = this.getTcpBindings();
        this.pendingTcpRemoves.put(addr, this.tcpBindings.get(addr));
        this.tcpBindings.remove(addr);
        this.pendingTcpAdds.remove(addr);
        Set<SocketAddress> newVal = this.getTcpBindings();

        if (this.selector != null) {
            this.selector.wakeup();
        }
        firePropertyChange(TCP_BINDINGS_PROP, oldVal, newVal);
        return this;
    }

    public synchronized Set<SocketAddress> getTcpBindings() {
        Set<SocketAddress> bindings = new HashSet<SocketAddress>();
        bindings.addAll(this.tcpBindings.keySet());
        return bindings;
    }

    public synchronized NioServer setTcpBindings(Set<SocketAddress> newSet) {
        Set<SocketAddress> toAdd = new HashSet<SocketAddress>();
        Set<SocketAddress> toRemove = new HashSet<SocketAddress>();

        toRemove.addAll(getTcpBindings());
        for (SocketAddress addr : newSet) {
            if (toRemove.contains(addr)) {
                toRemove.remove(addr);
            } else {
                toAdd.add(addr);
            }
        }

        for (SocketAddress addr : toRemove) {
            removeTcpBinding(addr);
        }

        for (SocketAddress addr : toAdd) {
            addTcpBinding(addr);
        }

        return this;
    }

    public synchronized NioServer clearTcpBindings() {
        for (SocketAddress addr : getTcpBindings()) {
            removeTcpBinding(addr);
        }
        return this;
    }

    public synchronized NioServer addUdpBinding(SocketAddress addr) {
        return addUdpBinding(addr, null);
    }

    public synchronized NioServer addUdpBinding(SocketAddress addr, String group) {
        Map<SocketAddress, String> oldVal = this.getUdpBindings();
        this.udpBindings.put(addr, null);
        this.pendingUdpAdds.add(addr);
        this.pendingUdpRemoves.remove(addr);
        if (group != null) {
            this.multicastGroups.put(addr, group);
        }
        Map<SocketAddress, String> newVal = this.getUdpBindings();
        if (this.selector != null) {
            this.selector.wakeup();
        }
        firePropertyChange(UDP_BINDINGS_PROP, oldVal, newVal);
        return this;
    }

    public synchronized NioServer removeUdpBinding(SocketAddress addr) {
        Map<SocketAddress, String> oldVal = this.getUdpBindings();
        this.pendingUdpRemoves.put(addr, this.udpBindings.get(addr));
        this.udpBindings.remove(addr);
        this.multicastGroups.remove(addr);
        this.pendingUdpAdds.remove(addr);
        Map<SocketAddress, String> newVal = this.getUdpBindings();

        if (this.selector != null) {
            this.selector.wakeup();
        }
        firePropertyChange(UDP_BINDINGS_PROP, oldVal, newVal);
        return this;
    }

    public synchronized Map<SocketAddress, String> getUdpBindings() {
        Map<SocketAddress, String> bindings = new HashMap<SocketAddress, String>();
        for (SocketAddress addr : this.udpBindings.keySet()) {
            bindings.put(addr, this.multicastGroups.get(addr));
        }
        return bindings;
    }

    public synchronized NioServer setUdpBindings(Map<SocketAddress, String> newMap) {
        Map<SocketAddress, String> toAdd = new HashMap<SocketAddress, String>();
        Map<SocketAddress, String> toRemove = new HashMap<SocketAddress, String>();

        toRemove.putAll(getUdpBindings());
        for (Map.Entry<SocketAddress, String> e : newMap.entrySet()) {
            SocketAddress addr = e.getKey();
            String group = e.getValue();
            if (toRemove.containsKey(addr)) {
                toRemove.remove(addr);
            } else {
                toAdd.put(addr, group);
            }
        }

        for (Map.Entry<SocketAddress, String> e : toRemove.entrySet()) {
            removeUdpBinding(e.getKey());
        }

        for (Map.Entry<SocketAddress, String> e : toAdd.entrySet()) {
            addUdpBinding(e.getKey(), e.getValue());
        }

        return this;
    }

    public synchronized NioServer clearUdpBindings() {
        for (SocketAddress addr : getUdpBindings().keySet()) {
            removeUdpBinding(addr);
        }
        return this;
    }

    public synchronized NioServer setSingleTcpPort(int port) {
        int oldVal = getSingleTcpPort();
        if (oldVal == port) {
            return this;
        }
        clearTcpBindings();
        addTcpBinding(new InetSocketAddress(port));
        int newVal = port;
        firePropertyChange(SINGLE_TCP_PORT_PROP, oldVal, newVal);
        return this;
    }

    public synchronized NioServer setSingleUdpPort(int port) {
        return setSingleUdpPort(port, null);
    }

    public synchronized NioServer setSingleUdpPort(int port, String group) {
        int oldVal = getSingleUdpPort();
        if (oldVal == port) {
            return this;
        }
        clearUdpBindings();
        addUdpBinding(new InetSocketAddress(port), group);
        int newVal = port;
        firePropertyChange(SINGLE_UDP_PORT_PROP, oldVal, newVal);
        return this;
    }

    public synchronized int getSingleTcpPort() {
        int port = -1;
        Set<SocketAddress> bindings = getTcpBindings();
        if (bindings.size() == 1) {
            SocketAddress sa = bindings.iterator().next();
            if (sa instanceof InetSocketAddress) {
                port = ((InetSocketAddress) sa).getPort();
            }
        }
        return port;
    }

    public synchronized int getSingleUdpPort() {
        int port = -1;
        Map<SocketAddress, String> bindings = getUdpBindings();
        if (bindings.size() == 1) {
            SocketAddress sa = bindings.keySet().iterator().next();
            if (sa instanceof InetSocketAddress) {
                port = ((InetSocketAddress) sa).getPort();
            }
        }
        return port;
    }

    public synchronized void addNioServerListener(NioServer.Listener l) {
        listeners.add(l);
        cachedListeners = null;
    }

    public synchronized void removeNioServerListener(NioServer.Listener l) {
        listeners.remove(l);
        cachedListeners = null;
    }

    protected synchronized void fireTcpDataReceived(SelectionKey key, ByteBuffer inBuff, ByteBuffer outBuff) {

        if (cachedListeners == null) {
            cachedListeners = listeners.toArray(new NioServer.Listener[listeners.size()]);
        }
        this.event.reset(key, inBuff, outBuff, null);

        for (NioServer.Listener l : cachedListeners) {
            try {
                l.tcpDataReceived(event);
            } catch (Exception exc) {
                LOGGER.warn("NioServer.Listener " + l + " threw an exception: " + exc.getMessage());
                fireExceptionNotification(exc);
            }
        }
    }

    protected synchronized void fireTcpReadyToWrite(SelectionKey key, ByteBuffer outBuff) {
        assert knownState(outBuff, "[PL..]");

        if (cachedListeners == null) {
            cachedListeners = listeners.toArray(new NioServer.Listener[listeners.size()]);
        }
        this.event.reset(key, null, outBuff, null);

        for (NioServer.Listener l : cachedListeners) {
            try {
                l.tcpReadyToWrite(event);
            } catch (Exception exc) {
                exc.printStackTrace();
                LOGGER.warn("NioServer.Listener " + l + " threw an exception: " + exc.getMessage());
                fireExceptionNotification(exc);
            }
        }
    }

    protected synchronized void fireUdpDataReceived(SelectionKey key, ByteBuffer inBuff, ByteBuffer outBuff, SocketAddress remote) {

        if (cachedListeners == null) {
            cachedListeners = listeners.toArray(new NioServer.Listener[listeners.size()]);
        }
        this.event.reset(key, inBuff, outBuff, remote);

        for (NioServer.Listener l : cachedListeners) {
            try {
                l.udpDataReceived(event);
            } catch (Exception exc) {
                LOGGER.warn("NioServer.Listener " + l + " threw an exception: " + exc.getMessage());
                fireExceptionNotification(exc);
            }
        }
    }

    protected synchronized void fireConnectionClosed(SelectionKey key) {

        if (cachedListeners == null) {
            cachedListeners = listeners.toArray(new NioServer.Listener[listeners.size()]);
        }
        this.event.reset(key, null, null, null);

        for (NioServer.Listener l : cachedListeners) {
            try {
                l.connectionClosed(event);
            } catch (Exception exc) {
                LOGGER.warn("NioServer.Listener " + l + " threw an exception: " + exc.getMessage());
                fireExceptionNotification(exc);
            }
        }
    }

    protected synchronized void fireNewConnection(SelectionKey key, ByteBuffer outBuff) {

        if (cachedListeners == null) {
            cachedListeners = listeners.toArray(new NioServer.Listener[listeners.size()]);
        }
        this.event.reset(key, null, outBuff, null);

        for (NioServer.Listener l : cachedListeners) {
            try {
                l.newConnectionReceived(event);
            } catch (Exception exc) {
                LOGGER.warn("NioServer.Listener " + l + " threw an exception: " + exc.getMessage());
                fireExceptionNotification(exc);
            }
        }
    }

    public synchronized void fireProperties() {
        firePropertyChange(STATE_PROP, null, getState());
        firePropertyChange(INPUT_BUFFER_SIZE_PROP, null, getInputBufferSize());
        firePropertyChange(OUTPUT_BUFFER_SIZE_PROP, null, getOutputBufferSize());
    }

    protected synchronized void firePropertyChange(final String prop, final Object oldVal, final Object newVal) {
        try {
            propSupport.firePropertyChange(prop, oldVal, newVal);
        } catch (Exception exc) {
            LOGGER.log(Level.WARN,
                    "A property change listener threw an exception: " + exc.getMessage(), exc);
            fireExceptionNotification(exc);
        }
    }

    public synchronized void addPropertyChangeListener(PropertyChangeListener listener) {
        propSupport.addPropertyChangeListener(listener);
    }

    public synchronized void addPropertyChangeListener(String property, PropertyChangeListener listener) {
        propSupport.addPropertyChangeListener(property, listener);
    }

    public synchronized void removePropertyChangeListener(PropertyChangeListener listener) {
        propSupport.removePropertyChangeListener(listener);
    }

    public synchronized void removePropertyChangeListener(String property, PropertyChangeListener listener) {
        propSupport.removePropertyChangeListener(property, listener);
    }

    public synchronized Throwable getLastException() {
        return this.lastException;
    }

    protected void fireExceptionNotification(Throwable t) {
        Throwable oldVal = this.lastException;
        this.lastException = t;
        firePropertyChange(LAST_EXCEPTION_PROP, oldVal, t);
    }

    public static void setLoggingLevel(Level level) {
        LOGGER.setLevel(level);
    }

    public static Level getLoggingLevel() {
        return LOGGER.getLevel();
    }

    public static interface Listener extends java.util.EventListener {

        public abstract void newConnectionReceived(NioServer.Event evt);

        public abstract void tcpDataReceived(NioServer.Event evt);

        public abstract void udpDataReceived(NioServer.Event evt);

        public abstract void tcpReadyToWrite(NioServer.Event evt);

        public abstract void connectionClosed(NioServer.Event evt);
    }

    public static class Adapter implements NioServer.Listener {

        public void tcpDataReceived(NioServer.Event evt) {
        }

        public void udpDataReceived(NioServer.Event evt) {
        }

        public void newConnectionReceived(NioServer.Event evt) {
        }

        public void connectionClosed(NioServer.Event evt) {
        }

        public void tcpReadyToWrite(NioServer.Event evt) {
        }
    }

    public static class Event extends java.util.EventObject {

        private final static long serialVersionUID = 1;

        private SelectionKey key;

        private ByteBuffer inBuff;

        private ByteBuffer outBuff;

        private SocketAddress remoteUdp;

        public Event(NioServer src) {
            super(src);
        }

        public NioServer getNioServer() {
            return (NioServer) getSource();
        }

        public NioServer.State getState() {
            return getNioServer().getState();
        }

        public SelectionKey getKey() {
            return this.key;
        }

        protected void reset(SelectionKey key, ByteBuffer inBuff, ByteBuffer outBuff, SocketAddress remoteUdp) {
            this.key = key;
            this.inBuff = inBuff;
            this.outBuff = outBuff;
            this.remoteUdp = remoteUdp;
        }

        public ByteBuffer getInputBuffer() {
            return this.inBuff;
        }

        public ByteBuffer getOutputBuffer() {
            return this.outBuff;
        }

        public SocketAddress getLocalSocketAddress() {
            SocketAddress addr = null;
            if (this.key != null) {
                SelectableChannel sc = this.key.channel();
                if (sc instanceof SocketChannel) {
                    addr = ((SocketChannel) sc).socket().getLocalSocketAddress();
                } else if (sc instanceof DatagramChannel) {
                    addr = ((DatagramChannel) sc).socket().getLocalSocketAddress();
                }
            }
            return addr;
        }

        public SocketAddress getRemoteSocketAddress() {
            SocketAddress addr = null;
            if (this.key != null) {
                SelectableChannel sc = this.key.channel();
                if (sc instanceof SocketChannel) {
                    addr = ((SocketChannel) sc).socket().getRemoteSocketAddress();
                } else if (sc instanceof DatagramChannel) {
                    addr = this.remoteUdp;
                }
            }
            return addr;
        }

        public boolean isTcp() {
            return this.key == null ? false : this.key.channel() instanceof SocketChannel;
        }

        public boolean isUdp() {
            return this.key == null ? false : this.key.channel() instanceof DatagramChannel;
        }

        public void setNotifyOnTcpWritable(boolean notify) {
            this.getNioServer().setNotifyOnWritable(this.key, notify);
        }

        public void closeAfterWriting() {
            this.getNioServer().closeAfterWriting(this.key);
        }

        public void close() throws IOException {
            this.key.channel().close();
        }
    }
}
