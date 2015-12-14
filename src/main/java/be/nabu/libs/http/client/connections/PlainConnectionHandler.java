package be.nabu.libs.http.client.connections;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.SSLContext;

import be.nabu.libs.http.api.client.ConnectionHandler;
import be.nabu.libs.http.api.client.Proxy;

public class PlainConnectionHandler implements ConnectionHandler {

	private SSLContext secureContext;
	private List<Socket> openSockets = new ArrayList<Socket>();
	private int connectionTimeout, socketTimeout;
	private boolean closeOnRelease = true;
	
	public PlainConnectionHandler(SSLContext context, int connectionTimeout, int socketTimeout) {
		this.secureContext = context;
		this.connectionTimeout = connectionTimeout;
		this.socketTimeout = socketTimeout;
	}
	
	@Override
	public void close() throws IOException {
		synchronized(openSockets) {
			for (Socket socket : openSockets)
				socket.close();
			openSockets.clear();
		}
	}

	@Override
	public SSLContext getSecureContext() {
		return secureContext;
	}

	@Override
	public Socket connect(String host, int port, boolean secure) throws IOException {
		Socket socket = secure ? secureContext.getSocketFactory().createSocket() : new Socket();
		socket.connect(new InetSocketAddress(host, port), connectionTimeout);
		socket.setSoTimeout(socketTimeout);
		synchronized(openSockets) {
			openSockets.add(socket);
		}
		return socket;
	}

	@Override
	public void release(Socket socket) throws IOException {
		if (closeOnRelease) {
			close(socket);
		}
	}

	@Override
	public void close(Socket socket) throws IOException {
		if (openSockets.contains(socket)) {
			synchronized(openSockets) {
				openSockets.remove(socket);
			}
		}
		if (!socket.isClosed())
			socket.close();
	}

	@Override
	protected void finalize() {
		try {
			close();
		}
		catch (IOException e) {
			// do nothing
		}
	}

	@Override
	public Proxy getProxy() {
		return null;
	}

	@Override
	public int getSocketTimeout() {
		return socketTimeout;
	}

	@Override
	public int getConnectionTimeout() {
		return connectionTimeout;
	}
	
	public List<Socket> getOpenSockets() {
		return new ArrayList<Socket>(openSockets);
	}

	public boolean isCloseOnRelease() {
		return closeOnRelease;
	}
	public void setCloseOnRelease(boolean closeOnRelease) {
		this.closeOnRelease = closeOnRelease;
	}
}
