/* Copyright 2012 Rolf Lear

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
   
 */

package net.tuis.resolver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ext.EntityResolver2;

/**
 * Accesses web-based resources, caches them on disk for future retrieval. The
 * cache honours all the normal life-cycle indicators for web resources. It will
 * not re-access the web unless the document is out of date. Even then, it will
 * check to make sure the web document has actually changed. Only then will it
 * download the full web-based document.
 * <p>
 * This Repository is thread-safe. Additionally, it uses File locking to allow
 * multiple instances of this Cache to access the same files on disk. It is thus
 * safe to have multiple instances of this cache sharing a single cache
 * directory, even if the other instances are in different JVMs. Because this
 * uses File locking, it is probably not safe to use a network-based (NFS/Samba)
 * location for storing the cache.
 * <p>
 * There are a number of limitations this cache has:
 * <ul>
 * <li>It only handles HTTP-based (or HTTPS) URLs
 * <li>When reading files from the cache to the end user, it reads the entire
 * file in to memory (so that if some other thread updates the file the
 * in-memory copy cannot change).
 * <li>It cannot process items more than 2GB (Integer.MAX_VALUE bytes) since the
 * entire file has to fit in a byte[] array.
 * <li>If some other tool (other than this class) manipulates the cache
 * cachefolder it may cause this tool to become confused. This tool always errs
 * on the side of caution when returning resources, and if there is a problem
 * with a cache entry this tool will remove the cache entry, and try again.
 * </ul>
 * <p>
 * When it comes to the actual cache entries, this code honours the standard
 * HTTP Caching directives in the protocol. These directives are typically used
 * to indicate to caching proxy servers how long to cache the entry. This is the
 * same thing this code does, so it makes sense to use the same directives.
 * Unfortunately, it is still possible for there to be a mismatch then between
 * this cache and the source.
 * <p>
 * This class uses the internal java.util.logging API to log information about
 * caching. It uses it's class name as the logging key. All logging is done at
 * either the 'FINE' or 'FINEST' level. Consider these to be Info, and Debug
 * type messages. There are adapters and other tools to convert these messages
 * to other Logging systems, but while the value of the messages is useful for
 * diagnosing issues, the cost of including some other Logging API is too
 * significant to use some other API. If you want to see messages from this
 * class you can:
 * <p>
 * 
 * <pre>
 * ConsoleHandler ch = new ConsoleHandler();
 * ch.setLevel(Level.ALL);
 * Logger logger = Logger.getLogger(CachedHTTPRepository.class.getName());
 * logger.setLevel(Level.ALL);
 * logger.addHandler(ch);
 * </pre>
 * 
 * @author Rolf Lear
 */
public class CachedHTTPRepository implements EntityResolver2 {

	/**
	 * Used to indicate that there is a problem with a Cache Entry. The message will describe the problem.
	 * 
	 * @author Rolf Lear
	 *
	 */
	private static final class CacheEntryCorruptException extends Exception {

		private static final long serialVersionUID = 1L;

		public CacheEntryCorruptException(String message, Throwable cause) {
			super(message, cause);
		}

		public CacheEntryCorruptException(String message) {
			super(message);
		}

	}

	/**
	 * Used to indicate that there is network problem with a URL.
	 * 
	 * @author Rolf Lear
	 *
	 */
	private static final class NoAccessException extends Exception {

		private static final long serialVersionUID = 1L;

		public NoAccessException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	/** The lock interrupt thread gets a unique ID based on this. */
	private static final AtomicInteger threadid = new AtomicInteger();

	/** Used to name and daemonize the interrupt threads. */
	private static final ThreadFactory threadfac = new ThreadFactory() {
		@Override
		public Thread newThread(Runnable r) {
			final Thread ret = new Thread(r, "CachedHTTPRepository Lock Interrupt Thread " + threadid.incrementAndGet());
			ret.setDaemon(true);
			return ret;
		}
	};

	/** This scheduler is used to time the interrupt of a File lock attempt */
	private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, threadfac);

	/** used for logging messages. */
	private static final Logger logger = Logger.getLogger(CachedHTTPRepository.class.getName());

	/** Only populated once per JVM */
	private static final AtomicReference<File> defaultdir = new AtomicReference<File>();

	/** What to tell Web servers we are */
	private static final String DEFAULTUSERAGENT = CachedHTTPRepository.class.getName() + " 1.0";

	/** Thread-local for storing md5 MessageDigests */
	private static final ThreadLocal<MessageDigest> localmd5 = new ThreadLocal<MessageDigest>();

	// HTTP 1.1 protocol says, unless specified otherwise, the charset is...
	private static final String DEFCHARSET = "ISO-8859-1";

	// The following are properties we use for managing the cache. CURL is short for CachedHTTPRepository
	private static final String EXPIRES    = "CURL_EXPIRES";
	private static final String EXPIRESHR  = "CURL_EXPIRESHR";
	private static final String SYSTEMURL  = "CURL_URL";
	private static final String MD5        = "CURL_MD5";
	private static final String CHARSET    = "CURL_CHARSET";
	private static final String MODIFIED   = "CURL_MODIFIED";
	private static final String RESKEY     = "CURL_RESKEY";
	private static final String TOUCHTIME  = "CURL_TOUCHED";
	private static final String SPEEDTIME  = "CURL_SPEED";
	private static final String CREATEDTIME= "CURL_CREATED";

