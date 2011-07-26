package mcast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public class MulticastSender {
	public static void main(String[] arstring) { 
	      try { 
	         String msg = new String("abcde"); 
	         System.out.println("Java Multicast Sender Demo"+ '\n'+ "please input a message:"); 
	         BufferedReader inFromUser = 
	          new BufferedReader(new InputStreamReader(System.in)); 
	         msg = inFromUser.readLine(); 
	         int port = 7777; 
	         byte[] arb = msg.getBytes(); 
	         InetAddress inetAddress = InetAddress.getByName("230.0.0.1"); 
	         DatagramPacket datagramPacket = 
	              new DatagramPacket(arb, arb.length, inetAddress, port); 
	         MulticastSocket multicastSocket = new MulticastSocket(); 
	         multicastSocket.send(datagramPacket); 
	         multicastSocket.close(); 
	         System.out.println("One Multicast Message has been sent."); 
	     } catch (Exception exception) { 
	         exception.printStackTrace(); 
	     } 
	}}
