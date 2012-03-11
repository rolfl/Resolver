package net.tuis.resolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

import org.junit.BeforeClass;
import org.junit.Test;
import org.xml.sax.InputSource;

@SuppressWarnings("javadoc")
public class TestCachedHTTPRepository {
	
	@BeforeClass
	public static final void setLogging() {
		Handler ch = new StreamHandler(System.out, new SimpleFormatter()) ;
		ch.setLevel(Level.ALL);
		Logger logger = Logger.getLogger("net.tuis");
		logger.setLevel(Level.FINEST);
		logger.addHandler(ch);
		
	}
	
	private static final String result(final InputSource source) throws IOException {
		Reader reader = source.getCharacterStream();
		if (reader == null) {
			if (source.getEncoding() != null) {
				reader = new InputStreamReader(source.getByteStream(), source.getEncoding());
			} else {
				reader = new InputStreamReader(source.getByteStream());
			}
		}
		char[] buffer = new char[512];
		CharArrayWriter caw = new CharArrayWriter();
		int len = 0;
		while ((len = reader.read(buffer)) >= 0) {
			caw.write(buffer, 0, len);
		}
		return new String(caw.toCharArray());
	}
	
	@Test
	public void testResolveW3() throws IOException {
		CachedURLRepository repo = new CachedURLRepository();
		final String xhtmldtd = result(repo.resolve("-//W3C//DTD XHTML 1.0 Strict//EN",
				new URL("http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd")));
		assertTrue(xhtmldtd != null);
	}
	
	@Test
	public void testResolveLocal() throws IOException {
		CachedURLRepository repo = new CachedURLRepository();
		final String val = result(repo.resolve(null, new URL("http://localhost/index.html")));
		assertEquals("<html><body><h1>It works!</h1></body></html>", val);
		final String xhtmldtd = result(repo.resolve("-//W3C//DTD XHTML 1.0 Strict//EN",
				new URL("http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd")));
		assertTrue(xhtmldtd != null);
	}
	
}