	/**
	 * This key map translates a String representation of a URL to an Object The
	 * Object is a safe item to do Synchronization on. One object per unique
	 * resource. Additionally, the hashCode() of the Object is typically unique
	 * in a JVM, and it is used as an ID for log messages - to associate log
	 * messages of one lookup URL to other log messages of the same url.
	 */
	private static final ConcurrentHashMap<String, Object> keymap = new ConcurrentHashMap<String, Object>();

	/**
	 * Useful shortcut for checking properties.
	 * 
	 * @param props
	 *        The Properties to get the property from.
	 * @param key
	 *        The property to get
	 * @return the property value.
	 * @throws CacheEntryCorruptException
	 *         if the property is not set - which indicates corruption.
	 */
	private static final String requireProperty(final Properties props, final String key)
			throws CacheEntryCorruptException {
		final String val = props.getProperty(key);
		if (val == null) {
			throw new CacheEntryCorruptException("Properties file is required to have key " + key);
		}
		return val;
	}

	/**
	 * Useful shortcut for checking properties - 
	 * the actual property must match the expected value
	 * 
	 * @param props
	 *        The Properties to get the property from.
	 * @param key
	 *        The property to get
	 * @param expect
	 *        The value we expect the property to have.
	 * @throws CacheEntryCorruptException
	 *         if the property is not set - which indicates corruption.
	 */
	private static final void checkProperty(final Properties props, final String key, final String expect)
			throws CacheEntryCorruptException {
		final String actual = requireProperty(props, key);
		if (!actual.equals(expect)) {
			throw new CacheEntryCorruptException(String.format(
					"We expect property %s to have value '%s' but instead it has value '%s'", key, expect, actual));
		}
		// everything is OK.
	}

	/**
	 * Useful shortcut for checking properties that are expected to be long
	 * values.
	 * 
	 * @param props
	 *        The Properties to get the property from.
	 * @param key
	 *        The property to get
	 * @return the property value.
	 * @throws CacheEntryCorruptException
	 * @throws CacheEntryCorruptException
	 *         if the property is not set - which indicates corruption.
	 */
	private static final long requireLongProperty(final Properties props, final String key)
			throws CacheEntryCorruptException {
		final String val = requireProperty(props, key);
		try {
			return Long.parseLong(val);
		} catch (NumberFormatException nfe) {
			throw new CacheEntryCorruptException("String value " + val + " is not a long value.", nfe);
		}
	}

	/**
	 * Convert the MD5 bytes to a String in the standard form.
	 * 
	 * @param md5
	 *        The MD5 bytes to convert to string.
	 * @return the string representation of the bytes.
	 */
	private static final String md5ToString(final byte[] md5) {
		return Base64.encodeToString(md5, false);
	}

	/**
	 * Log the header details of an HTTP Response
	 * 
	 * @param lk
	 *        The ID of this sync-lock key
	 * @param con
	 *        The connection (with the header details)
	 */
	private static final void logHeaders(final String lk, final HttpURLConnection con) {
		if (logger.isLoggable(Level.FINEST)) {
			StringBuilder sb = new StringBuilder();
			sb.append("URL Lookup for key ").append(lk).append(": ");
			sb.append(con.getURL().toExternalForm()).append("\n");
			for (Map.Entry<String, List<String>> me : con.getHeaderFields().entrySet()) {
				sb.append(String.format(" %-20s = %s\n", me.getKey(), String.valueOf(me.getValue())));
			}
			logger.finest(sb.toString());
		}
	}

	/**
	 * Locate a default, standard location for storing the cache in.
	 * 
	 * @return The default cache directory.
	 * @throws IOException
	 *         if the cache location is not appropriate for some reason.
	 */
	private static final File getDefaultCacheDir() throws IOException {
		final File current = defaultdir.get();
		if (current != null) {
			// it has been set once already....
			return current;
		}

		final String sysdir = System.getProperty(CachedHTTPRepository.class.getName());
		if (sysdir != null) {
			// use the specified cachefolder.
			final File f = new File(sysdir);
			if (!f.exists()) {
				if (!f.mkdirs()) {
					throw new IOException("Unable to create directory: " + f);
				}
			}
			if (!f.isDirectory()) {
				throw new IOException("Not a directory: " + f);
			}

			if (!defaultdir.compareAndSet(null, f)) {
				// some concurrent process got there before us.
				return defaultdir.get();
			}

			if (logger.isLoggable(Level.FINE)) {
				logger.fine("Located Default CacheDir: " + f.getAbsolutePath());
			}
			return f;
		}

		// create a temp file, and use the temp file to pinpoint the temp
		// directory.
		final File tmpf = File.createTempFile("urlrepo.", ".deleteme");
		final File dir = tmpf.getParentFile();
		tmpf.deleteOnExit();
		if (!tmpf.delete()) {
			tmpf.deleteOnExit();
		}
		String dname = "urlrepo." + System.getProperty("user.name");
		dname = dname.replaceAll("\\W", "_");
		final File ret = new File(dir, dname);

		if (!defaultdir.compareAndSet(null, ret)) {
			// some concurrent process got there before us.
			return defaultdir.get();
		}

		if (logger.isLoggable(Level.FINE)) {
			logger.fine("Located Default CacheDir: " + ret.getAbsolutePath());
		}
		return ret;
	}

	/**
	 * Get a thread-safe MessageDigest instance
	 * 
	 * @return The instance.
	 */
	private static final MessageDigest getMD5Digest() {
		final MessageDigest ret = localmd5.get();
		if (ret != null) {
			ret.reset();
			return ret;
		}
		try {
			final MessageDigest nmd = MessageDigest.getInstance("MD5");
			localmd5.set(nmd);
			return nmd;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Unable to retrieve an MD5 Digest algorithm", e);
		}

	}

