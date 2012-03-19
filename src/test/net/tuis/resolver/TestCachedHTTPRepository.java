package net.tuis.resolver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.CharArrayWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.Properties;
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
		CachedHTTPRepository repo = new CachedHTTPRepository();
		final String xhtmldtd = result(repo.resolve("-//W3C//DTD XHTML 1.0 Strict//EN",
				new URL("http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd")));
		assertTrue(xhtmldtd != null);
	}
	
	@Test
	public void testResolveLocal() throws IOException {
		CachedHTTPRepository repo = new CachedHTTPRepository();
		final String val = result(repo.resolve(null, new URL("http://localhost/index.html")));
		assertEquals("<html><body><h1>It works!</h1></body></html>", val);
		final String xhtmldtd = result(repo.resolve("-//W3C//DTD XHTML 1.0 Strict//EN",
				new URL("http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd")));
		assertTrue(xhtmldtd != null);
	}
	
	private void copyResource(final File destination, final InputStream source) throws IOException {
		destination.getParentFile().mkdirs();
		final FileOutputStream fos = new FileOutputStream(destination);
		final byte[] buffer = new byte[1024];
		int len = 0;
		while ((len = source.read(buffer)) >= 0) {
			fos.write(buffer, 0, len);
		}
		fos.flush();
		fos.close();
		source.close();
	}
	
	@Test
	public void testResolveNoConnection() throws IOException {
		final Properties props = new Properties();
		final String resbase = TestCachedHTTPRepository.class.getName().replace('.', '/');
		final InputStream is = ClassLoader.getSystemResourceAsStream(resbase + "_control");
		props.load(is);
		final String key = props.getProperty("CURL_RESKEY");
		final String url = props.getProperty("CURL_URL");
		
		final CachedHTTPRepository repo = new CachedHTTPRepository();

		final File control = new File(repo.getCacheFolder(), key + ".control");
		final File data    = new File(repo.getCacheFolder(), key + ".data");
		
		copyResource(control, ClassLoader.getSystemResourceAsStream(resbase + "_control"));
		copyResource(data,    ClassLoader.getSystemResourceAsStream(resbase + "_data"));
		
		final String val = result(repo.resolve(null, new URL(url)));
		assertEquals("0001332089779999\n", val);
	}
	
}
