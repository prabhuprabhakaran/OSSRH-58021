package com.github.prabhuprabhakaran.jsockets;

import java.beans.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

/**
 * <p>A robust class for establishing a TCP Client and manipulating its
 * listening port. The {@link Event}s and property change events make it an
 * appropriate tool in a threaded, GUI application. It is almost identical in
 * design to the UdpServer class which accompanies this one at <a
 * href="http://iHarder.net">iHarder.net</a>.</p>
 *
 * <p>To start a TCP Client, create a new TcpClient and call start():</p>
 *
 * <pre> TCPClient client = new TCPClient();
 * client.start();</pre>
 *
 * <p>Of course it won't be much help unless you register as a listener so
 * you'll know when a <tt>java.net.Socket</tt> has come in:</p>
 *
 * <pre> client.addTcpClientListener( new TCPClient.Listener(){
 *     public void socketReceived( TCPClient.Event evt ){
 *         Socket socket = evt.getSocket();
 *         ...
 *     }   // end socket received
 * });</pre>
 *
 * <p>The client runs on one thread, and all events may be fired on that thread
 * if desired by setting the executor to null
 * <code>client.setExecutor(null)</code>. By default a cached thread pool is
 * used (
 * <code>Executors.newCachedThreadPool()</code>) so that when you handle a
 * socketReceived event, you are already working in a dedicated thread.</p>
 *
 * <p>The public methods are all synchronized on <tt>this</tt>, and great care
 * has been taken to avoid deadlocks and race conditions. That being said, there
 * may still be bugs (please contact the author if you find any), and you
 * certainly still have the power to introduce these problems yourself.</p>
 *
 * <p>It's often handy to have your own class extend this one rather than making
 * an instance field to hold a TCPClient where you'd have to pass along all the
 * setPort(...) methods and so forth.</p>
 *
 * <p>The supporting {@link Event} and {@link Listener} classes are static inner
 * classes in this file so that you have only one file to copy to your project.
 * You're welcome.</p>
 *
 * <p>Since the TCPClient.java, UdpServer.java, and NioServer.java are so
 * similar, and since lots of copying and pasting was going on among them, you
 * may find some comments that refer to TCP instead of UDP or vice versa. Please
 * feel free to let me know, so I can correct that.</p>
 *
 * <p>This code is released into the Public Domain. Since this is Public Domain,
 * you don't need to worry about licensing, and you can simply copy this
 * TCPClient.java file to your own package and use it as you like. Enjoy. Please
 * consider leaving the following statement here in this code:</p>
 *
 * <p><em>This <tt>TCPClient</tt> class was copied to this project from its
 * source as found at <a href="http://iharder.net"
 * target="_blank">iHarder.net</a>.</em></p>
 *
 * @author Robert Harder
 * @author rharder@users.sourceforge.net
 * @version 0.1
 * @see TCPClient
 * @see Event
 * @see Listener
 */
public class TcpClient {

    static {
        //PropertyConfigurator.configure("Properties/log4j.properties");
    }
    private final static Logger LOGGER = Logger.getLogger(TcpClient.class.getName());
    /**
     * The port property <tt>port</tt> used with the property change listeners
     * and the preferences, if a preferences object is given.
     */
    public final static String PORT_PROP = "port";
    public final static String HOST_PROP = "host";
    private final static int PORT_DEFAULT = 1234;
    private final static String HOST_DEFAULT = "localhost";
    private int port = PORT_DEFAULT;
    private String host = HOST_DEFAULT;
    private int localport = PORT_DEFAULT + 1;
    private String localhost = HOST_DEFAULT;
    /**
     * The Executor property <tt>executor</tt> used with the property change
     * listeners and the preferences, if a preferences object is given.
     */
    public final static String EXECUTOR_PROP = "executor";
    private final static Executor EXECUTOR_DEFAULT = Executors.newCachedThreadPool();
    private Executor executor = EXECUTOR_DEFAULT;

    /**
     * <p>One of four possible states for the client to be in:</p>
     *
     * <ul> <li>STARTING</li> <li>STARTED</li> <li>STOPPING</li>
     * <li>STOPPED</li> </ul>
     */
    public static enum State {

