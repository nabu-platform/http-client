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

package be.nabu.libs.http.client;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.ClientAuthenticationHandler;
import be.nabu.libs.http.api.client.ConnectionHandler;
import be.nabu.libs.http.api.client.TimedHTTPClient;
import be.nabu.libs.http.core.DefaultDynamicResourceProvider;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.resources.api.DynamicResourceProvider;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;

public class DefaultHTTPClient implements TimedHTTPClient {
	
	private ConnectionHandler connectionHandler;
	private ClientAuthenticationHandler authenticationHandler;
	private HTTPExecutor executor;
	private boolean allowCircularRedirect = false;
	private DynamicResourceProvider dynamicResourceProvider;
	/**
	 * Whether or not to allow a redirect that degrades security (e.g. from https > http)
	 */
	private boolean allowDegradingRedirect = true;
	
	private boolean retryOnFailure = false;
	
	public DefaultHTTPClient(ConnectionHandler connectionHandler, ClientAuthenticationHandler authenticationHandler, CookieHandler cookieHandler, boolean useContinue) {
		this.connectionHandler = connectionHandler;
		this.executor = new HTTPExecutor(new DefaultDynamicResourceProvider(), cookieHandler, useContinue);
		this.authenticationHandler = authenticationHandler;
	}
	
	@Override
	public HTTPResponse execute(HTTPRequest request, Principal principal, boolean secure, boolean followRedirects) throws IOException, FormatException, ParseException {
		return execute(request, principal, secure, followRedirects, null, null);
	}
	
