package xylon.thread;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;


import net.spy.memcached.MemcachedClient;

public class FutureT implements Callable<Future<Boolean>>{
	
	private MemcachedClient client;
	
	public FutureT(MemcachedClient client){
		this.client = client;
	}
	
	
	public Future call() throws Exception {
		return client.add("ewew", 10000, "fdfdfdfd");
	}
}	