        STARTING, STARTED, STOPPING, STOPPED
    };
    private TcpClient.State currentState = TcpClient.State.STOPPED;
    public final static String STATE_PROP = "state";
    private Collection<TcpClient.Listener> listeners = new LinkedList<TcpClient.Listener>();                // Event listeners
    private TcpClient.Event event = new TcpClient.Event(this);                                              // Shared event
    private PropertyChangeSupport propSupport = new PropertyChangeSupport(this);        // Properties
    private TcpClient This = this;                                                      // To aid in synchronizing
    private ThreadFactory threadFactory;                                                // Optional thread factory
    private Thread ioThread;                                                            // Performs IO
    private Socket tcpClient;                                                     // The client
    public final static String LAST_EXCEPTION_PROP = "lastException";
    private Throwable lastException;
    BufferedWriter lSocketWriter = null;
    BufferedReader lSocketReader = null;
    private boolean AutoStart;

    /* ********  C O N S T R U C T O R S  ******** */
    /**
     * Constructs a new TCPClient that will listen on the default port 1234 (but
     * not until {@link #start} is called). The I/O thread will not be in daemon
     * mode.
     */
    public TcpClient() {
    }

    /**
     * Constructs a new TCPClient that will listen on the given port (but not
     * until {@link #start} is called). The I/O thread will not be in daemon
     * mode.
     *
     * @param port the port on which to listen
     */
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

    /**
     * Constructs a new TCPClient that will listen on the given port (but not
     * until {@link #start} is called). The provided ThreadFactory will be used
     * when starting and running the client.
     *
     * @param port the port to listen to
     * @param factory for creating the io thread
     */
    public TcpClient(int port, ThreadFactory factory) {
        this.port = port;
        this.threadFactory = factory;
    }

    /* ********  R U N N I N G  ******** */
    /**
     * Attempts to start the client listening and returns immediately. Listen
     * for start events to know if the client was successfully started.
     *
     * @see Listener
     */
    public synchronized void start() {
        if (this.currentState == TcpClient.State.STOPPED) {

            // Shouldn't have a thread
            assert ioThread == null : ioThread;             // Shouldn't have a thread

            Runnable run = new Runnable() {
                @Override
                public void run() {

                    runClient();                            // This runs for a long time

                    ioThread = null;
                    setState(TcpClient.State.STOPPED);              // Clear thread
                    if (isAutoStart()) {
                        start();
                    }
                }   // end run
            };  // end runnable

            if (this.threadFactory != null) {               // User-specified threads
                this.ioThread = this.threadFactory.newThread(run);

            } else {                                        // Our own threads
                this.ioThread = new Thread(run, this.getClass().getName());   // Named
            }

            setState(TcpClient.State.STARTING);                     // Update state
            this.ioThread.start();

            // Start thread
            // end if: currently stopped

        }   // end if: currently stopped
    }   // end start

    /**
     * Attempts to stop the client, if the client is in the STARTED state, and
     * returns immediately. Be sure to listen for stop events to know if the
     * client was successfully stopped.
     *
     * @see Listener
     */
    public synchronized void stop() {
        if (this.currentState == TcpClient.State.STARTED) {   // Only if already STARTED
            setState(TcpClient.State.STOPPING);             // Mark as STOPPING
            if (this.tcpClient != null) {           // 
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
            }   // end if: not null
        }   // end if: already STARTED
    }   // end stop

    /**
     * Returns the current state of the client, one of STOPPED, STARTING, or
     * STARTED.
     *
     * @return state of the client
     */
    public synchronized TcpClient.State getState() {
        return this.currentState;
    }

    /**
     * Sets the state and fires an event. This method does not change what the
     * client is doing, only what is reflected by the currentState variable.
     *
     * @param state the new client state
     */
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

