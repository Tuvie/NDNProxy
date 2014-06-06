package org.littleshoot.proxy.impl;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.udt.nio.NioUdtProvider;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.UnknownHostException;
import java.nio.channels.spi.SelectorProvider;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.UnknownTransportProtocolError;
import org.ndnx.ndn.NDNHandle;
import org.ndnx.ndn.NDNInterestHandler;
import org.ndnx.ndn.config.ConfigurationException;
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

public class EGWServer implements NDNInterestHandler{
	static final Logger LOG = LoggerFactory.getLogger(EGWServer.class);
	protected NDNHandle handle;
	protected String prefix;
	protected Map<String, ServerConnection> connectionMap;
	protected Set<String> pendingRequest;
	ServerGroup serverGroup;
	protected Key key;
	protected PublisherPublicKeyDigest publisherID;
	protected KeyLocator keyLocator;
	private ExecutorService threadPool;
	Map<EGWReturnData, Integer> activeReturnDatas;
	protected boolean running;
	private PseudoToAutonym pta;
	boolean alone = false;
	
	public EGWServer(String configFile){
		File config = new File(configFile);
		Properties props = new Properties();
        if (config.isFile()) {
            InputStream is = null;
            try {
                is = new FileInputStream(config);
                props.load(is);
            } catch (final IOException e) {
                LOG.warn("Could not load props file?", e);
            } finally {
                IOUtils.closeQuietly(is);
            }
        }
		prefix = props.getProperty("prefix", "NDNProxy_Local");
		connectionMap = new ConcurrentHashMap<String, ServerConnection>();
		serverGroup = new ServerGroup("egw");
		pendingRequest = new HashSet<String>();
		threadPool = Executors.newFixedThreadPool(32);
		activeReturnDatas = new ConcurrentHashMap<EGWReturnData, Integer>();
		pta = new PseudoToAutonym(5000);
	}
	
	public boolean start(){
		try{
			running = true;
			handle = NDNHandle.open();
			pta.open();
			LOG.info("Connect to ndnd.");
			key = handle.keyManager().getDefaultSigningKey();
			publisherID = handle.keyManager().getDefaultKeyID();
			keyLocator = handle.keyManager().getDefaultKeyLocator();
			new CancelReturnDataThread(this).start();
			new KeepAliveThread(this).start();
			new KeepAlive(this).start();
		}catch (ConfigurationException e) {
			LOG.error("Configuration exception initializing NDN library: " + e.getMessage());
			return false;
		}catch (IOException e) {
			LOG.error("IO exception initializing NDN library: " + e.getMessage());
			return false;
		}	
		try{
			handle.registerFilter(new ContentName("NDNProxy").append(new ContentName(prefix)), this);
			LOG.info("Register name prefix: " + prefix);
			return true;
		}catch (IOException e){
			LOG.error("IO exception register prefix: " + prefix);
			return false;
		}
	}
	
	public void close(){
		running = false;
	}
	
	@Override
	public boolean handleInterest(Interest interest){
		ContentName name = interest.name();
		
		if(SegmentationProfile.getSegmentNumber(name) > 0){
			putNACK(name);
			return true;
		}
		
//		LOG.info("Receive Interest: " + name.toURIString());
		
		HttpRequest request;
		try{
			request = TransUtils.tranInterest(name, pta, alone);
		}catch(Exception e){
			putNACK(name);
			return true;
		}
		
		if(request == null){
			putNACK(name);
			return true;
		}
	
		if(pendingRequest.contains(name.parent().toURIString())){
			return true;
		}
		pendingRequest.add(name.parent().toURIString());
		threadPool.execute(new ServerThread(interest, this, LOG, name, request) );
		return true;
	}
		
	
	protected EventLoopGroup getProxyToServerWorkerFor(
            TransportProtocol transportProtocol) {
        return this.serverGroup.proxyToServerWorkerPools.get(transportProtocol);
    }
	
	protected void registerChannel(Channel channel) {
        this.serverGroup.allChannels.add(channel);
    }
	
