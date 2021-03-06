package com.justinsb.etcd;

import java.io.Closeable;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

public class EtcdClient implements Closeable {
	private static final Gson gson = new GsonBuilder().create();

	private final CloseableHttpAsyncClient httpClient;
	private final URI baseUri;

	static CloseableHttpAsyncClient buildDefaultHttpClient() {
		RequestConfig requestConfig = RequestConfig.custom().build();
		HttpAsyncClientBuilder builder = HttpAsyncClients.custom().setDefaultRequestConfig(requestConfig);
		// default is 2 per host which won't let us monitor multiple directories
		builder.setMaxConnPerRoute(Integer.MAX_VALUE);
		builder.setMaxConnTotal(Integer.MAX_VALUE);
		CloseableHttpAsyncClient httpClient = builder.build();
		httpClient.start();
		return httpClient;
	}

	public EtcdClient(URI baseUri) {
		this(baseUri, buildDefaultHttpClient());
	}

	public EtcdClient(URI baseUri, CloseableHttpAsyncClient httpClient) {
		this.httpClient = httpClient;
		String uri = baseUri.toString();
		if (!uri.endsWith("/")) {
			uri += "/";
			this.baseUri = URI.create(uri);
		} else {
			this.baseUri = baseUri;
		}
	}

	/**
	 * Retrieves a key. Returns null if not found.
	 */
	public EtcdResult get(String key) throws EtcdClientException {
		URI uri = buildKeyUri("v2/keys", key, "");
		HttpGet request = new HttpGet(uri);

		EtcdResult result = syncExecute(request, new int[] { 200, 404 }, 100);
		if (result.isError()) {
			if (result.errorCode == 100) {
				return null;
			}
		}
		return result;
	}

	/**
	 * Deletes the given key
	 */
	public EtcdResult delete(String key) throws EtcdClientException {
		URI uri = buildKeyUri("v2/keys", key, "");
		HttpDelete request = new HttpDelete(uri);

		return syncExecute(request, new int[] { 200, 404 });
	}

	/**
	 * Sets a key to a new value
	 */
	public EtcdResult set(String key, String value) throws EtcdClientException {
		return set(key, value, null);
	}

	/**
	 * Sets a key to a new value with an (optional) ttl
	 */

	public EtcdResult set(String key, String value, Integer ttl) throws EtcdClientException {
		List<BasicNameValuePair> data = Lists.newArrayList();
		data.add(new BasicNameValuePair("value", value));
		if (ttl != null) {
			data.add(new BasicNameValuePair("ttl", Integer.toString(ttl)));
		}

		return set0(key, data, new int[] { 200, 201 });
	}

	/**
	 * Creates a directory
	 */
	public EtcdResult createDirectory(String key) throws EtcdClientException {
		List<BasicNameValuePair> data = Lists.newArrayList();
		data.add(new BasicNameValuePair("dir", "true"));
		return set0(key, data, new int[] { 200, 201 });
	}

	/**
	 * Lists a directory
	 */
	public List<EtcdNode> listDirectory(String key) throws EtcdClientException {
		EtcdResult result = get(key + "/");
		if (result == null || result.node == null) {
			return null;
		}
		return result.node.nodes;
	}

	/**
	 * Delete a directory
	 */
	public EtcdResult deleteDirectory(String key) throws EtcdClientException {
		URI uri = buildKeyUri("v2/keys", key, "?dir=true");
		HttpDelete request = new HttpDelete(uri);
		return syncExecute(request, new int[] { 202 });
	}

	/**
	 * Sets a key to a new value, if the value is a specified value
	 */
	public EtcdResult cas(String key, String prevValue, String value) throws EtcdClientException {
		List<BasicNameValuePair> data = Lists.newArrayList();
		data.add(new BasicNameValuePair("value", value));
		data.add(new BasicNameValuePair("prevValue", prevValue));

		return set0(key, data, new int[] { 200, 412 }, 101);
	}

	/**
	 * Watches the given subtree
	 */
	public ListenableFuture<EtcdResult> watch(String key) throws EtcdClientException {
		return watch(key, null, false);
	}

	/**
	 * Watches the given subtree
	 */
	public ListenableFuture<EtcdResult> watch(String key, Long index, boolean recursive) {
		String suffix = "?wait=true";
		if (index != null) {
			suffix += "&waitIndex=" + index;
		}
		if (recursive) {
			suffix += "&recursive=true";
		}
		URI uri = buildKeyUri("v2/keys", key, suffix);

		HttpGet request = new HttpGet(uri);
		return asyncExecute(request, new int[] { 200 });
	}

