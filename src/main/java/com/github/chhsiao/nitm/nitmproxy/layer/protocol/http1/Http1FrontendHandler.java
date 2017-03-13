package com.github.chhsiao.nitm.nitmproxy.layer.protocol.http1;

import com.github.chhsiao.nitm.nitmproxy.Address;
import com.github.chhsiao.nitm.nitmproxy.ConnectionInfo;
import com.github.chhsiao.nitm.nitmproxy.NitmProxyConfig;
import com.github.chhsiao.nitm.nitmproxy.ProxyMode;
import com.github.chhsiao.nitm.nitmproxy.layer.protocol.tls.TlsHandler;
import com.google.common.base.Strings;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.*;

public class Http1FrontendHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Pattern PATH_PATTERN = Pattern.compile("(https?)://([a-zA-Z0-9\\.\\-]+)(:(\\d+))?(/.*)");
    private static final Pattern TUNNEL_ADDR_PATTERN = Pattern.compile("^([a-zA-Z0-9\\.\\-_]+):(\\d+)");

    private static final Logger LOGGER = LoggerFactory.getLogger(Http1FrontendHandler.class);

    private NitmProxyConfig config;
    private ConnectionInfo connectionInfo;
    private boolean tunneled;

    private Channel outboundChannel;
    private ChannelHandler httpServerCodec;
    private ChannelHandler httpObjectAggregator;

    public Http1FrontendHandler(NitmProxyConfig config, ConnectionInfo connectionInfo) {
        this(config, connectionInfo, null, false);
    }

    public Http1FrontendHandler(NitmProxyConfig config, ConnectionInfo connectionInfo, Channel outboundChannel) {
        this(config, connectionInfo, outboundChannel, true);
    }

    public Http1FrontendHandler(NitmProxyConfig config, ConnectionInfo connectionInfo, Channel outboundChannel,
                                boolean tunneled) {
        super();
        this.config = config;
        this.connectionInfo = connectionInfo;
        this.outboundChannel = outboundChannel;
        this.tunneled = tunneled;

    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        LOGGER.info("{} : Http1FrontendHandler handlerAdded", connectionInfo);

        httpServerCodec = new HttpServerCodec();
        httpObjectAggregator = new HttpObjectAggregator(config.getMaxContentLength());
        ctx.pipeline()
           .addBefore(ctx.name(), null, httpServerCodec)
           .addBefore(ctx.name(), null, httpObjectAggregator);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        LOGGER.info("{} : Http1FrontendHandler handlerRemoved", connectionInfo);

        ctx.pipeline().remove(httpServerCodec).remove(httpObjectAggregator);

        if (outboundChannel != null) {
            outboundChannel.close();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx,
                                FullHttpRequest request) throws Exception {
        if (config.getProxyMode() == ProxyMode.HTTP && !tunneled) {
            if (request.method() == HttpMethod.CONNECT) {
                handleTunnelProxyConnection(ctx, request);
            } else {
                handleHttpProxyConnection(ctx, request);
            }
        } else {
            checkState(outboundChannel != null);
            LOGGER.info("[Client ({})] => [Server ({})] : {}",
                        connectionInfo.getClientAddr(), connectionInfo.getServerAddr(),
                        request);
            outboundChannel.writeAndFlush(ReferenceCountUtil.retain(request));
        }
    }

    private void handleTunnelProxyConnection(ChannelHandlerContext ctx,
                                             HttpRequest request) throws Exception {
        Address address = resolveTunnelAddr(request.uri());
        createOutboundChannel(ctx, address)
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (future.isSuccess()) {
                            HttpResponse response =
                                    new DefaultHttpResponse(request.protocolVersion(), HttpResponseStatus.OK);
                            LOGGER.info("[Client ({})] <= [Proxy] : {}", connectionInfo.getClientAddr(), response);
                            ctx.writeAndFlush(response);

                            ConnectionInfo newConnInfo = new ConnectionInfo(connectionInfo.getClientAddr(), address);
                            TlsHandler tlsHandler = new TlsHandler(config, newConnInfo, future.channel(), false);
                            ctx.pipeline().replace(Http1FrontendHandler.this, null, tlsHandler);
                        }
                    }
                });
    }

    private void handleHttpProxyConnection(ChannelHandlerContext ctx,
                                           HttpRequest request) throws Exception {
        FullPath fullPath = resolveHttpProxyPath(request.uri());
        Address serverAddr = new Address(fullPath.host, fullPath.port);
        if (outboundChannel != null && !connectionInfo.getServerAddr().equals(serverAddr)) {
            outboundChannel.close();
            outboundChannel = null;
        }
        if (outboundChannel == null) {
            outboundChannel = createOutboundChannel(ctx, serverAddr).channel();
        }

        HttpRequest newRequest = new DefaultHttpRequest(
                request.protocolVersion(), request.method(), fullPath.path, request.headers());

        LOGGER.info("[Client ({})] => [Server ({})] : {}",
                    connectionInfo.getClientAddr(), connectionInfo.getServerAddr(),
                    newRequest);
        outboundChannel.writeAndFlush(newRequest);
    }

    private FullPath resolveHttpProxyPath(String fullPath) {
        Matcher matcher = PATH_PATTERN.matcher(fullPath);
        if (matcher.find()) {
            String scheme = matcher.group(1);
            String host = matcher.group(2);
            int port = resolvePort(scheme, matcher.group(4));
            String path = matcher.group(5);
            return new FullPath(scheme, host, port, path);
        } else {
            throw new IllegalStateException("Illegal http proxy path: " + fullPath);
        }
    }

    private Address resolveTunnelAddr(String addr) {
        Matcher matcher = TUNNEL_ADDR_PATTERN.matcher(addr);
        if (matcher.find()) {
            return new Address(matcher.group(1), Integer.parseInt(matcher.group(2)));
        } else {
            throw new IllegalStateException("Illegal tunnel addr: " + addr);
        }
    }

    private int resolvePort(String scheme, String port) {
        if (Strings.isNullOrEmpty(port)) {
            return "https".equals(scheme) ? 443 : 80;
        }
        return Integer.parseInt(port);
    }

    private ChannelFuture createOutboundChannel(ChannelHandlerContext ctx, Address serverAddr) {
        connectionInfo = new ConnectionInfo(connectionInfo.getClientAddr(),
                                            new Address(serverAddr.getHost(), serverAddr.getPort()));
        Bootstrap bootstrap = new Bootstrap()
                .group(ctx.channel().eventLoop())
                .channel(ctx.channel().getClass())
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) throws Exception {
                        TlsHandler tlsHandler = new TlsHandler(config, connectionInfo, ctx.channel(), true);
                        ch.pipeline().addLast(tlsHandler);
                    }
                });
        ChannelFuture future = bootstrap.connect(serverAddr.getHost(), serverAddr.getPort());
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    ctx.channel().close();
                }
            }
        });
        return future;
    }

    private static class FullPath {
        private String scheme;
        private String host;
        private int port;
        private String path;

        private FullPath(String scheme, String host, int port, String path) {
            this.scheme = scheme;
            this.host = host;
            this.port = port;
            this.path = path;
        }

        @Override
        public String toString() {
            return "FullPath{" +
                   "scheme='" + scheme + '\'' +
                   ", host='" + host + '\'' +
                   ", port=" + port +
                   ", path='" + path + '\'' +
                   '}';
        }
    }
}
