package com.cxz.standard;

public interface HelloWorldMBean {
	public void setGreeting(String greeting);

	public String getGreeting();

	public void printGreeting();
}
