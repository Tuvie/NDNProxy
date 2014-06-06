package org.littleshoot.proxy.impl;

import org.ndnx.ndn.NDNContentHandler;
import org.ndnx.ndn.protocol.ContentObject;
import org.ndnx.ndn.protocol.Interest;

public class IGWReceiveData implements NDNContentHandler{
	@Override
	public Interest handleContent(ContentObject data, Interest interest){
		return null;
	}
}
