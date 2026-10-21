package com.example.feed;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.util.LambdaSafe;
import org.springframework.boot.web.embedded.tomcat.TomcatConnectorCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Compression;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.StringUtils;

/**
 * Two things, both through Spring Boot's documented WebServerFactoryCustomizer hook.
 *
 * 1. Socket buffers. Tomcat NIO allocates a read and a write ByteBuffer for every open connection,
 * 8 KB each by default (SocketBufferHandler, on the heap), even while the connection sits idle in
 * keep-alive. Measured on the heap: ~18 KB per idle connection, so ~18 GB at 1M connections. Tomcat's
 * connector docs say, for socket.appReadBufSize / socket.appWriteBufSize: "For an extremely high amount
 * of keep alive connections, decrease this number or increase your heap size." Spring Boot has no
 * property for them, so they're set with a connector customizer. Requests here are well under 1 KB
 * (headers are parsed in the processor's own buffer, sized from max-http-request-header-size), and
 * responses larger than the write buffer are written in more than one chunk.
 *
 * 2. Second port (env EXTRA_PORT, optional). Spring Boot's documented way to listen on two ports is
 * factory.addAdditionalTomcatConnectors(connector). Boot applies its server.* / server.tomcat.*
 * settings only to the main connector (TomcatServletWebServerFactory.customizeConnector), so the extra
 * connector gets the same treatment here: the same (default NIO) protocol and address, every registered connector
 * customizer (max-connections, accept-count, keep-alive-timeout, connection-timeout,
 * max-keep-alive-requests, ... and the socket buffers above), every protocol-handler customizer
 * (the virtual-thread executor), and the server.compression settings, exactly as Boot's own
 * CompressionConnectorCustomizer applies them (that class is package-private). This customizer runs
 * last (LOWEST_PRECEDENCE), after Boot's TomcatWebServerFactoryCustomizer (order 0) and
 * TomcatVirtualThreadsWebServerFactoryCustomizer (order 1) have registered theirs.
 */
@Configuration(proxyBeanMethods = false)
class TomcatConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory>, Ordered {

    private static final String SOCKET_BUFFER_BYTES = "4096";

    private final int extraPort;

    TomcatConfig(@Value("${EXTRA_PORT:0}") int extraPort) {
        this.extraPort = extraPort;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addConnectorCustomizers(connector -> {
            connector.setProperty("socket.appReadBufSize", SOCKET_BUFFER_BYTES);
            connector.setProperty("socket.appWriteBufSize", SOCKET_BUFFER_BYTES);
        });
        if (extraPort > 0) {
            factory.addAdditionalTomcatConnectors(extraConnector(factory));
        }
    }

    @SuppressWarnings("unchecked")
    private Connector extraConnector(TomcatServletWebServerFactory factory) {
        Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
        connector.setThrowOnFailure(true);
        connector.setPort(extraPort);
        ProtocolHandler handler = connector.getProtocolHandler();
        if (handler instanceof AbstractHttp11Protocol<?> http11) {
            if (factory.getAddress() != null) {
                http11.setAddress(factory.getAddress());
            }
            Compression compression = factory.getCompression();
            if (compression != null && compression.getEnabled()) {
                http11.setCompression("on");
                http11.setCompressionMinSize((int) compression.getMinResponseSize().toBytes());
                http11.setCompressibleMimeType(StringUtils.arrayToCommaDelimitedString(compression.getMimeTypes()));
                if (compression.getExcludedUserAgents() != null) {
                    http11.setNoCompressionUserAgents(
                            StringUtils.arrayToCommaDelimitedString(compression.getExcludedUserAgents()));
                }
            }
        }
        LambdaSafe.callbacks(TomcatProtocolHandlerCustomizer.class, factory.getTomcatProtocolHandlerCustomizers(),
                handler).invoke(customizer -> customizer.customize(handler));
        for (TomcatConnectorCustomizer customizer : factory.getTomcatConnectorCustomizers()) {
            customizer.customize(connector);
        }
        return connector;
    }
}
