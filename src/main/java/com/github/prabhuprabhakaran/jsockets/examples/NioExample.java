package com.github.prabhuprabhakaran.jsockets.examples;

import com.github.prabhuprabhakaran.jsockets.nio.NioServer;
import com.github.prabhuprabhakaran.jsockets.tcp.TcpClient;
import com.github.prabhuprabhakaran.jsockets.udp.MulticastClient;
import com.github.prabhuprabhakaran.jsockets.udp.UniCastClient;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import org.apache.log4j.BasicConfigurator;

public class NioExample {

    public static void main(String[] args) throws InterruptedException, IOException {
        BasicConfigurator.configure();
        NioServer server = new NioServer();
        server.addTcpBinding(new InetSocketAddress(8540));
        server.addUdpBinding(new InetSocketAddress(8541));
        server.addUdpBinding(new InetSocketAddress(8542), "localhost");

        TcpClient tcpClient = new TcpClient("localhost", 8540, "localhost", 9450);
        UniCastClient udpUniCastClient = new UniCastClient(9451);
        MulticastClient udpMultiCastClient = new MulticastClient("localhost", 9452);

        server.addNioServerListener(new NioServer.Adapter() {
            @Override
            public void tcpDataReceived(NioServer.Event evt) {
                ByteBuffer inputBuffer = evt.getInputBuffer();
                inputBuffer.mark();
                String message = Charset.defaultCharset().decode(inputBuffer).toString();
                inputBuffer.reset();
                System.out.println("Got TCP Message <Server> : " + message);
                evt.getNioServer().send(evt.getKey(), "Reply : " + message);
            }

            @Override
            public void udpDataReceived(NioServer.Event evt) {
                ByteBuffer inputBuffer = evt.getInputBuffer();
                inputBuffer.mark();
                String message = Charset.defaultCharset().decode(inputBuffer).toString();
                inputBuffer.reset();
                System.out.println("Got UDP Message <Server> : " + message);
//                evt.getNioServer().send(evt.getKey(), "Reply : " + message);
            }

        });

        tcpClient.addTcpClientListener(new TcpClient.Listener() {
            @Override
            public void socketReceived(TcpClient.Event lEvent, String Messgae) {
                System.out.println("Got TCP Message <Client> : " + Messgae);
            }

            @Override
            public void socketAcknowledge(TcpClient.Event lEvent) {

            }
        });

        udpUniCastClient.addUdpClientListener(new UniCastClient.Listener() {
            @Override
            public void packetReceived(UniCastClient.Event evt) {
                System.out.println("Got UDP Unicast Message <Client> : " + evt.getPacketAsString());
            }
        });

        udpMultiCastClient.addUdpClientListener(new MulticastClient.Listener() {
            @Override
            public void packetReceived(MulticastClient.Event evt) {
                System.out.println("Got UDP Multicast Message <Client> : " + evt.getPacketAsString());
            }
        });

        server.start();

        Thread.sleep(1000);

        tcpClient.start();
        udpUniCastClient.start();
        udpMultiCastClient.start();

        Thread.sleep(1000);
        {
            tcpClient.send("Hello World TCP");
        }
        {
            String lMessage = "Hello World Unicast UDP";
            DatagramPacket lPacket = new DatagramPacket(lMessage.getBytes(), lMessage.getBytes().length);
            lPacket.setSocketAddress(new InetSocketAddress("localhost", 8541));
            udpUniCastClient.send(lPacket);
        }
        {
            String lMessage = "Hello World Multicast UDP";
            DatagramPacket lPacket = new DatagramPacket(lMessage.getBytes(), lMessage.getBytes().length);
            lPacket.setSocketAddress(new InetSocketAddress("localhost", 8542));
            udpMultiCastClient.send(lPacket);
        }
        Thread.sleep(1000);

        server.stop();
        tcpClient.stop();
        udpUniCastClient.stop();
        udpMultiCastClient.start();
    }
}
