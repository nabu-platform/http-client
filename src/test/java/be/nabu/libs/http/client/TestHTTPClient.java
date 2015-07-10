package be.nabu.libs.http.client;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;

import javax.net.ssl.SSLContext;

import be.nabu.libs.http.api.HTTPRequest;
import be.nabu.libs.http.api.HTTPResponse;
import be.nabu.libs.http.api.client.ConnectionHandler;
import be.nabu.libs.http.api.client.HTTPClient;
import be.nabu.libs.http.client.connections.PooledConnectionHandler;
import be.nabu.libs.http.core.CustomCookieStore;
import be.nabu.libs.http.core.DefaultHTTPRequest;
import be.nabu.utils.mime.impl.FormatException;
import be.nabu.utils.mime.impl.MimeHeader;
import be.nabu.utils.mime.impl.PlainMimeEmptyPart;

public class TestHTTPClient {
	
	@SuppressWarnings("resource")
	public static void main(String...args) throws NoSuchAlgorithmException, IOException, FormatException, ParseException {
		ConnectionHandler connectionHandler = new PooledConnectionHandler(SSLContext.getDefault(), 5)
			.setConnectionTimeout(5000)
			.setSocketTimeout(5000)
			.setProxy(new HTTPProxy("localhost", 8080, new NTLMPrincipalImpl("", "", ""), new SPIAuthenticationHandler(), 5000, 5000, null));
		
		try {
			HTTPClient client = new DefaultHTTPClient(
				connectionHandler,
				new SPIAuthenticationHandler(),
				new CookieManager(new CustomCookieStore(), CookiePolicy.ACCEPT_ALL),
				true
			);

			HTTPRequest request = new DefaultHTTPRequest("GET", "/", new PlainMimeEmptyPart(null, 
				new MimeHeader("Host", "slashdot.org:443"),
				new MimeHeader("User-Agent", "utils-http")
			));
			
			HTTPResponse response = client.execute(request, null, true, true);
			
			System.out.println(response.getCode() + " " + response.getMessage());
		}
		finally {
			connectionHandler.close();
		}
	}
}