	@Override
	public HTTPResponse execute(HTTPRequest request, Principal principal, boolean secure, boolean followRedirects, Long timeout, TimeUnit unit) throws IOException, FormatException, ParseException {
		boolean keepAlive = HTTPUtils.keepAlive(request);
		boolean requestSucceeded = false;
		HTTPResponse response = null;
		List<URI> redirects = new ArrayList<URI>();
		Socket socket = null;
		// some hosts keep sending 301 with the exact same Location as you are already accessing but actually their problem is with "GET / HTTP/1.1" and a host header and instead want the full location in the GET request
		boolean triedAbsoluteRedirect = false;
		int triesAfter401 = 0;
		String lastHost = null;
		while (!requestSucceeded) {
			URI uri = HTTPUtils.getURI(request, secure);
			
			String host = uri.getAuthority();
			int port = secure ? 443 : 80;
			int indexOfColon = host.indexOf(':');
			if (indexOfColon >= 0) {
				String substring = host.substring(indexOfColon + 1);
				if (!substring.trim().isEmpty()) {
					port = new Integer(substring);
				}
				host = host.substring(0, indexOfColon);
			}

			// remove connection if it is closed
			if (socket != null && socket.isClosed()) {
				connectionHandler.close(socket);
				socket = null;
			}
			
			if (socket != null && lastHost != null && !lastHost.equals(host + ":" + port)) {
				connectionHandler.close(socket);
				socket = null;
			}
			
			// (re)connect if no connection
			if (socket == null) {
				socket = connectionHandler.connect(host, port, secure);
				lastHost = host + ":" + port;
			}

			try {
				// for the actual sending, synchronize on the socket so only one party is interacting with it at the same time
				synchronized(socket) {
					if (timeout == null || timeout == 0) {
						socket.setSoTimeout(0);
					}
					else {
						socket.setSoTimeout((int) (unit == null ? timeout : TimeUnit.MILLISECONDS.convert(timeout, unit)));
					}
					// we try on the socket
					try {
						response = executor.execute(socket, request, principal, secure, followRedirects);
					}
					// we could have network issues (e.g. remote host restarted or whatever)
					catch (IOException e) {
						// close the socket
						connectionHandler.close(socket);
						if (retryOnFailure) {
							// and try once more
							socket = connectionHandler.connect(host, port, secure);
							synchronized(socket) {
								try {
									response = executor.execute(socket, request, principal, secure, followRedirects);
								}
								// if we still get an exception, just stop
								catch (IOException f) {
									connectionHandler.close(socket);
									throw f;
								}
							}
						}
						else {
							throw e;
						}
					}
				}

				keepAlive = HTTPUtils.keepAlive(response);

				// set proxy-keep alive
				if (connectionHandler.getProxy() != null && keepAlive){
					if (MimeUtils.getHeader("Proxy-Connection", request.getContent().getHeaders()) == null)
						request.getContent().setHeader(new MimeHeader("Proxy-Connection", "Keep-Alive")); 
				}
				
				if (response.getCode() == 407 && authenticationHandler != null) {
					Header authenticationHeader = HTTPUtils.authenticateProxy(response, connectionHandler.getProxy().getPrincipal(), authenticationHandler);
					if (authenticationHeader != null)
						request.getContent().setHeader(authenticationHeader);
					else
						requestSucceeded = true;
				}
				// unauthorized, check if we can try again with authorization
				else if (response.getCode() == 401 && authenticationHandler != null) {
					// a 401 can involve a few back and forth messages, but currently no more than 2
					// stop if we go over this amount to prevent request loops
					if (triesAfter401 > 2) {
						requestSucceeded = true;
					}
					else {
						triesAfter401++;
						Header authenticationHeader = HTTPUtils.authenticateServer(response, principal, authenticationHandler);
						if (authenticationHeader != null) {
							request.getContent().removeHeader(HTTPUtils.SERVER_AUTHENTICATE_RESPONSE);
							request.getContent().setHeader(authenticationHeader);
						}
						else {
							requestSucceeded = true;
						}
					}
				}
				else if ((response.getCode() == 301 || response.getCode() == 302 || response.getCode() == 307) && followRedirects) {
					Header locationHeader = MimeUtils.getHeader("Location", response.getContent().getHeaders());
					if (locationHeader != null) {
						try {
							URI newTarget = new URI(locationHeader.getValue());
							// you can also have relative redirect locations although the standard (currently, this will change) states absolute
							if (newTarget.getAuthority() == null) {
								newTarget = HTTPUtils.getURI(request, secure).resolve(newTarget);
							}
							if (redirects.contains(newTarget)) {
								if (!triedAbsoluteRedirect) {
									request = HTTPUtils.redirect(request, newTarget, true);
									triedAbsoluteRedirect = true;
								}
								else if (!allowCircularRedirect)
									throw new IOException("Circular redirect found: " + newTarget + " in " + redirects);
							}
							else {
								redirects.add(newTarget);
								request = HTTPUtils.redirect(request, newTarget, false);
								triedAbsoluteRedirect = false;
							}
							// check if the security is impacted by the new scheme
							String newScheme = newTarget.getScheme();
							// if no scheme is given, assume http, for example if you try to connect to slashdot.org:443, it may send you a redirect to "//slashdot.org", notice the missing scheme...
							if (newScheme == null)
								newScheme = "http";

							boolean newSecure = newScheme.equalsIgnoreCase("https");
							// need to close the socket because we need to switch security
							if (newSecure != secure) {
								if (!newSecure && !isAllowDegradingRedirect())
									throw new IOException("A server redirect to " + newTarget + " will degrade security and this is currently not allowed");
								keepAlive = false;
							}
							secure = newSecure;
						}
						catch (URISyntaxException e) {
							throw new ParseException("Can not parse the redirected uri " + locationHeader.getValue() + ": " + e.getMessage(), 0);
						}	
					}
				}
				else
					requestSucceeded = true;
				
				if (socket.isClosed() || socket.isInputShutdown() || socket.isOutputShutdown()) {
					keepAlive = false;
				}
			}
			finally {
				if (!keepAlive && socket != null) {
					if (!socket.isClosed()) {
						connectionHandler.close(socket);
					}
					socket = null;
				}
			}
		}
		if (keepAlive)
			connectionHandler.release(socket);
		return response;
	}

	public ConnectionHandler getConnectionHandler() {
		return connectionHandler;
	}

	public ClientAuthenticationHandler getAuthenticationHandler() {
		return authenticationHandler;
	}

	public boolean isAllowCircularRedirect() {
		return allowCircularRedirect;
	}

	public DefaultHTTPClient setAllowCircularRedirect(boolean allowCircularRedirect) {
		this.allowCircularRedirect = allowCircularRedirect;
		return this;
	}

	public boolean isAllowDegradingRedirect() {
		return allowDegradingRedirect;
	}

	public DefaultHTTPClient setAllowDegradingRedirect(boolean allowDegradingRedirect) {
		this.allowDegradingRedirect = allowDegradingRedirect;
		return this;
	}
	
	public DynamicResourceProvider getDynamicResourceProvider() {
		if (dynamicResourceProvider == null) {
			dynamicResourceProvider = new DefaultDynamicResourceProvider();
		}
		return dynamicResourceProvider;
	}

	public void setDynamicResourceProvider(DynamicResourceProvider dynamicResourceProvider) {
		this.dynamicResourceProvider = dynamicResourceProvider;
	}

	@Override
	public void close() throws IOException {
		getConnectionHandler().close();
	}

	public HTTPExecutor getExecutor() {
		return executor;
	}
	
}
