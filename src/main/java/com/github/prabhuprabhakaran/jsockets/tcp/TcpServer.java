package com.github.prabhuprabhakaran.jsockets.tcp;

import java.beans.*;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

public class TcpServer {

    private final static Logger LOGGER = Logger.getLogger(TcpServer.class.getName());

    public final static String PORT_PROP = "port";
    private final static int PORT_DEFAULT = 1234;
    private int port = PORT_DEFAULT;

    public final static String EXECUTOR_PROP = "executor";
    private final static Executor EXECUTOR_DEFAULT = Executors.newCachedThreadPool();
    private Executor executor = EXECUTOR_DEFAULT;

    static {

    }

    public static enum State {

        STARTING, STARTED, STOPPING, STOPPED
    };
    private State currentState = State.STOPPED;
    public final static String STATE_PROP = "state";
    private Collection<Listener> listeners = new LinkedList<Listener>();
    private Event event = new Event(this);
    private PropertyChangeSupport propSupport = new PropertyChangeSupport(this);
    private TcpServer This = this;
    private ThreadFactory threadFactory;
    private Thread ioThread;
    private ServerSocket tcpServer;
    private Socket socket;
    public final static String LAST_EXCEPTION_PROP = "lastException";
    private Throwable lastException;
    BufferedWriter lSocketWriter = null;
    BufferedReader lSocketReader = null;
    private boolean AutoStart;

    public TcpServer() {
    }

    public TcpServer(int port) {
        this.port = port;
    }