	/**
	 * Convert a URL to a String that is useful for a file name too.
	 * 
	 * @param url
	 *        The URL to convert
	 * @return The key filename
	 */
	private static String getCacheLocation(URL url) {
		// needs to be a stringBuffer to use appendReplacement
		final StringBuffer sb = new StringBuffer();
		sb.append(url.getProtocol()).append("/");
		final String auth = url.getAuthority().replace(':', '~');
		sb.append(auth);
		final String path = url.getPath();
		sb.append(path);
		final String query = url.getQuery();
		if (query != null && query.length() > 0) {
			sb.append("$q.");
			Matcher mat = Pattern.compile("(\\W)").matcher(query);
			while (mat.find()) {
				sb.append(mat.appendReplacement(sb, String.format("$u%04x", mat.group(1).charAt(0))));
			}
			mat.appendTail(sb);
		}

		return sb.toString();
	}

	/**
	 * Get a unique Object used to lock each item in the cache.
	 * 
	 * @param key
	 *        The location for this lock.
	 * @return The locking object.
	 */
	private static Object getSyncLock(String key) {
		final Object lock = new Object();
		final Object o = keymap.putIfAbsent(key, lock);
		if (o != null) {
			return o;
		}
		return lock;
	}

	/**
	 * Set up an interruptible system of file-lock creation. The goal is to try
	 * to lock a file, but give up after a set time (timeout milliseconds).
	 * 
	 * @param file
	 *        The file to lock (used for reporting only).
	 * @param channel
	 *        The file channel that is being locked
	 * @param timeout
	 *        the number of milliseconds to wait for before timing out the lock
	 *        attempt.
	 * @param shared
	 *        Whether the lock should be shared or not.
	 * @return the new lock, or null if a lock is not accomplished in the time
	 * @throws IOException
	 *         For a number of reasons. See the message.
	 */
	private static FileLock lockFile(final String lk, final File file, final FileChannel channel, final int timeout,
			final boolean shared) throws IOException {

		if (Thread.currentThread().isInterrupted()) {
			// last-ditch check to see if we are already interrupted....
			throw new IllegalStateException("Cannot lock a file when the interrupt flag is set on locking thread: "
					+ Thread.currentThread().getName());
		}

		// kill-thread-interrupted (kti)
		// we also use the kti as a monitor lock.... this makes it contain the
		// state,
		// as well as making the interrupt and state change 'atomic'.
		// kti is not really useful as an atomic, but it makes a great boolean
		// container....
		final AtomicBoolean kti = new AtomicBoolean(false);

		// the thread to interrupt after 30 seconds.
		final Thread threadtointerrupt = Thread.currentThread();

		final Runnable killit = new Runnable() {
			@Override
			public void run() {
				boolean done = false;
				// synchronize on the kti to make the kti-set and the interrupt
				// an atomic operation....
				// kti is not really useful as an atomic, but it makes a great
				// boolean container....
				synchronized (kti) {
					if (!kti.get() && !threadtointerrupt.isInterrupted()) {
						
						threadtointerrupt.interrupt();
						kti.set(true);
						// thread has not yet locked, and it is still trying...
						done = true;
					}
				}
				logger.fine(lk + " Lock-Interrupt Thread run, " + (done ? "Interrupted" : "No need to interrupt"));
			}
		};

		final ScheduledFuture<?> killjob = scheduler.schedule(killit, timeout, TimeUnit.MILLISECONDS);

		FileLockInterruptionException flie = null;

		try {
			// try to get the lock, waiting 'indefinitely' (but we will be
			// interrupted in 'timeout' millisecs)
			return channel.lock(0, Long.MAX_VALUE, shared);
		} catch (FileLockInterruptionException e) {
			// we were interrupted either before, or while waiting for the lock
			flie = e;
			return null;
		} finally {

			// make sure to kill the scheduled job....
			killjob.cancel(true);

			synchronized (kti) {
				// Four thread states....
				// 1. killthread timed out, and interrupted us.
				// 2. we got lock, killthread is waiting before killing us.
				// 3. some other thread interrupted us
				// 4. Some uncaught exception or error happened.
				//
				// first thing we do is indicate that, if it has not yet happened,
				// the interrupt should not happen from the kill-thread.
				// we record the state of the killthread too....

				final boolean kstate = kti.getAndSet(true);
				if (kstate) {
					// state 1 - but we may already have got the file lock.... 
					// it is possible that the interrupt happened after we got the
					// file lock, but before we got the kti sync lock. The difference
					// between those will be that we either return non-null, or null.
					// since kstate is true though, it means that our interrupt
					// state is caused by the kill-thread.... and we need to undo
					// that. We reset our interrupt flag.... 
					Thread.interrupted();
				} else if (flie != null) {
					// state 3
					// some thread other than the kill thread interrupted us
					// make sure our interrupt flag is still set.
					Thread.currentThread().interrupt();
					// throw the flie (an IOException too).
					throw flie;
				} else {
					// state 2 or 4.... doesn't matter.
					// either returning the lock, or throwing an exception....
					// in either case, we have already cancelled the kill thread.
				}
			}

		}
	}

	private static final String getFirstHeader(final Map<String,List<String>> headers, final String key) {
		final List<String> lst = headers.get(key);
		if (lst == null) {
			return null;
		}
		if (!lst.isEmpty()) {
			return lst.get(0);
		}
		return null;
	}

