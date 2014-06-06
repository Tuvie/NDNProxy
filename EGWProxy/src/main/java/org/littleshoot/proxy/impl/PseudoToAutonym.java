package org.littleshoot.proxy.impl;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.ndnx.ndn.NDNContentHandler;
import org.ndnx.ndn.NDNHandle;
import org.ndnx.ndn.protocol.Component;
import org.ndnx.ndn.protocol.ContentName;
import org.ndnx.ndn.protocol.ContentObject;
import org.ndnx.ndn.protocol.Interest;
import org.ndnx.ndn.protocol.MalformedContentNameStringException;

public class PseudoToAutonym implements NDNContentHandler {
	private NDNHandle handle;
	private int timeout;
	private Map<ContentName,ContentName> responseMap;
	private ContentName autonym;
	Interest interest;
	
	public PseudoToAutonym(int timeout){
		this.timeout=timeout;
		responseMap= new ConcurrentHashMap<ContentName,ContentName>();
	}
	
	public boolean open() throws org.ndnx.ndn.config.ConfigurationException{
		try{
			handle = NDNHandle.open();
//			LOG.info("Connect to ndnd.");
			return true;
		}catch (IOException e) {
//			LOG.error("IO exception initializing NDN library: " + e.getMessage());
			return false;
		}
	}
	
	public ContentName getAutonym(ContentName name) throws MalformedContentNameStringException {
		try{
			if (responseMap.get(name) == null){
				autonym = new ContentName("NDNProxy").append(new ContentName("GW_MS"));
				autonym = autonym.append(new ContentName("de")).append(name);
				interest = new Interest(autonym);
				ContentObject content = handle.get(interest, timeout);
				if(content == null){
					return null;
				}
				autonym = new ContentName(Component.printNative(content.content()));
				responseMap.put(name, autonym);	
			}
			else{
				autonym = responseMap.get(name);
			}
		}catch(IOException e){
//			LOG.error("IO exception sending Interest: " + e.getMessage());
		}
		
		return autonym;
	}
	

	public Interest handleContent(ContentObject data, Interest interest){		
		return interest;
	}
}
 