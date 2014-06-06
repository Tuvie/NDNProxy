package org.littleshoot.proxy.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.ndnx.ndn.config.ConfigurationException;
import org.ndnx.ndn.protocol.ContentName;
import org.ndnx.ndn.protocol.MalformedContentNameStringException;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

public class TransUtils {
	
	public static boolean needTran(HttpRequest request){
		if(request.getMethod() != HttpMethod.GET){
			return false;
		}
		HttpHeaders headers = request.headers();
		Iterator<Entry<String, String>> iterator = headers.iterator();
		while(iterator.hasNext()){
			Entry<String, String> headerField = iterator.next();
			if (headerField.getKey().indexOf("Cookie") == 0) {
				return false;
			}else if (headerField.getKey().indexOf("If-None_Match") == 0){
				return false;
			}else if (headerField.getKey().indexOf("If-Modified-Since") == 0){
				return false;
			}
		}
		return true;
	}
	
	public static ContentName transRequest(HttpRequest request, AutonymToPseudo atp, Map<String, Integer> inactive, boolean alone) throws ConfigurationException, MalformedContentNameStringException, IOException{
		
		String[] domain = request.getUri().split("/");
		ContentName name = null;
		if(!alone){
			name = atp.getProxy(domain[2], inactive);
		}
		if(name == null){
			name = new ContentName("NDNProxy");
			name = name.append(new ContentName("NDNProxy_Local"));
		}
		name = name.append(new ContentName(request.getUri()));
		ContentName transform;
		HttpHeaders headers = request.headers();
		String valueString;
		
		valueString = headers.get("User-Agent");
		if(valueString != null){
			transform = new ContentName("Agent::" + valueString);
			if(!alone){
				ContentName suffix = atp.getPseudo(transform);
				if(suffix == null){
					name = name.append(transform);
				}else{
					name = name.append(suffix);
				}
			}else{
				name = name.append(transform);
			}
		}
		
		valueString = headers.get("Accept-Language");
		if(valueString != null){
			transform = new ContentName("Language::" + valueString);
			if(!alone){
				ContentName suffix = atp.getPseudo(transform);
				if(suffix == null){
					name = name.append(transform);
				}else{
					name = name.append(suffix);
				}
			}else{
				name = name.append(transform);
			}
		}
		
		valueString = headers.get("Accept-Charset");
		if(valueString != null){
			transform = new ContentName("Charset::" + valueString);
			if(!alone){
				ContentName suffix = atp.getPseudo(transform);
				if(suffix == null){
					name = name.append(transform);
				}else{
					name = name.append(suffix);
				}
			}else{
				name = name.append(transform);
			}
		}
		
		valueString = headers.get("Accept-Encoding");
		if(valueString != null){
			transform = new ContentName("Encoding::" + valueString);
			if(!alone){
				ContentName suffix = atp.getPseudo(transform);
				if(suffix == null){
					name = name.append(transform);
				}else{
					name = name.append(suffix);
				}
			}else{
				name = name.append(transform);
			}
		}
		
		return name;
	}
	
	public static HttpResponse decodeHttpResponse(String data){
		BufferedReader reader = new BufferedReader(new StringReader(data));
		HttpResponse response = null;
		try{
			response = new DefaultHttpResponse(
					new HttpVersion(reader.readLine(), true),
					new HttpResponseStatus(Integer.parseInt(reader.readLine()), reader.readLine()));
			HttpHeaders headers = response.headers();
			String field;
			while((field = reader.readLine()) != null){
				headers.add(field, reader.readLine());
			}
			reader.close();
		}catch(IOException e){
		}
		
		return response;
	}
}


class ObjectData{
	HttpObject object;
	boolean last;
	
	public ObjectData(HttpObject object, boolean last) {
		this.object = object;
		this.last = last;
	}
}