	// Where the cache is.
	private final File cachefolder;

	// The user-agent string sent to web servers.
	private final AtomicReference<String> useragent = new AtomicReference<String>();

	// The largest file we will cache.
	private final AtomicInteger maxentrysize = new AtomicInteger();
	
	// how long we will wait while trying to lock a file.
	private final AtomicInteger locktimeout = new AtomicInteger();

	/**
	 * Create a CachedHTTPRepository that uses a default location for the cache.
	 * 
	 * @throws IOException
	 *         if the Default cachefolder is not accessible
	 */
	public CachedHTTPRepository() throws IOException {
		this(getDefaultCacheDir());
	}

	/**
	 * Create a caching web-based Repository that caches the web resources
	 * locally in the supplied cachedir.
	 * 
	 * @param cachedir
	 *        Where to cache the web resources.
	 * @throws IOException
	 *         if the Default cachefolder is not accessible
	 */
	public CachedHTTPRepository(File cachedir) throws IOException {
		if (cachedir == null) {
			throw new NullPointerException("Null Cache directory");
		}
		if (!cachedir.exists()) {
			if (!cachedir.mkdirs()) {
				throw new IOException("Unable to create directory " + cachedir);
			}
		}
		if (!cachedir.isDirectory()) {
			throw new IOException("Cache location is not a directory " + cachedir);
		}
		try {
			// create a temp file to make sure all is in order.
			final File tmpfile = File.createTempFile("jdom.", ".deleteme", cachedir);
			if (!tmpfile.delete()) {
				tmpfile.deleteOnExit();
			}
		} catch (IOException e) {
			throw new IOException("Unable to create a test file in " + cachedir + ": " + e.getMessage(), e);
		}
		// ensure that we can get an MD5 digest.
		// may throw IllegalStateExcetion ... but it is good to do it in the
		// constructor....
		getMD5Digest();
		useragent.set(DEFAULTUSERAGENT);
		maxentrysize.set(32 << 20); // 32MB.
		locktimeout.set(30000); // 30 seconds.
		cachefolder = cachedir;
	}
	
	/**
	 * Get the location of the Cache.
	 * @return The Cache folder location.
	 */
	public File getCacheFolder() {
		return cachefolder;
	}

	/**
	 * Get the current user agent used by this Repository
	 * 
	 * @return the current user agent string.
	 */
	public String getUserAgent() {
		return useragent.get();
	}

	/**
	 * Set the current user agent used by this Repository
	 * 
	 * @param agent
	 *        the user agent string to set. Use null or empty string to restore
	 *        the default value.
	 */
	public void setUserAgent(final String agent) {
		if (agent == null || agent.length() == 0) {
			useragent.set(DEFAULTUSERAGENT);
		} else {
			useragent.set(agent);
		}
	}

	/**
	 * Get the size of the largest entry that will be cached
	 * 
	 * @return The size of the largest entry that will be cached.
	 */
	public int getMaxEntrySize() {
		return maxentrysize.get();
	}
	
	/**
	 * Set the size of the largest entry that will be cached
	 * 
	 * @param maxsize
	 *        The size of the largest entry that will be cached.
	 */
	public void setMaxEntrySize(int maxsize) {
		if (maxsize < 0) {
			throw new IllegalArgumentException("maxsize must be positive (or 0).");
		}
		if (maxsize == Integer.MAX_VALUE) {
			// we cannot actually do MAX because we test for value larger than
			// maxsize,
			// and there is no larger value...
			maxsize = Integer.MAX_VALUE - 1;
		}
		maxentrysize.set(maxsize);
	}

	/**
	 * Get the number of milliseconds we wait for a file lock.
	 * 
	 * @return the number of milliseconds we wait for a file lock.
	 */
	public int getLockTimeout() {
		return locktimeout.get();
	}

	/**
	 * Set the number of milliseconds we wait for a file lock.
	 * 
	 * @param timeout the number of milliseconds we wait for a file lock.
	 */
	public void setLockTimeout(int timeout) {
		if (timeout < 0) {
			throw new IllegalArgumentException("timeout must be positive (or 0).");
		}
		locktimeout.set(timeout);
	}

