# **JSockets**

**jSockets** is a java framework, that used to implement TCP / UDP / NIO Server and Clients.

we can integrate it easily using listener callbacks with our application. They are thread-safe and robust and well-suited for Java application.

## NIO Server Implementation

```java
NioServer server = new NioServer();
server.start();
...
...
server.addTcpBinding( new InetSocketAddress( LOCAL_PORT ) );
server.addUdpBinding( new InetSocketAddress( LOCAL_PORT ) );
...
...
server.addNioServerListener( new NioServer.Adapter(){

    public void tcpDataReceived( NioServer.Event evt ){
    	ByteBuffer buff = evt.getBuffer();
    	...
    } 

    public void udpDataReceived( NioServer.Event evt ){
    	ByteBuffer buff = evt.getBuffer();
    	...
    } 
});
```

## TCP Server Implementation

```
TcpServer server = new TcpServer(LOCAL_PORT);
server.addTcpServerListener(new TcpServer.Listener() {
    @Override
    public void socketReceived(TcpServer.Event evt, String Message) {
        ...
    }
});
 server.start();
```

## TCP Client Implementation

```java
TCPClient client = new TCPClient(REMOTE_SERVER, REMOTE_PORT);
client.addTcpClientListener( new TCPClient.Listener(){
	public void socketReceived( TCPClient.Event evt ){
		Socket socket = evt.getSocket();
			...
	}
});
client.start();
...
client.send(message);
```

## UDP Unicast Client Implementation

```java
 UniCastClient client = new UniCastClient(LOCAL_PORT);
 client.addUdpClientListener(new UniCastClient.Listener() {
            @Override
            public void packetReceived(UniCastClient.Event evt) {
               ...
            }
        });
client.start();
...
DatagramPacket lPacket = new DatagramPacket(message.getBytes(), message.getBytes().length);
lPacket.setSocketAddress(new InetSocketAddress(REMOTE_IP, REMOTE_PORT));
client.send(lPacket);
```

## UDP Multicast Client Implementation

```
MulticastClient client = new MulticastClient(REMOTE_IP, MULTICAST_PORT);
 client.addUdpClientListener(new MulticastClient.Listener() {
            @Override
            public void packetReceived(MulticastClient.Event evt) {
                ...
            }
});
client.start();
DatagramPacket lPacket = new DatagramPacket(message.getBytes(), message.getBytes().length);
lPacket.setSocketAddress(new InetSocketAddress(REMOTE_IP, REMOTE_PORT));
client.send(lPacket);
```

Inspired from: http://iharder.sourceforge.net/current/java/servers/

Its is completely free, Happy Coding :)