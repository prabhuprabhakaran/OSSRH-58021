package com.github.prabhuprabhakaran.jsockets.examples;

import com.github.prabhuprabhakaran.jsockets.udp.MulticastClient;
import com.github.prabhuprabhakaran.jsockets.udp.MulticastServer;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;

public class MulticastExample {

    private static final Logger LOG = Logger.getLogger(MulticastExample.class.getName());

    public static void main(String[] args) throws InterruptedException, IOException {
        BasicConfigurator.configure();
        MulticastServer server = new MulticastServer("localhost", 8541);

        MulticastClient client = new MulticastClient("localhost", 9451);

        server.addUdpServerListener(new MulticastServer.Listener() {
            @Override
            public void packetReceived(MulticastServer.Event evt) {
                try {
                    System.out.println("Got Message <Server> : " + evt.getPacketAsString());
                    String lReturnMessage = "Reply : " + evt.getPacketAsString();
                    DatagramPacket lPacket = new DatagramPacket(lReturnMessage.getBytes(), lReturnMessage.getBytes().length);
                    lPacket.setSocketAddress(evt.getPacket().getSocketAddress());
                    evt.getUdpServer().send(lPacket);
                } catch (IOException ex) {
                    LOG.error(ex);
                }
            }
        });

        client.addUdpClientListener(new MulticastClient.Listener() {
            @Override
            public void packetReceived(MulticastClient.Event evt) {
                System.out.println("Got Message <Client> : " + evt.getPacketAsString());
            }
        });

        server.start();

        Thread.sleep(1000);

        client.start();

        Thread.sleep(1000);

        String lMessage = "Hello World";
        DatagramPacket lPacket = new DatagramPacket(lMessage.getBytes(), lMessage.getBytes().length);
        lPacket.setData(lMessage.getBytes());
        lPacket.setSocketAddress(new InetSocketAddress("localhost", 8541));
        client.send(lPacket);

        Thread.sleep(5000);

        server.stop();
        client.stop();
    }
}
