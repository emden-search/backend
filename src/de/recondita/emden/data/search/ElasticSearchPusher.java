package de.recondita.emden.data.search;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import de.recondita.emden.data.RESTHandler;
import de.recondita.emden.data.Settings;
import de.recondita.emden.data.input.SourceSetup;

/**
 * Used for Streaming Data via POST
 * 
 * @author felix
 *
 */
public class ElasticSearchPusher implements Pusher {
	private StringBuilder builder = new StringBuilder();
	private final static String INDEX = "{\"index\":{}}\n";
	private final static String LINEBREAK = "\n";
	private int zaehler = 0;
	private int BLOCK_SIZE;
	private final URL u;
	private final RESTHandler rest = new RESTHandler();
	private ArrayList<Thread> pool = new ArrayList<Thread>();
	private final ElasticsearchWrapper searchWrapper;
	private final String index;

	public ElasticSearchPusher(String index, ElasticsearchWrapper searchWrapper) throws IOException {
		String url = Settings.getInstance().getProperty("elasticsearch.url") + "/"
				+ Settings.getInstance().getProperty("index.basename") + index.toLowerCase() + SourceSetup.APPENDIX;
		u = new URL(url + "/_doc/_bulk");
		rest.delete(url);
		this.searchWrapper = searchWrapper;
		this.index = index.toLowerCase();
		BLOCK_SIZE = Integer.parseInt(Settings.getInstance().getProperty("http.blocksize"));
	}

	/**
	 * Finishes the stream
	 * 
	 * @param stream
	 *            Stream to flush
	 * @return responsecode
	 * @throws IOException
	 */
	private int flush(HttpURLConnection oldcon) throws IOException {
		oldcon.getOutputStream().flush();
		oldcon.getOutputStream().close();
		BufferedReader in = new BufferedReader(new InputStreamReader(oldcon.getInputStream()));
		while ((in.readLine()) != null)
			;

		in.close();
		return oldcon.getResponseCode();
	}

	private void send(final String bulk) {
		Thread t = new Thread() {
			public void run() {
				try {
					HttpURLConnection con = rest.initNewConnection(u);
					byte[] payload = bulk.getBytes(StandardCharsets.UTF_8);
					con.setFixedLengthStreamingMode(payload.length);
					con.getOutputStream().write(payload);
					System.out.println("Block send with Status Code: " + flush(con));
				} catch (IOException e) {
					e.printStackTrace();
				}

			}
		};
		pool.add(t);
		t.start();
	}

	private void join() {
		for (Thread t : pool) {
			try {
				t.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	private void sendPartialData() throws IOException {
		if (builder.length() <= 0)
			return;
		String s = builder.toString();
		builder.setLength(0);
		send(s);
	}

	@Override
	public void send() throws IOException {
		sendPartialData();
		join();
		searchWrapper.renameIndex(Settings.getInstance().getProperty("index.basename") + index + SourceSetup.APPENDIX,
				Settings.getInstance().getProperty("index.basename") + index,zaehler);
	}

	@Override
	public synchronized void writeJsonString(String json) throws IOException {
		builder.append(INDEX);
		builder.append(json);
		builder.append(LINEBREAK);
		if (zaehler % BLOCK_SIZE==0) {
			sendPartialData();
		}

		zaehler++;
	}

}
