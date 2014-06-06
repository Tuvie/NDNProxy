package org.ndnMapServer.ndnMapServer;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.xml.DOMConfigurator;
import org.ndnx.ndn.NDNHandle;
import org.ndnx.ndn.NDNInterestHandler;
import org.ndnx.ndn.config.ConfigurationException;
import org.ndnx.ndn.protocol.ContentName;
import org.ndnx.ndn.protocol.ContentObject;
import org.ndnx.ndn.protocol.Interest;
import org.ndnx.ndn.protocol.KeyLocator;
import org.ndnx.ndn.protocol.MalformedContentNameStringException;
import org.ndnx.ndn.protocol.PublisherPublicKeyDigest;
import org.ndnx.ndn.protocol.SignedInfo;
import org.ndnx.ndn.protocol.SignedInfo.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MapServer implements NDNInterestHandler {
	private static final Logger LOG = LoggerFactory.getLogger(MapServer.class);
	
	private NDNHandle handle;
	private ContentName prefix;
	private String value;
	protected Key key;
	protected PublisherPublicKeyDigest publisherID;
	protected KeyLocator keyLocator;
	
	private String filename1 = "proxy_num.txt";
	private String filename = "proxy.txt";
	private File file = new File(filename);
	private File file1 = new File(filename1);
	ArrayList<String> proxyList = new ArrayList<String>();
	ConcurrentHashMap<String, String> proxyMap = new ConcurrentHashMap<String, String>();
	private LineNumberReader reader;
	private LineNumberReader reader1;
	
	public MapServer(){
		pollLog4JConfigurationFileIfAvailable();
	}
	
	public boolean start(){
		new ProxyKeepAlive(proxyList, this).start();
		try{
			handle = NDNHandle.open();
			LOG.info("Map Server started.");
			key = handle.keyManager().getDefaultSigningKey();
			publisherID = handle.keyManager().getDefaultKeyID();
			keyLocator = handle.keyManager().getDefaultKeyLocator();
		}catch (ConfigurationException e) {
			return false;
		}catch (IOException e) {
			return false;
		}	
		try{
			prefix = new ContentName("NDNProxy");
			prefix = prefix.append(new ContentName("GW_MS"));
			handle.registerFilter(new ContentName(prefix), this);
			return true;
		}catch (IOException e){
			return false;
		}
	}
	
	public boolean handleInterest(Interest interest){
		ContentName name = interest.name(); 
		if(name.count() < 4){
			return true;
		}
		String type = new String(name.component(2));
		String origin = new String(name.component(3));
		try {
			value = Transform.getMapValue(type, origin, this);
		} catch (NoSuchAlgorithmException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		LOG.info("Map: " + type + "/" + origin + "->" + value);
		
	    SignedInfo signedInfo = new SignedInfo(publisherID, ContentType.DATA, keyLocator, 30, null);
		byte[] data = new byte[value.length()];
		data = value.getBytes();
		try {
			ContentObject content = new ContentObject(name, signedInfo, data, 0, data.length, key);
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
	
	public void load() throws IOException {
		if(!file.exists()){
			file.createNewFile();
		}
		FileReader fileReader = new FileReader(file);
		reader = new LineNumberReader(fileReader);
		String s = reader.readLine();	
		while (null != s){
			int end = s.indexOf('#');
			if(end < 0){
				end = s.length();
			}
			s = s.substring(0, end).trim();
			String string[] = s.split(",");
			if(string.length == 2) {
				proxyMap.put(string[0], string[1]);
			}
			s = reader.readLine();
		}
		if(!file1.exists()){
			file1.createNewFile();
		}
		FileReader fileReader1 = new FileReader(file1);
		reader1 = new LineNumberReader(fileReader1);
		String s1 = reader1.readLine();	
		while(null != s1){
			int end = s1.indexOf('#');
			if(end < 0){
				end = s1.length();
			}
			s1 = s1.substring(0, end).trim();
			if(!s1.equals("")){
				proxyList.add(s1);
			}
		s1 = reader1.readLine();
		}	
	}
	
	
	public static void main(String[] args) throws MalformedContentNameStringException, IOException{
		MapServer mp = new MapServer();
		mp.load();
		mp.start();
	}	
	
	private static void pollLog4JConfigurationFileIfAvailable() {
        File log4jConfigurationFile = new File(
                "src/test/resources/log4j.xml");
        if (log4jConfigurationFile.exists()) {
            DOMConfigurator.configureAndWatch(
                    log4jConfigurationFile.getAbsolutePath(), 15);
        }
    }
}

