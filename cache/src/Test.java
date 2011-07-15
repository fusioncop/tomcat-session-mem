

import java.io.IOException;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.spy.memcached.DefaultConnectionFactory;
import net.spy.memcached.MemcachedClient;

import de.javakaffee.web.msm.TranscoderService;

public class Test{

	public static void main(String[] args) {
		
//		Map<String, Object> attributes = new HashMap<String, Object>();
//		attributes.put("1", 1);
//		attributes.put("2", 2);
//		attributes.put("3", 3);
//		attributes.put("4", 4);
//		attributes.put("5", 5);
//		String EMPTY_ARRAY[] = new String[attributes.size()];
//		final String keys[] = attributes.keySet().toArray( EMPTY_ARRAY );
//		System.out.println(keys[1]);
//		System.out.println(EMPTY_ARRAY.length);
//		String id = "sssssss";
//		try {
//			byte[] tt = id.getBytes( "UTF-8" );
//			String t = String.valueOf(tt);
//			System.out.println(id instanceof Serializable);
//			System.out.println(t instanceof Serializable);
//		} catch (UnsupportedEncodingException e) {
//			e.printStackTrace();
//		}
		
//		byte[] data = new byte[10];
//		TranscoderService.encodeNum(121211212, data, 0, 2);
//		System.out.println(TranscoderService.decodeNum(data, 0, 2));
//		
//		System.out.println(16>>3);
		
//		
//		long i  = System.currentTimeMillis();
//		
//		try {
//			
//			System.out.println(String.valueOf(i).getBytes().length);
//		} catch (Exception e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
		
//		AtomicInteger _count = new AtomicInteger();
//		System.out.println(_count.get()+"---->"+_count.incrementAndGet()+"-->"+_count.get());
		
		
	    //memcachedNodes 正则
//	     String NODE_REGEX = "([\\w]+):([^:]+):([\\d]+)";
//	    //memcachedNodes 正则 Pattern 对象
//	     Pattern NODE_PATTERN = Pattern.compile( NODE_REGEX );
//	    //多个 memcachedNodes 正则
//	     String NODES_REGEX = NODE_REGEX + "(?:(?:\\s+|,)" + NODE_REGEX + ")*";
//	  //多个 memcachedNodes 正则 Pattern 对象
//	     Pattern NODES_PATTERN = Pattern.compile( NODES_REGEX );
//		
//	     Matcher matcher = NODE_PATTERN.matcher("n2:192.168.0.83:11211 n1:192.168.0.84:11211");
//	     if(matcher.find()){
//	    	System.out.println(matcher.group(1)+"---->"+matcher.group(1));
//	    	System.out.println(matcher.group(2)+"---->"+matcher.group(2));
//	    	System.out.println(matcher.group(3)+"---->"+matcher.group(3));
//	     }
//		TimeUnit timeUnit = TimeUnit.SECONDS;
//		System.out.println(timeUnit.toMillis( 500L ));
		
		
//		testMemcached();
		testMemcached1();
		testMemcached2();
	}
	
	public static void testMemcached(){
		try {
			final MemcachedClient client = new MemcachedClient( new DefaultConnectionFactory(),
			        Arrays.asList( new InetSocketAddress( "192.168.119.170", 11211 ) , new InetSocketAddress( "192.168.119.166", 11211 )) );
			System.out.println(client.add("0DA5BC0367ED3B3D1573ACCCAFEC498E-n1", 10, "吃饭").get());
			System.out.println(client.set("0DA5BC0367ED3B3D1573ACCCAFEC498E-n1", 10, "吃饭").get());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	
	public static void testMemcached1(){
		try {
			final MemcachedClient client = new MemcachedClient( new DefaultConnectionFactory(),
			        Arrays.asList( new InetSocketAddress( "192.168.119.170", 11211 )) );
			System.out.println(client.get("validity:F9DC8CCA3F2019113CA40D47C07036E5-n1"));
			System.out.println(client.get("lock:baF9DC8CCA3F2019113CA40D47C07036E5-n1"));
			System.out.println(client.get("bak:F9DC8CCA3F2019113CA40D47C07036E5-n1"));
			System.out.println(client.get("bak:validity:F9DC8CCA3F2019113CA40D47C07036E5-n1"));
			System.out.println(client.get("F9DC8CCA3F2019113CA40D47C07036E5-n1"));
			
			System.out.println(client.get("validity:F9DC8CCA3F2019113CA40D47C07036E5-n2"));
			System.out.println(client.get("lock:baF9DC8CCA3F2019113CA40D47C07036E5-n2"));
			System.out.println(client.get("bak:F9DC8CCA3F2019113CA40D47C07036E5-n2"));
			System.out.println(client.get("bak:validity:F9DC8CCA3F2019113CA40D47C07036E5-n2"));
			System.out.println(client.get("F9DC8CCA3F2019113CA40D47C07036E5-n2"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public static void testMemcached2(){
		try {
			final MemcachedClient client = new MemcachedClient( new DefaultConnectionFactory(),
			        Arrays.asList(new InetSocketAddress( "192.168.119.166", 11211 )) );
			System.out.println(client.get("validity:F9DC8CCA3F2019113CA40D47C07036E5-n1"));
			System.out.println(client.get("lock:baF9DC8CCA3F2019113CA40D47C07036E5-n1"));
			System.out.println(client.get("bak:F9DC8CCA3F2019113CA40D47C07036E5-n1"));
			System.out.println(client.get("bak:validity:F9DC8CCA3F2019113CA40D47C07036E5-n1"));
			System.out.println(client.get("F9DC8CCA3F2019113CA40D47C07036E5-n1"));
			
			System.out.println(client.get("validity:F9DC8CCA3F2019113CA40D47C07036E5-n2"));
			System.out.println(client.get("lock:baF9DC8CCA3F2019113CA40D47C07036E5-n2"));
			System.out.println(client.get("bak:F9DC8CCA3F2019113CA40D47C07036E5-n2"));
			System.out.println(client.get("bak:validity:F9DC8CCA3F2019113CA40D47C07036E5-n2"));
			System.out.println(client.get("F9DC8CCA3F2019113CA40D47C07036E5-n2"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
