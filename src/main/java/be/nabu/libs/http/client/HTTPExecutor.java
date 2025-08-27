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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLSocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import be.nabu.libs.http.HTTPInterceptorManager;
import be.nabu.libs.http.api.HTTPInterceptor;
import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.DefaultHTTPResponse;
import be.nabu.libs.http.core.HTTPFormatter;
import be.nabu.libs.http.core.HTTPParser;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.resources.api.DynamicResourceProvider;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.api.WritableContainer;
import be.nabu.utils.io.containers.EOFReadableContainer;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.api.ModifiableContentPart;
import be.nabu.utils.mime.api.MultiPart;
import be.nabu.utils.mime.api.Part;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;

public class HTTPExecutor {
	
	private CookieHandler cookieHandler;
	private HTTPFormatter formatter;
	private HTTPParser parser;
	private boolean useContinue;
	private HTTPInterceptor interceptor;
	private boolean forceContentLength = false;
	
	// these are the methods that are automatically assumed to be continuable
	// you can force a continue by explicitly setting the header though
	private static List<String> continuableMethods = Arrays.asList(new String [] { "PUT", "POST" });
	private boolean debug = Boolean.parseBoolean(System.getProperty("http.client.debug", "false"));
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	public HTTPExecutor(DynamicResourceProvider dynamicResourceProvider, CookieHandler cookieHandler, boolean useContinue) {
		this.cookieHandler = cookieHandler;
		this.formatter = new HTTPFormatter(false);
		this.parser = new HTTPParser(dynamicResourceProvider, true);
		this.useContinue = useContinue;
	}
	
