package com.launchdarkly.sdk.server;

import com.launchdarkly.sdk.server.interfaces.ClientContext;
import com.launchdarkly.sdk.server.interfaces.EventSender;
import com.launchdarkly.sdk.server.interfaces.EventSenderFactory;
import com.launchdarkly.sdk.server.interfaces.HttpConfiguration;

import org.junit.Test;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.launchdarkly.sdk.server.TestComponents.clientContext;
import static com.launchdarkly.sdk.server.TestUtil.makeSocketFactorySingleHost;
import static com.launchdarkly.sdk.server.TestHttpUtil.httpsServerWithSelfSignedCert;
import static com.launchdarkly.sdk.server.TestHttpUtil.makeStartedServer;
import static com.launchdarkly.sdk.server.interfaces.EventSender.EventDataKind.ANALYTICS;
import static com.launchdarkly.sdk.server.interfaces.EventSender.EventDataKind.DIAGNOSTICS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

@SuppressWarnings("javadoc")
public class DefaultEventSenderTest {
  private static final String SDK_KEY = "SDK_KEY";
  private static final String FAKE_DATA = "some data";
  private static final SimpleDateFormat httpDateFormat = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz");
  private static final Duration BRIEF_RETRY_DELAY = Duration.ofMillis(50);
  
  private static EventSender makeEventSender() {
    return makeEventSender(LDConfig.DEFAULT);
  }

  private static EventSender makeEventSender(LDConfig config) {
    return new DefaultEventSender(
        clientContext(SDK_KEY, config).getHttp(),
        BRIEF_RETRY_DELAY
        );
  }

  private static URI getBaseUri(MockWebServer server) {
    return server.url("/").uri();
  }

  @Test
  public void factoryCreatesDefaultSenderWithDefaultRetryDelay() throws Exception {
    EventSenderFactory f = new DefaultEventSender.Factory();
    ClientContext context = clientContext(SDK_KEY, LDConfig.DEFAULT);
    try (EventSender es = f.createEventSender(context.getBasic(), context.getHttp())) {
      assertThat(es, isA(EventSender.class));
      assertThat(((DefaultEventSender)es).retryDelay, equalTo(DefaultEventSender.DEFAULT_RETRY_DELAY));
    }
  }

  @Test
  public void constructorUsesDefaultRetryDelayIfNotSpecified() throws Exception {
    ClientContext context = clientContext(SDK_KEY, LDConfig.DEFAULT);
    try (EventSender es = new DefaultEventSender(context.getHttp(), null)) {
      assertThat(((DefaultEventSender)es).retryDelay, equalTo(DefaultEventSender.DEFAULT_RETRY_DELAY));
    }
  }
  
  @Test
  public void analyticsDataIsDelivered() throws Exception {
    try (MockWebServer server = makeStartedServer(eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        EventSender.Result result = es.sendEventData(ANALYTICS, FAKE_DATA, 1, getBaseUri(server));
        
        assertTrue(result.isSuccess());
        assertFalse(result.isMustShutDown());
      }
      
      RecordedRequest req = server.takeRequest();   
      assertEquals("/bulk", req.getPath());
      assertEquals("application/json; charset=utf-8", req.getHeader("content-type"));
      assertEquals(FAKE_DATA, req.getBody().readUtf8());
    }
  }

  @Test
  public void diagnosticDataIsDelivered() throws Exception {
    try (MockWebServer server = makeStartedServer(eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        EventSender.Result result = es.sendEventData(DIAGNOSTICS, FAKE_DATA, 1, getBaseUri(server));
        
        assertTrue(result.isSuccess());
        assertFalse(result.isMustShutDown());
      }
      
      RecordedRequest req = server.takeRequest();     
      assertEquals("/diagnostic", req.getPath());
      assertEquals("application/json; charset=utf-8", req.getHeader("content-type"));
      assertEquals(FAKE_DATA, req.getBody().readUtf8());
    }
  }

