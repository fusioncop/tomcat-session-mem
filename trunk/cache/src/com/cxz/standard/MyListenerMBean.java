package com.cxz.standard;

import javax.management.NotificationListener;

public interface MyListenerMBean extends NotificationListener {
	public void printInfo(String message);
}