	/**
	 * Resolve a URL to a Resource.
	 * 
	 * @param publicID
	 *        the Public ID to put on the returned Resource
	 * @param url
	 *        The URL to resolve
	 * @return The Resource (or null if this tool could not do the resolution).
	 * @throws IOException
	 *         if there is a problem loading the Resource.
	 */
	public Resource resolve(final String publicID, final URL url) throws IOException {

		{
			// Simple scope-block to deal with some validation.
			final String protocol = url.getProtocol();
	
			if (!"http".equals(protocol) && !"https".equals(protocol)) {
				if (logger.isLoggable(Level.FINE)) {
					logger.fine("Unable to resolve non-http(s) protocol: " + url);
				}
				return null;
			}
			
			final String fragment = url.getRef();
			if (fragment != null && fragment.length() > 0) {
				if (logger.isLoggable(Level.FINE)) {
					logger.fine("Unable to resolve URL's that have document Fragment references: " + url);
				}
				return null;
			}

		}
		
		/*
		 * Right, this is the complex method. Because of the various IO type
		 * resources that are opened, and need to be closed (safely), it is very
		 * hard to break this method in to separate pieces. The try/catch blocks
		 * are ridiculous.... apologies in advance.
		 */

		// The cache location... it is a relative path name too.
		final String kk = getCacheLocation(url);

		// get an object that is unique for the file location.
		final Object lock = getSyncLock(kk);
		
		// convert the unique lock in to a string that is unique too, and
		// useful for logging.
		final String lk = String.format("%08x:", System.identityHashCode(lock));

		synchronized (lock) {
			
			// Only one thread at a time (in this JVM) can enter the control
			// for this particular cached object (file location)
			//
			// The procedure is to get a read-lock on the control file.
			// if the control file is up to date, we return the file data.
			// otherwise we get a write lock on the file, and update the web
			// resource. Once we have the web resource, we update the data file.
			// With the data file updated, we then update the control file,
			// and relinquish the lock.
			//
			// If we try to escalate to a write lock, and fail, it means some
			// other JVM is updating the control file. We kick back to the
			// beginning of the loop, and wait for the other JVM to update the
			// file (we will know because we block waiting for a lock).
			if (logger.isLoggable(Level.FINE)) {
				logger.fine(lk + " Resolving " + url);
			}
			
			boolean failed = false;
			
			try {

				final File control = new File(cachefolder, kk + ".control");
				final File datafile = new File(cachefolder, kk + ".data");
				
				{
					// another simple scope-limiting block.
					// no need to make the 'dir' variable stick around
					// too long.
					final File dir = control.getParentFile();
					if (!dir.isDirectory()) {
						if (!dir.mkdirs()) {
							logger.fine(lk + " Abandoning: Unable to create cachefolder " + dir);
							return null;
						}
					}
				}
	
				// start off with a shared lock.
				boolean lockshared = true;
	
				int tries = 0;
				// we have a number of 'continue' statements in this loop.
				// by labelling the loop it is easier to manage.
				// note, internal try-catch blocks have finally blocks... so
				// the 'continue' statements may run finally blocks before
				// actually looping back.
				attemptloop: while (++tries <= 5) {
					
					if (logger.isLoggable(Level.FINEST)) {
						logger.finest(String.format("%s Attempt %d of 5.", lk, tries));
					}
	
					// rafok will be set if the RAF is to beclosed in a normal state.
					boolean rafxthrow = false;
					final RandomAccessFile raf = new RandomAccessFile(control, "rw");
					try {
						// the finally block of this try/catch must ensure the RAF is closed.
						// the raf.close() will also close the channel.
						final FileChannel channel = raf.getChannel();
	
						// Lock the entire file.
						// this will block for locktimeout seconds or until the file becomes
						// available.
						final FileLock slock = lockFile(lk, control, channel, locktimeout.get(), lockshared);
	
						if (slock == null) {
							// timed out! try again
							// in order to be here, there must be one of:
							// 1. some OS issue
							// 2. The file is share-locked by some other JVM and we
							// are
							// attempting an exclusive lock,
							// 3. The file is exclusive-locked by some other JVM,
							// and we are trying to lock it.....
							if (logger.isLoggable(Level.FINEST)) {
								logger.finest(lk + " Timeout getting " + (lockshared ? "shared" : "exclusive") 
										+ " lock on control file. Will try again ");
							}
							// indicate a close() failure thould be thrown
							rafxthrow = true;
							continue attemptloop;
						}
	
						// OK, we have lock on the control file... let's play.
						// normally, one would not use exceptions to manage program
						// control.... but, in this case there are so many places that
						// could cause a cache entry to be broken, we do.
						// So, the 'normal' case is to use an existing cache entry.
						// If the existing cache entry is broken for some reason (or
						// does not exist), then we throw a special exception, and
						// rebuild the entry.
						// Remember, at this point we have a sync lock on this entry
						// in this JVM, and also
						// a file-lock (perhaps shared) on the control file....
	
						// this is the reference time to see if things
						// are out of date.
						final long time = System.currentTimeMillis();
	
						try {
							if (logger.isLoggable(Level.FINEST)) {
								logger.finest(String
										.format("%s %s Lock obtained ", lk, lockshared ? "Shared" : "Exclusive"));
							}
	
							try {
	
								if (!datafile.exists()) {
									// need to load from fresh.
									throw new CacheEntryCorruptException("Non-existing data file: " + datafile);
								}
	
								final Properties props = chanToProperties(channel);
	
								// when do the properties say we expire.
								final long expires = requireLongProperty(props, EXPIRES);
								final String lastmod = requireProperty(props, MODIFIED);
								final String xmd5 = requireProperty(props, MD5);
								final String charset = requireProperty(props, CHARSET);
	
								final String urlef = url.toExternalForm();
	
								checkProperty(props, RESKEY, kk);
								checkProperty(props, SYSTEMURL, urlef);
								
								boolean stale = false;
	
								// all properties are in order
								// cache entry is complete...
	
								if (expires < time) {
									// it has expired... let's update it....
									if (logger.isLoggable(Level.FINEST)) {
										logger.finest(String.format("%s Resource expired %.3fs ago.", lk,
												(time - expires) / 1000.0));
									}
	
									if (lockshared) {
										// need to reload it....
										// and we need an exclusive lock...
										lockshared = false;
										if (logger.isLoggable(Level.FINE)) {
											logger.fine(String.format("%s Cache out of date. Upgrading to exclusive lock.",
													lk));
										}
										continue attemptloop;
									}
									// disk cache has expired, rebuild, or
									// something.
	
									// we have an exclusive lock, we check the
									// resource is up to date on the server....
									// if the server file has changed, we throw a
									// CacheEntryCorrupt exception....
									try {
										checkResource(time, lk, channel, lastmod, props, url);
									} catch (NoAccessException nae) {
										// we have an existing, expired, but otherwise valid cache
										// entry. We cannot connect to a network. We return the expired
										// entry.
										stale = true;
									}
	
								}
	
								// If we get here, then the cache entry is in good
								// condition
								// this readfile will compare the md5 of the actual
								// data with the expectations...
								// which may also throw a cacheentrycorrupt....
								final byte[] data = readFile(kk, xmd5, datafile);
								
								// indicate a close() failure should be thrown
								rafxthrow = true;
	
								// return our new resource
								return new Resource(data, null, charset, publicID, urlef, expires, stale);
	
							} catch (CacheEntryCorruptException cece) {
								if (lockshared) {
									if (logger.isLoggable(Level.FINE)) {
										logger.fine(String.format("%s Cache entry rebuild needs exclusive lock: %s", lk,
												cece.getMessage()));
									}
									lockshared = false;
									// indicate a close() failure should be thrown
									rafxthrow = true;
									continue attemptloop;
								}
								// we have an exclusive lock, and there's something amiss with the
								// cache entry, recreate it.
								try {
									final Resource ret = recreateResource(lk, kk, channel, datafile, url, publicID, time);
									// indicate a close() failure should be thrown
									rafxthrow = true;
									return ret;
								} catch (NoAccessException nae) {
									final Throwable cause = nae.getCause();
									if (cause instanceof IOException) {
										throw (IOException)cause;
									}
									throw new IOException("Unable to recreate Resource " + url, cause);
								}
							}
	
						} finally {
							slock.release();
						}
					} finally {
						// we did not do a clean close on the RandomAccessFile.
						// which also means we are in the process of throwing an
						// exception already.
						try {
							// raf.close() will also close the attached channel.
							raf.close();
						} catch (IOException ioe) {
							if (rafxthrow) {
								throw ioe;
							}
							// we must already be throwing an exception. Just log this one.
							logger.log(Level.FINE, "Unable to close RandomAccessFile on " + control, ioe);
						}
					}
				}
				if (logger.isLoggable(Level.FINE)) {
					logger.fine(String.format("%s Exceeded %d attempts. Giving up.", lk, tries - 1));
				}
				return null;
			} catch (IOException ioe) {
				failed = true;
				if (logger.isLoggable(Level.FINE)) {
					logger.log(Level.FINE, String.format("%s Resolution failed: ", lk), ioe);
				}
				throw ioe;
			} finally {
				if (!failed && logger.isLoggable(Level.FINE)) {
					logger.log(Level.FINE, String.format("%s Resolution complete for %s", lk, url.toExternalForm()));
				}
			}
		}
	}

