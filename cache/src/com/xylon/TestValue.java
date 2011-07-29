package com.xylon;

import java.io.IOException;

import javax.servlet.ServletException;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;

public class TestValue extends ValveBase{

	public void invoke(Request request, Response response) throws IOException,
			ServletException {
		System.out.println("------>Valve--->start");
		try {
			getNext().invoke(request, response);   
		} catch (Exception e) {
			e.printStackTrace();
		}finally{
			try {
				
				Thread.currentThread().sleep(5000);
				System.out.println("------>Valve--->end");
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
}
