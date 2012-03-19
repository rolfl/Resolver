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
	private final boolean stale;

	/**
	 * Construct a resource.
	 * @param bdata The byte data
	 * @param cdata The byte data
	 * @param encoding The character encoding (may be null)
	 * @param publicID The publicID (may be null)
	 * @param systemID The systemID
	 * @param expires When this Resource expires.
	 * @param stale indicates whether this Resource is stale.
	 */
	public Resource(final byte[] bdata, final char[] cdata, 
			final String encoding, final String publicID,
			final String systemID, final long expires, final boolean stale) {
		super();
		this.bdata = bdata;
		this.cdata = cdata;
		this.encoding = encoding;
		this.publicID = publicID;
		this.systemID = systemID;
		this.expires = expires;
		this.stale = stale;
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

	/**
	 * Get the time at which this entry expires.
	 * @return The expires time.
	 */
	public long getExpires() {
		return expires;
	}
	
	/**
	 * Indicate whether the cache entry was retuened even though it is stale.
	 * If true it means that we have an expired cache entry, but there is no
	 * way to connect to the origin server to revalidate it.
	 * 
	 * @return true if this Resource is stale.
	 */
	public boolean isStale() {
		return stale;
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
