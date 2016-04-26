package be.nabu.libs.http.client;

import java.io.IOException;
import java.io.InputStream;

public class LoggingInputStream extends InputStream {

	private InputStream input;

	public LoggingInputStream(InputStream input) {
		this.input = input;
	}
	
	@Override
	public int read() throws IOException {
		int read = input.read();
		System.out.print((char) read);
		return read;
	}

	@Override
	public int read(byte[] b) throws IOException {
		int read = input.read(b);
		System.out.print(new String(b));
		return read;
	}

	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int read = input.read(b, off, len);
		System.out.print(new String(b, off, len));
		return read;
	}

	@Override
	public long skip(long n) throws IOException {
		return input.skip(n);
	}

	@Override
	public int available() throws IOException {
		return input.available();
	}

	@Override
	public void close() throws IOException {
		input.close();
	}

	@Override
	public synchronized void mark(int readlimit) {
		input.mark(readlimit);
	}

	@Override
	public synchronized void reset() throws IOException {
		input.reset();
	}

	@Override
	public boolean markSupported() {
		return input.markSupported();
	}

}
