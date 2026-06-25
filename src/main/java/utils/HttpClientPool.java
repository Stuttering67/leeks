package utils;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * @author by laugh on 2016/3/29.
 */
public class HttpClientPool {

    private static volatile HttpClientPool clientInstance;
    private HttpClient httpClient;
    // retry and metrics
    private int maxRetries = 2;
    private long retryBackoffMillis = 300L;

    private final AtomicLong metricTotalRequests = new AtomicLong(0);
    private final AtomicLong metricSuccesses = new AtomicLong(0);
    private final AtomicLong metricFailures = new AtomicLong(0);
    private final AtomicLong metricRetries = new AtomicLong(0);
    private final AtomicLong metricTotalBytes = new AtomicLong(0);
    private volatile java.util.concurrent.ScheduledFuture<?> metricsReporterFuture;

    public static HttpClientPool getHttpClient() {
        HttpClientPool tmp = clientInstance;
        if (tmp == null) {
            synchronized (HttpClientPool.class) {
                tmp = clientInstance;
                if (tmp == null) {
                    tmp = new HttpClientPool();
                    clientInstance = tmp;
                }
            }
        }
        return tmp;
    }

    private HttpClientPool() {
        buildHttpClient(null);
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    public void setRetryBackoffMillis(long millis) {
        this.retryBackoffMillis = Math.max(0L, millis);
    }

    public long getMetricTotalRequests() { return metricTotalRequests.get(); }
    public long getMetricSuccesses() { return metricSuccesses.get(); }
    public long getMetricFailures() { return metricFailures.get(); }
    public long getMetricRetries() { return metricRetries.get(); }
    public long getMetricTotalBytes() { return metricTotalBytes.get(); }

    public void buildHttpClient(String proxyStr) {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager(100, TimeUnit.SECONDS);
        connectionManager.setMaxTotal(200);
        connectionManager.setDefaultMaxPerRoute(100);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(3000)
                .setSocketTimeout(5000)
                .setConnectTimeout(3000)
                .build();
        HttpClientBuilder httpClientBuilder = HttpClients.custom().setConnectionManager(connectionManager);
        if (proxyStr != null && !proxyStr.isEmpty()) {
            String[] s = proxyStr.split(":");
            if (s.length == 2) {
                String host = s[0];
                int port = Integer.parseInt(s[1]);
                httpClientBuilder.setProxy(new HttpHost(host, port));
            }
            LogUtil.info("Leeks setup proxy success->" + proxyStr);
        }
        httpClient = httpClientBuilder.setDefaultRequestConfig(requestConfig).build();
    }

    public synchronized void startMetricsReporter(long initialDelaySeconds, long periodSeconds) {
        try {
            if (metricsReporterFuture != null && !metricsReporterFuture.isCancelled()) metricsReporterFuture.cancel(false);
            metricsReporterFuture = ThreadPools.getScheduledExecutor().scheduleAtFixedRate(() -> {
                try {
                    String s = String.format("HttpClientPool metrics: total=%d success=%d fail=%d retries=%d bytes=%d",
                            metricTotalRequests.get(), metricSuccesses.get(), metricFailures.get(), metricRetries.get(), metricTotalBytes.get());
                    LogUtil.info(s);
                } catch (Throwable t) {
                    // swallow
                }
            }, Math.max(0, initialDelaySeconds), Math.max(1, periodSeconds), TimeUnit.SECONDS);
        } catch (Throwable ignore) {}
    }

    public synchronized void stopMetricsReporter() {
        try {
            if (metricsReporterFuture != null) {
                metricsReporterFuture.cancel(false);
                metricsReporterFuture = null;
            }
        } catch (Throwable ignore) {}
    }

    public String getMetricsAsString() {
        return String.format("total=%d success=%d fail=%d retries=%d bytes=%d",
                metricTotalRequests.get(), metricSuccesses.get(), metricFailures.get(), metricRetries.get(), metricTotalBytes.get());
    }

    public String get(String url) throws Exception {
        return getResponseContent(url, () -> new org.apache.http.client.methods.HttpGet(url));
    }

    public String get(String url, String headerName, String headerValue) throws Exception {
        return getResponseContent(url, () -> {
            org.apache.http.client.methods.HttpGet get = new org.apache.http.client.methods.HttpGet(url);
            get.setHeader(headerName, headerValue);
            return get;
        });
    }

    public String post(String url) throws Exception {
        return getResponseContent(url, () -> new org.apache.http.client.methods.HttpPost(url));
    }

    public String post(String url, String jsonBody, String headerName, String headerValue) throws Exception {
        return getResponseContent(url, () -> {
            org.apache.http.client.methods.HttpPost post = new org.apache.http.client.methods.HttpPost(url);
            post.setHeader("Content-Type", "application/json");
            post.setHeader(headerName, headerValue);
            post.setEntity(new StringEntity(jsonBody, StandardCharsets.UTF_8));
            return post;
        });
    }

    private String getResponseContent(String url, Supplier<HttpRequestBase> requestSupplier) throws Exception {
        int attempt = 0;
        Exception lastEx = null;
        while (attempt <= maxRetries) {
            HttpRequestBase request = null;
            org.apache.http.HttpResponse response = null;
            try {
                request = requestSupplier.get();
                prepareHeaders(request, url);
                metricTotalRequests.incrementAndGet();
                response = httpClient.execute(request);
                String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                metricSuccesses.incrementAndGet();
                return body;
            } catch (Exception e) {
                lastEx = e;
                metricFailures.incrementAndGet();
                if (attempt < maxRetries) {
                    metricRetries.incrementAndGet();
                    long backoff = retryBackoffMillis * (1L << attempt);
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Interrupted during HTTP retry backoff", ie);
                    }
                } else {
                    throw new Exception("got an error from HTTP for url : " + URLDecoder.decode(url, "UTF-8"), e);
                }
            } finally {
                if (response != null) EntityUtils.consumeQuietly(response.getEntity());
                if (request != null) request.releaseConnection();
            }
        }
        throw lastEx == null ? new Exception("unknown http error") : lastEx;
    }