	/**
	 * Get an HttpURLConnection to a web-resource
	 * @param method The HTTP Method for the resource.
	 * @param url The URL we are connecting to
	 * @param lastmod the time the server told us the resource was last modified.
	 * @return The connection
	 * @throws IOException for the normal reasons.
	 */
	private HttpURLConnection getConnection(final String method, final URL url, 
			final String lastmod) throws NoAccessException, IOException {
		final HttpURLConnection con = (HttpURLConnection) url.openConnection();
		// we are a cache ourselves... we want to punch through to the real source.
		con.setUseCaches(false);
		// We don't want to have to handle the redirects ourselves.
		con.setInstanceFollowRedirects(true);
		// we only get the HEAD if we previously had the file, but it is out of
		// date...
		con.setRequestMethod(method);
		if (lastmod != null && lastmod.length() > 0) {
			// we expect there to be a previous copy, with this mod time.
			// avoid bug with getLastModified not parsing time-zone....
			con.addRequestProperty("If-Modified-Since", lastmod);
		}
		// set our user-agent.
		con.setRequestProperty("User-Agent", useragent.get());
		// push through the actual connection.
		try {
			con.connect();
		} catch (SocketException ce) {
			if (logger.isLoggable(Level.FINE)) {
				logger.log(Level.FINE, "Unable to connect to " + url, ce);
			}
			throw new NoAccessException("Unable to connect to URL", ce);
		}
		return con;
	}

	/**
	 * calculate whether a web resource is still up to date.
	 * @param time The 'current' time
	 * @param lk The logging key
	 * @param channel The channel for the control file
	 * @param lastmod When the web resource was last modified
	 * @param props The properties of the control file
	 * @param url The url of the web resource
	 * @throws IOException If there is a read problem
	 * @throws CacheEntryCorruptException If the web resource has been changed.
	 * @throws NoAccessException 
	 */
	private void checkResource(final long time, final String lk,
			final FileChannel channel, final String lastmod, final Properties props,
			final URL url) throws IOException, CacheEntryCorruptException, NoAccessException {
		// we need to check that the resource is up to date.
		// we use the 'HEAD' method to get the state
		final HttpURLConnection con = getConnection("HEAD", url, lastmod);
		try {
			logHeaders(lk, con);
			if (con.getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED) {
				if (logger.isLoggable(Level.FINEST)) {
					logger.finest(lk + " Web resource " + url + " not modified.");
				}
				final Map<String, List<String>> headers = con.getHeaderFields();
				final long exp = calcExpire(time, con.getExpiration(), headers.get("Cache-Control"));
				// bug in getLastModified - does not parse the TimeZone... assumes UTC.
				//final long lmod = con.getLastModified();
				final String lmod = getFirstHeader(headers, "Last-Modified");
				props.setProperty(EXPIRES, Long.toString(exp));
				props.setProperty(EXPIRESHR, String.format("%tc", exp));
				props.setProperty(MODIFIED, lmod == null ? "" : lmod);
				// write out the properties
				propertiesToChannel(channel, props);
				return;
			}
			throw new CacheEntryCorruptException("Entry has been modified on server.");

		} finally {
			con.disconnect();
		}

	}

