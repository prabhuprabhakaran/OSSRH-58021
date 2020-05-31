package com.github.prabhuprabhakaran.jsockets.examples;

import com.github.prabhuprabhakaran.jsockets.udp.UniCastClient;
import com.github.prabhuprabhakaran.jsockets.udp.UniCastServer;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;

public class UniCastExample {

    private static final Logger LOG = Logger.getLogger(UniCastExample.class.getName());

    public static void main(String[] args) throws InterruptedException, IOException {
        BasicConfigurator.configure();
        UniCastServer server = new UniCastServer(8541);

        UniCastClient client = new UniCastClient(9451);

        server.addUdpServerListener(new UniCastServer.Listener() {
            @Override
            public void packetReceived(UniCastServer.Event evt) {
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

        client.addUdpClientListener(new UniCastClient.Listener() {
            @Override
            public void packetReceived(UniCastClient.Event evt) {
                System.out.println("Got Message <Client> : " + evt.getPacketAsString());
            }
        });

        server.start();

        Thread.sleep(1000);

        client.start();

        Thread.sleep(1000);

        String lMessage = "Hello World";
        DatagramPacket lPacket = new DatagramPacket(lMessage.getBytes(), lMessage.getBytes().length);
        lPacket.setSocketAddress(new InetSocketAddress("localhost", 8541));
        client.send(lPacket);

        Thread.sleep(5000);

        server.stop();
        client.stop();
    }
}
