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

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.core.HTTPFormatter;
import be.nabu.libs.http.core.HTTPParser;
import be.nabu.libs.http.core.HTTPUtils;
import be.nabu.libs.resources.api.DynamicResourceProvider;
import be.nabu.utils.io.IOUtils;
import be.nabu.utils.io.api.ByteBuffer;
import be.nabu.utils.io.containers.EOFReadableContainer;
import be.nabu.utils.mime.api.Header;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.MimeUtils;

public class HTTPExecutor {
	
	private CookieHandler cookieHandler;
	private HTTPFormatter formatter;
	private HTTPParser parser;
	private boolean useContinue;
	// these are the methods that are automatically assumed to be continuable
	// you can force a continue by explicitly setting the header though
	private static List<String> continuableMethods = Arrays.asList(new String [] { "PUT", "POST" });
	private boolean debug = Boolean.parseBoolean(System.getProperty("http.client.debug", "false"));
	
	private Logger logger = LoggerFactory.getLogger(getClass());
	
	public HTTPExecutor(DynamicResourceProvider dynamicResourceProvider, CookieHandler cookieHandler, boolean useContinue) {
		this.cookieHandler = cookieHandler;
		this.formatter = new HTTPFormatter();
		this.parser = new HTTPParser(dynamicResourceProvider, true);
		this.useContinue = useContinue;
	}
	
	@SuppressWarnings("resource")
	public HTTPResponse execute(Socket socket, HTTPRequest request, Principal principal, boolean secure, boolean followRedirects) throws IOException, FormatException, ParseException {
		URI uri = null;

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
			for (String cookie : cookies.keySet()) {
				for (String value : cookies.get(cookie))
					additionalHeaders.add(new MimeHeader(cookie, value));
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
			else {
				formatter.formatRequest(request, IOUtils.wrap(output));
			}
			
			output.flush();
			
			HTTPResponse response = parser.parseResponse(readable);
	
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
				map.get(header.getName().toLowerCase()).add(header.getValue());
		}
		return map;
	}

	public CookieHandler getCookieHandler() {
		return cookieHandler;
	}
}
