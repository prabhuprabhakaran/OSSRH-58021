# **JSockets**

**jSockets** is a java framework, that used to implement TCP/UDP/NIO Server and Clients.

```java
NioServer server = new NioServer();
server.start();
...
...
server.addTcpBinding( new InetSocketAddress( 80 ) );
server.addUdpBinding( new InetSocketAddress( 80 ) );
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

```java
TCPClient client = new TCPClient("REMOTE_SERVER",REMOTE_PORT);
client.start();

client.addTcpClientListener( new TCPClient.Listener(){
	public void socketReceived( TCPClient.Event evt ){
		Socket socket = evt.getSocket();
			...
	}
});
```

```java

```

