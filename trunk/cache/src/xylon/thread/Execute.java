package xylon.thread;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.spy.memcached.DefaultConnectionFactory;
import net.spy.memcached.MemcachedClient;

public class Execute {
	 
	private MemcachedClient client =null;
	
	public Execute(){
		try {
			client = new MemcachedClient(new DefaultConnectionFactory(), Arrays.asList(new InetSocketAddress( "192.168.30.32", 11211 )));
		} catch (IOException e) {
			e.printStackTrace();
		}
         
	}
	
	public void test(){
		long start = System.nanoTime();
		client.set("12121", 1000, "fdfdsfsd");
//		client.set("sss", 1000, "fdfdsfsd");
//		client.set("sss", 1000, "fdfdsfsd");
//		client.set("sss", 1000, "fdfdsfsd");
//		client.set("sss", 1000, "fdfdsfsd");
		System.out.println("--->1£º"+(System.nanoTime()-start));
	}
	
	public void test2(){
		long start = System.nanoTime();
		Future<Boolean> t = client.set("sss", 1000, "fdfdsfsd");
		try {
			t.get();
			System.out.println("--->2£º"+(System.nanoTime()-start));
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
	}
	
	public static void main(String[] args) {
		Execute execute = new Execute();
//		execute.test2();
//		execute.test();
//		System.out.println("+++++++++++++++++++++++++++++");
//		execute.test2();
		execute.test();
	}
	 
}