    public TcpServer(int port, ThreadFactory factory) {
        this.port = port;
        this.threadFactory = factory;
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
            setState(State.STARTING);
            this.ioThread.start();
        }
    }

    public synchronized void stop() {
        if (this.currentState == State.STARTED) {
            setState(State.STOPPING);
            if (this.tcpServer != null) {
                try {
                    this.lSocketWriter.close();
                    this.lSocketReader.close();
                    this.tcpServer.close();
                } catch (IOException exc) {
                    LOGGER.log(
                            Level.FATAL,
                            "An error occurred while closing the TCP server. "
                            + "This may have left the server in an undefined state.",
                            exc);
                    fireExceptionNotification(exc);
                }
            }
        }
    }

    public synchronized State getState() {
        return this.currentState;
    }

    public boolean isAutoStart() {
        return AutoStart;
    }

    public void setAutoStart(boolean AutoStart) {
        this.AutoStart = AutoStart;
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
                            TcpServer server = (TcpServer) evt.getSource();
                            server.removePropertyChangeListener(STATE_PROP, this);
                            server.start();
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
            System.out.println("Message Sent From Server " + getSocket().getLocalSocketAddress() + " To " + getSocket().getRemoteSocketAddress() + " : " + pData);
        } catch (IOException ex) {
            Logger.getLogger(TcpServer.class.getName()).log(Level.FATAL, null, ex);
        }
    }

    public synchronized void send(byte[] pData) {
        try {
            lSocketWriter.write(new String(pData, Charset.defaultCharset()));
            lSocketWriter.newLine();
            lSocketWriter.flush();
            System.out.println("Message Sent From Server " + getSocket().getLocalSocketAddress() + " To " + getSocket().getRemoteSocketAddress() + " : " + pData);
        } catch (IOException ex) {
            Logger.getLogger(TcpServer.class.getName()).log(Level.FATAL, null, ex);
        }
    }

    protected void runServer() {
        try {
            this.tcpServer = new ServerSocket(getPort());
            setState(State.STARTED);
            LOGGER.info("TCP Server established on port " + getPort());

            while (!this.tcpServer.isClosed()) {
                synchronized (this) {
                    if (this.currentState == State.STOPPING) {
                        LOGGER.info("Stopping TCP Server by request.");
                        this.tcpServer.close();
                    }
                }

                if (!this.tcpServer.isClosed()) {
                    String readLine = null;
                    if (this.socket == null) {

                        LOGGER.info("TCP Server Waiting for Accept");
                        this.socket = this.tcpServer.accept();
                        lSocketReader = new BufferedReader(new InputStreamReader(getSocket().getInputStream()));
                        lSocketWriter = new BufferedWriter(new OutputStreamWriter(getSocket().getOutputStream()));
                        LOGGER.info("TCP Server is connected at " + this.socket.getLocalSocketAddress() + ", " + this.socket.getRemoteSocketAddress());

                    } else if (this.socket.isClosed()) {

                        LOGGER.info("TCP Server Waiting for Accept");
                        this.socket = this.tcpServer.accept();
                        lSocketReader = new BufferedReader(new InputStreamReader(getSocket().getInputStream()));
                        lSocketWriter = new BufferedWriter(new OutputStreamWriter(getSocket().getOutputStream()));
                        LOGGER.info("TCP Server is connected with " + this.socket.getRemoteSocketAddress());

                    } else if (!this.socket.isClosed()) {
                        readLine = lSocketReader.readLine();

                    }
                    if (LOGGER.isEnabledFor(Level.DEBUG)) {
                        LOGGER.debug("TCP Server incoming socket: " + socket);
                    }

                    if (readLine != null) {
                        if (!readLine.isEmpty()) {
                            fireTcpServerSocketReceived(readLine);
                        }
                    }

                }
            }

        } catch (Exception exc) {
            synchronized (this) {
                if (this.currentState == State.STOPPING) {
                    try {
                        this.tcpServer.close();
                        this.socket.close();
                        LOGGER.info("TCP Server closed normally.");
                    } catch (IOException exc2) {
                        LOGGER.log(
                                Level.FATAL,
                                "An error occurred while closing the TCP server. "
                                + "This may have left the server in an undefined state.",
                                exc2);
                        fireExceptionNotification(exc2);
                    }
                } else {
                    LOGGER.log(Level.WARN, "Server closed unexpectedly: " + exc.getMessage(), exc);
                    fireExceptionNotification(exc);
                }
            }
        } finally {
            setState(State.STOPPING);
            if (this.tcpServer != null) {
                try {
                    this.tcpServer.close();
                    LOGGER.info("TCP Server closed normally.");
                } catch (IOException exc2) {
                    LOGGER.log(
                            Level.FATAL,
                            "An error occurred while closing the TCP server. "
                            + "This may have left the server in an undefined state.",
                            exc2);
                    fireExceptionNotification(exc2);
                }
            }
            this.tcpServer = null;
            this.socket = null;
        }
    }

    public synchronized Socket getSocket() {
        return this.socket;
    }

    public synchronized String getMessageAsString() {
        String lReturn = "";
        if (!getSocket().isClosed()) {
            try {
                InputStream in = getSocket().getInputStream();

                byte[] buff = new byte[getSocket().getReceiveBufferSize()];
                int num = -1;
                while ((num = in.read(buff)) >= 0) {
                    lReturn = new String(buff, 0, num);

                }
            } catch (IOException exc) {
                exc.printStackTrace();
            }
        }
        return lReturn;
    }

    public synchronized byte[] getMessageAsByte() {
        byte[] lReturn = null;
        if (!getSocket().isClosed()) {
            try {
                InputStream in = getSocket().getInputStream();

                byte[] buff = new byte[getSocket().getReceiveBufferSize()];
                int num = -1;
                while ((num = in.read(buff)) >= 0) {

                    lReturn = Arrays.copyOf(buff, num);

                }
            } catch (IOException exc) {
                exc.printStackTrace();
            }
        }
        return lReturn;
    }

    public synchronized int getPort() {
        return this.port;
    }

    public synchronized void setPort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Cannot set port outside range 0..65535: " + port);
        }

        int oldVal = this.port;
        this.port = port;
        if (getState() == State.STARTED && oldVal != port) {
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

    public synchronized void addTcpServerListener(TcpServer.Listener l) {
        listeners.add(l);
    }

    public synchronized void removeTcpServerListener(TcpServer.Listener l) {
        listeners.remove(l);
    }

    protected synchronized void fireTcpServerSocketReceived(final String Message) {

        final TcpServer.Listener[] ll = listeners.toArray(new TcpServer.Listener[listeners.size()]);

        Runnable r = new Runnable() {
            public void run() {
                for (Listener l : ll) {
                    try {
                        l.socketReceived(event, Message);
                    } catch (Exception exc) {
                        LOGGER.warn("TcpServer.Listener " + l + " threw an exception: " + exc.getMessage());
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

        public abstract void socketReceived(Event evt, String Message);
    }

    public static class Event extends java.util.EventObject {

        private final static long serialVersionUID = 1;

        public Event(TcpServer src) {
            super(src);
        }

        public TcpServer getTcpServer() {
            return (TcpServer) getSource();
        }

        public TcpServer.State getState() {
            return getTcpServer().getState();
        }

        public Socket getSocket() {
            return getTcpServer().getSocket();
        }
    }
}
