package net.tuis.resolver.testconcurrent;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

import net.tuis.resolver.CachedHTTPRepository;
import net.tuis.resolver.Resource;

@SuppressWarnings("javadoc")
public class Engine implements Runnable {

	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		if (args.length != 7) {
			throw new IllegalArgumentException("Engine requires four arguments: port, threads, refresh, delay\n" +
					"    cachedir - the Location for the cache data\n" +
					"    name     - the Name for this Engine\n" +
					"    port     - the port running the test HTTP server\n" +
					"    threads  - the number of concurrent threads trying to access the page\n" +
					"    refresh  - how long (secs) the page should remain unchanged on the server\n" +
					"    delay    - how long (secs) the server should take to serve the page\n" +
					"    duration - how long (secs) the test should run\n"
					);
		}
		
		Thread.sleep(1000);
		
		final StreamHandler ch = new StreamHandler(System.out, new SimpleFormatter()) ;
		ch.setLevel(Level.ALL);
		ch.flush();
		Logger logger = Logger.getLogger("net.tuis");
		logger.setLevel(Level.FINEST);
		logger.addHandler(ch);
		
		
		final String dirname = args[0];
		final String engname = args[1];
		final int port     = Integer.parseInt(args[2]);
		final int threads  = Integer.parseInt(args[3]);
		final int refresh  = Integer.parseInt(args[4]) * 1000;
		final int delay    = Integer.parseInt(args[5]) * 1000;
		final int duration = Integer.parseInt(args[6]) * 1000;
		
		final File cachedir = new File(dirname).getAbsoluteFile();
		
		final long dieat = System.currentTimeMillis() + duration;
		final Thread[] threadarray = new Thread[threads];
		final URL url = new URL(String.format("http://localhost:%d/ticks/%d/%d", port, refresh, delay));

		for (int i = 0; i < threads; i++) {
			final String name = "Engine " + engname + " thread " + i;
			final Engine eng = new Engine(ch, name, cachedir, dieat, url);
			final Thread t = new Thread(eng, name);
			t.setDaemon(true);
			threadarray[i] = t;
			t.start();
		}
		
		for (int i = 0; i < threads; i++) {
			try {
				threadarray[i].join();
			} catch (InterruptedException e) {
				throw new IllegalStateException("We were interrupted.", e);
			}
		}

	}
	
	private final String name;
	private final long diesat;
	private final URL url;
	private final CachedHTTPRepository repo;
	private final Handler handler;
	

	public Engine(Handler handler, String name, File dir, long diesat, URL url) throws IOException {
		this.handler = handler;
		this.name = name;
		this.diesat = diesat;
		this.url = url;
		this.repo = new CachedHTTPRepository(dir);
		this.repo.setUserAgent(name);
	}	
	
	@Override
	public void run() {
		int cnt = 0;
		while (System.currentTimeMillis() < diesat) {
			try {
				cnt++;
				final Resource res = repo.resolve("nothing", url);
				handler.flush();
				System.out.printf("%s %04d resolved %s\n", name, 
						cnt, (res == null ? "nothing" : "encoding " + res.getEncoding()));
			} catch (IOException e) {
				e.printStackTrace();
				return;
			}
		}
		
	}

}