    /**
     * Fires an event declaring the current state of the client. This may
     * encourage lazy programming on your part, but it's handy to set yourself
     * up as a listener and then fire an event in order to initialize this or
     * that.
     */
    //public synchronized void fireState(){
    //    fireTCPClientStateChanged();
    //}
    /**
     * Resets the client, if it is running, otherwise does nothing. This is
     * accomplished by registering as a listener, stopping the client, detecting
     * the stop, unregistering, and starting the client again. It's a useful
     * design pattern, and you may want to look at the source code for this
     * method to check it out.
     */
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
                        }   // end if: stopped
                    }   // end prop change
                });
                stop();
                break;
        }   // end switch
    }

    public synchronized void send(String pData) {
        try {
            lSocketWriter.write(pData);
            lSocketWriter.newLine();
            lSocketWriter.flush();
            //System.out.println("Message Sent From " + getSocket().getLocalSocketAddress() + " To " + getSocket().getRemoteSocketAddress() + " As " + pData);
        } catch (IOException ex) {
            Logger.getLogger(TcpClient.class.getName()).log(Level.FATAL, null, ex);
        }
    }

    /**
     * This method starts up and listens indefinitely for TCP packets. On
     * entering this method, the state is assumed to be STARTING. Upon exiting
     * this method, the state will be STOPPING.
     */
    protected void runClient() {
        try {
            this.tcpClient = new Socket(InetAddress.getByName(getHost()), getPort(), InetAddress.getByName(getLocalHost()), getLocalPort());
            this.tcpClient.setReuseAddress(true);  // Create client

            LOGGER.info("TCP Client established on Port" + this.tcpClient.getLocalPort());
            lSocketReader = new BufferedReader(new InputStreamReader(getSocket().getInputStream()));
            lSocketWriter = new BufferedWriter(new OutputStreamWriter(getSocket().getOutputStream()));
            setState(TcpClient.State.STARTED);                                      // Mark as started
            fireInitialAcknowledgeMessageSend();
            LOGGER.info("Acknowledge Message Sucessfully Send.");
            while (!this.tcpClient.isClosed()) {
                synchronized (this) {
                    if (this.currentState == TcpClient.State.STOPPING) {
                        LOGGER.info("Stopping Tcp Client by request.");
                        this.tcpClient.close();
                    }   // end if: stopping
                }   // end sync

                if (!this.tcpClient.isClosed()) {
                    ////////  B L O C K I N G
                    String readLine = lSocketReader.readLine();
//                    lSocketReader.close();
                    ////////  B L O C K I N G
                    if (LOGGER.isEnabledFor(Level.DEBUG)) {
                        LOGGER.debug("Tcp Client incoming socket: " + tcpClient);
                    }
                    if (readLine != null) {
                        if (!readLine.isEmpty()) {
                            fireTCPClientSocketReceived(readLine);
                        }
                    }
                }   //end if: not closed
            }   // end while: keepGoing

        } catch (Exception exc) {
            synchronized (this) {
                if (this.tcpClient != null) {
                    if (this.currentState == TcpClient.State.STOPPING) {  // User asked to stop
                        try {
                            this.tcpClient.close();
                            //LOGGER.info("Tcp Client closed normally.");
//                            fireTCPClientSocketReceived("Stop");
                        } catch (IOException exc2) {
                            LOGGER.log(
                                    Level.FATAL,
                                    "An error occurred while closing the Tcp Client. "
                                    + "This may have left the client in an undeDEBUGd state.",
                                    exc2);
                            fireExceptionNotification(exc2);
                        }   // end catch IOException
                    } else {
                        //LOGGER.log(Level.WARN, "Client closed unexpectedly: " + exc.getMessage(), exc);
                        //LOGGER.info("Server Stop Temporarily, Please Wait for Re-Connect......");
//                        fireTCPClientSocketReceived("Stop");
                    }   // end else
                }   // end sync
                fireExceptionNotification(exc);
            }
        } finally {
            setState(TcpClient.State.STOPPING);
            if (this.tcpClient != null) {
                try {
                    this.tcpClient.close();
                    //LOGGER.info("Tcp Client closed normally.");
//                    fireTCPClientSocketReceived("Stop");
                } catch (IOException exc2) {
                    LOGGER.log(
                            Level.FATAL,
                            "An error occurred while closing the Tcp Client. "
                            + "This may have left the client in an undeDEBUGd state.",
                            exc2);
                    fireExceptionNotification(exc2);
                    LOGGER.info("Problem occoured while closing the Tcp Client.");
                }   // end catch IOException
            }   // end if: not null
            //this.tcpClient = null;
        }
    }

    /* ********  S O C K E T  ******** */
    /**
     * Returns the last Socket received.
     *
     * @return the socket just received
     */
    public synchronized Socket getSocket() {
        return this.tcpClient;
    }

    /* ********  P O R T  ******** */
    /**
     * Returns the port on which the client is or will be listening.
     *
     * @return The port for listening.
     */
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

    /**
     * Sets the new port on which the client will attempt to listen. If the
     * client is already listening, then it will attempt to restart on the new
     * port, generating start and stop events. If the old port and new port are
     * the same, events will be fired, but the client will not actually reset.
     *
     * @param port the new port for listening
     * @throws IllegalArgumentException if port is outside 0..65535
     */
    public synchronized void setPort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Cannot set port outside range 0..65535: " + port);
        }   // end if: port outside range


        int oldVal = this.port;
        this.port = port;
        if (getState() == TcpClient.State.STARTED && oldVal != port) {
            reset();
        }   // end if: is running

        firePropertyChange(PORT_PROP, oldVal, port);
    }

    /* ********  E X E C U T O R  ******** */
    /**
     * Returns the Executor (or null if none is set) that is used to execute the
     * event firing.
     *
     * @return Executor used for event firing or null
     */
    public synchronized Executor getExecutor() {
        return this.executor;
    }

    /**
     * <p>Sets (or clears, if null) the Executor used to fire events. If an
     * Executor is set, then for each event, all listeners of that event are
     * called in seqentially on a thread generated by the Executor.</p>
     *
     * <p>Take the following example:</p>
     *
     * <code>import java.util.concurrent.*;
     * ...
     * client.setExecutor( Executors.newCachedThreadPool() );</code>
     *
     * <p>Let's say three objects are registered to listen for events from the
     * TCPClient. When the client state changes, the three objects will be
     * called sequentially on the same thread, generated by the Cached Thread
     * Pool. Say one of those objects takes a long time to respond, and a new
     * incoming connection is established while waiting. Those three objects
     * will sequentially be notified of the new connection on a different
     * thread, generated by the Cached Thread Pool.</p>
     *
     * @param exec the new Executor or null if no executor is to be used
     */
    public synchronized void setExecutor(Executor exec) {
        Executor oldVal = this.executor;
        this.executor = exec;

        firePropertyChange(EXECUTOR_PROP, oldVal, exec);
    }

    /* ********  E V E N T S  ******** */
    /**
     * Adds a {@link Listener}.
     *
     * @param l the listener
     */
    public synchronized void addTcpClientListener(TcpClient.Listener l) {
        listeners.add(l);
    }

    /**
     * Removes a {@link Listener}.
     *
     * @param l the listener
     */
    public synchronized void removeTcpClientListener(TcpClient.Listener l) {
        listeners.remove(l);
    }

    /**
     * Fires event when a socket is received
     */
    protected synchronized void fireTCPClientSocketReceived(final String Message) {

        final TcpClient.Listener[] ll = listeners.toArray(new TcpClient.Listener[listeners.size()]);

        // Make a Runnable object to execute the calls to listeners.
        // In the event we don't have an Executor, this results in
        // an unnecessary object instantiation, but it also makes
        // the code more maintainable.
        Runnable r = new Runnable() {
            public void run() {
                for (TcpClient.Listener l : ll) {
                    try {
                        l.socketReceived(event, Message);
                    } catch (Exception exc) {
                        LOGGER.warn("TCPClient.Listener " + l + " threw an exception: " + exc.getMessage());
                        fireExceptionNotification(exc);
                    }   // end catch
                }   // end for: each listener
            }   // end run
        };

        if (this.executor == null) {
            r.run();
        } else {
            try {
                this.executor.execute(r);
            } catch (Exception exc) {
                LOGGER.warn("Supplied Executor " + this.executor + " threw an exception: " + exc.getMessage());
                fireExceptionNotification(exc);
            }   // end catch
        }   // end else: other thread
    }  // end fireTCPClientPacketReceived

    protected synchronized void fireInitialAcknowledgeMessageSend() {

        final TcpClient.Listener[] ll = listeners.toArray(new TcpClient.Listener[listeners.size()]);

        // Make a Runnable object to execute the calls to listeners.
        // In the event we don't have an Executor, this results in
        // an unnecessary object instantiation, but it also makes
        // the code more maintainable.
        Runnable r = new Runnable() {
            public void run() {
                for (TcpClient.Listener l : ll) {
                    try {
                        l.socketAcknowledge(event);
                    } catch (Exception exc) {
                        LOGGER.warn("TCPClient.Listener " + l + " threw an exception: " + exc.getMessage());
                        fireExceptionNotification(exc);
                    }   // end catch
                }   // end for: each listener
            }   // end run
        };

        if (this.executor == null) {
            r.run();
        } else {
            try {
                this.executor.execute(r);
            } catch (Exception exc) {
                LOGGER.warn("Supplied Executor " + this.executor + " threw an exception: " + exc.getMessage());
                fireExceptionNotification(exc);
            }   // end catch
        }   // end else: other thread
    }
    /* ********  P R O P E R T Y   C H A N G E  ******** */

    /**
     * Fires property chagne events for all current values setting the old value
     * to null and new value to the current.
     */
    public synchronized void fireProperties() {
        firePropertyChange(PORT_PROP, null, getPort());      // Port
        firePropertyChange(HOST_PROP, null, getHost());
        firePropertyChange(STATE_PROP, null, getState());      // State
    }

    /**
     * Fire a property change event on the current thread.
     *
     * @param prop name of property
     * @param oldVal old value
     * @param newVal new value
     */
    protected synchronized void firePropertyChange(final String prop, final Object oldVal, final Object newVal) {
        try {
            propSupport.firePropertyChange(prop, oldVal, newVal);
        } catch (Exception exc) {
            LOGGER.log(Level.WARN,
                    "A property change listener threw an exception: " + exc.getMessage(), exc);
            fireExceptionNotification(exc);
        }   // end catch
    }   // end fire

    /**
     * Add a property listener.
     *
     * @param listener the property change listener
     */
    public synchronized void addPropertyChangeListener(PropertyChangeListener listener) {
        propSupport.addPropertyChangeListener(listener);
    }

    /**
     * Add a property listener for the named property.
     *
     * @param property the sole property name for which to register
     * @param listener the property change listener
     */
    public synchronized void addPropertyChangeListener(String property, PropertyChangeListener listener) {
        propSupport.addPropertyChangeListener(property, listener);
    }

    /**
     * Remove a property listener.
     *
     * @param listener the property change listener
     */
    public synchronized void removePropertyChangeListener(PropertyChangeListener listener) {
        propSupport.removePropertyChangeListener(listener);
    }

    /**
     * Remove a property listener for the named property.
     *
     * @param property the sole property name for which to stop receiving events
     * @param listener the property change listener
     */
    public synchronized void removePropertyChangeListener(String property, PropertyChangeListener listener) {
        propSupport.removePropertyChangeListener(property, listener);
    }

    /* ********  E X C E P T I O N S  ******** */
    /**
     * Returns the last exception (Throwable, actually) that the client
     * encountered.
     *
     * @return last exception
     */
    public synchronized Throwable getLastException() {
        return this.lastException;
    }

    /**
     * Fires a property change event with the new exception.
     *
     * @param t
     */
    protected void fireExceptionNotification(Throwable t) {
        Throwable oldVal = this.lastException;
        this.lastException = t;
        firePropertyChange(LAST_EXCEPTION_PROP, oldVal, t);
    }

    /* ********  L O G G I N G  ******** */
    /**
     * Static method to set the logging level using Java's
     * <tt>java.util.logging</tt> package. Example:
     * <code>TCPClient.setLoggingLevel(Level.OFF);</code>.
     *
     * @param level the new logging level
     */
    public static void setLoggingLevel(Level level) {
        LOGGER.setLevel(level);
    }

    /**
     * Static method returning the logging level using Java's
     * <tt>java.util.logging</tt> package.
     *
     * @return the logging level
     */
    public static Level getLoggingLevel() {
        return LOGGER.getLevel();
    }

    /* ********                                                          ******** */
    /* ********                                                          ******** */
    /* ********   S T A T I C   I N N E R   C L A S S   L I S T E N E R  ******** */
    /* ********                                                          ******** */
    /* ********                                                          ******** */
    /**
     * An interface for listening to events from a {@link TCPClient}. A single
     * {@link Event} is shared for all invocations of these methods.
     *
     * <p>This code is released into the Public Domain. Since this is Public
     * Domain, you don't need to worry about licensing, and you can simply copy
     * this TCPClient.java file to your own package and use it as you like.
     * Enjoy. Please consider leaving the following statement here in this
     * code:</p>
     *
     * <p><em>This <tt>TCPClient</tt> class was copied to this project from its
     * source as found at <a href="http://iharder.net"
     * target="_blank">iHarder.net</a>.</em></p>
     *
     * @author Robert Harder
     * @author rharder@users.sourceforge.net
     * @version 0.1
     * @see TCPClient
     * @see Event
     */
    public static interface Listener extends java.util.EventListener {

        /**
         * Called when a packet is received. This is called on the IO thread, so
         * don't take too long, and if you want to offload the processing to
         * another thread, be sure to copy the data out of the datagram since it
         * will be clobbered the next time around.
         *
         * @param evt the event
         */
        public abstract void socketReceived(TcpClient.Event lEvent, String Messgae);

        public abstract void socketAcknowledge(TcpClient.Event lEvent);
    }
