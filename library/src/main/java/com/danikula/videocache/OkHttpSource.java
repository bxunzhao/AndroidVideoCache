package com.danikula.videocache;

import android.text.TextUtils;

import com.danikula.videocache.file.MyLog;
import com.danikula.videocache.headers.HeaderInjector;
import com.danikula.videocache.sourcestorage.SourceInfoStorage;
import com.danikula.videocache.sourcestorage.SourceInfoStorageFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.danikula.videocache.LOG.LOG_TAG;
import static com.danikula.videocache.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;

public class OkHttpSource extends UrlSource {
    private static final int MAX_REDIRECTS = 5;

    private OkHttpClient.Builder httpClient = new OkHttpClient.Builder();
    private InputStream          inputStream;
    private SourceInfo           sourceInfo;
    private HeaderInjector       headerInjector;

    public OkHttpSource(String url, SourceInfoStorage sourceInfoStorage, HeaderInjector headerInjector) {
        this(url);
        SourceInfo sourceInfo = sourceInfoStorage.get(url);
        this.sourceInfo = sourceInfo != null ? sourceInfo :
                new SourceInfo(url, Integer.MIN_VALUE, ProxyCacheUtils.getSupposablyMime(url));
        this.headerInjector = headerInjector;
    }

    public OkHttpSource(OkHttpSource okHttpSource) {
        this(okHttpSource.url);
    }

    public OkHttpSource(String url) {
        super(url);
        this.sourceInfo = SourceInfoStorageFactory.newEmptySourceInfoStorage().get(url);
    }

    @Override
    public long length() throws ProxyCacheException {
        if (length == Integer.MIN_VALUE) {
            tryLoadMimeCache();
        }
        if (length == Integer.MIN_VALUE) {
            fetchContentInfo();
        }
        return length;
    }

    @Override
    public String getMime() throws ProxyCacheException {
        if (TextUtils.isEmpty(mime)) {
            tryLoadMimeCache();
        }
        if (TextUtils.isEmpty(mime)) {
            fetchContentInfo();
        }
        return mime;
    }

    @Override
    public void open(long offset) throws ProxyCacheException {
        try {
            Response response = openConnection(offset, 30000);
            mime = response.body().contentType().toString();
            length = readSourceAvailableBytes(response, offset);
            inputStream = new BufferedInputStream(response.body().byteStream(), DEFAULT_BUFFER_SIZE);
        } catch (IOException e) {
            throw new ProxyCacheException("Error opening connection for " + url + " with offset " + offset, e);
        }
    }

    private long readSourceAvailableBytes(Response response, long offset) throws IOException {
        int responseCode = response.code();
        int contentLength = (int) response.body().contentLength();
        return responseCode == HTTP_OK ? contentLength
                : responseCode == HTTP_PARTIAL ? contentLength + offset : length;
    }

    @Override
    public int read(byte[] buffer) throws ProxyCacheException {
        if (inputStream == null) {
            throw new ProxyCacheException("Error reading data from " + url + ": connection is absent!");
        }
        try {
            return inputStream.read(buffer, 0, buffer.length);
        } catch (InterruptedIOException e) {
            throw new InterruptedProxyCacheException("Reading source " + url + " is interrupted", e);
        } catch (IOException e) {
            throw new ProxyCacheException("Error reading data from " + url, e);
        }
    }

    @Override
    public void close() throws ProxyCacheException {
        ProxyCacheUtils.close(inputStream);
    }

    private void fetchContentInfo() throws ProxyCacheException {
        MyLog.d(LOG_TAG, "Read content info from " + url);
        Response response = null;
        try {
            response = openConnectionForHeader(30000);
            if (response == null || !response.isSuccessful()) {
                throw new ProxyCacheException("Fail to fetchContentInfo: " + url);
            }
            length = (int) response.body().contentLength();
            mime = response.body().contentType().toString();
            tryPutMimeCache();
            MyLog.i(LOG_TAG, "Content info for `" + url + "`: mime: " + mime + ", content-length: " + length);
        } catch (IOException e) {
            MyLog.e(LOG_TAG, "Error fetching info from " + url, e);
        } finally {
            MyLog.d(LOG_TAG, "Closed connection from :" + url);
        }
    }

    private Response openConnectionForHeader(int timeout) throws IOException, ProxyCacheException {
        if (timeout > 0) {
            httpClient.connectTimeout(timeout, TimeUnit.MILLISECONDS);
            httpClient.readTimeout(timeout, TimeUnit.MILLISECONDS);
            httpClient.writeTimeout(timeout, TimeUnit.MILLISECONDS);
        }
        Response response;
        boolean isRedirect = false;
        String newUrl = this.url;
        int redirectCount = 0;
        do {
            Request request = new Request.Builder()
                    .head()
                    .url(newUrl)
                    .build();
            response = httpClient.build().newCall(request).execute();
            if (response.isRedirect()) {
                newUrl = response.header("Location");
                isRedirect = response.isRedirect();
                redirectCount++;
            }
            if (redirectCount > MAX_REDIRECTS) {
                throw new ProxyCacheException("Too many redirects: " + redirectCount);
            }
        } while (isRedirect);

        return response;
    }

    private Response openConnection(long offset, int timeout) throws IOException, ProxyCacheException {
        if (timeout > 0) {
            httpClient.connectTimeout(timeout, TimeUnit.MILLISECONDS);
            httpClient.readTimeout(timeout, TimeUnit.MILLISECONDS);
            httpClient.writeTimeout(timeout, TimeUnit.MILLISECONDS);
        }
        Response response;
        boolean isRedirect = false;
        String newUrl = this.url;
        int redirectCount = 0;
        do {
            MyLog.d(LOG_TAG, "Open connection" + (offset > 0 ? " with offset " + offset : "") + " to " + url);
            Request.Builder requestBuilder = new Request.Builder();
            requestBuilder.get();
            requestBuilder.url(newUrl);
            if (offset > 0) {
                requestBuilder.addHeader("Range", "bytes=" + offset + "-");
            }
            requestBuilder.addHeader("Connection", "close");
            injectCustomHeaders(requestBuilder, url);
            response = httpClient.build().newCall(requestBuilder.build()).execute();
            if (response.isRedirect()) {
                newUrl = response.header("Location");
                isRedirect = response.isRedirect();
                redirectCount++;
            }
            if (redirectCount > MAX_REDIRECTS) {
                throw new ProxyCacheException("Too many redirects: " + redirectCount);
            }
        } while (isRedirect);

        return response;
    }

    private void injectCustomHeaders(Request.Builder connection, String url) {
        if (headerInjector == null)
            return;
        Map<String, String> extraHeaders = headerInjector.addHeaders(url);
        if (extraHeaders == null || extraHeaders.isEmpty()) {
            return;
        }
        HttpProxyCacheDebuger.printfError("****** injectCustomHeaders ****** :" + extraHeaders.size());
        for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
            connection.addHeader(header.getKey(), header.getValue());
        }
    }

    private void tryLoadMimeCache() {
        if (sourceInfo != null) {
            this.mime = sourceInfo.mime;
            this.length = sourceInfo.length;
            this.url = sourceInfo.url;
        }
    }

    private void tryPutMimeCache() {
        if (sourceInfo != null) {
            sourceInfo = new SourceInfo(url, length, mime);
        }
    }
}
