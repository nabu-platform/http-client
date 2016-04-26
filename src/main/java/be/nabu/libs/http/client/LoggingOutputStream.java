package be.nabu.libs.http.client;

import java.io.IOException;
import java.io.OutputStream;

public class LoggingOutputStream extends OutputStream {

	private OutputStream target;

	public LoggingOutputStream(OutputStream target) {
		this.target = target;
	}
	
	@Override
	public void write(int b) throws IOException {
		System.out.print((char) b);
		target.write(b);
	}

	@Override
	public void write(byte[] b) throws IOException {
		System.out.print(new String(b));
		target.write(b);
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		System.out.print(new String(b, off, len));
		target.write(b, off, len);
	}

	@Override
	public void flush() throws IOException {
		target.flush();
	}

	@Override
	public void close() throws IOException {
		target.close();
	}

}
