import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Method;

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
		
		
		
            // Use APR to get a crypto secure entropy value
            byte[] result = new byte[32];
            boolean apr = false;
            try {
                String methodName = "random";
                Class paramTypes[] = new Class[2];
                paramTypes[0] = result.getClass();
                paramTypes[1] = int.class;
                Object paramValues[] = new Object[2];
                paramValues[0] = result;
                paramValues[1] = new Integer(32);
                Method method = Class.forName("org.apache.tomcat.jni.OS")
                    .getMethod(methodName, paramTypes);
                method.invoke(null, paramValues);
                
            } catch (Throwable t) {
                // Ignore
            }
            for(int i = 0; i < result.length; i++){
  				 System.out.print(result[i]);
               }
           
	}
}
