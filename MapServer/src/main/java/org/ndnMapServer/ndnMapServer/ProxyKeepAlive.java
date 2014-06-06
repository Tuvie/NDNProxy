package org.ndnMapServer.ndnMapServer;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Iterator;

import org.ndnx.ndn.NDNHandle;
import org.ndnx.ndn.NDNInterestHandler;
import org.ndnx.ndn.config.ConfigurationException;
import org.ndnx.ndn.protocol.ContentName;
import org.ndnx.ndn.protocol.ContentObject;
import org.ndnx.ndn.protocol.Interest;
import org.ndnx.ndn.protocol.SignedInfo;
import org.ndnx.ndn.protocol.SignedInfo.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ProxyKeepAlive extends Thread implements NDNInterestHandler {
	private static final Logger LOG = LoggerFactory.getLogger(ProxyKeepAlive.class);
	
	private MapServer server;
	private ArrayList<String> proxyList;
	private ArrayList<String> inactiveProxyList;
	private boolean running;
	private NDNHandle handle;
	private ContentName prefix;
	
	public ProxyKeepAlive(ArrayList<String> proxyList, MapServer server){
		this.server = server;
		this.proxyList = proxyList;
		this.inactiveProxyList = new ArrayList<String>();
		running = false;
		prefix = new ContentName("NDNProxy");
		prefix = prefix.append(new ContentName("GW_MS"));
		prefix = prefix.append(new ContentName("hello"));
		try{
			handle = NDNHandle.open();
			handle.registerFilter(prefix, this);
		}catch (ConfigurationException e) {
			e.printStackTrace();
		}catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void run() {
		// TODO Auto-generated method stub
		running = true;
		try{
			while(running){
				sleep(1000);
				Iterator<String> iterator = inactiveProxyList.iterator();
				while(iterator.hasNext()){
					String proxy = iterator.next();
					isAlive(proxy, true, iterator);
				}
				iterator = proxyList.iterator();
				while(iterator.hasNext()){
					String proxy = iterator.next();
					isAlive(proxy, false, iterator);
				}
			}
		}catch(Exception e){
			e.printStackTrace();
		}
		
		handle.unregisterFilter(prefix, this);
		handle.close();
	}
	
	private void isAlive(String proxy, boolean alreadyInactive, Iterator<String> iterator){
		ContentName name = new ContentName("NDNProxy").append(new ContentName(proxy));
		name = name.append(new ContentName("hello"));
		int count = 0;
		ContentObject content = null;
		while(count < 3){
			try{
				content = handle.get(name, 2000);
			}catch(IOException e){
				e.printStackTrace();
			}
			if(content == null){
				count++;
			}else{
				break;
			}
		}
		if(content == null && !alreadyInactive){
			LOG.info("EGWProxy is inactive: " + proxy);
			synchronized (proxyList) {
				iterator.remove();
			}
			inactiveProxyList.add(proxy);
		}else if(content != null && alreadyInactive){
			LOG.info("EGWProxy is active: " + proxy);
			iterator.remove();
			synchronized (proxyList) {
				proxyList.add(proxy);
			}
		}
	}

	public boolean handleInterest(Interest interest) {
		// TODO Auto-generated method stub
		String value = "";
		for(String proxy : inactiveProxyList){
			value = value + proxy + "\n";
		}
		SignedInfo signedInfo = new SignedInfo(server.publisherID, ContentType.DATA, server.keyLocator, 5, null);
		byte[] data = new byte[value.length()];
		data = value.getBytes();
		try {
			ContentObject content = new ContentObject(interest.name(), signedInfo, data, 0, data.length, server.key);
			handle.put(content);			
		} catch (InvalidKeyException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SignatureException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}	
		return true;
	}
}
