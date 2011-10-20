package com.cxz.standard;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.ObjectName;

import com.sun.jdmk.comm.HtmlAdaptorServer;

public class HelloAgent implements NotificationListener{
	private MBeanServer server = null;

	public HelloAgent() {
		server = MBeanServerFactory.createMBeanServer("HelloAgent");

		HtmlAdaptorServer adapter = new HtmlAdaptorServer();

		HelloWorld hw = new HelloWorld();

		try {

			MyListener listener = new MyListener();
			server.registerMBean(listener, new ObjectName(
					"HelloAgent:name=myListener"));
						
			server.registerMBean(hw, new ObjectName(
					"HelloAgent:name=helloWorld"));
			
			hw.addNotificationListener(listener, null, null);
			hw.addNotificationListener(this, null, null);

			adapter.setPort(9092);
			server.registerMBean(adapter, new ObjectName(
					"HelloAgent:name=htmlAdaptor,port=9092"));
			adapter.start();			

		} catch (MalformedObjectNameException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NullPointerException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InstanceAlreadyExistsException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (MBeanRegistrationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (NotCompliantMBeanException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}

	public static void main(String[] args) {
		System.out.println("HelloAgent is running!");
		HelloAgent agent = new HelloAgent();
	}

	public void handleNotification(Notification notification, Object handback) {
		System.out.println(notification.getMessage());
	}
}