	protected void putNACK(ContentName name) {
		SignedInfo signedInfo = new SignedInfo(publisherID, ContentType.NACK, keyLocator, 1, null);
		byte[] data = new byte[0];
		try{
			ContentObject content = new ContentObject(name, signedInfo, data, 0, 0, key);
			handle.put(content);
//			LOG.warn("Putting Content NACK " + seg + ": " + name.toURIString());
		}catch(InvalidKeyException e){
			LOG.warn("Invalid key.");
		}catch(SignatureException e){
			LOG.warn("Signature exception");
		}catch(IOException e){
			LOG.warn("Putting Content error." + name.toURIString());
		}
	}
	
	private static class ServerGroup {
        private static final int INCOMING_ACCEPTOR_THREADS = 2;
        private static final int INCOMING_WORKER_THREADS = 0;
        private static final int OUTGOING_WORKER_THREADS = 8;

        /**
         * A name for this ServerGroup to use in naming threads.
         */
        private final String name;

        /**
         * Keep track of all channels for later shutdown.
         */
        private final ChannelGroup allChannels = new DefaultChannelGroup(
                "HTTP-Proxy-Server", GlobalEventExecutor.INSTANCE);

        /**
         * These {@link EventLoopGroup}s accept incoming connections to the
         * proxies. A different EventLoopGroup is used for each
         * TransportProtocol, since these have to be configured differently.
         */
        private final Map<TransportProtocol, EventLoopGroup> clientToProxyBossPools = new ConcurrentHashMap<TransportProtocol, EventLoopGroup>();

        /**
         * These {@link EventLoopGroup}s process incoming requests to the
         * proxies. A different EventLoopGroup is used for each
         * TransportProtocol, since these have to be configured differently.
         */
        private final Map<TransportProtocol, EventLoopGroup> clientToProxyWorkerPools = new ConcurrentHashMap<TransportProtocol, EventLoopGroup>();

        /**
         * These {@link EventLoopGroup}s are used for making outgoing
         * connections to servers. A different EventLoopGroup is used for each
         * TransportProtocol, since these have to be configured differently.
         */
        private final Map<TransportProtocol, EventLoopGroup> proxyToServerWorkerPools = new ConcurrentHashMap<TransportProtocol, EventLoopGroup>();

        private volatile boolean stopped = false;

        private ServerGroup(String name) {
            this.name = name;
            // Set up worker pools for each transport protocol
            for (TransportProtocol transportProtocol : TransportProtocol
                    .values()) {
                try {
                    initializeTransport(transportProtocol);
                } catch (Throwable t) {
                    LOG.warn("Unable to initialize transport protocol {}: {}",
                            transportProtocol,
                            t.getMessage(),
                            t);
                }
            }

            Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
                public void uncaughtException(final Thread t, final Throwable e) {
                    LOG.error("Uncaught throwable", e);
                }
            });

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                public void run() {
                    stop();
                }
            }));
        }

        private void initializeTransport(TransportProtocol transportProtocol) {
            SelectorProvider selectorProvider = null;
            switch (transportProtocol) {
            case TCP:
                selectorProvider = SelectorProvider.provider();
                break;
            case UDT:
                selectorProvider = NioUdtProvider.BYTE_PROVIDER;
                break;
            default:
                throw new UnknownTransportProtocolError(transportProtocol);
            }

            NioEventLoopGroup inboundAcceptorGroup = new NioEventLoopGroup(
                    INCOMING_ACCEPTOR_THREADS,
                    new CategorizedThreadFactory("ClientToProxyAcceptor"),
                    selectorProvider);
            NioEventLoopGroup inboundWorkerGroup = new NioEventLoopGroup(
                    INCOMING_WORKER_THREADS,
                    new CategorizedThreadFactory("ClientToProxyWorker"),
                    selectorProvider);
            inboundWorkerGroup.setIoRatio(90);
            NioEventLoopGroup outboundWorkerGroup = new NioEventLoopGroup(
                    OUTGOING_WORKER_THREADS,
                    new CategorizedThreadFactory("ProxyToServerWorker"),
                    selectorProvider);
            outboundWorkerGroup.setIoRatio(90);
            this.clientToProxyBossPools.put(transportProtocol,
                    inboundAcceptorGroup);
            this.clientToProxyWorkerPools.put(transportProtocol,
                    inboundWorkerGroup);
            this.proxyToServerWorkerPools.put(transportProtocol,
                    outboundWorkerGroup);
        }

        synchronized private void stop() {
            LOG.info("Shutting down proxy");
            if (stopped) {
                LOG.info("Already stopped");
                return;
            }

            LOG.info("Closing all channels...");

            final ChannelGroupFuture future = allChannels.close();
            future.awaitUninterruptibly(10 * 1000);

            if (!future.isSuccess()) {
                final Iterator<ChannelFuture> iter = future.iterator();
                while (iter.hasNext()) {
                    final ChannelFuture cf = iter.next();
                    if (!cf.isSuccess()) {
                        LOG.info(
                                "Unable to close channel.  Cause of failure for {} is {}",
                                cf.channel(),
                                cf.cause());
                    }
                }
            }

            LOG.info("Shutting down event loops");
            List<EventLoopGroup> allEventLoopGroups = new ArrayList<EventLoopGroup>();
            allEventLoopGroups.addAll(clientToProxyBossPools.values());
            allEventLoopGroups.addAll(clientToProxyWorkerPools.values());
            allEventLoopGroups.addAll(proxyToServerWorkerPools.values());
            for (EventLoopGroup group : allEventLoopGroups) {
                group.shutdownGracefully();
            }

            for (EventLoopGroup group : allEventLoopGroups) {
                try {
                    group.awaitTermination(60, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    LOG.warn("Interrupted while shutting down event loop");
                }
            }

            stopped = true;

            LOG.info("Done shutting down proxy");
        }

        private class CategorizedThreadFactory implements ThreadFactory {
            private String category;
            private int num = 0;

            public CategorizedThreadFactory(String category) {
                super();
                this.category = category;
            }

            public Thread newThread(final Runnable r) {
                final Thread t = new Thread(r,
                        name + "-" + category + "-" + num++);
                return t;
            }
        }
    }
}