	/**
	 * Gets the etcd version
	 */
	public String getVersion() throws EtcdClientException {
		URI uri = baseUri.resolve("version");
		HttpGet request = new HttpGet(uri);

		// Technically not JSON, but it'll work
		// This call is the odd one out
		JsonResponse s = syncExecuteJson(request, 200);
		if (s.httpStatusCode != 200) {
			throw new EtcdClientException("Error while fetching versions", s.httpStatusCode);
		}
		return s.json;
	}

	private EtcdResult set0(String key, List<BasicNameValuePair> data, int[] httpErrorCodes, int... expectedErrorCodes)
			throws EtcdClientException {
		URI uri = buildKeyUri("v2/keys", key, "");
		HttpPut request = new HttpPut(uri);
		UrlEncodedFormEntity entity = new UrlEncodedFormEntity(data, Charsets.UTF_8);
		request.setEntity(entity);

		return syncExecute(request, httpErrorCodes, expectedErrorCodes);
	}

	public EtcdResult listChildren(String key) throws EtcdClientException {
		URI uri = buildKeyUri("v2/keys", key, "/");
		HttpGet request = new HttpGet(uri);
		return syncExecute(request, new int[] { 200 });
	}

	protected ListenableFuture<EtcdResult> asyncExecute(HttpUriRequest request, int[] expectedHttpStatusCodes,
			final int... expectedErrorCodes) {
		ListenableFuture<JsonResponse> json = asyncExecuteJson(request, expectedHttpStatusCodes);
		return Futures.transform(json, new AsyncFunction<JsonResponse, EtcdResult>() {
			@Override
			public ListenableFuture<EtcdResult> apply(JsonResponse json) {
				try {
					EtcdResult result = jsonToEtcdResult(json, expectedErrorCodes);
					return Futures.immediateFuture(result);
				} catch (EtcdClientException e) {
					return Futures.immediateFailedFuture(e);
				}
			}
		});
	}

	protected EtcdResult syncExecute(HttpUriRequest request, int[] expectedHttpStatusCodes, int... expectedErrorCodes)
			throws EtcdClientException {
		try {
			return asyncExecute(request, expectedHttpStatusCodes, expectedErrorCodes).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();

			throw new EtcdClientException("Interrupted during request", e);
		} catch (ExecutionException e) {
			throw unwrap(e);
		}
		// String json = syncExecuteJson(request);
		// return jsonToEtcdResult(json, expectedErrorCodes);
	}

	private EtcdClientException unwrap(ExecutionException e) {
		Throwable cause = e.getCause();
		if (cause instanceof EtcdClientException) {
			return (EtcdClientException) cause;
		}
		return new EtcdClientException("Error executing request", e);
	}

	private EtcdResult jsonToEtcdResult(JsonResponse response, int... expectedErrorCodes) throws EtcdClientException {
		// if etcd goes down during watch(..) response.json will be ""
		if (response == null || response.json == null || response.json.isEmpty()) {
			throw new EtcdClientException("No response from etcd");
		}

		EtcdResult result = parseEtcdResult(response.json);

		result.etcdIndex = response.etcdIndex;
		result.raftIndex = response.raftIndex;
		result.raftTerm = response.raftTerm;

		if (result.isError()) {
			if (!contains(expectedErrorCodes, result.errorCode)) {
				throw new EtcdClientException(result.message, result);
			}
		}
		return result;
	}

	private EtcdResult parseEtcdResult(String json) throws EtcdClientException {
		try {
			return gson.fromJson(json, EtcdResult.class);
		} catch (JsonParseException e) {
			throw new EtcdClientException("Error parsing response from etcd", e);
		}
	}

	private static boolean contains(int[] list, int find) {
		for (final int listItem : list) {
			if (listItem == find) {
				return true;
			}
		}
		return false;
	}

	protected List<EtcdResult> syncExecuteList(HttpUriRequest request) throws EtcdClientException {
		JsonResponse response = syncExecuteJson(request, 200);
		if (response.json == null) {
			return null;
		}

		if (response.httpStatusCode != 200) {
			EtcdResult etcdResult = parseEtcdResult(response.json);
			throw new EtcdClientException("Error listing keys", etcdResult);
		}

		try {
			List<EtcdResult> ret = new ArrayList<EtcdResult>();
			JsonParser parser = new JsonParser();
			JsonArray array = parser.parse(response.json).getAsJsonArray();
			for (int i = 0; i < array.size(); i++) {
				EtcdResult next = gson.fromJson(array.get(i), EtcdResult.class);
				ret.add(next);
			}
			return ret;
		} catch (JsonParseException e) {
			throw new EtcdClientException("Error parsing response from etcd", e);
		}
	}

