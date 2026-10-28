package net.moddedrealms.aibridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;

/**
 * Local JSON-lines socket on 127.0.0.1. One controller at a time; it must send
 * {"op":"auth","token":...} first. Requests run on the client thread.
 */
final class BridgeServer extends Thread {
	private final int port;
	private final byte[] token;
	private volatile Socket client;
	private volatile boolean authed;

	BridgeServer(int port, String token) {
		super("ai-bridge-server");
		setDaemon(true);
		this.port = port;
		this.token = token.getBytes(StandardCharsets.UTF_8);
	}

	boolean hasClient() {
		return client != null && authed;
	}

	@Override
	public void run() {
		try (ServerSocket ss = new ServerSocket(port, 4, InetAddress.getLoopbackAddress())) {
			AiBridge.LOG.info("AI bridge listening on 127.0.0.1:{}", port);
			while (true) {
				Socket s = ss.accept();
				Socket old = client;
				if (old != null) old.close();
				authed = false;
				client = s;
				Thread reader = new Thread(() -> serve(s), "ai-bridge-client");
				reader.setDaemon(true);
				reader.start();
			}
		} catch (Exception e) {
			AiBridge.LOG.error("AI bridge server stopped", e);
		}
	}

	private void serve(Socket s) {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = in.readLine()) != null) {
				if (line.isBlank()) continue;
				JsonObject req;
				try {
					req = JsonParser.parseString(line).getAsJsonObject();
				} catch (Exception e) {
					continue;
				}
				handle(s, req);
			}
		} catch (Exception ignored) {
			// connection closed
		} finally {
			if (client == s) {
				client = null;
				authed = false;
			}
		}
	}

	private void handle(Socket s, JsonObject req) {
		long id = req.has("id") ? req.get("id").getAsLong() : -1;
		String op = req.has("op") ? req.get("op").getAsString() : "";
		if (!authed) {
			byte[] given = req.has("token") ? req.get("token").getAsString().getBytes(StandardCharsets.UTF_8) : new byte[0];
			if ("auth".equals(op) && MessageDigest.isEqual(given, token)) {
				authed = true;
				reply(s, id, true, null, null);
			} else {
				reply(s, id, false, null, "not authenticated");
			}
			return;
		}
		// Each request waits for the client thread on its own thread, so a slow one doesn't block the rest.
		Thread worker = new Thread(() -> {
			try {
				JsonElement result = Minecraft.getInstance().submit(() -> {
					try {
						return Ops.handle(op, req);
					} catch (RuntimeException e) {
						throw e;
					} catch (Exception e) {
						throw new RuntimeException(e.getMessage(), e);
					}
				}).get(30, TimeUnit.SECONDS);
				reply(s, id, true, result, null);
			} catch (Exception e) {
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				reply(s, id, false, null, cause.getMessage() == null ? cause.toString() : cause.getMessage());
			}
		}, "ai-bridge-op");
		worker.setDaemon(true);
		worker.start();
	}

	private void reply(Socket s, long id, boolean ok, JsonElement result, String error) {
		JsonObject o = new JsonObject();
		o.addProperty("id", id);
		o.addProperty("ok", ok);
		if (result != null) o.add("result", result);
		if (error != null) o.addProperty("error", error);
		write(s, o);
	}

	void event(JsonObject msg) {
		Socket s = client;
		if (s != null && authed) write(s, msg);
	}

	private void write(Socket s, JsonObject o) {
		byte[] bytes = (o.toString() + "\n").getBytes(StandardCharsets.UTF_8);
		synchronized (this) {
			try {
				OutputStream out = s.getOutputStream();
				out.write(bytes);
				out.flush();
			} catch (Exception ignored) {
				// reader thread notices the closed socket
			}
		}
	}
}