// end inner static class Listener

    /* ********                                                        ******** */
    /* ********                                                        ******** */
    /* ********   S T A T I C   I N N E R   C L A S S   A D A P T E R  ******** */
    /* ********                                                        ******** */
    /* ********                                                        ******** */
    /**
     * A helper class that implements all methods of the
     * {@link TCPClient.Listener} interface with empty methods.
     *
     * <p>This code is released into the Public Domain. Since this is Public
     * Domain, you don't need to worry about licensing, and you can simply copy
     * this TCPClient.java file to your own package and use it as you like.
     * Enjoy. Please consider leaving the following statement here in this
     * code:</p>
     *
     * <p><em>This <tt>TCPClient</tt> class was copied to this project from its
     * source as found at <a href="http://iharder.net"
     * target="_blank">iHarder.net</a>.</em></p>
     *
     * @author Robert Harder
     * @author rharder@users.sourceforge.net
     * @version 0.1
     * @see TCPClient
     * @see Listener
     * @see Event
     */
//    public class Adapter implements Listener {
    /**
     * Empty call for {@link TCPClient.Listener#tCPClientStateChanged}.
     *
     * @param evt the event
     */
//  public void tCPClientStateChanged(Event evt) {}
    /**
     * Empty call for {@link TCPClient.Listener#socketReceived}.
     *
     * @param evt the event
     */
