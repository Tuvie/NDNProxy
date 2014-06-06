package org.littleshoot.proxy.impl;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.ndnx.ndn.NDNContentHandler;
import org.ndnx.ndn.NDNHandle;
import org.ndnx.ndn.config.ConfigurationException;
import org.ndnx.ndn.profiles.SegmentationProfile;
import org.ndnx.ndn.protocol.ContentName;
import org.ndnx.ndn.protocol.ContentObject;
import org.ndnx.ndn.protocol.Interest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class NDNAgent implements NDNContentHandler {
	static final Logger LOG = LoggerFactory.getLogger(NDNAgent.class);
	
	NDNHandle handle;
	private Map<ContentName, ArrayList<ClientToProxyConnection>> responseMap;
	private Map<ContentName, HttpRequest> requestMap;
	private Map<ContentName, SlideWindow> windowMap;
	boolean running = false;
	boolean alone = false;
	Map<Interest, Integer> pendingInterestSet;
	Map<String, Integer> inactiveProxies;
	
	public NDNAgent(){
		responseMap = new ConcurrentHashMap<ContentName, ArrayList<ClientToProxyConnection>>();
		requestMap = new ConcurrentHashMap<ContentName, HttpRequest>();
		windowMap = new ConcurrentHashMap<ContentName, SlideWindow>();
		pendingInterestSet = new ConcurrentHashMap<Interest, Integer>();
		inactiveProxies = new ConcurrentHashMap<String, Integer>();
	}
	
	public boolean open(){
		try{
			handle = NDNHandle.open();
			LOG.info("Connect to ndnd.");
			running = true;
			new CancelInterestThread(this).start();
			new KeepAliveThread(this).start();
			return true;
		}catch (ConfigurationException e) {
			LOG.error("Configuration exception initializing NDN library: " + e.getMessage());
			return false;
		}catch (IOException e) {
			LOG.error("IO exception initializing NDN library: " + e.getMessage());
			return false;
		}
	}

	public void close(){
		running = false;
		LOG.info("Disconnect to ndnd.");
		handle.close();
	}
	
	public void getData(ContentName name, ClientToProxyConnection connection, HttpRequest request){
		try{
			Interest interest =	SegmentationProfile.firstSegmentInterest(name, null);
			if(requestMap.get(name) != null){
				return;
			}
			windowMap.put(name, new SlideWindow());
			byte[] lifetime = new byte[1];
			lifetime[0] = 2;
			interest.interestLifetime(lifetime);
			synchronized (pendingInterestSet) {
				pendingInterestSet.put(interest, 0);
			}
			handle.expressInterest(interest, this);
			if (responseMap.get(name) == null){
				responseMap.put(name, new ArrayList<ClientToProxyConnection>());
			}
			responseMap.get(name).add(connection);
//			LOG.info("request: " + request.toString());
			requestMap.put(name, request);
			LOG.info("Sending Interest: " + name.toString());
		}catch(IOException e){
			LOG.error("IO exception sending Interest: " + e.getMessage());
		}
	}
	
	@Override
	public Interest handleContent(ContentObject data, Interest interest){
		ContentName name = data.name();
//		LOG.info("Receive Interest: " + name);
		ContentName root = SegmentationProfile.segmentRoot(name);
		
		synchronized (pendingInterestSet) {
			pendingInterestSet.remove(interest);
		}
		
		long seg = SegmentationProfile.getSegmentNumber(name);
		
		if(data.isNACK()){
			if(seg == 0){
//				LOG.info("Interest time out: " + name);
				ArrayList<ClientToProxyConnection> connections = responseMap.get(root);
				for(ClientToProxyConnection connection : connections){
					connection.disconnect();
				}
				connections.clear();
				responseMap.remove(root);
				requestMap.remove(root);
				windowMap.get(root).release();
				windowMap.remove(root);
				return null;
			}else{
				return null;
			}
		}
//		LOG.info("Receiving chunk " + seg + ": " + name.toURIString());
		ArrayList<ClientToProxyConnection> connections = responseMap.get(root);
		HttpRequest request = requestMap.get(root);
		if(connections == null){
			responseMap.remove(root);
			requestMap.remove(root);
			windowMap.remove(root);
			return null;
		}
		HttpResponse response = null;
		HttpContent content = null;
		HttpObject object = null;
		
		if(seg == 0){
			response = TransUtils.decodeHttpResponse(new String(data.content()));
			object = response;
//			LOG.info("Receiving Data: " + response);
		}else{
			content = new DefaultHttpContent(Unpooled.copiedBuffer(data.content()));
			object = content;
			
		}
		
//		LOG.info("Receiving chunk " + seg + ": " + name.toURIString());
		SlideWindow window = windowMap.get(root);
		long last = window.getLast();
		List<HttpObject> objects = window.getData((int)seg, object);
		
		int k = objects.size();
		if(Arrays.equals(data.signedInfo().getFinalBlockID(), name.lastComponent())){
			last = seg;
			window.setLast(last);
		}
		boolean finalSeg = false;
		for(int i = 0; i < k; i++){
			HttpObject o = objects.get(i);
			for(ClientToProxyConnection connection : connections){
				if(last == seg + i){
					connection.writeNDNHttp(o, request, true, seg);
					finalSeg = true;
				}else{
					connection.writeNDNHttp(o, request, false, seg);
				}
			}
		}
		
		if(finalSeg){
			connections.clear();
			responseMap.remove(root);
			requestMap.remove(root);
			windowMap.get(root).release();
			windowMap.remove(root);
			LOG.info("Receiving final chunk " + seg + ": " + name.toURIString());
			return null;
		}
		
		long nextSeg = window.getNext();
		if(nextSeg > last && last > 0){
			return null;
		}
		
		if(window.extraInterest()){
			Interest nextInterest = SegmentationProfile.segmentInterest(root, nextSeg, null);
			try{
				pendingInterestSet.put(nextInterest, 0);
				handle.expressInterest(nextInterest, this);
//				LOG.info("Sending Interest: " + nextInterest.name().toURIString());
			}catch(IOException e){
				LOG.error("IO exception sending Interest: " + e.getMessage());
			}
			nextSeg = window.getNext();
		}
		
		Interest nextInterest = SegmentationProfile.segmentInterest(root, nextSeg, null);
		try{
			pendingInterestSet.put(nextInterest, 0);
			handle.expressInterest(nextInterest, this);
//			LOG.info("Sending Interest: " + nextInterest.name().toURIString());
		}catch(IOException e){
			LOG.error("IO exception sending Interest: " + e.getMessage());
		}
//		LOG.info("Sending Interest: " + nextInterest.name().toURIString());
		return null;
	}
	
	void timeout(Interest interest){
		responseMap.remove(interest.name());
		requestMap.remove(interest.name());
		windowMap.remove(interest.name());
		if(SegmentationProfile.getSegmentNumber(interest.name()) == 0){
			LOG.info("Interest time out: " + interest.name().toURIString());
		}
	}
}