	/**
	 * Read a file off disk, calculating the MD5 sum, and comparing it to what we expect.
	 * @param key The key of the file we are loading.
	 * @param expect The MD5 sum we expect
	 * @param datafile The actual file to load
	 * @return The byte[] array from the file
	 * @throws IOException if there is an IO problem
	 * @throws CacheEntryCorruptException If the MD5 sum does not match.
	 */
	private byte[] readFile(final String key, final String expect, final File datafile) throws IOException,
			CacheEntryCorruptException {

		// it is safe to do an int cast because we never cache more than int
		// sized files.
		final byte[] buffer = new byte[(int) datafile.length()];
		final FileInputStream fis = new FileInputStream(datafile);
		int len = 0;
		int offset = 0;
		final MessageDigest md = getMD5Digest();
		while (offset < buffer.length && (len = fis.read(buffer, offset, buffer.length - offset)) >= 0) {
			md.update(buffer, offset, len);
			offset += len;
		}
		if (offset < buffer.length) {
			// end of file before file length....
			throw new CacheEntryCorruptException("Unexpected File Size Mismatch (smaller than expected): " + key);
		}
		if (fis.read() >= 0) {
			// end of file before file length....
			throw new CacheEntryCorruptException("Unexpected File Size Mismatch (larger than expected): " + key);
		}
		final String actual = md5ToString(md.digest());
		if (!expect.equals(actual)) {
			throw new CacheEntryCorruptException("Unexpected MD5 mismatch: " + key);
		}
		return buffer;
	}

	private Resource recreateResource(final String lk, final String key, final FileChannel channel,
			final File datafile, final URL url, final String publicID, final long time)
					throws IOException, NoAccessException {
		// we need to update the resource.
		long start = System.currentTimeMillis();
		final HttpURLConnection con = getConnection("GET", url, null);
		try {
			final int stat = con.getResponseCode();
			logHeaders(lk, con);
			if (stat == HttpURLConnection.HTTP_OK) {
				final int contentlen = con.getContentLength();
				final int largest = maxentrysize.get();
				if (contentlen > largest) {
					if (logger.isLoggable(Level.FINE)) {
						logger.finest(lk + " Web resource " + url + " is too large to cache: " + contentlen
								+ " bytes is larger than max acceptable " + largest + " bytes.");
					}
				}
				final Map<String, List<String>> headers = con.getHeaderFields();
				final long exp = calcExpire(time, con.getExpiration(), headers.get("Cache-Control"));
				final String lmod = getFirstHeader(headers, "Last-Modified");
				final String enc = getCharset(con.getContentType());
				final String servermd5 = con.getHeaderField("Content-MD5");

				boolean fosok = false;
				final FileOutputStream fos = new FileOutputStream(datafile);
				try {
					final int sz = contentlen > 0 ? contentlen : 10240;
					final ByteArrayOutputStream baos = new ByteArrayOutputStream(sz);
					final byte[] buffer = new byte[4096];
					int len = 0;
					int sizesofar = 0;
					final MessageDigest md = getMD5Digest();
					boolean webok = false;
					final InputStream webis = con.getInputStream();
					try {
						while ((len = webis.read(buffer)) >= 0) {
							fos.write(buffer, 0, len);
							baos.write(buffer, 0, len);
							md.update(buffer, 0, len);
							sizesofar += len;
							if (sizesofar > largest) {
								if (logger.isLoggable(Level.FINE)) {
									logger.finest(lk + " Web resource " + url + " is too large to cache: " + sizesofar
											+ " bytes is larger than max acceptable " + largest + " bytes.");
								}
								webis.close();
								webok = true;
								// null means that this code does not support
								// the URL...
								return null;
							}
						}
						webis.close();
						webok = true;
					} finally {
						if (!webok) {
							// we are exception handling... which means we are
							// never going to continue from here....
							// we close the URL InputStream as a courtesy.
							try {
								webis.close();
							} catch (IOException ioe) {
								logger.fine("Failed to close URL InputStream: " + url);
							}
						}
					}
					final String mymd5 = md5ToString(md.digest());
					if (servermd5 != null && !mymd5.equalsIgnoreCase(servermd5)) {
						throw new IOException("Unable to verify Our MD5 sum " + mymd5 + " against the server's "
								+ servermd5 + ". Data was corrupted on the stream");
					}

					final Properties props = new Properties();
					StringBuilder sb = new StringBuilder(1024);
					for (Map.Entry<String, List<String>> me : headers.entrySet()) {
						sb.setLength(0);
						if (me.getValue() != null) {
							boolean first = true;
							for (String s : me.getValue()) {
								if (!first) {
									sb.append(", ");
								}
								sb.append(s);
								first = false;
							}
						}
						props.setProperty(String.valueOf(me.getKey()), sb.toString());
					}
					
					final long touched = System.currentTimeMillis();

					props.setProperty(MD5, mymd5);
					props.setProperty(EXPIRES, Long.toString(exp));
					props.setProperty(EXPIRESHR, String.format("%tc", exp));
					props.setProperty(MODIFIED, lmod);
					props.setProperty(CHARSET, enc == null ? "" : enc);
					props.setProperty(SYSTEMURL, url.toExternalForm());
					props.setProperty(RESKEY, key);
					props.setProperty(SPEEDTIME, String.format(
							"%.3f seconds", (touched - start) / 1000.0));
					props.setProperty(CREATEDTIME, String.format("%tc", touched));

					// write out the properties
					propertiesToChannel(channel, props);
					fos.flush();
					fos.close();
					fosok = true;

					return new Resource(baos.toByteArray(), null, enc, publicID, url.toExternalForm(), exp, false);

				} finally {
					if (!fosok) {
						// there were previous exceptions.
						try {
							fos.flush();
							fos.close();
						} catch (IOException ioe) {
							// we are in exception handling anyway, just log
							// the problem.
							logger.fine("Failed to close File OutpuStream " + datafile);
						}
					}
				}

			}
			if (logger.isLoggable(Level.FINE)) {
				logger.fine(lk + " Unsuported HTTP return code: " + stat);
			}
			return null;

		} finally {
			con.disconnect();
		}

	}