//        public void socketReceived(Event evt) {}
//    }   // end static inner class Adapter
    /* ********                                                    ******** */
    /* ********                                                    ******** */
    /* ********   S T A T I C   I N N E R   C L A S S   E V E N T  ******** */
    /* ********                                                    ******** */
    /* ********                                                    ******** */
    /**
     * An event representing activity by a {@link TCPClient}.
     *
     * <p>This code is released into the Public Domain. Since this is Public
     * Domain, you don't need to worry about licensing, and you can simply copy
     * this TCPClient.java file to your own package and use it as you like.
     * Enjoy. Please consider leaving the following statement here in this
     * code:</p>
     *
     * <p><em>This <tt>TCPClient</tt> class was copied to this project from its
     * source as found at <a href="http://iharder.net"
     * target="_blank">iHarder.net</a>.</em></p>
     *
     * @author Robert Harder
     * @author rharder@users.sourceforge.net
     * @version 0.1
     * @see TCPClient
     * @see Listener
     */
    public static class Event extends java.util.EventObject {

        private final static long serialVersionUID = 1;

        /**
         * Creates a Event based on the given {@link TCPClient}.
         *
         * @param src the source of the event
         */
        public Event(TcpClient src) {
            super(src);
        }

        /**
         * Returns the source of the event, a {@link TCPClient}. Shorthand for
         * <tt>(TCPClient)getSource()</tt>.
         *
         * @return the client
         */
        public TcpClient getTcpClient() {
            return (TcpClient) getSource();
        }

        /**
         * Shorthand for <tt>getTcpClient().getState()</tt>.
         *
         * @return the state of the client
         * @see TCPClient.State
         */
        public TcpClient.State getState() {
            return getTcpClient().getState();
        }

        /**
         * Returns the most recent datagram packet received by the
         * {@link TCPClient}. Shorthand for <tt>getTcpClient().getPacket()</tt>.
         *
         * @return the most recent datagram
         */
        public Socket getSocket() {
            return getTcpClient().getSocket();
        }
    }   // end static inner class Event
}   // end class TCPClient

