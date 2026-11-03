package com.sushil.codemap;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Disposer;
import com.sushil.codemap.analysis.CodeGraphService;
import com.sushil.codemap.analysis.GraphSnapshot;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.jetbrains.io.Responses;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-sent events over the built-in server's Netty channel: a chunked {@code text/event-stream} response that
 * stays open. {@code event: graph} / {@code data: {"version":N,"changed":[...]}} after each published snapshot,
 * {@code : heartbeat} comments every 15 s. Each stream is one {@link Disposable} child of the project's
 * {@link CodeGraphService}: it is disposed (listener removed, heartbeat cancelled) when the channel closes, and the
 * channel is closed when the project closes.
 */
final class CodeMapEvents {
    private static final Logger LOG = Logger.getInstance(CodeMapEvents.class);
    /** Heartbeat interval; -Dcodemap.sse.heartbeatSec=N overrides it (tests). */
    static final long HEARTBEAT_SECONDS = Math.max(1, Long.getLong("codemap.sse.heartbeatSec", 15));
    /** Open streams (all projects), for diagnostics/tests. */
    static final AtomicInteger OPEN = new AtomicInteger();

    private CodeMapEvents() {}

    /** Starts a stream on {@code channel}. Call on the channel's event loop (inside HttpRequestHandler.process). */
    static void open(CodeGraphService service, Channel channel) {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response.headers().set("X-Accel-Buffering", "no");
        HttpUtil.setTransferEncodingChunked(response, true);  // no Content-Length: the body never ends
        Responses.addServer(response);

        Stream stream = new Stream(channel);
        try {
            Disposer.register(service, stream);  // project closed -> stream disposed -> channel closed
        } catch (RuntimeException e) {  // service already disposed (project closing)
            Disposer.dispose(stream);
            return;
        }
        channel.closeFuture().addListener(f -> Disposer.dispose(stream));
        if (!channel.isOpen()) {  // closed before the listener was attached
            Disposer.dispose(stream);
            return;
        }
        channel.write(response);
        long version = service.snapshot().version();
        stream.send("retry: 2000\nevent: hello\ndata: {\"version\":" + version + "}\n\n");
        service.addListener(stream::graphChanged, stream);
        stream.heartbeat = channel.eventLoop().scheduleAtFixedRate(
                () -> stream.send(": heartbeat\n\n"), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        if (stream.disposed.get()) stream.heartbeat.cancel(false);  // closed meanwhile
    }

    static String graphEvent(GraphSnapshot s) {
        StringBuilder sb = new StringBuilder("event: graph\ndata: {\"version\":").append(s.version()).append(",\"changed\":[");
        List<String> changed = s.changedFiles() == null ? List.of() : s.changedFiles().stream().sorted().toList();
        for (int i = 0; i < changed.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.str(changed.get(i)));
        }
        return sb.append("]}\n\n").toString();
    }

    private static final class Stream implements Disposable {
        private final Channel channel;
        private final AtomicBoolean disposed = new AtomicBoolean();
        volatile ScheduledFuture<?> heartbeat;

        Stream(Channel channel) {
            this.channel = channel;
            OPEN.incrementAndGet();
        }

        /** Called on a background thread by CodeGraphService. Netty queues the write onto the event loop. */
        void graphChanged(GraphSnapshot snapshot) {
            send(graphEvent(snapshot));
        }

        void send(String text) {
            if (disposed.get() || !channel.isActive()) return;
            if (!channel.isWritable()) {
                // client stopped reading (output buffer above the high-water mark): drop it, EventSource reconnects
                LOG.info("codemap: closing slow event stream " + channel.remoteAddress());
                channel.close();
                return;
            }
            channel.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8)))
                    .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
        }

        @Override
        public void dispose() {
            if (!disposed.compareAndSet(false, true)) return;
            ScheduledFuture<?> h = heartbeat;
            if (h != null) h.cancel(false);
            OPEN.decrementAndGet();
            if (channel.isOpen()) channel.close();
        }
    }
}
