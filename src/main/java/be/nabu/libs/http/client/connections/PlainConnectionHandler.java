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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

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
		// support for SNI
		if (socket instanceof SSLSocket) {
			SSLParameters sslParameters = new SSLParameters();
			sslParameters.setServerNames(Arrays.asList(new SNIServerName[] { new SNIHostName(host) }));
			((SSLSocket) socket).setSSLParameters(sslParameters);
		}
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