  @Test
  public void defaultHeadersAreSentForAnalytics() throws Exception {
    HttpConfiguration httpConfig = clientContext(SDK_KEY, LDConfig.DEFAULT).getHttp();
    
    try (MockWebServer server = makeStartedServer(eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        es.sendEventData(ANALYTICS, FAKE_DATA, 1, getBaseUri(server));
      }
      
      RecordedRequest req = server.takeRequest();
      for (Map.Entry<String, String> kv: httpConfig.getDefaultHeaders()) {
        assertThat(req.getHeader(kv.getKey()), equalTo(kv.getValue()));
      }
    }
  }

  @Test
  public void defaultHeadersAreSentForDiagnostics() throws Exception {
    HttpConfiguration httpConfig = clientContext(SDK_KEY, LDConfig.DEFAULT).getHttp();
    
    try (MockWebServer server = makeStartedServer(eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        es.sendEventData(DIAGNOSTICS, FAKE_DATA, 1, getBaseUri(server));
      }
      
      RecordedRequest req = server.takeRequest();      
      for (Map.Entry<String, String> kv: httpConfig.getDefaultHeaders()) {
        assertThat(req.getHeader(kv.getKey()), equalTo(kv.getValue()));
      }
    }
  }

  @Test
  public void eventSchemaIsSentForAnalytics() throws Exception {
    try (MockWebServer server = makeStartedServer(eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        es.sendEventData(ANALYTICS, FAKE_DATA, 1, getBaseUri(server));
      }

      RecordedRequest req = server.takeRequest();
      assertThat(req.getHeader("X-LaunchDarkly-Event-Schema"), equalTo("3"));
    }
  }

  @Test
  public void eventPayloadIdIsSentForAnalytics() throws Exception {
    try (MockWebServer server = makeStartedServer(eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        es.sendEventData(ANALYTICS, FAKE_DATA, 1, getBaseUri(server));
      }

      RecordedRequest req = server.takeRequest();
      String payloadHeaderValue = req.getHeader("X-LaunchDarkly-Payload-ID");
      assertThat(payloadHeaderValue, notNullValue(String.class));
      assertThat(UUID.fromString(payloadHeaderValue), notNullValue(UUID.class));
    }
  }

  @Test
  public void eventPayloadIdReusedOnRetry() throws Exception {
    MockResponse errorResponse = new MockResponse().setResponseCode(429);

    try (MockWebServer server = makeStartedServer(errorResponse, eventsSuccessResponse(), eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        es.sendEventData(ANALYTICS, FAKE_DATA, 1, getBaseUri(server));
        es.sendEventData(ANALYTICS, FAKE_DATA, 1, getBaseUri(server));
      }

      // Failed response request
      RecordedRequest req = server.takeRequest(0, TimeUnit.SECONDS);
      String payloadId = req.getHeader("X-LaunchDarkly-Payload-ID");
      // Retry request has same payload ID as failed request
      req = server.takeRequest(0, TimeUnit.SECONDS);
      String retryId = req.getHeader("X-LaunchDarkly-Payload-ID");
      assertThat(retryId, equalTo(payloadId));
      // Second request has different payload ID from first request
      req = server.takeRequest(0, TimeUnit.SECONDS);
      payloadId = req.getHeader("X-LaunchDarkly-Payload-ID");
      assertThat(retryId, not(equalTo(payloadId)));
    }
  }
  
  @Test
  public void eventSchemaNotSetOnDiagnosticEvents() throws Exception {
    try (MockWebServer server = makeStartedServer(eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        es.sendEventData(DIAGNOSTICS, FAKE_DATA, 1, getBaseUri(server));
      }

      RecordedRequest req = server.takeRequest();
      assertNull(req.getHeader("X-LaunchDarkly-Event-Schema"));
    }
  }

  @Test
  public void http400ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(400);
  }

  @Test
  public void http401ErrorIsUnrecoverable() throws Exception {
    testUnrecoverableHttpError(401);
  }

  @Test
  public void http403ErrorIsUnrecoverable() throws Exception {
    testUnrecoverableHttpError(403);
  }

  // Cannot test our retry logic for 408, because OkHttp insists on doing its own retry on 408 so that
  // we never actually see that response status.
