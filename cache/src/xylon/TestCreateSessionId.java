package xylon;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import org.apache.catalina.session.StandardManager;

public class TestCreateSessionId {
	public static void main(String[] args) {
//		 try {
//			byte[] test = new byte[16];
//			File f=new File("C:\\wlrun.log");
//			 if(f.exists()){
//				 DataInputStream  randomIS = new DataInputStream( new FileInputStream(f));
//				 randomIS.read(test);
//				 for(int i = 0; i < test.length; i++){
//					 System.out.println(test[i]);
//				 }
//			 }
////			 7382356860016486003
//		} catch (FileNotFoundException e) {
//			e.printStackTrace();
//		} catch (IOException e) {
//			e.printStackTrace();
//		}
		
//		System.out.println((long)(199 & 0xff) << 1 | (long)(199 & 0xff) << 1);
//		System.out.println(1111111111%256);
//		System.out.println(Integer.toBinaryString(0xff));
//		System.out.println(1 << 4);
		
		
		
//            // Use APR to get a crypto secure entropy value
//            byte[] result = new byte[32];
//            boolean apr = false;
//            try {
//                String methodName = "random";
//                Class paramTypes[] = new Class[2];
//                paramTypes[0] = result.getClass();
//                paramTypes[1] = int.class;
//                Object paramValues[] = new Object[2];
//                paramValues[0] = result;
//                paramValues[1] = new Integer(32);
//                Method method = Class.forName("org.apache.tomcat.jni.OS")
//                    .getMethod(methodName, paramTypes);
//                method.invoke(null, paramValues);
//                
//            } catch (Throwable t) {
//                // Ignore
//            }
//            for(int i = 0; i < result.length; i++){
//  				 System.out.print(result[i]);
//               }
//		long seed = System.currentTimeMillis();
//		System.out.println("------>seed:"+seed);
//		byte[] entropy = {1,1,1,1,1,1};
//		for (int i = 0; i < entropy.length; i++) {
////            long update = ((byte) entropy[i]) << ((i % 8) * 8);
//			long update =  entropy[i];
//            System.out.println("------>update:"+update);
//            seed ^= update;
//            System.out.println("------>update:"+seed);
//        }
//		
		
//		System.out.println((char)('0'+'1'));
		
//		System.out.println(System.getSecurityManager().getClass().getName());
//		testMessageDigest();
//		testRandom();
		testByte2Hex();
	}
	
	/**
	 * windows中随机数种子计算方法
	 */
	public static void testSeed(){
		long seed = System.currentTimeMillis();
		System.out.println("------>seed:"+seed);
		char[] entropy = {1,1,1,1,1,1};
		for (int i = 0; i < entropy.length; i++) {
			// 目的是将byte 转换为 long,可视为将 entropy[] 数组整体转换为一个long型
            long update = ((byte) entropy[i]) << ((i % 8) * 8);
            seed ^= update;
            System.out.println("------>update:"+seed);
        }
	}
	
	/**
	 * 设定种子后的随机数，只要种子未变，随机数一定相同
	 */
	public static void testRandom(){
		byte random1[] = new byte[16];
		long t = 1L;
		SecureRandom random = new SecureRandom();
		random.setSeed(t);
		random.nextBytes(random1);
		for(int i = 0; i < random1.length; i++){
			System.out.print(random1[i]);
			System.out.print(", ");
		}
	}
	
	/**
	 * byte转换为 16进制
	 */
	public static void testByte2Hex(){
		StringBuilder builder = new StringBuilder();
		int resultLenBytes = 0;
		int sessionIdLength = 16;
		byte random[] = {10, 100, 20, 127, 10, 100, 20, 127, 10, 100, 20, 127, 10, 100, 20, 127};
		 //转换为 16 进制
        for (int j = 0;  j < random.length && resultLenBytes < sessionIdLength; j++) {
        	//取该 byte数的低4位  16的倍数
            byte b1 = (byte) ((random[j] & 0xf0) >> 4);
            //取该 byte数的高4位  16的余数
            byte b2 = (byte) (random[j] & 0x0f);
            if (b1 < 10)
            	builder.append((char) ('0' + b1));
            else
            	builder.append((char) ('A' + (b1 - 10)));
            if (b2 < 10)
            	builder.append((char) ('0' + b2));
            else
            	builder.append((char) ('A' + (b2 - 10)));
            resultLenBytes++;
        }
        System.out.println(builder.toString());
	}
	
}