class SlideWindow{
	private Map<Integer, HttpObject> data;
	private int windowSize;
	private int currentSeg;
	private int nextSeg;
	private final int maxWindowSize = 100;
	private int lastSeg;
	
	SlideWindow() {
		data = new ConcurrentHashMap<Integer, HttpObject>();
		windowSize = 1;
		currentSeg = 0;
		nextSeg = 0;
		lastSeg = -1;
	}
	
	List<HttpObject> getData(int seg, HttpObject object){
		List<HttpObject> result = new ArrayList<HttpObject>();
		if(windowSize < maxWindowSize){
			windowSize++;
		}
		if(seg == currentSeg){
			currentSeg++;
			result.add(object);
			object = data.get(currentSeg);
			while(object != null){
				result.add(object);
				data.remove(currentSeg++);
				object = data.get(currentSeg);
			}
		}else{
			data.put(seg, object);
		}
		return result;
	}
	
	boolean extraInterest(){
		if(windowSize >= maxWindowSize){
			return false;
		}
		return true;
	}
	
	long getNext(){
		return ++nextSeg;
	}
	
	void release(){
		data.clear();
	}
	
	void setLast(long last){
		this.lastSeg = (int)last;
	}
	
	int getLast(){
		return this.lastSeg;
	}

}

class CancelInterestThread extends Thread{
	private Set<Interest> dangerousInterestSet;
	private NDNAgent agent;
	
	public CancelInterestThread(NDNAgent agent){
		this.agent = agent;
		dangerousInterestSet = new HashSet<Interest>();
	}
	
	@Override
	public void run(){
		try{
			while(agent.running){
				sleep(20000);
				for(Interest interest: dangerousInterestSet){
					if(agent.pendingInterestSet.keySet().contains(interest)){
						agent.pendingInterestSet.remove(interest);
						agent.handle.cancelInterest(interest, agent);
						agent.timeout(interest);
					}
				}
				dangerousInterestSet.clear();
				for(Interest interest : agent.pendingInterestSet.keySet()){
					dangerousInterestSet.add(interest);
				}
			}
		}catch (Exception e){
			e.printStackTrace();
		}
	}
}

class KeepAliveThread extends Thread{
	NDNAgent agent;
	
	public KeepAliveThread(NDNAgent agent){
		this.agent = agent;
	}
	
	@Override
	public void run(){
		try{
			while(agent.running){
				sleep(10000);
				ContentName name = new ContentName("NDNProxy");
				name = name.append(new ContentName("GW_MS"));
				name = name.append(new ContentName("hello"));
				ContentObject content = agent.handle.get(name, 2000);
				int count = 0;
				while(content == null && count < 3){
					content = agent.handle.get(name, 2000);
					count++;
				}
				if(content == null){
					if(!agent.alone){
						agent.alone = true;
						NDNAgent.LOG.info("Cannot connect to Map Server.");
					}
				}else{
					agent.inactiveProxies.clear();
					getEGW(new String(content.content()));
					if(agent.alone){
						agent.alone = false;
						NDNAgent.LOG.info("Connect to Map Server again.");
					}
				}
			}
		}catch (Exception e){
			e.printStackTrace();
		}
	}
	
	public void getEGW(String proxies){
		String[] EGWs = proxies.split("\n");
		for(String EGW : EGWs){
			if(!EGW.equals("") && agent.inactiveProxies.get(EGW) == null){
				agent.inactiveProxies.put(EGW, 0);
			}
		}
	}
}
