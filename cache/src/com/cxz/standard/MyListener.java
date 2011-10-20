package com.cxz.standard;

import javax.management.Notification;

public class MyListener implements MyListenerMBean {

	public void printInfo(String message) {
		System.out.println(message);
	}

	public void handleNotification(Notification notification, Object handback) {
		printInfo("My listener received Notification: "
				+ notification.getType() + " " + notification.getMessage());
	}
}