class ServerConnection{
	private Queue<ProxyToServerConnection> connections;
	private String serverHostAndPort;
	
	ServerConnection(String serverHostAndPort) {
//		this.connections = new ProxyToServerConnection[5];
		this.connections = new LinkedList<ProxyToServerConnection>();
		this.serverHostAndPort = serverHostAndPort;
	}
	
	protected ProxyToServerConnection useConnection(HttpRequest initialHttpRequest, EGWServer egwServer) throws UnknownHostException{
		ProxyToServerConnection connection;
		synchronized(connections){
			connection = connections.poll();
			if (connection == null){
				connection = ProxyToServerConnection.create(serverHostAndPort, initialHttpRequest, egwServer);
				connections.offer(connection);
			}else if(!connection.done){
				connections.offer(connection);
				connection = ProxyToServerConnection.create(serverHostAndPort, initialHttpRequest, egwServer);
				connections.offer(connection);
			}
		}
				
		return connection;
	}
}

class ServerThread implements Runnable{
	Interest interest;
	ContentName name;
	HttpRequest request;
	EGWServer egwServer;
	
	public ServerThread(Interest interest, EGWServer server, Logger LOG, ContentName name, HttpRequest request){
		this.interest = interest;
		this.egwServer = server;
		this.name = name;
		this.request = request;
	}
	
