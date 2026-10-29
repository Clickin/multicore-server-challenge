package challenge;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.epoll.EpollChannelOption;
import io.vertx.core.impl.transports.EpollTransport;
import io.vertx.core.net.TcpConfig;

// Accept a connection only once its first request has arrived: one wake-up per new connection instead of two.
final class DeferAcceptTransport extends EpollTransport {
    @Override public void configure(TcpConfig config, boolean domainSocket, ServerBootstrap bootstrap) {
        super.configure(config, domainSocket, bootstrap);
        if (!domainSocket) bootstrap.option(EpollChannelOption.TCP_DEFER_ACCEPT, 1);
    }
}
