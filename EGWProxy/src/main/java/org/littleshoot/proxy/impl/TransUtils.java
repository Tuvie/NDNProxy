package org.littleshoot.proxy.impl;

import java.net.URISyntaxException;
import java.util.List;
import java.util.Map.Entry;

import org.ndnx.ndn.config.ConfigurationException;
import org.ndnx.ndn.protocol.ContentName;
import org.ndnx.ndn.protocol.MalformedContentNameStringException;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;

public class TransUtils {
	
	public static HttpRequest tranInterest(ContentName name, PseudoToAutonym pta, boolean alone) throws MalformedContentNameStringException, ConfigurationException, URISyntaxException{
		ContentName pseudo= new ContentName();
		String url = new String(name.component(2));
		int count = name.count() - 1;
		int cookielocate = 0;
		boolean cookie = false;
		int end = url.indexOf("/", 7);
		String host = url.substring(7, end);
		
		//new		
		HttpRequest request;
		if(new String(name.component(name.count()-2)).equals("POST")){	
			request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, url);
			count = count - 1;
		}else{
			request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, url);
		}
		
		try{
			request.headers().set("Host", host);
		}catch(Exception e){
			return null;
		}

		//new 
		//detect cookie 		
		if(new String(name.component(count-1)).contains("Cookie")){
			count = count - 1;
			cookie = true;
			cookielocate = count;
		}else{
			cookie = false;
		}
		
		//new
		//detect If-None-Match
		if(new String(name.component(count-1)).contains("Matchs")){	
			count = count - 1;
			request.headers().set("If-None-Match", new String(name.component(count)).replace("\\\\", "\"").substring(7));
		}
		
		//new
		//detect If-Modified-Since
		if(new String(name.component(count-1)).contains("Modify")){
			count = count - 1;
			request.headers().set("If-Modified-Since", new String(name.component(count)).substring(7));
		}
		
		for(int i = 3; i < count ; i++){
			
		    if(!(new String(name.component(i)).contains("::"))) {
		    	if(!alone){
		    		pseudo = pta.getAutonym(new ContentName(name.component(i)));
		    	}else{
		    		pseudo = null;
		    	}
		    	
		    	if(pseudo == null){
		    		return request;
		    	}
		    }else{
		    	pseudo = new ContentName(name.component(i));
		    }
		    
			String[] field = new String(pseudo.component(0)).split("::");
			if(field[0].equals("Agent")){
				request.headers().set("User-Agent", field[1]);
			}else if(field[0].equals("Language")){
				request.headers().set("Accept-Language", field[1]);
			}else if(field[0].equals("Charset")){
				request.headers().set("Accept-Charset", field[1]);
			}else if(field[0].equals("Encoding")){
				request.headers().set("Accept-Encoding", field[1]);
			}else if(field[0].equals("Range")){
				request.headers().set("Accept-Range", field[1]);
			}
		}
		
		//new
		//add cookie into request packet
		if(cookie == true){
			String cookieString = new String(name.component(cookielocate));
			request.headers().set("Cookie", cookieString.substring(7));
		}
		return request;
	}
	
	public static String encodeHttpResponse(HttpResponse response){
		StringBuffer buffer = new StringBuffer("");
		buffer.append(response.getProtocolVersion() + "\n");
		buffer.append(response.getStatus().code() + "\n");
		buffer.append(response.getStatus().reasonPhrase() + "\n");
		List<Entry<String, String>> headers = response.headers().entries();
		for (Entry<String, String> entry : headers){
			buffer.append(entry.getKey() + "\n");
			buffer.append(entry.getValue() + "\n");
		}
		return new String(buffer);
	}
	
	public static void removeHost(HttpRequest request){
		String url = request.getUri();
		int end = url.indexOf("/", 7);
		String path = url.substring(end);
		request.setUri(path);
	}
}
