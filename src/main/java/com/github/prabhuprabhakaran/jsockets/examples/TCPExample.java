package com.github.prabhuprabhakaran.jsockets.examples;

import com.github.prabhuprabhakaran.jsockets.tcp.TcpClient;
import com.github.prabhuprabhakaran.jsockets.tcp.TcpServer;
import org.apache.log4j.BasicConfigurator;

public class TCPExample {

    public static void main(String[] args) throws InterruptedException {
        BasicConfigurator.configure();
        TcpServer server = new TcpServer(8540);
        TcpClient client = new TcpClient("localhost", 8540, "localhost", 9450);

        server.addTcpServerListener(new TcpServer.Listener() {
            @Override
            public void socketReceived(TcpServer.Event evt, String Message) {
                System.out.println("Got Message <Server> : " + Message);
                evt.getTcpServer().send("Reply : " + Message);
            }
        });

        client.addTcpClientListener(new TcpClient.Listener() {
            @Override
            public void socketReceived(TcpClient.Event lEvent, String Messgae) {
                System.out.println("Got Message <Client> : " + Messgae);
            }

            @Override
            public void socketAcknowledge(TcpClient.Event lEvent) {

            }
        });

        server.start();

        Thread.sleep(1000);

        client.start();

        Thread.sleep(1000);

        client.send("Hello World");

        Thread.sleep(5000);

        server.stop();
        client.stop();
    }
}