	protected JsonResponse syncExecuteJson(HttpUriRequest request, int... expectedHttpStatusCodes)
			throws EtcdClientException {
		try {
			return asyncExecuteJson(request, expectedHttpStatusCodes).get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new EtcdClientException("Interrupted during request processing", e);
		} catch (ExecutionException e) {
			throw unwrap(e);
		}
	}

	protected ListenableFuture<JsonResponse> asyncExecuteJson(HttpUriRequest request,
			final int[] expectedHttpStatusCodes) {
		ListenableFuture<HttpResponse> response = asyncExecuteHttp(request);

		return Futures.transform(response, new AsyncFunction<HttpResponse, JsonResponse>() {
			@Override
			public ListenableFuture<JsonResponse> apply(HttpResponse httpResponse) throws Exception {
				JsonResponse json = extractJsonResponse(httpResponse, expectedHttpStatusCodes);
				return Futures.immediateFuture(json);
			}
		});
	}

	/**
	 * We need the status code & the response to parse an error response.
	 */
	static class JsonResponse {
		final String json;
		final int httpStatusCode;
		final long etcdIndex, raftIndex, raftTerm;

		public JsonResponse(String json, int statusCode, long etcdIndex, long raftIndex, long raftTerm) {
			this.json = json;
			this.httpStatusCode = statusCode;
			this.etcdIndex = etcdIndex;
			this.raftIndex = raftIndex;
			this.raftTerm = raftTerm;
		}

	}

	protected JsonResponse extractJsonResponse(HttpResponse httpResponse, int[] expectedHttpStatusCodes)
			throws EtcdClientException {
		try {
			StatusLine statusLine = httpResponse.getStatusLine();
			int statusCode = statusLine.getStatusCode();

			String json = null;

			if (httpResponse.getEntity() != null) {
				try {
					json = EntityUtils.toString(httpResponse.getEntity());
				} catch (IOException e) {
					throw new EtcdClientException("Error reading response", e);
				}
			}

			if (!contains(expectedHttpStatusCodes, statusCode)) {
				if (statusCode == 400 && json != null) {
					// More information in JSON
				} else {
					throw new EtcdClientException("Error response from etcd: " + statusLine.getReasonPhrase(),
							statusCode);
				}
			}

			final long etcdIndex = parseLongHeader(httpResponse.getFirstHeader("X-Etcd-Index"));
			final long raftIndex = parseLongHeader(httpResponse.getFirstHeader("X-Raft-Index"));
			final long raftTerm = parseLongHeader(httpResponse.getFirstHeader("X-Raft-Term"));

			return new JsonResponse(json, statusCode, etcdIndex, raftIndex, raftTerm);
		} finally {
			close(httpResponse);
		}
	}

	private static long parseLongHeader(Header header) {
		return parseLongHeader(header, Long.MIN_VALUE);
	}

	private static long parseLongHeader(Header header, long dfl) {
		if (header == null) {
			return dfl;
		} else {
			return Long.parseLong(header.getValue());
		}
	}

	private URI buildKeyUri(String prefix, String key, String suffix) {
		StringBuilder sb = new StringBuilder();
		sb.append(prefix);
		if (key.startsWith("/")) {
			key = key.substring(1);
		}
		for (String token : Splitter.on('/').split(key)) {
			sb.append("/");
			sb.append(urlEscape(token));
		}
		sb.append(suffix);

		return baseUri.resolve(sb.toString());
	}

	protected ListenableFuture<HttpResponse> asyncExecuteHttp(HttpUriRequest request) {
		final SettableFuture<HttpResponse> future = SettableFuture.create();

		httpClient.execute(request, new FutureCallback<HttpResponse>() {
			@Override
			public void completed(HttpResponse result) {
				future.set(result);
			}

			@Override
			public void failed(Exception ex) {
				future.setException(ex);
			}

			@Override
			public void cancelled() {
				future.setException(new InterruptedException());
			}
		});

		return future;
	}

	public static void close(HttpResponse response) {
		if (response == null) {
			return;
		}
		HttpEntity entity = response.getEntity();
		if (entity != null) {
			EntityUtils.consumeQuietly(entity);
		}
	}

	protected static String urlEscape(String s) {
		try {
			return URLEncoder.encode(s, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			throw new IllegalStateException();
		}
	}

	public static String format(Object o) {
		try {
			return gson.toJson(o);
		} catch (Exception e) {
			return "Error formatting: " + e.getMessage();
		}
	}

	@Override
	public void close() throws IOException {
		httpClient.close();
	}
}
