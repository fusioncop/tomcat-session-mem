/*
 * $Id$
 * (c) Copyright 2009 freiheit.com technologies GmbH
 *
 * Created on Mar 13, 2010 by Martin Grotzke (martin.grotzke@freiheit.com)
 *
 * This file contains unpublished, proprietary trade secret information of
 * freiheit.com technologies GmbH. Use, transcription, duplication and
 * modification are strictly prohibited without prior written consent of
 * freiheit.com technologies GmbH.
 */
package xylon;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.MemcachedClientBuilder;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;
import net.rubyeye.xmemcached.exception.MemcachedException;
import net.rubyeye.xmemcached.utils.AddrUtil;

public class MemcachedTest {
	
	public static void main(String[] args) throws TimeoutException, InterruptedException, MemcachedException {
		MemcachedClientBuilder builder = new XMemcachedClientBuilder(AddrUtil.getAddressMap("192.168.22.23:11211 192.168.119.166:11211"));
		builder.setFailureMode(true);
		try {
			MemcachedClient memcachedClient=builder.build();
			memcachedClient.add("nishiyigedahaoren", 5, "nishiyigedahaoren");
			System.out.println("------------>");
			Thread.currentThread().sleep(10);
			
			System.out.println(memcachedClient.get("nishiyigedahaoren"));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
   
}