	private String getCharset(final String contentType) {
		// parameter delimeter.
		if (contentType == null) {
			return DEFCHARSET;
		}
		final String[] parms = contentType.split("\\s*;\\s*");
		if (parms.length <= 1) {
			return DEFCHARSET;
		}
		Pattern pat = Pattern.compile("^charset\\s*=\\s*(.+)\\s*$");
		for (int i = 1; i < parms.length; i++) {
			Matcher matcher = pat.matcher(parms[i]);
			if (matcher.matches()) {
				return matcher.group(1);
			}
		}

		return DEFCHARSET;
	}

	private long calcExpire(final long time, final long expiration, final List<String> cachecontrol) {
		if (cachecontrol == null || cachecontrol.isEmpty()) {
			if (expiration < time) {
				// expires in 1 second.
				return time + 1000;
			}
			return expiration;
		}
		final long maxage = lookup(cachecontrol, "max-age");
		if (maxage > 0L) {
			return time + (maxage * 1000);
		}

		return time + 1000;
	}

	private long lookup(List<String> cachecontrol, String key) {
		final Pattern pat = Pattern.compile("^\\s*" + Pattern.quote(key) + "\\s*=\\s*(\\d+?)\\s*$");
		for (String p : cachecontrol) {
			Matcher mat = pat.matcher(p);
			if (mat.matches()) {
				return Long.parseLong(mat.group(1));
			}
		}
		return 0L;
	}

	/**
	 * Read a file channel in to a byte array, and convert that to a Properties
	 * 
	 * @param channel
	 *        The channel to read.
	 * @return The Properties
	 * @throws IOException
	 *         For all sorts of reasons.
	 */
	private Properties chanToProperties(FileChannel channel) throws IOException {
		if (channel.size() > 1 << 30) {
			throw new IllegalStateException("Channel file is too large " + channel.size());
		}
		channel.position(0L);
		final int sz = (int) channel.size();
		final byte[] bytes = new byte[sz < 1024 ? 1024 : sz];
		final ByteBuffer buffer = ByteBuffer.wrap(bytes);
		int len = 0;
		while ((len = channel.read(buffer)) >= 0) {
			if (len == 0 && buffer.remaining() <= 0) {
				throw new IllegalStateException("File size is apparently " + channel.size() + " but we have read "
						+ buffer.position() + " bytes.");
			}
		}
		len = buffer.position();
		Properties props = new Properties();
		ByteArrayInputStream bais = new ByteArrayInputStream(bytes, 0, len);
		props.load(bais);
		bais.close();
		return props;
	}

	/**
	 * Write a Properties to a file channel
	 * 
	 * @param channel
	 *        The channel to write.
	 * @param props
	 *        The Properties
	 * @throws IOException
	 *         For all sorts of reasons.
	 */
	private void propertiesToChannel(final FileChannel channel, final Properties props) throws IOException {
		// write the properties to a byte array.
		final ByteArrayOutputStream baos = new ByteArrayOutputStream();
		props.store(baos, "CachedHTTPRepository");
		props.setProperty(TOUCHTIME, String.format("%tc", System.currentTimeMillis()));
		final byte[] data = baos.toByteArray();

		// write the byte array to the channel.
		channel.position(0L);
		final ByteBuffer buffer = ByteBuffer.wrap(data);
		buffer.limit(data.length);
		while (buffer.hasRemaining()) {
			channel.write(buffer);
		}
		// set the channel's length as the limit of the data.
		channel.truncate(data.length);
	}



	@Override
	public InputSource resolveEntity(String publicId, String systemId)
			throws SAXException, IOException {
		return resolve(publicId, new URL(systemId));
	}

	/**
	 * This CachedHTTPRepository has nothing to do with external subsets.
	 * It always returns null.
	 */
	@Override
	public InputSource getExternalSubset(String name, String baseURI)
			throws SAXException, IOException {
		return null;
	}

	@Override
	public InputSource resolveEntity(String name, String publicId,
			String baseURI, String systemId) throws SAXException, IOException {
		try {
			final URI sysuri = new URI(systemId);
			if (!sysuri.isAbsolute() && baseURI != null) {
				final URI base = new URI(baseURI);
				final URI resolved = base.resolve(sysuri);
				return resolve(publicId, resolved.toURL());
			}
			return resolve(publicId, sysuri.toURL());
		} catch (URISyntaxException usi) {
			throw new SAXException("Unable to resolve Base (" + baseURI + 
					") and System (" + systemId + ") URI's to a URL", usi);
		}
	}


}