	@SuppressWarnings("resource")
	public HTTPResponse execute(Socket socket, HTTPRequest request, Principal principal, boolean secure, boolean followRedirects) throws IOException, FormatException, ParseException {
		URI uri = null;

		if (interceptor != null) {
			interceptor.intercept(request);
		}
		
		// allow centralized interception of requests (may be deprecated?)
		request = HTTPInterceptorManager.intercept(request);
		
		try {
			uri = request.getMethod().equalsIgnoreCase("CONNECT") 
				? new URI(socket instanceof SSLSocket ? "https" : "http", socket.getInetAddress().getHostName() + ":" + socket.getPort(), "/", null, null) 
				: HTTPUtils.getURI(request, secure);
		}
		catch (URISyntaxException e) {
			throw new FormatException(e);
		}
	
		InputStream input = new BufferedInputStream(socket.getInputStream());
		OutputStream output = new BufferedOutputStream(socket.getOutputStream());
		
		if (debug) {
			output = new LoggingOutputStream(output);
			input = new LoggingInputStream(input);
		}
		
		List<Header> additionalHeaders = new ArrayList<Header>();
		if (cookieHandler != null) {
			Map<String, List<String>> cookies = cookieHandler.get(uri, getHeadersAsMap(request.getContent().getHeaders()));
			StringBuilder cookieBuilder = new StringBuilder();
			for (String cookie : cookies.keySet()) {
				for (String value : cookies.get(cookie)) {
					//additionalHeaders.add(new MimeHeader(cookie, value));
					if (!cookieBuilder.toString().isEmpty()) {
						cookieBuilder.append(";");
					}
					cookieBuilder.append(value);
				}
			}
			if (!cookieBuilder.toString().isEmpty()) {
				additionalHeaders.add(new MimeHeader("Cookie", cookieBuilder.toString()));
			}
		}
		request.getContent().setHeader(additionalHeaders.toArray(new Header[additionalHeaders.size()]));

		Date timestamp = new Date();
		logger.debug("> socket:" + socket.hashCode() + " [request:" + request.hashCode() + "] " + request.getMethod() + ": " + uri);
		if (logger.isTraceEnabled()) {
			for (Header header : request.getContent().getHeaders()) {
				logger.trace("	> [" + request.hashCode() + "] " + header.getName() + ": " + header.getValue());
			}
		}
		
		// only use continue
		Header expectHeader = request.getContent() != null ? MimeUtils.getHeader("Expect", request.getContent().getHeaders()) : null;
		boolean forceUseContinue = expectHeader != null && expectHeader.getValue().trim().equalsIgnoreCase("100-Continue");
		EOFReadableContainer<ByteBuffer> readable = new EOFReadableContainer<ByteBuffer>(IOUtils.wrap(input));
		
		try {
			// @2025-07-29: we had a case where a target server (uitdatabank) has file upload logic that does not work if we format the request directly to the socket
			// instead we have to format the entire request and then send it at once, presumably this is to do with TCP framing of the packages underneath
			// to control this behavior I've added this header so you can force local formatting before fully pushing it
			// note however that this impacts memory usage so should be used with caution
			Header bufferRequestHeader = request.getContent() != null ? MimeUtils.getHeader("X-Nabu-Buffer-Request-Formatting", request.getContent().getHeaders()) : null;
			if (forceUseContinue || (request.getVersion() >= 1.1 && continuableMethods.contains(request.getMethod().toUpperCase()) && useContinue && request.getContent() != null)) {
				logger.trace("> [" + request.hashCode() + "] Headers only: 100-Continue");
				request.getContent().setHeader(new MimeHeader("Expect", "100-Continue"));
				formatter.formatRequestHeaders(request, IOUtils.wrap(output));
				output.flush();
				HTTPResponse continueResponse = parser.parseResponse(readable);
				if (continueResponse.getCode() == 100) {
					logger.trace("> [" + request.hashCode() + "] Headers OK, sending content");
					formatter.formatRequestContent(request, IOUtils.wrap(output));
				}
				else {
					logger.trace("> [" + request.hashCode() + "] Headers rejected [" + continueResponse.getCode() + "]: " + continueResponse.getMessage());
					return continueResponse;
				}
			}
			else if (bufferRequestHeader != null && "true".equalsIgnoreCase(bufferRequestHeader.getValue())) {
				// does not need to be transmitted
				request.getContent().removeHeader("X-Nabu-Buffer-Request-Formatting");
				WritableContainer<ByteBuffer> bufferWritable = IOUtils.bufferWritable(IOUtils.wrap(output), IOUtils.newByteBuffer());
				if (forceContentLength) {
					formatter.formatRequestWithContentLength(request, bufferWritable);
				}
				else {
					formatter.formatRequest(request, bufferWritable);
				}
				bufferWritable.flush();
			}
			else {
				if (forceContentLength) {
					formatter.formatRequestWithContentLength(request, IOUtils.wrap(output));
				}
				else {
					formatter.formatRequest(request, IOUtils.wrap(output));
				}
			}
			
			output.flush();
			
			HTTPResponse response = parser.parseResponse(readable);
			
			// link to request
			if (response instanceof DefaultHTTPResponse) {
				((DefaultHTTPResponse) response).setRequest(request);
			}
			
			// we back it with a dynamic resource provider
			// this "should" be reopenable but...
			MimeUtils.setReopenable(response.getContent(), true);
			
			if (interceptor != null) {
				interceptor.intercept(response);
			}
			// allow intercept of response
			response = HTTPInterceptorManager.intercept(response);
	
			logger.debug("< socket:" + socket.hashCode() + " [request:" + request.hashCode() + "] (" + (new Date().getTime() - timestamp.getTime()) + "ms) " + response.getCode() + ": " + response.getMessage());
			if (logger.isTraceEnabled() && response.getContent() != null) {
				for (Header header : response.getContent().getHeaders()) {
					logger.trace("	< [" + request.hashCode() + "] " + header.getName() + ": " + header.getValue());
				}
			}
			
			// push the response into the cookiestore
			if (cookieHandler != null && response.getContent() != null)
				cookieHandler.put(uri, getHeadersAsMap(response.getContent().getHeaders()));
			return response;
		}
		catch (ParseException e) {
			// if the readable was closed, we assume parse exceptions occured because of IO issues
			if (readable.isEOF()) {
				throw new IOException("Could not parse the response because the connection was closed", e);
			}
			else {
				throw e;
			}
		}
	}
	private Map<String, List<String>> getHeadersAsMap(Header...headers) {
		Map<String, List<String>> map = new HashMap<String, List<String>>();
		for (Header header : headers) {
			if (!map.containsKey(header.getName().toLowerCase()))
				map.put(header.getName().toLowerCase(), new ArrayList<String>());
			if (header.getValue() != null)
				map.get(header.getName().toLowerCase()).add(MimeUtils.getFullHeaderValue(header));
		}
		return map;
	}

	public CookieHandler getCookieHandler() {
		return cookieHandler;
	}

	public HTTPInterceptor getInterceptor() {
		return interceptor;
	}

	public void setInterceptor(HTTPInterceptor interceptor) {
		this.interceptor = interceptor;
	}

	public boolean isForceContentLength() {
		return forceContentLength;
	}

	public void setForceContentLength(boolean forceContentLength) {
		this.forceContentLength = forceContentLength;
	}
	
}