	@Override
	public void run() {
		String serverHostAndPort = ProxyUtils.parseHostAndPort(request);
        if (StringUtils.isBlank(serverHostAndPort)) {
            List<String> hosts = request.headers().getAll(
                    HttpHeaders.Names.HOST);
            if (hosts != null && !hosts.isEmpty()) {
            	serverHostAndPort = hosts.get(1);
            }
        }
        
        EGWServer.LOG.debug("Ensuring that hostAndPort are available in {}",
                request.getUri());
        if (serverHostAndPort == null || StringUtils.isBlank(serverHostAndPort)) {
        	EGWServer.LOG.warn("No host and port found in {}", request.getUri());
    		egwServer.putNACK(name);
    		egwServer.pendingRequest.remove((name.parent().toURIString()));
    		return;
        }

        EGWServer.LOG.debug("Finding ProxyToServerConnection for: {}", serverHostAndPort);

        ServerConnection serverConnection = egwServer.connectionMap.get(serverHostAndPort);
        if(serverConnection == null){
        	serverConnection = new ServerConnection(serverHostAndPort);
        	egwServer.connectionMap.put(serverHostAndPort, serverConnection);
        }
        ProxyToServerConnection currentServerConnection;
    	
        try {
        	currentServerConnection = serverConnection.useConnection(request, egwServer);
            if (currentServerConnection == null) {
            	EGWServer.LOG.warn("Unable to create server connection, probably no chained proxies available");
                egwServer.putNACK(name);
                egwServer.pendingRequest.remove((name.parent().toURIString()));
                return;
            }
            // Remember the connection for later
        } catch (UnknownHostException e) {
        	EGWServer.LOG.warn("Bad Host {}", request.getUri());
            egwServer.putNACK(name);
            egwServer.pendingRequest.remove((name.parent().toURIString()));
            return;
        }

        TransUtils.removeHost(request);
//        LOG.info("Sending request:" + request.getUri());
        
    	
		EGWReturnData proxyToNDN = new EGWReturnData(egwServer, egwServer.handle, SegmentationProfile.segmentRoot(name), egwServer.key, egwServer.publisherID, egwServer.keyLocator);
		try{
			proxyToNDN.setFirstInterest(interest);
			egwServer.handle.registerFilter(SegmentationProfile.segmentRoot(name), proxyToNDN);
		}catch (IOException e){
			EGWServer.LOG.warn("IO exception register prefix: " + SegmentationProfile.segmentRoot(name));
			egwServer.putNACK(name);
			egwServer.pendingRequest.remove((name.parent().toURIString()));
			return;
		}
		
		egwServer.activeReturnDatas.put(proxyToNDN, 0);
        currentServerConnection.writeRightAway(request, proxyToNDN);
        return;
	}
}

class CancelReturnDataThread extends Thread{
	private EGWServer server;
	
	public CancelReturnDataThread(EGWServer server){
		this.server = server;
	}
	
	@Override
	public void run(){
		try{
			while(server.running){
				sleep(20000);
				for(EGWReturnData returnData : server.activeReturnDatas.keySet()){
					if(!returnData.active){
						returnData.release();
						server.activeReturnDatas.remove(returnData);
					}else{
						returnData.active = false;
					}
				}
			}
		}catch(Exception e){
			e.printStackTrace();
		}
	}
}

class KeepAlive implements NDNInterestHandler{
	NDNHandle handle;
	ContentName prefix;
	EGWServer server;
	
	public KeepAlive(EGWServer server){
		this.server = server;
		this.prefix = new ContentName("NDNProxy").append(new ContentName(server.prefix));
	}
	
	public void start(){
		try{
			handle = NDNHandle.open();
			prefix = prefix.append(new ContentName("hello"));
			handle.registerFilter(prefix, this);
		}catch (ConfigurationException e) {
			e.printStackTrace();
		}catch (IOException e) {
			e.printStackTrace();
		}	
	}
	
	public void close(){
		handle.unregisterFilter(prefix, this);
		handle.close();
	}
	
	@Override
	public boolean handleInterest(Interest interest){
		SignedInfo signedInfo = new SignedInfo(server.publisherID, ContentType.DATA, server.keyLocator, 1, null);
		byte[] data = new byte[0];
		try {
			ContentObject content = new ContentObject(interest.name(), signedInfo, data, 0, 0, server.key);
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

class KeepAliveThread extends Thread{
	EGWServer server;
	
	public KeepAliveThread(EGWServer server){
		this.server = server;
	}
	
	@Override
	public void run(){
		try{
			while(server.running){
				sleep(5000);
				ContentName name = new ContentName("NDNProxy").append(new ContentName("GW_MS"));
				name = name.append(new ContentName("hello"));
				ContentObject content = server.handle.get(name, 2000);
				int count = 0;
				while(content == null && count < 3){
					content = server.handle.get(name, 2000);
					count++;
				}
				if(content == null){
					if(!server.alone){
						server.alone = true;
						EGWServer.LOG.info("Cannot connect to Map Server.");
					}
				}else{
					if(server.alone){
						server.alone = false;
						EGWServer.LOG.info("Connect to Map Server again.");
					}
				}
			}
		}catch (Exception e){
			e.printStackTrace();
		}
	}
}