    public byte[] getBytes(String url) throws Exception {
        int attempt = 0;
        Exception lastEx = null;
        while (attempt <= maxRetries) {
            org.apache.http.client.methods.HttpGet httpGet = new org.apache.http.client.methods.HttpGet(url);
            org.apache.http.HttpResponse response = null;
            try {
                prepareHeaders(httpGet, url);
                metricTotalRequests.incrementAndGet();
                response = httpClient.execute(httpGet);
                byte[] data = EntityUtils.toByteArray(response.getEntity());
                if (data != null) metricTotalBytes.addAndGet(data.length);
                metricSuccesses.incrementAndGet();
                return data;
            } catch (Exception e) {
                lastEx = e;
                metricFailures.incrementAndGet();
                if (attempt < maxRetries) {
                    metricRetries.incrementAndGet();
                    long backoff = retryBackoffMillis * (1L << attempt);
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new Exception("Interrupted during HTTP retry backoff", ie); }
                } else {
                    throw new Exception("got an error from HTTP for url : " + URLDecoder.decode(url, "UTF-8"), e);
                }
            } finally {
                if (response != null) EntityUtils.consumeQuietly(response.getEntity());
                httpGet.releaseConnection();
            }
        }
        throw lastEx == null ? new Exception("unknown http error") : lastEx;
    }

    private void prepareHeaders(HttpRequestBase request, String url) {
        if (url.contains("hq.sinajs.cn")) {
            request.setHeader("Referer", "https://finance.sina.com.cn");
        }
        if (url.contains("push2.eastmoney.com")) {
            request.setHeader("Referer", "https://www.eastmoney.com");
            request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        }
        if (url.contains("10jqka.com.cn") || url.contains("d.10jqka.com.cn")) {
            request.setHeader("Referer", "https://www.10jqka.com.cn");
            request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        }
        if (url.contains("itick.org")) {
            request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        }
        if (url.contains("qos.hk")) {
            request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        }
        if (url.contains("zhituapi.com")) {
            request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        }
        if (url.contains("gtimg.cn")) {
            request.setHeader("Referer", "https://finance.qq.com");
            request.setHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        }
    }
}