//  @Test
//  public void http408ErrorIsRecoverable() throws Exception {
//    testRecoverableHttpError(408);
//  }

  @Test
  public void http429ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(429);
  }

  @Test
  public void http500ErrorIsRecoverable() throws Exception {
    testRecoverableHttpError(500);
  }
 
  @Test
  public void serverDateIsParsed() throws Exception {
    long fakeTime = ((new Date().getTime() - 100000) / 1000) * 1000; // don't expect millisecond precision
    MockResponse resp = addDateHeader(eventsSuccessResponse(), new Date(fakeTime));

    try (MockWebServer server = makeStartedServer(resp)) {
      try (EventSender es = makeEventSender()) {
        EventSender.Result result = es.sendEventData(DIAGNOSTICS, FAKE_DATA, 1, getBaseUri(server));
        
        assertNotNull(result.getTimeFromServer());
        assertEquals(fakeTime, result.getTimeFromServer().getTime());
      }
    }
  }

  @Test
  public void invalidServerDateIsIgnored() throws Exception {
    MockResponse resp = eventsSuccessResponse().addHeader("Date", "not a date");

    try (MockWebServer server = makeStartedServer(resp)) {
      try (EventSender es = makeEventSender()) {
        EventSender.Result result = es.sendEventData(DIAGNOSTICS, FAKE_DATA, 1, getBaseUri(server));
        
        assertTrue(result.isSuccess());
        assertNull(result.getTimeFromServer());
      }
    }
  }
  
  @Test
  public void httpClientDoesNotAllowSelfSignedCertByDefault() throws Exception {
    try (TestHttpUtil.ServerWithCert serverWithCert = httpsServerWithSelfSignedCert(eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        EventSender.Result result = es.sendEventData(ANALYTICS, FAKE_DATA, 1, serverWithCert.uri());
        
        assertFalse(result.isSuccess());
        assertFalse(result.isMustShutDown());
      }

      assertEquals(0, serverWithCert.server.getRequestCount());
    }
  }
  
  @Test
  public void httpClientCanUseCustomTlsConfig() throws Exception {
    try (TestHttpUtil.ServerWithCert serverWithCert = httpsServerWithSelfSignedCert(eventsSuccessResponse())) {
      LDConfig config = new LDConfig.Builder()
          .http(Components.httpConfiguration()
              .sslSocketFactory(serverWithCert.socketFactory, serverWithCert.trustManager)
              // allows us to trust the self-signed cert
              )
          .build();
      
      try (EventSender es = makeEventSender(config)) {
        EventSender.Result result = es.sendEventData(ANALYTICS, FAKE_DATA, 1, serverWithCert.uri());
        
        assertTrue(result.isSuccess());
        assertFalse(result.isMustShutDown());
      }
      
      assertEquals(1, serverWithCert.server.getRequestCount());
    }
  }
  
  @Test
  public void httpClientCanUseCustomSocketFactory() throws Exception {
    try (MockWebServer server = makeStartedServer(eventsSuccessResponse())) {
      HttpUrl serverUrl = server.url("/");
      LDConfig config = new LDConfig.Builder()
        .http(Components.httpConfiguration().socketFactory(makeSocketFactorySingleHost(serverUrl.host(), serverUrl.port())))
        .build();

        URI uriWithWrongPort = URI.create("http://localhost:1");
        try (EventSender es = makeEventSender(config)) {
          EventSender.Result result = es.sendEventData(DIAGNOSTICS, FAKE_DATA, 1, uriWithWrongPort);
            
          assertTrue(result.isSuccess());
          assertFalse(result.isMustShutDown());
        }
        
        assertEquals(1, server.getRequestCount());
    }
  }

  @Test
  public void baseUriDoesNotNeedTrailingSlash() throws Exception {
    try (MockWebServer server = makeStartedServer(eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        URI uriWithoutSlash = URI.create(server.url("/").toString().replaceAll("/$", ""));
        EventSender.Result result = es.sendEventData(ANALYTICS, FAKE_DATA, 1, uriWithoutSlash);
        
        assertTrue(result.isSuccess());
        assertFalse(result.isMustShutDown());
      }
      
      RecordedRequest req = server.takeRequest();   
      assertEquals("/bulk", req.getPath());
      assertEquals("application/json; charset=utf-8", req.getHeader("content-type"));
      assertEquals(FAKE_DATA, req.getBody().readUtf8());
    }
  }

  @Test
  public void baseUriCanHaveContextPath() throws Exception {
    try (MockWebServer server = makeStartedServer(eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        URI baseUri = URI.create(server.url("/context/path").toString());
        EventSender.Result result = es.sendEventData(ANALYTICS, FAKE_DATA, 1, baseUri);
        
        assertTrue(result.isSuccess());
        assertFalse(result.isMustShutDown());
      }
      
      RecordedRequest req = server.takeRequest();   
      assertEquals("/context/path/bulk", req.getPath());
      assertEquals("application/json; charset=utf-8", req.getHeader("content-type"));
      assertEquals(FAKE_DATA, req.getBody().readUtf8());
    }
  }

  @Test
  public void nothingIsSentForNullData() throws Exception {
    try (MockWebServer server = makeStartedServer(eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        EventSender.Result result1 = es.sendEventData(ANALYTICS, null, 0, getBaseUri(server));
        EventSender.Result result2 = es.sendEventData(DIAGNOSTICS, null, 0, getBaseUri(server));
        
        assertTrue(result1.isSuccess());
        assertTrue(result2.isSuccess());
        assertEquals(0, server.getRequestCount());
      }
    }
  }

  @Test
  public void nothingIsSentForEmptyData() throws Exception {
    try (MockWebServer server = makeStartedServer(eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        EventSender.Result result1 = es.sendEventData(ANALYTICS, "", 0, getBaseUri(server));
        EventSender.Result result2 = es.sendEventData(DIAGNOSTICS, "", 0, getBaseUri(server));
        
        assertTrue(result1.isSuccess());
        assertTrue(result2.isSuccess());
        assertEquals(0, server.getRequestCount());
      }
    }
  }
  
  private void testUnrecoverableHttpError(int status) throws Exception {
    MockResponse errorResponse = new MockResponse().setResponseCode(status);
    
    try (MockWebServer server = makeStartedServer(errorResponse)) {
      try (EventSender es = makeEventSender()) {
        EventSender.Result result = es.sendEventData(DIAGNOSTICS, FAKE_DATA, 1, getBaseUri(server));
        
        assertFalse(result.isSuccess());
        assertTrue(result.isMustShutDown());
      }

      RecordedRequest req = server.takeRequest(0, TimeUnit.SECONDS);
      assertThat(req, notNullValue(RecordedRequest.class)); // this was the initial request that received the error
      
      // it does not retry after this type of error, so there are no more requests 
      assertThat(server.takeRequest(0, TimeUnit.SECONDS), nullValue(RecordedRequest.class));
    }
  }
  
  private void testRecoverableHttpError(int status) throws Exception {
    MockResponse errorResponse = new MockResponse().setResponseCode(status);

    // send two errors in a row, because the flush will be retried one time
    try (MockWebServer server = makeStartedServer(errorResponse, errorResponse, eventsSuccessResponse())) {
      try (EventSender es = makeEventSender()) {
        EventSender.Result result = es.sendEventData(DIAGNOSTICS, FAKE_DATA, 1, getBaseUri(server));
        
        assertFalse(result.isSuccess());
        assertFalse(result.isMustShutDown());
      }

      RecordedRequest req = server.takeRequest(0, TimeUnit.SECONDS);
      assertThat(req, notNullValue(RecordedRequest.class));
      req = server.takeRequest(0, TimeUnit.SECONDS);
      assertThat(req, notNullValue(RecordedRequest.class));
      req = server.takeRequest(0, TimeUnit.SECONDS);
      assertThat(req, nullValue(RecordedRequest.class)); // only 2 requests total
    }
  }

  private MockResponse eventsSuccessResponse() {
    return new MockResponse().setResponseCode(202);
  }
  
  private MockResponse addDateHeader(MockResponse response, Date date) {
    return response.addHeader("Date", httpDateFormat.format(date));
  }

}
