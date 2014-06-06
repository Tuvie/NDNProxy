package org.littleshoot.proxy.impl;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.SignatureException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import org.ndnx.ndn.NDNHandle;
import org.ndnx.ndn.NDNInterestHandler;
import org.ndnx.ndn.profiles.SegmentationProfile;
import org.ndnx.ndn.protocol.ContentName;
import org.ndnx.ndn.protocol.ContentObject;
import org.ndnx.ndn.protocol.Interest;
import org.ndnx.ndn.protocol.KeyLocator;
import org.ndnx.ndn.protocol.PublisherPublicKeyDigest;
import org.ndnx.ndn.protocol.SignedInfo;
import org.ndnx.ndn.protocol.SignedInfo.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EGWReturnData implements NDNInterestHandler{
	private static final Logger LOG = LoggerFactory.getLogger(EGWReturnData.class);
	private ByteBuf buf;
	private ByteBuf header;
	private NDNHandle handle;
	private ContentName prefix;
	private boolean last;
	private boolean start;
	boolean done;
	boolean active;
	private Interest firstInterest;
	private Key key;
	private PublisherPublicKeyDigest publisherID;
	private KeyLocator keyLocator;
	private EGWServer server;
//	private int repeatedTimes;
	private long current;
	private long until;
	private long end;
	
	public EGWReturnData(EGWServer server, NDNHandle handle, ContentName prefix, Key key, PublisherPublicKeyDigest publisherID, KeyLocator keyLocator) {
		this.server = server;
		buf = Unpooled.buffer();
		header = Unpooled.buffer();
		this.handle = handle;
		this.prefix = prefix;
		this.last = false;
		this.start = false;
		this.done = false;
		this.active = true;
		this.key = key;
		this.publisherID = publisherID;
		this.keyLocator = keyLocator;
//		repeatedTimes = 0;
		current = 0;
		end = -1;
		until = 0;
	}
	
	@Override
	public boolean handleInterest(Interest interest){
		ContentName name = interest.name();
		int seg = (int)SegmentationProfile.getSegmentNumber(name);
		SignedInfo signedInfo = new SignedInfo(publisherID, keyLocator);
		byte[] data = null;
		if(seg <= until){
		}else if(end > 0 && seg > end){
			signedInfo = new SignedInfo(publisherID, ContentType.NACK, keyLocator, 1, null);
			data = new byte[0];
			try{
				ContentObject content = new ContentObject(name, signedInfo, data, 0, 0, key);
				handle.put(content);
//				LOG.info("Interest timeout: " + name);
			}catch(InvalidKeyException e){
				LOG.warn("Invalid key.");
				return true;
			}catch(SignatureException e){
				LOG.warn("Signature exception");
				return true;
			}catch(IOException e){
				LOG.warn("Putting Content error." + name.toURIString());
				return true;
			}
		}else{
			until = seg;
			synchronized (this) {
				if(last){
					putUntil();
				}
			}
		}
		return true;
	}
	
	public boolean putContent(Interest interest){
		ContentName name = interest.name();
		int seg = (int)SegmentationProfile.getSegmentNumber(name);
		
		SignedInfo signedInfo = new SignedInfo(publisherID, keyLocator);
		int len = 0;
		byte[] data = null;
		if(seg == 0){
			if(start == true){
				data = new byte[header.readableBytes()];
				header.readBytes(data);
//					LOG.info("Sending Data: " + name.toURIString());
			}else{
				return true;
			}
		}else{
			if(end > 0 && seg > end){
				signedInfo = new SignedInfo(publisherID, ContentType.NACK, keyLocator, 1, null);
				data = new byte[0];
				try{
					ContentObject content = new ContentObject(name, signedInfo, data, 0, 0, key);
					handle.put(content);
					current++;
//						LOG.warn("Putting Content NACK " + seg + ": " + name.toURIString());
				}catch(InvalidKeyException e){
					LOG.warn("Invalid key.");
					return true;
				}catch(SignatureException e){
					LOG.warn("Signature exception");
					return true;
				}catch(IOException e){
					LOG.warn("Putting Content error." + name.toURIString());
					return true;
				}
				return true;
			}
			len = buf.readableBytes();
			if(len < 4096 && (end < 0 || seg < end)){
				return true;
			}
			
			if(seg == end && end > 0){
				byte [] finalBlockID = SegmentationProfile.getSegmentNumberNameComponent(seg);
				signedInfo.setFinalBlockID(finalBlockID);
				done = true;
				len = buf.readableBytes();
				data = new byte[len];
				buf.readBytes(data);
				current++;
			}else if (end < 0 || seg < end){
				data = new byte[4096];
				buf.readBytes(data);
				current++;
//					LOG.info("index: " + buf.readerIndex());
			}else{
				signedInfo = new SignedInfo(publisherID, ContentType.NACK, keyLocator, 2, null);
				data = new byte[0];
				try{
					ContentObject content = new ContentObject(name, signedInfo, data, 0, 0, key);
					handle.put(content);
//						LOG.info("Interest timeout: " + name);
				}catch(InvalidKeyException e){
					LOG.warn("Invalid key.");
					return true;
				}catch(SignatureException e){
					LOG.warn("Signature exception");
					return true;
				}catch(IOException e){
					LOG.warn("Putting Content error." + name.toURIString());
					return true;
				}
			}
		}
		
		try{
			active = true;
			ContentObject content = new ContentObject(name, signedInfo, 
					data, 0, data.length, key);
			handle.put(content);
//				LOG.info("Putting Data " + seg + ": " + name.toURIString());
		}catch(InvalidKeyException e){
			LOG.warn("Invalid key.");
			return true;
		}catch(SignatureException e){
			LOG.warn("Signature exception");
			return true;
		}catch(IOException e){
			LOG.warn("Putting Content error." + name.toURIString());
			return true;
		}
		
		if(done){
			header.release();
			buf.release();
			handle.unregisterFilter(prefix, this);
			server.pendingRequest.remove(name.parent().toURIString());
			server.activeReturnDatas.remove(this);
//				LOG.info("Putting Data final " + seg + ": " + name.toURIString());
		}
		
		return true;
	}
	
	public void writeChunk(ByteBuf data){
		if(!server.activeReturnDatas.containsKey(this)){
			return;
		}
		buf.writeBytes(data);
	}
	
	public void writeHeader(byte[] data){
		if(!server.activeReturnDatas.containsKey(this)){
			return;
		}
		header.writeBytes(data);
		start = true;
	}
	
	public void setFinal(){
		this.end = this.current + buf.readableBytes() / 4096 + 1;
		synchronized (this) {
			this.last = true;
			putUntil();
		}
	}
	
	public void setFirstInterest(Interest interest){
		this.firstInterest = interest;
	}
	
	public void sendFirstSeg(){
		if(!server.activeReturnDatas.containsKey(this)){
			return;
		}
		putContent(firstInterest);
	}
	
	public void putUntil(){
		if(!server.activeReturnDatas.containsKey(this)){
			return;
		}
		while(until > current){
			if(buf.readableBytes() >= 4096 || last){
				putContent(SegmentationProfile.segmentInterest(
						SegmentationProfile.segmentRoot(firstInterest.name()), current + 1, publisherID));
			}else{
				break;
			}
		}
	}
	
	public void release(){
		this.header.release();
		this.buf.release();
	}
}
