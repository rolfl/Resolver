package net.tuis.resolver.testconcurrent;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import net.tuis.resolver.Base64;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;

@SuppressWarnings("javadoc")
public class ResolverTestHTTPServer extends AbstractHandler {
	
	private static final class Resources {
		private final MessageDigest md5engine;
		private final SimpleDateFormat sdfengine = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
		
		public Resources() throws ServletException {
			try {
				md5engine = MessageDigest.getInstance("MD5");
			} catch (NoSuchAlgorithmException e) {
				throw new ServletException(e);
			}
		}
	}
	
	private static final ScheduledExecutorService killme = Executors.newScheduledThreadPool(1, new ThreadFactory() {
		@Override
		public Thread newThread(Runnable r) {
			final Thread t = new Thread(r, "KillMe");
			t.setDaemon(true);
			return t;
		}
	});

	public static void main(String[] args) throws Exception {
		if (args.length != 2) {
			throw new IllegalArgumentException("Usage: port duration");
		}
		final int port = Integer.parseInt(args[0]);
		final int duration = Integer.parseInt(args[1]);
		
		final Server http = new Server(port);
		
		http.setGracefulShutdown(1000);
		
		final Runnable killServer = new Runnable() {
			
			@Override
			public void run() {
				try {
					
					System.out.println("Stopping HTTP server");
					http.stop();
					
				} catch (Exception e) {
					System.out.flush();
					System.err.flush();
					
					e.printStackTrace();

					System.out.flush();
					System.err.flush();

					// hard exit.
					System.exit (1);
				}
			}
		};
		
		http.setHandler(new ResolverTestHTTPServer(killServer));
		
		http.start();
		
		killme.schedule(killServer, duration, TimeUnit.SECONDS);
		
		System.out.println("Waiting for HTTP server to stop");
		http.join();
		System.out.println("HTTP server stopped. Exiting.");
		
	}
	
	private static final String TICKPFX = "/ticks/";
	private static final ThreadLocal<Resources> resources = new ThreadLocal<Resources>();
	
	private final ConcurrentHashMap<String, AtomicInteger> countermap = 
			new ConcurrentHashMap<String, AtomicInteger>();
	private final Runnable server;
	private final Charset iso88591 = Charset.forName("iso-8859-1");
	
	public ResolverTestHTTPServer(Runnable server) {
		this.server = server;
	}
	
	private final AtomicInteger getCounter(final String name) {
		final AtomicInteger counter = countermap.get(name);
		if (counter != null) {
			return counter;
		}
		final AtomicInteger addcounter = new AtomicInteger();
		final AtomicInteger prev = countermap.putIfAbsent(name, addcounter);
		if (prev != null) {
			return prev;
		}
		return addcounter;
	}

	@Override
	public void handle(final String target, final Request baseRequest,
			final HttpServletRequest request, final HttpServletResponse response) 
					throws IOException, ServletException {
		if ("/shutdown".equals(target)) {
			try {
				System.out.println("shutdown scheduled");
				killme.schedule(server, 1, TimeUnit.SECONDS);
				return;
			} catch (Exception e) {
				throw new ServletException("Unable to shut down.", e);
			}
		}
		
		if (target.startsWith(TICKPFX)) {
			final String path = target.substring(TICKPFX.length());
			final int spos = path.indexOf('/');
			final String name = (spos >= 0) ? path.substring(0, spos) : path;
			
			final String sdelay = (spos >= 0) ? path.substring(spos + 1) : "-1";
				
			final long millisecs = Long.parseLong(name);
			final long delay = Long.parseLong(sdelay);
			
			final AtomicInteger counter = getCounter(name); 

			final int start = counter.incrementAndGet();
			if (start != 1) {
				throw new ServletException("We expect no active requests for this resource " + target);
			}
			try {
				handleTime(target, baseRequest, request, response, millisecs, delay);
			} finally {
				final int end = counter.decrementAndGet();
				if (end != 0) {
					throw new ServletException("We expect only one request at a time for this resource " + target);
				}
			}
		}
	}
	
	public void handleTime(final String target, final Request baseRequest, final HttpServletRequest request,
			final HttpServletResponse response, final long time, final long delay)
					throws IOException, ServletException {
		final long now = System.currentTimeMillis();
		final long mod = (now / time) * time;
		final long exp = mod + time - 1;
		final long act = exp - now;
        final long maxage = (act / 1000) - 1;
		
		final Enumeration<String> cachecontrol = request.getHeaders("Cache-Control");
        boolean nocache = false;
		if (cachecontrol != null && cachecontrol.hasMoreElements()) {
			while (cachecontrol.hasMoreElements()) {
				final String val = cachecontrol.nextElement();
				if (val.trim().matches("max-age\\s*=\\s*0")) {
					nocache =true;
					System.out.println("No-Cache is true");
				}
			}
		}
		
		final SimpleDateFormat mysdf = getSDF();
		final MessageDigest mymd5 = getMD5();
		final byte[] data = String.format("%016d\n", exp).getBytes(iso88591);
		
		final String checkmod = request.getHeader("If-Modified-Since");
		
		
        response.setContentType("text/plain; charset=iso-8859-1");
        response.setContentLength(data.length);
        response.setHeader("Content-MD5", Base64.encodeToString(mymd5.digest(data), false));
        response.setHeader("Last-Modified", mysdf.format(new Date(mod)));
        response.setHeader("Date", mysdf.format(new Date(now)));
        response.setHeader("Expires", mysdf.format(new Date(exp)));
        
        if (maxage > 0) {
        	response.setHeader("Cache-Control", "max-age=" + maxage);
        }
        
        System.out.printf("%tc Processing %s %s from %s with delay=%d\n", now, 
        		request.getMethod(), target, request.getHeader("User-Agent"), delay);
        
        if (delay > 0) {
        	try {
				Thread.sleep(delay);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
        }
        
        if (!nocache && checkmod != null) {
        	long clm;
        	String actmod = mysdf.format(new Date(mod));
			try {
				clm = mysdf.parse(checkmod).getTime();
	        	if (clm < mod) {
	                response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
	                baseRequest.setHandled(true);
	                System.out.printf("Source not modified.\n  actmod: %s\n  chkmod: %s\n", actmod, checkmod);
	                return;
	        	}
                System.out.printf("Source modified.\n  actmod: %s\n  chkmod: %s\n", actmod, checkmod);
			} catch (ParseException e) {
				throw new ServletException(e);
			}
        }
        
        response.setStatus(HttpServletResponse.SC_OK);
        baseRequest.setHandled(true);
        if ("GET".equals(request.getMethod())) {
        	// only output for GET (ignore ouput for HEAD).
        	response.getOutputStream().write(data);
        }
		
		
	}
	
	private Resources getResources() throws ServletException {
		final Resources ret = resources.get();
		if (ret != null) {
			return ret;
		}
		final Resources nret = new Resources();
		resources.set(nret);
		return nret;
	}

	private SimpleDateFormat getSDF() throws ServletException {
		return getResources().sdfengine;
	}
	
	private MessageDigest getMD5() throws ServletException {
		final MessageDigest ret = getResources().md5engine;
		ret.reset();
		return ret; 
	}
	

}
