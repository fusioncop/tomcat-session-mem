package mcast;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class MulticastReceiver { 
    public static void main(String[] arstring) { 
        try { 
           System.out.println("Java Multicast Receiver Demo"+ '\n'+ "Multicast Listening Established."); 
           int port = 7777; 
           byte[] arb = new byte[100]; 
           int count=0;int i; 
           MulticastSocket multicastSocket = new   MulticastSocket(port); 
           InetAddress inetAddress = InetAddress.getByName("230.0.0.1"); 
           multicastSocket.joinGroup(inetAddress); 
           while ( count<3 ) { 
                DatagramPacket datagramPacket = new DatagramPacket(arb,arb.length); 
                multicastSocket.receive(datagramPacket); 
                System.out.println("Message Received: " + new String(arb));   
                count++; 
                for(i=0;i<arb.length;i++) 
                { arb[i]=' ' ;} } 
           multicastSocket.close(); 
       }   catch (Exception exception) { 
            exception.printStackTrace(); 
       } 
   } 
}


