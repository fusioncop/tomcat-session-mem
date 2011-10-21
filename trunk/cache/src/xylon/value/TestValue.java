package xylon.value;

import java.io.IOException;
import java.util.regex.Pattern;

import javax.servlet.ServletException;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

public class TestValue extends ValveBase{

	
	
	
	public void invoke(Request request, Response response) throws IOException,
			ServletException {
		System.out.println("------>Valve--->start"+request.getRequestURI());
		String requestUriIgnorePattern=".*\\.(png|gif|jpg|jpeg|bmp|ico|css|js|xml|html|htm)";
		if(Pattern.compile(requestUriIgnorePattern).matcher(request.getRequestURI()).matches()){
			getNext().invoke(request, response);
			return;
		}
		try {
			final ClassLoader classLoader = getContainer().getLoader().getClassLoader();
			System.out.println(classLoader);
			Class.forName( "com.hc360.cms.struts.form.login.LoginForm", false, classLoader );
			getNext().invoke(request, response);   
		} catch (Exception e) {
			e.printStackTrace();
		}finally{
			System.out.println("------>Valve--->end"+request.getRequestURI());
		}
	}
	
}
