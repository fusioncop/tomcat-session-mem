

import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

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
		
		byte[] data = new byte[10];
		 for ( int i = 0; i < 10; i++ ) {
	            final int pos = 10 - i - 1; // the position of the byte in the number
	            final int idx = 0 + pos; // the index in the data array
	            data[idx] = (byte) ( ( 10000000 >> ( 8 * i ) ) & 0xff );
	            System.out.println( ( 10000000 >> ( 8 * i ) ) & 0xff);
	        }
	}

}
