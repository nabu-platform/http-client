package be.nabu.libs.http.client;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.Socket;
import java.security.Principal;
import java.text.ParseException;

import javax.net.ssl.SSLContext;

import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.AuthenticationHandler;
import be.nabu.libs.http.api.client.ConnectionHandler;
import be.nabu.libs.http.api.client.Proxy;
import be.nabu.libs.http.client.connections.PlainConnectionHandler;
import be.nabu.libs.http.core.CustomCookieStore;
import be.nabu.libs.http.core.DefaultDynamicResourceProvider;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class HTTPProxy implements Proxy {

	private String host;
	private int port;
	private HTTPExecutor httpExecutor;
	private Principal principal;
	private ConnectionHandler connectionHandler;
	private AuthenticationHandler authenticationHandler;
	private boolean secure = false;
	
	public HTTPProxy(String host, int port, Principal principal, AuthenticationHandler authenticationHandler, int connectionTimeout, int socketTimeout, SSLContext context) {
		this.port = port;
		this.host = host;
		this.connectionHandler = new PlainConnectionHandler(context, connectionTimeout, socketTimeout);
		this.secure = context != null;
		this.httpExecutor = new HTTPExecutor(new DefaultDynamicResourceProvider(), new CookieManager(new CustomCookieStore(), CookiePolicy.ACCEPT_ALL), false);
		this.principal = principal;
		this.authenticationHandler = authenticationHandler;
	}
	
	@Override
	public Socket tunnel(String host, int port, int connectionTimeout, int socketTimeout, boolean secure) throws IOException {
		Socket proxySocket = connectionHandler.connect(this.host, this.port, this.secure);
		if (secure) {
			try {
				DefaultHTTPRequest request = new DefaultHTTPRequest("CONNECT", host + ":" + port,
					new PlainMimeEmptyPart(null,
						new MimeHeader("Host", host),
						new MimeHeader("Proxy-Connection", "Keep-Alive"),
						new MimeHeader("Connection", "Keep-Alive"),
						new MimeHeader("User-Agent", "utils-http")
					)
				);
				
				HTTPResponse response = httpExecutor.execute(proxySocket, request, principal, secure, false);

				int tries = 0;
				while(response.getCode() == 407 && authenticationHandler != null) {
					Header proxyConnectionHeader = MimeUtils.getHeader("Proxy-Connection", response.getContent().getHeaders());
					if (proxyConnectionHeader != null && proxyConnectionHeader.getValue().equalsIgnoreCase("close"))
						proxySocket.close();
					
					if (proxySocket.isClosed()) {
						connectionHandler.close(proxySocket);
						proxySocket = connectionHandler.connect(this.host, this.port, this.secure);
					}
					Header authenticationHeader = HTTPUtils.authenticateProxy(response, principal, authenticationHandler);
					if (authenticationHeader != null) {
						request.getContent().removeHeader(authenticationHeader.getName());
						request.getContent().setHeader(authenticationHeader);
						response = httpExecutor.execute(proxySocket, request, principal, secure, false);
					}
					else
						break;
					
					// random max amount of tries to make sure we don't get into an infinite loop
					if (++tries >= 4)
						break;
				}
				
				if (response.getCode() != 200)
					throw new IOException("After " + tries + " tries the proxy returned: " + response.getCode() + ": " + response.getMessage());
				else
					return proxySocket;
			}
			catch (ParseException e) {
				connectionHandler.close(proxySocket);
				throw new IOException(e);
			}
			catch (FormatException e) {
				connectionHandler.close(proxySocket);
				throw new IOException(e);
			}
			catch (IOException e) {
				connectionHandler.close(proxySocket);
				throw e;
			}
		}
		else
			return proxySocket;
	}

	@Override
	public Principal getPrincipal() {
		return principal;
	}
}
