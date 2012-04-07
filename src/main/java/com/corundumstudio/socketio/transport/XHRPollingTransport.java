/**
 * Copyright (c) 2012 Nikita Koksharov
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */
package com.corundumstudio.socketio.transport;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.corundumstudio.socketio.PacketListener;
import com.corundumstudio.socketio.SocketIORouter;
import com.corundumstudio.socketio.parser.Decoder;
import com.corundumstudio.socketio.parser.Encoder;
import com.corundumstudio.socketio.parser.ErrorAdvice;
import com.corundumstudio.socketio.parser.ErrorReason;
import com.corundumstudio.socketio.parser.Packet;
import com.corundumstudio.socketio.parser.PacketType;

public class XHRPollingTransport implements SocketIOTransport {

	private final Logger log = LoggerFactory.getLogger(getClass());
	
	private final Map<UUID, XHRPollingClient> sessionId2Client = new ConcurrentHashMap<UUID, XHRPollingClient>();
	
	private final SocketIORouter socketIORouter;
	private final PacketListener packetListener;
	private final Decoder decoder;
	private final Encoder encoder;
	private final String pollingPath;
	
	public XHRPollingTransport(int protocol, Decoder decoder, Encoder encoder, 
			SocketIORouter socketIORouter, PacketListener packetListener) {
		this.pollingPath = "/socket.io/" + protocol + "/xhr-polling/";
		this.decoder = decoder;
		this.encoder = encoder;
		this.socketIORouter = socketIORouter;
		this.packetListener = packetListener;
	}
	
	public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        Object msg = e.getMessage();
        if (msg instanceof HttpRequest) {
        	HttpRequest req = (HttpRequest) msg;
        	QueryStringDecoder queryDecoder = new QueryStringDecoder(req.getUri());
        	
        	Channel channel = ctx.getChannel();
			if (HttpMethod.POST.equals(req.getMethod())) {
        		onPost(queryDecoder, channel, req);
        	} else if (HttpMethod.GET.equals(req.getMethod())) {
        		onGet(queryDecoder, channel, req);
        	}
        }
	}
	
	private void onPost(QueryStringDecoder queryDecoder, Channel channel, HttpRequest req) {
		String path = queryDecoder.getPath();
		if (!path.startsWith(pollingPath)) {
			log.warn("Wrong POST request path: {}, from ip: {}. Channel closed!", 
					new Object[] {path, channel.getRemoteAddress()});
			channel.close();
			return;
		}
		
		String[] parts = path.split("/");
		if (parts.length > 3) {
			UUID sessionId = UUID.fromString(parts[4]);
			XHRPollingClient client = sessionId2Client.get(sessionId);
			if (client == null) {
				log.debug("Client with sessionId: {} was already disconnected. Channel closed!", sessionId);
				channel.close();
				return;
			}
			
			String content = req.getContent().toString(CharsetUtil.UTF_8);
			log.trace("Request content: {}", content);
			try {
				List<Packet> packets = decoder.decodePayload(content);
				for (Packet packet : packets) {
					packetListener.onPacket(packet, client);
				}
			} catch (IOException e) {
				
			}
			HttpHeaders.setKeepAlive(req, false);
			
			//send a response that allows for cross domain access
			HttpResponse resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
			String origin = req.getHeader(HttpHeaders.Names.ORIGIN);
			if (origin != null) {
				resp.addHeader("Access-Control-Allow-Origin", origin);
				resp.addHeader("Access-Control-Allow-Credentials", "true");
			}
			sendHttpResponse(channel, req, resp);
		} else {
			log.warn("Wrong POST request path: {}, from ip: {}. Channel closed!", 
					new Object[] {path, channel.getRemoteAddress()});
			channel.close();
		}
	}
	
	private void onGet(QueryStringDecoder queryDecoder, Channel channel, HttpRequest req) throws IOException {
		String path = queryDecoder.getPath();
		if (!path.startsWith(pollingPath)) {
			log.warn("Wrong GET request path: {}, from ip: {}. Channel closed!", 
					new Object[] {path, channel.getRemoteAddress()});
			channel.close();
			return;
		}
		
		String[] parts = path.split("/");
		if (parts.length > 3) {
			UUID sessionId = UUID.fromString(parts[4]);
			if (socketIORouter.isSessionAuthorized(sessionId)) {
				XHRPollingClient client = sessionId2Client.get(sessionId);
				if (client == null) {
					client = createClient(sessionId);
				}
				client.doReconnect(channel, req);
				if (queryDecoder.getParameters().containsKey("disconnect")) {
					disconnect(sessionId);
				}
			} else {
				sendError(channel, req, sessionId);
			}
		} else {
			log.warn("Wrong GET request path: {}, from ip: {}. Channel closed!", 
					new Object[] {path, channel.getRemoteAddress()});
			channel.close();
		}
	}

	private XHRPollingClient createClient(UUID sessionId) {
		XHRPollingClient client = new XHRPollingClient(encoder, socketIORouter, sessionId);
		sessionId2Client.put(sessionId, client);

		socketIORouter.connect(client);
		log.debug("Client for sessionId: {} was created", sessionId);
		return client;
	}

	private void sendError(Channel channel, HttpRequest req, UUID sessionId) {
		log.debug("Client with sessionId: {} was not found! Reconnect error response sended", sessionId);
		XHRPollingClient client = new XHRPollingClient(encoder, socketIORouter, null);
		Packet packet = new Packet(PacketType.ERROR);
		packet.setReason(ErrorReason.CLIENT_NOT_HANDSHAKEN);
		packet.setAdvice(ErrorAdvice.RECONNECT);
		client.send(packet);
		client.doReconnect(channel, req);
	}

    private void sendHttpResponse(Channel channel, HttpRequest req, HttpResponse res) {
        if (res.getStatus().getCode() != 200) {
            res.setContent(
                    ChannelBuffers.copiedBuffer(
                        res.getStatus().toString(), CharsetUtil.UTF_8));
            HttpHeaders.setContentLength(res, res.getContent().readableBytes());
        }

        ChannelFuture f = channel.write(res);
        if (!HttpHeaders.isKeepAlive(req) || res.getStatus().getCode() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

	public void disconnect(UUID sessionId) {
		XHRPollingClient client = sessionId2Client.remove(sessionId);
		client.send(new Packet(PacketType.DISCONNECT));
		socketIORouter.disconnect(client);
	}
	
}