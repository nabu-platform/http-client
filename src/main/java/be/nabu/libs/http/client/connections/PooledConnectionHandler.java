/*
* Copyright (C) 2015 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package be.nabu.libs.http.client.connections;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.http.api.client.ConnectionHandler;
import be.nabu.libs.http.api.client.Proxy;
import be.nabu.libs.http.api.client.ProxyBypassFilter;

public class PooledConnectionHandler implements ConnectionHandler {
	
	private SSLContext secureContext;
	
	private Map<String, SocketHandler> socketHandlers = new HashMap<String, SocketHandler>();
	
	private int maxAmountOfConnectionsPerTarget;
	private Proxy proxy;
	private int connectionTimeout = 10*1000*60, socketTimeout = 10*1000*60;
	private List<ProxyBypassFilter> proxyBypass;
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	public PooledConnectionHandler(SSLContext secureContext, int maxAmountOfConnectionsPerTarget) {
		this.secureContext = secureContext;
		this.maxAmountOfConnectionsPerTarget = maxAmountOfConnectionsPerTarget;
	}
	
	public PooledConnectionHandler setProxy(Proxy proxy, ProxyBypassFilter...proxyBypass) {
		this.proxy = proxy;
		this.proxyBypass = Arrays.asList(proxyBypass);
		return this;
	}

	public SSLContext getSecureContext() {
		return secureContext;
	}
	
	private boolean useProxy(String host, int port) {
		if (proxyBypass != null) {
			for (ProxyBypassFilter filter : proxyBypass) {
				if (filter.bypass(host, port))
					return false;
			}
		}
		return proxy != null;
	}
	
	@Override
	public Socket connect(String host, int port, boolean secure) throws IOException {
		String key = host + ":" + port;
		if (!socketHandlers.containsKey(key)) {
			synchronized(socketHandlers) {
				if (!socketHandlers.containsKey(key)) {
					socketHandlers.put(key, new SocketHandler(maxAmountOfConnectionsPerTarget, host, port));
				}
			}
		}
		Socket freeSocket = null; 
		while (freeSocket == null && !Thread.currentThread().isInterrupted()) {
			freeSocket = socketHandlers.get(key).getFreeSocket(secure);
		}
		if (freeSocket == null) {
			throw new IOException("Could not get a free socket to " + host + ":" + port);
		}
		return freeSocket;
	}
	
	@Override
	public void release(Socket socket) {
		boolean released = false;
		for (SocketHandler handler : socketHandlers.values()) {
			if (handler.release(socket)) {
				released = true;
				break;
			}
		}
		if (!released) {
			throw new RuntimeException("Can not release the socket " + socket + ", it is not controlled by this connection handler");
		}
	}
	
	/**
	 * Use this is the socket must be closed (no persistent connections)
	 * @throws IOException 
	 */
	@Override
	public void close(Socket socket) throws IOException {
		boolean closed = false;
		for (SocketHandler handler : socketHandlers.values()) {
			if (handler.close(socket)) {
				closed = true;
				break;
			}
		}
		if (!closed) {
			logger.warn("Can not close the socket {}, it is not controlled by this connection handler", socket);
			socket.close();
		}
	}
	
	public void close(String host, int port) throws IOException {
		String key = host + ":" + port;
		if (socketHandlers.containsKey(key)) {
			synchronized(socketHandlers) {
				if (socketHandlers.containsKey(key)) {
					socketHandlers.get(key).close();
					socketHandlers.remove(key);
				}
			}
		}
	}
	
	@Override
	public synchronized void close() throws IOException {
		IOException exception = null;
		for (SocketHandler handler : socketHandlers.values()) {
			try {
				handler.close();
			}
			catch (IOException e) {
				exception = e;
			}
		}
		if (exception != null)
			throw exception;
	}
	
	@Override
	protected void finalize() {
		try {
			close();
		}
		catch (IOException e) {
			// do nothing;
		}
	}

	@Override
	public int getConnectionTimeout() {
		return connectionTimeout;
	}

	public PooledConnectionHandler setConnectionTimeout(int connectionTimeout) {
		this.connectionTimeout = connectionTimeout;
		return this;
	}

	@Override
	public int getSocketTimeout() {
		return socketTimeout;
	}

	public PooledConnectionHandler setSocketTimeout(int socketTimeout) {
		this.socketTimeout = socketTimeout;
		return this;
	}

	@Override
	public Proxy getProxy() {
		return proxy;
	}
	
	private class SocketHandler implements Closeable {
		
		private volatile Socket [] sockets;
		private volatile boolean [] socketsInUse;
		
		private volatile int amountOfUsedSockets, amountOfFreeSockets, totalAmount;
		private int port;
		private String host;
		private String key;
		
		public SocketHandler(int size, String host, int port) {
			this.totalAmount = size;
			this.host = host;
			this.port = port;
			this.sockets = new Socket[size];
			this.socketsInUse = new boolean[size];
			this.key = host + ":" + port;
		}
		
		public boolean release(Socket socket) {
			boolean released = false;
			for (int i = 0; i < totalAmount; i++) {
				if (sockets[i] != null && socket.equals(sockets[i])) {
					synchronized(this) {
						socketsInUse[i] = false;
						amountOfUsedSockets--;
						amountOfFreeSockets++;
						released = true;
						break;
					}
				}
			}
			return released;
		}
		
		public boolean close(Socket socket) throws IOException {
			boolean closed = false;
			for (int i = 0; i < totalAmount; i++) {
				if (sockets[i] != null && socket.equals(sockets[i])) {
					synchronized(this) {
						if (sockets[i] != null && socket.equals(sockets[i])) {
							socket.close();
							socketsInUse[i] = false;
							sockets[i] = null;
							amountOfUsedSockets--;
							closed = true;
						}
					}
				}
			}
			return closed;
		}
		
		public Socket getFreeSocket(boolean secure) throws IOException {
			if (amountOfFreeSockets > 0) {
				for (int i = 0; i < totalAmount; i++) {
					if (!socketsInUse[i]) {
						synchronized(this) {
							Socket socket = sockets[i];
							if (!socketsInUse[i]) {
								socketsInUse[i] = true;
								amountOfFreeSockets--;
								amountOfUsedSockets++;
							}
							return socket;
						}
					}
				}
			}
			// if you don't have a free socket and there is still room for one, make it
			if (amountOfFreeSockets + amountOfUsedSockets < totalAmount) {
				synchronized(this) {
					if (amountOfFreeSockets + amountOfUsedSockets < totalAmount) {
						Socket socket = null;
						if (useProxy(host, port)) {
							socket = getProxy().tunnel(host, port, connectionTimeout, socketTimeout, secure);
							// wrap a SSLSocket around the regular socket
							if (secure) {
								SSLSocket secureSocket = (SSLSocket) secureContext.getSocketFactory().createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
								secureSocket.startHandshake();
								socket = secureSocket;
							}
						}
						else {
							socket = secure ? secureContext.getSocketFactory().createSocket() : new Socket();
							socket.connect(new InetSocketAddress(host, port), connectionTimeout);
							socket.setSoTimeout(socketTimeout);
						}
						if (socket == null) {
							throw new IOException("Could not set up connection to " + host + ":" + port);
						}
						synchronized(this) {
							for (int j = 0; j < totalAmount; j++) {
								if (sockets[j] == null) {
									sockets[j] = socket;
									amountOfUsedSockets++;
									socketsInUse[j] = true;
									break;
								}
							}
						}
						return socket;
					}
				}
			}
			return null;
		}
		
		@Override
		public int hashCode() {
			return key.hashCode();
		}
		
		@Override
		public boolean equals(Object object) {
			return object instanceof SocketHandler && ((SocketHandler) object).key.equals(key);
		}

		@Override
		public void close() throws IOException {
			IOException exception = null;
			for (Socket socket : sockets) {
				if (socket != null) {
					try {
						socket.close();
					}
					catch (IOException e) {
						exception = e;
					}
				}
			}
			for (Socket socket : sockets) {
				if (socket != null) {
					try {
						socket.close();
					}
					catch (IOException e) {
						exception = e;
					}
				}
			}
			if (exception != null) {
				throw exception;
			}
		}
	}
}
