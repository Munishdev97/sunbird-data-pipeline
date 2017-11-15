package org.ekstep.ep.samza.task;

import com.google.gson.Gson;
import org.apache.samza.config.Config;
import org.apache.samza.metrics.Counter;
import org.apache.samza.metrics.MetricsRegistry;
import org.apache.samza.storage.kv.KeyValueStore;
import org.apache.samza.system.IncomingMessageEnvelope;
import org.apache.samza.system.OutgoingMessageEnvelope;
import org.apache.samza.system.SystemStream;
import org.apache.samza.task.MessageCollector;
import org.apache.samza.task.TaskContext;
import org.apache.samza.task.TaskCoordinator;
import org.ekstep.ep.samza.cache.CacheEntry;
import org.ekstep.ep.samza.fixture.ContentFixture;
import org.ekstep.ep.samza.fixture.EventFixture;
import org.ekstep.ep.samza.search.domain.Content;
import org.ekstep.ep.samza.search.service.SearchServiceClient;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.mockito.Mockito;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static junit.framework.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.argThat;
import static org.mockito.Mockito.*;

public class ContentDeNormalizationTaskTest {

    private static final String SUCCESS_TOPIC = "telemetry.content.de_normalized";
    private static final String FAILED_TOPIC = "telemetry.content.de_normalized.fail";
    private static final String CONTENT_CACHE_TTL = "60000";
    private final String EVENTS_TO_SKIP = "";
    private final String EVENTS_TO_ALLOW = "GE_LAUNCH_GAME,OE_.*,ME_.*,CE_.*,CP_.*,BE_.*";
    private MessageCollector collectorMock;
    private SearchServiceClient searchServiceMock;
    private TaskContext contextMock;
    private MetricsRegistry metricsRegistry;
    private Counter counter;
    private TaskCoordinator coordinatorMock;
    private IncomingMessageEnvelope envelopeMock;
    private Config configMock;
    private ContentDeNormalizationTask contentDeNormalizationTask;
    private KeyValueStore contentStoreMock;

    @Before
    public void setUp() {
        collectorMock = mock(MessageCollector.class);
        searchServiceMock = mock(SearchServiceClient.class);
        contextMock = Mockito.mock(TaskContext.class);
        metricsRegistry = Mockito.mock(MetricsRegistry.class);
        counter = Mockito.mock(Counter.class);
        coordinatorMock = mock(TaskCoordinator.class);
        envelopeMock = mock(IncomingMessageEnvelope.class);
        configMock = Mockito.mock(Config.class);
        contentStoreMock = mock(KeyValueStore.class);

        stub(configMock.get("output.success.topic.name", SUCCESS_TOPIC)).toReturn(SUCCESS_TOPIC);
        stub(configMock.get("output.failed.topic.name", FAILED_TOPIC)).toReturn(FAILED_TOPIC);
        stub(configMock.get("events.to.skip", "")).toReturn(EVENTS_TO_SKIP);
        stub(configMock.get("events.to.allow", "")).toReturn(EVENTS_TO_ALLOW);
        stub(configMock.get("gid.overridden.events", "")).toReturn("oe.gid.field,ge.gid.field,me.gid.field,ce.gid.field,cp.gid.field,be.gid.field");
        stub(configMock.get("oe.gid.field", "")).toReturn("gdata.id");
        stub(configMock.get("ge.gid.field", "")).toReturn("edata.eks.gid");
        stub(configMock.get("me.gid.field", "")).toReturn("dimensions.content_id");
        stub(configMock.get("ce.gid.field", "")).toReturn("context.content_id");
        stub(configMock.get("be.gid.field", "")).toReturn("edata.eks.cid");
        stub(configMock.get("cp.gid.field", "")).toReturn("edata.eks.action");
        stub(configMock.containsKey("oe.gid.field")).toReturn(true);
        stub(configMock.containsKey("ge.gid.field")).toReturn(true);
        stub(configMock.containsKey("me.gid.field")).toReturn(true);
        stub(configMock.containsKey("ce.gid.field")).toReturn(true);
        stub(configMock.containsKey("be.gid.field")).toReturn(true);
        stub(configMock.containsKey("cp.gid.field")).toReturn(true);
        stub(configMock.get("content.store.ttl", "60000")).toReturn(CONTENT_CACHE_TTL);
        stub(metricsRegistry.newCounter(anyString(), anyString()))
                .toReturn(counter);
        stub(contextMock.getMetricsRegistry()).toReturn(metricsRegistry);

        contentDeNormalizationTask = new ContentDeNormalizationTask(configMock, contextMock, searchServiceMock, contentStoreMock);
    }

