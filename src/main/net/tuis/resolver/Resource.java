package net.tuis.resolver;

import java.io.ByteArrayInputStream;
import java.io.CharArrayReader;
import java.io.InputStream;
import java.io.Reader;

import org.xml.sax.InputSource;

/**
 * A resource is an InputSource that may be returned to a parser.
 * The resource is also what is cached.
 * 
 * @author Rolf Lear
 *
 */
public final class Resource extends InputSource {

	private final byte[] bdata;
	private final char[] cdata;
	private final String encoding;
	private final String publicID;
	private final String systemID;
	private final long expires;

	/**
	 * Construct a resource.
	 * @param bdata The byte data
	 * @param cdata The byte data
	 * @param encoding The character encoding (may be null)
	 * @param publicID The publicID (may be null)
	 * @param systemID The systemID
	 * @param expires When this Resource expires.
	 */
	public Resource(final byte[] bdata, final char[] cdata, 
			final String encoding, final String publicID,
			final String systemID, final long expires) {
		super();
		this.bdata = bdata;
		this.cdata = cdata;
		this.encoding = encoding;
		this.publicID = publicID;
		this.systemID = systemID;
		this.expires = expires;
	}

	@Override
	public InputStream getByteStream() {
		// the resolver deals with chars only.
		return bdata == null ? null : new ByteArrayInputStream(bdata);
	}

	@Override
	public Reader getCharacterStream() {
		return cdata == null ? null : new CharArrayReader(cdata);
	}

	@Override
	public String getEncoding() {
		return encoding;
	}

	@Override
	public String getPublicId() {
		return publicID;
	}

	@Override
	public String getSystemId() {
		return systemID;
	}

	long getExpires() {
		return expires;
	}
	

	@Override
	public void setByteStream(InputStream arg0) {
		throw new UnsupportedOperationException("Resource is ReadOnly");
	}

	@Override
	public void setCharacterStream(Reader arg0) {
		throw new UnsupportedOperationException("Resource is ReadOnly");
	}

	@Override
	public void setEncoding(String arg0) {
		throw new UnsupportedOperationException("Resource is ReadOnly");
	}

	@Override
	public void setPublicId(String arg0) {
		throw new UnsupportedOperationException("Resource is ReadOnly");
	}

	@Override
	public void setSystemId(String arg0) {
		throw new UnsupportedOperationException("Resource is ReadOnly");
	}

	int getCharCount() {
		return bdata == null ? cdata.length : bdata.length;
	}

}
