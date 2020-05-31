package com.github.prabhuprabhakaran.jsockets.tcp;

import java.beans.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class TcpClient {

    static {

    }
    private final static Logger LOGGER = Logger.getLogger(TcpClient.class.getName());

    public final static String PORT_PROP = "port";
    public final static String HOST_PROP = "host";
    private final static int PORT_DEFAULT = 1234;
    private final static String HOST_DEFAULT = "localhost";
    private int port = PORT_DEFAULT;
    private String host = HOST_DEFAULT;
    private int localport = PORT_DEFAULT + 1;
    private String localhost = HOST_DEFAULT;

    public final static String EXECUTOR_PROP = "executor";
    private final static Executor EXECUTOR_DEFAULT = Executors.newCachedThreadPool();
    private Executor executor = EXECUTOR_DEFAULT;

    public static enum State {

        STARTING, STARTED, STOPPING, STOPPED
    };
    private TcpClient.State currentState = TcpClient.State.STOPPED;
    public final static String STATE_PROP = "state";
    private Collection<TcpClient.Listener> listeners = new LinkedList<TcpClient.Listener>();
    private TcpClient.Event event = new TcpClient.Event(this);
    private PropertyChangeSupport propSupport = new PropertyChangeSupport(this);
    private TcpClient This = this;
    private ThreadFactory threadFactory;
    private Thread ioThread;
    private Socket tcpClient;
    public final static String LAST_EXCEPTION_PROP = "lastException";
    private Throwable lastException;
    BufferedWriter lSocketWriter = null;
    BufferedReader lSocketReader = null;
    private boolean AutoStart;

    public TcpClient() {
    }

    public TcpClient(int port) {
        this.port = port;
    }

    public TcpClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public TcpClient(String host, int port, String localhost, int localport) {
        this.host = host;
        this.port = port;
        this.localhost = localhost;
        this.localport = localport;
    }

    public TcpClient(int port, ThreadFactory factory) {
        this.port = port;
        this.threadFactory = factory;
    }

    public synchronized void start() {
        if (this.currentState == TcpClient.State.STOPPED) {

            assert ioThread == null : ioThread;

            Runnable run = new Runnable() {
                @Override
                public void run() {

                    runClient();

                    ioThread = null;
                    setState(TcpClient.State.STOPPED);
                    if (isAutoStart()) {
                        start();
                    }
                }
            };

            if (this.threadFactory != null) {
                this.ioThread = this.threadFactory.newThread(run);

            } else {
                this.ioThread = new Thread(run, this.getClass().getName());
            }

            setState(TcpClient.State.STARTING);
            this.ioThread.start();

        }
    }

    public synchronized void stop() {
        if (this.currentState == TcpClient.State.STARTED) {
            setState(TcpClient.State.STOPPING);
            if (this.tcpClient != null) {
                try {
                    this.lSocketWriter.close();
                    this.lSocketReader.close();
                    this.tcpClient.close();
                } catch (IOException exc) {
                    LOGGER.log(
                            Level.FATAL,
                            "An error occurred while closing the Tcp Client. "
                            + "This may have left the client in an undeDEBUGd state.",
                            exc);
                    fireExceptionNotification(exc);
                }
            }
        }
    }

    public synchronized TcpClient.State getState() {
        return this.currentState;
    }

    protected synchronized void setState(TcpClient.State state) {
        TcpClient.State oldVal = this.currentState;
        this.currentState = state;
        firePropertyChange(STATE_PROP, oldVal, state);
    }

    public boolean isAutoStart() {
        return AutoStart;
    }

    public void setAutoStart(boolean AutoStart) {
        this.AutoStart = AutoStart;
    }

    public synchronized void reset() {
        switch (this.currentState) {
            case STARTED:
                this.addPropertyChangeListener(STATE_PROP, new PropertyChangeListener() {
                    public void propertyChange(PropertyChangeEvent evt) {
                        TcpClient.State newState = (TcpClient.State) evt.getNewValue();
                        if (newState == TcpClient.State.STOPPED) {
                            TcpClient client = (TcpClient) evt.getSource();
                            client.removePropertyChangeListener(STATE_PROP, this);
                            client.start();
                        }
                    }
                });
                stop();
                break;
        }
    }

    public synchronized void send(String pData) {
        try {
            lSocketWriter.write(pData);
            lSocketWriter.newLine();
            lSocketWriter.flush();

        } catch (IOException ex) {
            Logger.getLogger(TcpClient.class.getName()).log(Level.FATAL, null, ex);
        }
    }

    protected void runClient() {
        try {
            this.tcpClient = new Socket(InetAddress.getByName(getHost()), getPort(), InetAddress.getByName(getLocalHost()), getLocalPort());
            this.tcpClient.setReuseAddress(true);

            LOGGER.info("TCP Client established on Port " + this.tcpClient.getLocalPort());
            lSocketReader = new BufferedReader(new InputStreamReader(getSocket().getInputStream()));
            lSocketWriter = new BufferedWriter(new OutputStreamWriter(getSocket().getOutputStream()));
            setState(TcpClient.State.STARTED);
            fireInitialAcknowledgeMessageSend();
            LOGGER.info("Acknowledge Message Sucessfully Send.");
            while (!this.tcpClient.isClosed()) {
                synchronized (this) {
                    if (this.currentState == TcpClient.State.STOPPING) {
                        LOGGER.info("Stopping Tcp Client by request.");
                        this.tcpClient.close();
                    }
                }

                if (!this.tcpClient.isClosed()) {

                    String readLine = lSocketReader.readLine();

                    if (LOGGER.isEnabledFor(Level.DEBUG)) {
                        LOGGER.debug("Tcp Client incoming socket: " + tcpClient);
                    }
                    if (readLine != null) {
                        if (!readLine.isEmpty()) {
                            fireTCPClientSocketReceived(readLine);
                        }
                    }
                }
            }

        } catch (Exception exc) {
            synchronized (this) {
                if (this.tcpClient != null) {
                    if (this.currentState == TcpClient.State.STOPPING) {
                        try {
                            this.tcpClient.close();

                        } catch (IOException exc2) {
                            LOGGER.log(
                                    Level.FATAL,
                                    "An error occurred while closing the Tcp Client. "
                                    + "This may have left the client in an undeDEBUGd state.",
                                    exc2);
                            fireExceptionNotification(exc2);
                        }
                    } else {

                    }
                }
                fireExceptionNotification(exc);
            }
        } finally {
            setState(TcpClient.State.STOPPING);
            if (this.tcpClient != null) {
                try {
                    this.tcpClient.close();

                } catch (IOException exc2) {
                    LOGGER.log(
                            Level.FATAL,
                            "An error occurred while closing the Tcp Client. "
                            + "This may have left the client in an undeDEBUGd state.",
                            exc2);
                    fireExceptionNotification(exc2);
                    LOGGER.info("Problem occoured while closing the Tcp Client.");
                }
            }

        }
    }

    public synchronized Socket getSocket() {
        return this.tcpClient;
    }

    public synchronized int getPort() {
        return this.port;
    }

    public synchronized String getHost() {
        return this.host;
    }

    public synchronized String getLocalHost() {
        return this.localhost;
    }

    public synchronized int getLocalPort() {
        return this.localport;
    }

    public synchronized void setPort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Cannot set port outside range 0..65535: " + port);
        }

        int oldVal = this.port;
        this.port = port;
        if (getState() == TcpClient.State.STARTED && oldVal != port) {
            reset();
        }

        firePropertyChange(PORT_PROP, oldVal, port);
    }

    public synchronized Executor getExecutor() {
        return this.executor;
    }

    public synchronized void setExecutor(Executor exec) {
        Executor oldVal = this.executor;
        this.executor = exec;

        firePropertyChange(EXECUTOR_PROP, oldVal, exec);
    }

    public synchronized void addTcpClientListener(TcpClient.Listener l) {
        listeners.add(l);
    }

    public synchronized void removeTcpClientListener(TcpClient.Listener l) {
        listeners.remove(l);
    }

    protected synchronized void fireTCPClientSocketReceived(final String Message) {

        final TcpClient.Listener[] ll = listeners.toArray(new TcpClient.Listener[listeners.size()]);

        Runnable r = new Runnable() {
            public void run() {
                for (TcpClient.Listener l : ll) {
                    try {
                        l.socketReceived(event, Message);
                    } catch (Exception exc) {
                        LOGGER.warn("TCPClient.Listener " + l + " threw an exception: " + exc.getMessage());
                        fireExceptionNotification(exc);
                    }
                }
            }
        };

        if (this.executor == null) {
            r.run();
        } else {
            try {
                this.executor.execute(r);
            } catch (Exception exc) {
                LOGGER.warn("Supplied Executor " + this.executor + " threw an exception: " + exc.getMessage());
                fireExceptionNotification(exc);
            }
        }
    }

    protected synchronized void fireInitialAcknowledgeMessageSend() {

        final TcpClient.Listener[] ll = listeners.toArray(new TcpClient.Listener[listeners.size()]);

        Runnable r = new Runnable() {
            public void run() {
                for (TcpClient.Listener l : ll) {
                    try {
                        l.socketAcknowledge(event);
                    } catch (Exception exc) {
                        LOGGER.warn("TCPClient.Listener " + l + " threw an exception: " + exc.getMessage());
                        fireExceptionNotification(exc);
                    }
                }
            }
        };

        if (this.executor == null) {
            r.run();
        } else {
            try {
                this.executor.execute(r);
            } catch (Exception exc) {
                LOGGER.warn("Supplied Executor " + this.executor + " threw an exception: " + exc.getMessage());
                fireExceptionNotification(exc);
            }
        }
    }

    public synchronized void fireProperties() {
        firePropertyChange(PORT_PROP, null, getPort());
        firePropertyChange(HOST_PROP, null, getHost());
        firePropertyChange(STATE_PROP, null, getState());
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

        public abstract void socketReceived(TcpClient.Event lEvent, String Messgae);

        public abstract void socketAcknowledge(TcpClient.Event lEvent);
    }

    public static class Event extends java.util.EventObject {

        private final static long serialVersionUID = 1;

        public Event(TcpClient src) {
            super(src);
        }

        public TcpClient getTcpClient() {
            return (TcpClient) getSource();
        }

        public TcpClient.State getState() {
            return getTcpClient().getState();
        }

        public Socket getSocket() {
            return getTcpClient().getSocket();
        }
    }
}
