package org.ndnMapServer.ndnMapServer;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Transform {
	private static Map<String,String> autonymToAlias = new ConcurrentHashMap<String, String>();
	private static Map<String,String> aliasToAutonym = new ConcurrentHashMap<String, String>();

	public Transform(){
	}
	
	public static String getMapValue(String judge, String key, MapServer server) throws NoSuchAlgorithmException, IOException{
		
		String str = "stop";
		if(judge.equals("en")){
			if(autonymToAlias.get(key) != null){
				str = autonymToAlias.get(key);
			}else{
				str = Md5(key);
				autonymToAlias.put(key, str);
				aliasToAutonym.put(str, key);
			}
		}else if(judge.equals("de")){
			if(aliasToAutonym.get(key) != null){
				str = aliasToAutonym.get(key);
			}else{
				str= "";
			}
		}else if(judge.equals("px")){
			synchronized (server.proxyList) {
				if(server.proxyList.size() == 0){
					return "";
				}
				String suffix = key;
				str = null;
				str = server.proxyMap.get(key);
				while(str == null){
					if(suffix.indexOf('.') > 0){
						suffix = suffix.substring(suffix.indexOf('.') + 1);
					}else{
						break;
					}
					str = server.proxyMap.get(suffix);
				}
				if(str == null){
					String proxy_id = null;
					proxy_id = server.proxyList.get(Math.abs(key.hashCode() % server.proxyList.size()));
					str = proxy_id;
				}
			}
		}
		
		return str;
	}
	
	 private static String Md5 (String plainText ) throws NoSuchAlgorithmException { 
	
		MessageDigest md = MessageDigest.getInstance("MD5"); 
		md.update(plainText.getBytes()); 
		byte b[] = md.digest(); 
		int i; 
		StringBuffer buf = new StringBuffer(""); 
		for (int offset = 0; offset < b.length; offset++) { 
			i = b[offset]; 
			if(i<0) i+= 256; 
			if(i<16) buf.append("0"); 
			buf.append(Integer.toHexString(i)); 
		} 
		return buf.toString().substring(8,24);
	}

}