    @Test
    public void shouldSkipIfContentIdIsBlank() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.EventWithoutContentId());
        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);
        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

    @Test
    public void shouldProcessEventFromCacheIfPresentAndSkipServiceCall() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.OeEvent());

        CacheEntry contentCache = new CacheEntry(ContentFixture.getContent(), new Date().getTime());
        String contentCacheJson = new Gson().toJson(contentCache, CacheEntry.class);

        stub(contentStoreMock.get(ContentFixture.getContentID())).toReturn(contentCacheJson);

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);

        verify(contentStoreMock, times(1)).get(ContentFixture.getContentID());
        verify(searchServiceMock, times(0)).searchContent(ContentFixture.getContentID());
        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

    @Test
    public void shouldCallSearchApiAndUpdateCacheIfEventIsNotPresentInCache() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.OeEvent());
        when(contentStoreMock.get(ContentFixture.getContentID())).thenReturn(null, getContentCacheJson());

        stub(searchServiceMock.searchContent(ContentFixture.getContentID())).toReturn(ContentFixture.getContent());

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);

        verify(contentStoreMock, times(2)).get(ContentFixture.getContentID());
        verify(searchServiceMock, times(1)).searchContent(ContentFixture.getContentID());
        verify(contentStoreMock, times(1)).put(anyString(), anyString());
        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

    @Test
    public void shouldCallSearchApiAndUpdateCacheIfCacheIsExpired() throws Exception {

        CacheEntry contentCache = new CacheEntry(ContentFixture.getContent(), new Date().getTime() - 100000);
        String contentCacheJson = new Gson().toJson(contentCache, CacheEntry.class);

        stub(contentStoreMock.get(ContentFixture.getContentID())).toReturn(contentCacheJson);
        stub(envelopeMock.getMessage()).toReturn(EventFixture.OeEvent());
        stub(searchServiceMock.searchContent(ContentFixture.getContentID())).toReturn(ContentFixture.getContent());

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);

        verify(contentStoreMock, times(2)).get(ContentFixture.getContentID());
        verify(searchServiceMock, times(1)).searchContent(ContentFixture.getContentID());
        verify(contentStoreMock, times(1)).put(anyString(), anyString());
        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

    @Test
    public void shouldProcessAllOeEventsAndUpdateContentData() throws Exception {
        when(contentStoreMock.get(ContentFixture.getContentID())).thenReturn(null, getContentCacheJson());
        stub(envelopeMock.getMessage()).toReturn(EventFixture.OeEvent());

        verifyEventHasBeenProcessed();
    }

    @Test
    public void shouldProcessGeLaunchEventAndUpdateContentData() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.GeLaunchEvent());

        verifyEventHasBeenProcessed();
    }

    @Test
    public void shouldProcessMeEventsAndUpdateContentCache() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.MeEvent());

        verifyEventHasBeenProcessed();
    }

    @Test
    public void shouldProcessCeEventsAndUpdateContentCache() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.CeEvent());

        verifyEventHasBeenProcessed();
    }

    @Test
    public void shouldProcessCpEventsAndUpdateContentCache() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.CpEvent());

        verifyEventHasBeenProcessed();
    }

    @Test
    public void shouldProcessBeEventsAndUpdateContentCache() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.BeEvent());

        verifyEventHasBeenProcessed();
    }

    @Test
    public void shouldNotProcessAnyGeEventOtherThanGenieLaunch() throws Exception {
        stub(envelopeMock.getMessage()).toReturn(EventFixture.OtherGeEvent());

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);

        verify(contentStoreMock, times(0)).get(anyString());
        verify(searchServiceMock, times(0)).searchContent(anyString());
        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

    private ArgumentMatcher<OutgoingMessageEnvelope> validateOutputTopic(final Object message, final String stream) {
        return new ArgumentMatcher<OutgoingMessageEnvelope>() {
            @Override
            public boolean matches(Object o) {
                OutgoingMessageEnvelope outgoingMessageEnvelope = (OutgoingMessageEnvelope) o;
                SystemStream systemStream = outgoingMessageEnvelope.getSystemStream();
                assertEquals("kafka", systemStream.getSystem());
                assertEquals(stream, systemStream.getStream());
                assertEquals(message, outgoingMessageEnvelope.getMessage());
                return true;
            }
        };
    }

    private String getContentCacheJson() {
        Content content = ContentFixture.getContent();
        CacheEntry contentCache = new CacheEntry(content, new Date().getTime());
        return new Gson().toJson(contentCache, CacheEntry.class);
    }

    private void verifyEventHasBeenProcessed() throws Exception {
        stub(searchServiceMock.searchContent(ContentFixture.getContentID())).toReturn(ContentFixture.getContent());

        CacheEntry expiredContent = new CacheEntry(ContentFixture.getContent(), new Date().getTime() - 100000);
        CacheEntry validContent = new CacheEntry(ContentFixture.getContent(), new Date().getTime() + 100000);

        when(contentStoreMock.get(ContentFixture.getContentID()))
                .thenReturn(
                        new Gson().toJson(expiredContent, CacheEntry.class),
                        new Gson().toJson(validContent, CacheEntry.class));

        contentDeNormalizationTask.process(envelopeMock, collectorMock, coordinatorMock);

        verify(searchServiceMock, times(1)).searchContent("do_30076072");
        Map<String, Object> processedMessage = (Map<String, Object>) envelopeMock.getMessage();

        assertTrue(processedMessage.containsKey("contentdata"));

        HashMap<String, Object> contentData = (HashMap<String, Object>) processedMessage.get("contentdata");
        assertEquals(contentData.get("name"), ContentFixture.getContentMap().get("name"));
        assertEquals(contentData.get("description"), ContentFixture.getContentMap().get("description"));

        verify(collectorMock).send(argThat(validateOutputTopic(envelopeMock.getMessage(), SUCCESS_TOPIC)));
    }

}