package org.littleshoot.proxy.impl;


import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.ndnx.ndn.NDNContentHandler;
import org.ndnx.ndn.NDNHandle;
import org.ndnx.ndn.config.ConfigurationException;
import org.ndnx.ndn.protocol.Component;
import org.ndnx.ndn.protocol.ContentName;
import org.ndnx.ndn.protocol.ContentObject;
import org.ndnx.ndn.protocol.Interest;
import org.ndnx.ndn.protocol.MalformedContentNameStringException;

public class AutonymToPseudo implements NDNContentHandler {
	private NDNHandle handle;
	private int timeout;
	private Map<ContentName,ContentName> responseMap;
	private Map<String, String> domainMap;
	private ContentName pseudo;
	Interest interest;
	
	public AutonymToPseudo(int timeout){
		this.timeout = timeout;		
		responseMap= new ConcurrentHashMap<ContentName,ContentName>();
		domainMap = new ConcurrentHashMap<String, String>();
	}
	
	public boolean open(){
		try{
			handle = NDNHandle.open();
//			LOG.info("Connect to ndnd.");
			return true;
		}catch (IOException e) {
//			LOG.error("IO exception initializing NDN library: " + e.getMessage());
			return false;
		}catch (ConfigurationException e) {
			// TODO: handle exception
			return false;
		}
	}
	
	public ContentName getProxy(String uri, Map<String, Integer> inactive) throws IOException{
		String EGW = domainMap.get(uri);
		if(EGW != null){
			if(inactive.keySet().contains(EGW)){
				domainMap.remove(uri);
			}
			return new ContentName("NDNProxy").append(new ContentName(EGW)	);
		}
		ContentName proxy = new ContentName("NDNProxy");
		proxy = proxy.append(new ContentName("GW_MS"));
//		System.out.println(uri);
		proxy = proxy.append(new ContentName("px")).append(new ContentName(uri));
		
//		System.out.println(proxy);
		
		interest = new Interest(proxy);
		ContentObject content = handle.get(interest, timeout);
		if(content == null){
			return null;
		}
		if(content.content().length == 0){
			return null;
		}
		EGW = Component.printNative(content.content());
		
		domainMap.put(uri, EGW);
		proxy = new ContentName("NDNProxy");
		proxy = proxy.append(new ContentName(EGW)); 
		return proxy;		
	}
	
	public ContentName getPseudo(ContentName name) throws MalformedContentNameStringException {
		try{
			if (responseMap.get(name) == null){
				pseudo = new ContentName("NDNProxy").append(new ContentName("GW_MS"));
				pseudo = pseudo.append(new ContentName("en")).append(name);
				interest = new Interest(pseudo);				
				ContentObject content = handle.get(interest, timeout);
				if(content == null){
					return null;
				}
				pseudo = new ContentName(Component.printNative(content.content()));
				responseMap.put(name, pseudo);				
			}
			else{
				pseudo = responseMap.get(name);
			}
		}catch(IOException e){
//			LOG.error("IO exception sending Interest: " + e.getMessage());
		}
		return pseudo;
	}
	
	public Interest handleContent(ContentObject data, Interest interest){		
		return interest;
	}
}
 