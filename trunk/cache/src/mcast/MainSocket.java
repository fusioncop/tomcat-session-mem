package mcast;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignedObject;

public class MainSocket {

	public MainSocket() {
		// Transmission control protocol user datagram protocol
		// DatagramSocket udp
		// send close
		// sock_stream sock_dgram sock_raw
		// DatagramPacket
		// InetAddress
		try {
			byte[] buf = new byte[1024];
			DatagramSocket ds = new DatagramSocket();
			InetAddress add = null;
			try {
				add = InetAddress.getByName("www.www");
			} catch (UnknownHostException e1) {
				e1.printStackTrace();
			}
			try {
				ds.send(new DatagramPacket(buf, buf.length, add, 5555));
			} catch (IOException e) {
				e.printStackTrace();
			}
			DatagramPacket dp = new DatagramPacket(buf, 1024);
			try {
				ds.receive(dp);
			} catch (IOException e) {

				e.printStackTrace();
			}
			new String(dp.getData(), 0, dp.getLength());
			dp.getAddress().getHostAddress();
		} catch (SocketException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		// ServerSocket tcp

	}

	public void server() {
		byte[] buf = new byte[1024];
		InetAddress add = null;
		DatagramSocket ds = null;
		DatagramPacket dp = null;
		try {
			add = InetAddress.getByName("www.www");
			dp = new DatagramPacket(buf, 1024, add, 5555);
			ds = new DatagramSocket(5555);
			ds.send(dp);
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	// ¶àµã´«ËÍ multi send
	public void mulit() {
		try {
			byte[] buf = new byte[1024];
			InetAddress group = InetAddress.getByName("");
			MulticastSocket ms = new MulticastSocket(5555);
			ms.joinGroup(group);
			DatagramPacket dp = new DatagramPacket(buf, buf.length, group, 5555);
			ms.send(dp);
			ms.leaveGroup(group);
		} catch (IOException e) {

			e.printStackTrace();
		}
	}

	public void client() {
		byte[] buf = new byte[1024];
		DatagramSocket ds;
		DatagramPacket dp = new DatagramPacket(buf, buf.length);
		try {
			ds = new DatagramSocket(5555);
			ds.receive(dp);
		} catch (SocketException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		Socket s = null;
		ObjectOutputStream os = null;
		s = new Socket("pureajava", 40000);
		os = new ObjectOutputStream(s.getOutputStream());
		// generate public and private keys
		KeyPairGenerator kgen = KeyPairGenerator.getInstance("DSA");
		kgen.initialize(256);
		KeyPair kpair = kgen.generateKeyPair();

		// generate a signature
		Signature sig = Signature.getInstance("SHA/DSA");
		PublicKey pub = kpair.getPublic();
		PrivateKey priv = kpair.getPrivate();
		sig.initSign(priv);

		// Read a file and compute a signature
		FileInputStream fis = new FileInputStream("demo");
		byte arr[] = new byte[fis.available()];
		fis.read(arr);
		sig.update(arr);

		// send the signedobject on the wire
		SignedObject obj = new SignedObject(arr, priv, sig);
		// new SignedObject(arr,sig.sign(),pub);
		os.writeObject(obj);
		fis.close();
		os.close();
		s.close();

		// service
		ServerSocket service = new ServerSocket(4000);
		Socket clientSocket = service.accept();
		ObjectInputStream ois = new ObjectInputStream(clientSocket
				.getInputStream());
		SignedObject ob = (SignedObject) ois.readObject();
		// generate object's signature
		Signature sign = Signature.getInstance("SHA/DSA");
		sign.initVerify(pub);
		sign.update(ob.getSignature());
		// verify the signature
		boolean valid = sign.verify(ob.getSignature());
	}

}
