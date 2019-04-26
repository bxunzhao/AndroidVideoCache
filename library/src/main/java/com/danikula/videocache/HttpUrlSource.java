package com.danikula.videocache;

import android.text.TextUtils;

import com.danikula.videocache.file.MyLog;
import com.danikula.videocache.headers.EmptyHeadersInjector;
import com.danikula.videocache.headers.HeaderInjector;
import com.danikula.videocache.sourcestorage.SourceInfoStorage;
import com.danikula.videocache.sourcestorage.SourceInfoStorageFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.danikula.videocache.LOG.LOG_TAG;
import static com.danikula.videocache.Preconditions.checkNotNull;
import static com.danikula.videocache.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;

/**
 * {@link Source} that uses http resource as source for {@link ProxyCache}.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class HttpUrlSource implements Source {


    private final SourceInfoStorage sourceInfoStorage;
    private final HeaderInjector    headerInjector;
    private       SourceInfo        sourceInfo;
    private       InputStream       inputStream;

    private Call requestCall = null;

    private static OkHttpClient okHttpClient = new OkHttpClient();

    private volatile long   length = Integer.MIN_VALUE;
    private volatile String mime;

    public HttpUrlSource(String url) {
        this(url, SourceInfoStorageFactory.newEmptySourceInfoStorage());
    }

    public HttpUrlSource(String url, SourceInfoStorage sourceInfoStorage) {
        this(url, sourceInfoStorage, new EmptyHeadersInjector());
    }

    public HttpUrlSource(String url, SourceInfoStorage sourceInfoStorage, HeaderInjector headerInjector) {
        this.sourceInfoStorage = checkNotNull(sourceInfoStorage);
        this.headerInjector = checkNotNull(headerInjector);
        SourceInfo sourceInfo = sourceInfoStorage.get(url);
        this.sourceInfo = sourceInfo != null ? sourceInfo :
                new SourceInfo(url, Integer.MIN_VALUE, ProxyCacheUtils.getSupposablyMime(url));
    }

    public HttpUrlSource(HttpUrlSource source) {
        this.sourceInfo = source.sourceInfo;
        this.sourceInfoStorage = source.sourceInfoStorage;
        this.headerInjector = source.headerInjector;
    }

    @Override
    public synchronized long length() throws ProxyCacheException {
        if (sourceInfo.length == Integer.MIN_VALUE) {
            fetchContentInfo();
        }
        return sourceInfo.length;
    }

    @Override
    public void open(long offset) throws ProxyCacheException {
        try {
            Response response = openConnection(offset, -1);
            String mime = response.header("Content-Type");
            inputStream = new BufferedInputStream(response.body().byteStream(), DEFAULT_BUFFER_SIZE);
            length = readSourceAvailableBytes(response, offset, response.code());
            this.sourceInfo = new SourceInfo(sourceInfo.url, length, mime);
            this.sourceInfoStorage.put(sourceInfo.url, sourceInfo);
        } catch (IOException e) {
            throw new ProxyCacheException("Error opening connection for " + sourceInfo.url + " with offset " + offset, e);
        }
    }

    private long readSourceAvailableBytes(Response response, long offset, int responseCode) throws IOException {
        int contentLength = Integer.valueOf(response.header("Content-Length", "-1"));
        return responseCode == HTTP_OK ? contentLength
                : responseCode == HTTP_PARTIAL ? contentLength + offset : length;
    }

    @Override
    public void close() throws ProxyCacheException {
        if (okHttpClient != null && inputStream != null && requestCall != null) {
            try {
                inputStream.close();
                requestCall.cancel();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public int read(byte[] buffer) throws ProxyCacheException {
        if (inputStream == null) {
            throw new ProxyCacheException("Error reading data from " + sourceInfo.url + ": connection is absent!");
        }
        try {
            return inputStream.read(buffer, 0, buffer.length);
        } catch (InterruptedIOException e) {
            throw new InterruptedProxyCacheException("Reading source " + sourceInfo.url + " is interrupted", e);
        } catch (IOException e) {
            throw new ProxyCacheException("Error reading data from " + sourceInfo.url, e);
        }
    }

    private void fetchContentInfo() throws ProxyCacheException {
        LOG.debug("Read content info from " + sourceInfo.url);
        Response response = null;
        InputStream inputStream = null;
        try {
            response = openConnection(0, 20000);
            length = Integer.valueOf(response.header("Content-Length", "-1"));
            mime = response.header("Content-Type");
            inputStream = response.body().byteStream();
            MyLog.i(LOG_TAG, "Content info for `" + sourceInfo.url + "`: mime: " + mime + ", content-length: " + length);
        } catch (IOException e) {
            MyLog.e(LOG_TAG, "Error fetching info from " + sourceInfo.url, e);
        } finally {
            ProxyCacheUtils.close(inputStream);
            if (response != null) {
                requestCall.cancel();
            }
        }
    }

    private Response openConnection(long offset, int timeout) throws IOException, ProxyCacheException {
        boolean redirected;
        int redirectCount = 0;
        String url = sourceInfo.url;
        LOG.debug("Open connection " + (offset > 0 ? " with offset " + offset : "") + " to " + url);
        okHttpClient.newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(8, 5, TimeUnit.MINUTES));
        Request.Builder builder = new Request.Builder();
        builder.url(url);
        injectCustomHeaders(builder, url);
        if (offset > 0) {
            builder.addHeader("Range", "bytes=" + offset + "-");
        }
        Request request = builder.build();
        requestCall = okHttpClient.newCall(request);
        return requestCall.execute();
    }

    private void injectCustomHeaders(Request.Builder builder, String url) {
        Map<String, String> extraHeaders = headerInjector.addHeaders(url);
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            builder.addHeader(header.getKey(), header.getValue());
        }
    }

    public synchronized String getMime() throws ProxyCacheException {
        if (TextUtils.isEmpty(sourceInfo.mime)) {
            fetchContentInfo();
        }
        return sourceInfo.mime;
    }

    public String getUrl() {
        return sourceInfo.url;
    }

    @Override
    public String toString() {
        return "HttpUrlSource{sourceInfo='" + sourceInfo + "}";
    }
}
