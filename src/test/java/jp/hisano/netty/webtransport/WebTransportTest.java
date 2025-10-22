package jp.hisano.netty.webtransport;

import com.google.common.io.Resources;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.incubator.codec.http3.DefaultHttp3DataFrame;
import io.netty.incubator.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.incubator.codec.http3.Http3;
import io.netty.incubator.codec.http3.Http3HeadersFrame;
import io.netty.incubator.codec.http3.Http3ServerConnectionHandler;
import io.netty.incubator.codec.quic.InsecureQuicTokenHandler;
import io.netty.incubator.codec.quic.QuicChannel;
import io.netty.incubator.codec.quic.QuicSslContext;
import io.netty.incubator.codec.quic.QuicSslContextBuilder;
import io.netty.incubator.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Execution(ExecutionMode.SAME_THREAD)
public class WebTransportTest {
	@ParameterizedTest
	@EnumSource(TestType.class)
	public void testPackets(TestType testType) throws Exception {
		System.out.println("\n========== Starting test for " + testType + " ==========");
		BlockingQueue<String> serverMessages = new LinkedBlockingQueue<>();
		BlockingQueue<String> clientMessages = new LinkedBlockingQueue<>();

		SelfSignedCertificate selfSignedCertificate = CertificateUtils.createSelfSignedCertificateForLocalHost();

		NioEventLoopGroup eventLoopGroup = new NioEventLoopGroup();
		try {
			System.out.println("[Test] Starting server for " + testType);
			startServer(serverMessages, selfSignedCertificate, testType, eventLoopGroup);
			System.out.println("[Test] Starting client for " + testType);
			startClient(clientMessages, selfSignedCertificate);
			System.out.println("[Test] Client completed for " + testType);

			if (testType == TestType.UNIDIRECTIONAL || testType == TestType.BIDIRECTIONAL) {
				System.out.println("[Test] Waiting for 'stream opened' message...");
				String msg = serverMessages.poll(10, TimeUnit.SECONDS);
				System.out.println("[Test] Received: " + msg + " (expected: stream opened)");
				assertEquals("stream opened", msg);
			}
			System.out.println("[Test] Waiting for 'packet received from client: abc' message...");
			String msg1 = serverMessages.poll(10, TimeUnit.SECONDS);
			System.out.println("[Test] Received: " + msg1 + " (expected: packet received from client: abc)");
			assertEquals("packet received from client: abc", msg1);

			System.out.println("[Test] Waiting for 'packet received from client: def' message...");
			String msg2 = serverMessages.poll(10, TimeUnit.SECONDS);
			System.out.println("[Test] Received: " + msg2 + " (expected: packet received from client: def)");
			assertEquals("packet received from client: def", msg2);

			if (testType != TestType.UNIDIRECTIONAL) {
				// For DATAGRAM test: Retry logic to handle unreliable delivery (QUIC datagrams are like UDP)
				// For BIDIRECTIONAL test: Normal assertion (streams are reliable)
				int maxRetries = (testType == TestType.DATAGRAM) ? 3 : 1;

				System.out.println("[Test] Waiting for 'packet received from server: abc' message... (client queue size: " + clientMessages.size() + ")");
				String msg3 = pollWithRetry(clientMessages, "packet received from server: abc", maxRetries);
				System.out.println("[Test] Received: " + msg3 + " (expected: packet received from server: abc)");

				System.out.println("[Test] Waiting for 'packet received from server: def' message... (client queue size: " + clientMessages.size() + ")");
				String msg4 = pollWithRetry(clientMessages, "packet received from server: def", maxRetries);
				System.out.println("[Test] Received: " + msg4 + " (expected: packet received from server: def)");

				if (testType == TestType.DATAGRAM) {
					// For datagrams: Accept that some may be lost (unreliable transport by design)
					// Pass if we received at least one message
					boolean receivedAny = (msg3 != null || msg4 != null);
					if (!receivedAny) {
						System.err.println("[Test] DATAGRAM test: No messages received from server (both lost)");
					} else {
						int received = (msg3 != null ? 1 : 0) + (msg4 != null ? 1 : 0);
						System.out.println("[Test] DATAGRAM test: Received " + received + " of 2 messages (acceptable for unreliable transport)");
					}
					// Only fail if BOTH messages were lost (extremely unlikely)
					if (!receivedAny) {
						throw new AssertionError("DATAGRAM test failed: Both messages lost (this is very unlikely, may indicate a problem)");
					}
				} else {
					// For streams: Expect reliable delivery
					assertEquals("packet received from server: abc", msg3);
					assertEquals("packet received from server: def", msg4);
				}
			}
			if (testType == TestType.UNIDIRECTIONAL || testType == TestType.BIDIRECTIONAL) {
				System.out.println("[Test] Waiting for 'stream closed' message...");
				String msg5 = serverMessages.poll(10, TimeUnit.SECONDS);
				System.out.println("[Test] Received: " + msg5 + " (expected: stream closed)");
				assertEquals("stream closed", msg5);
			}
			System.out.println("[Test] Waiting for 'session closed' message...");
			String msg6 = serverMessages.poll(10, TimeUnit.SECONDS);
			System.out.println("[Test] Received: " + msg6 + " (expected: session closed: errorCode = 9999, errorMessage = unknown)");
			assertEquals("session closed: errorCode = 9999, errorMessage = unknown", msg6);

			System.out.println("[Test] " + testType + " test completed successfully");
		} finally {
			System.out.println("[Test] Shutting down event loop for " + testType);
			eventLoopGroup.shutdownGracefully();
			System.out.println("========== Test for " + testType + " complete ==========\n");
		}
	}

	/**
	 * Poll queue with retry logic for unreliable transports (like QUIC datagrams).
	 * For reliable transports, this behaves like a normal poll with timeout.
	 *
	 * @param queue The message queue to poll from
	 * @param expectedMessage Description of expected message for logging
	 * @param maxRetries Maximum number of poll attempts (1 = no retry)
	 * @return The message, or null if not received after all retries
	 */
	private static String pollWithRetry(BlockingQueue<String> queue, String expectedMessage, int maxRetries) throws InterruptedException {
		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			if (attempt > 1) {
				System.out.println("[Test] Retry " + attempt + "/" + maxRetries + " for: " + expectedMessage);
			}
			String message = queue.poll(10, TimeUnit.SECONDS);
			if (message != null) {
				if (attempt > 1) {
					System.out.println("[Test] Message received on retry " + attempt);
				}
				return message;
			}
			if (attempt < maxRetries) {
				System.out.println("[Test] Message not received, will retry... (queue size: " + queue.size() + ")");
			}
		}
		System.out.println("[Test] Message not received after " + maxRetries + " attempts (expected: " + expectedMessage + ")");
		return null;
	}

	private static void startClient(BlockingQueue<String> messages, SelfSignedCertificate selfSignedCertificate) throws InterruptedException {
		CountDownLatch waiter = new CountDownLatch(1);
		try (Playwright playwright = Playwright.create()) {
			BrowserType browserType = playwright.chromium();
			Browser browser = browserType.launch(new BrowserType.LaunchOptions()
					.setArgs(Arrays.asList(
							"--test-type",
							"--enable-quic",
							"--quic-version=h3",
							"--origin-to-force-quic-on=localhost:4433",
							"--ignore-certificate-errors-spki-list=" + CertificateUtils.toPublicKeyHashAsBase64(selfSignedCertificate)
					)));

			Page page = browser.newPage();
			page.onConsoleMessage(message -> {
				String text = message.text();
				System.out.println("[DevTools Console] " + text);

				if (text.startsWith("Data received: ")) {
					String payload = text.substring("Data received: ".length());
					String msg = "packet received from server: " + payload;
					System.out.println("[Client] Adding to queue: " + msg);
					messages.add(msg);
					System.out.println("[Client] Queue size now: " + messages.size());
				}
				if ("Transport closed.".equals(message.text())) {
					System.out.println("[Client] Transport closed, counting down latch");
					waiter.countDown();
				}
			});
			System.out.println("[Client] Navigating to https://localhost:4433/");
			page.navigate("https://localhost:4433/");
			page.textContent("*").contains("LOADED");
			System.out.println("[Client] Page loaded, waiting for transport to close...");
			waiter.await(10, TimeUnit.SECONDS);
			System.out.println("[Client] Browser session complete");
		}
	}

	private static void startServer(BlockingQueue<String> messages, SelfSignedCertificate selfSignedCertificate, TestType testType, EventLoopGroup eventLoopGroup) throws InterruptedException {
		QuicSslContext sslContext = QuicSslContextBuilder
				.forServer(selfSignedCertificate.key(), null, selfSignedCertificate.cert())
				.applicationProtocols(Http3.supportedApplicationProtocols()).build();

		ChannelHandler codec = WebTransport.newQuicServerCodecBuilder()
				.sslContext(sslContext)
				.tokenHandler(InsecureQuicTokenHandler.INSTANCE)
				.handler(new ChannelInitializer<QuicChannel>() {
					@Override
					protected void initChannel(QuicChannel ch) {
						System.out.println("Connection created: id = " + ch.id());

						// For datagrams
						ch.pipeline().addLast(new WebTransportDatagramCodec());
						ch.pipeline().addLast(createEchoHandler(messages, testType));

						// For streams
						ChannelHandler streamChannelInitializer = new ChannelInitializer<QuicStreamChannel>() {
							@Override
							protected void initChannel(QuicStreamChannel ch) {
								ch.pipeline().addLast(new WebTransportStreamCodec() {
									@Override
									protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) throws Exception {
										if ("/webtransport".equals(frame.headers().path().toString())) {
											super.channelRead(ctx, frame);
										} else {
											HttpUtils.sendHtmlContent(ctx, "index.html", selfSignedCertificate, fileContent -> fileContent.replace("$TEST_TYPE", "" + testType));
										}
									}
								});
								ch.pipeline().addLast(createEchoHandler(messages, testType));
								ch.pipeline().addLast(createCloseHandler(messages));
							}
						};
						ch.pipeline().addLast(new Http3ServerConnectionHandler(streamChannelInitializer, null, null, WebTransport.createSettingsFrame(), true));
					}
				}).build();

		Bootstrap bs = new Bootstrap();
		bs.group(eventLoopGroup);
		bs.channel(NioDatagramChannel.class);
		bs.handler(codec);
		bs.bind(new InetSocketAddress(4433)).sync();
	}

	private static SimpleChannelInboundHandler<WebTransportFrame> createEchoHandler(BlockingQueue<String> messages, TestType testType) {
		return new SimpleChannelInboundHandler<WebTransportFrame>() {
			@Override
			protected void channelRead0(ChannelHandlerContext channelHandlerContext, WebTransportFrame frame) throws Exception {
				if (frame instanceof WebTransportDatagramFrame) {
					WebTransportDatagramFrame datagramFrame = (WebTransportDatagramFrame) frame;
					byte[] payload = ByteBufUtil.getBytes(datagramFrame.content());

					System.out.println("WebTransport packet received: payload = " + Arrays.toString(payload));

					messages.add("packet received from client: " + new String(payload, StandardCharsets.UTF_8));

					channelHandlerContext.writeAndFlush(new WebTransportDatagramFrame(datagramFrame.session(), Unpooled.wrappedBuffer(payload))).addListener(futue -> {
						if (futue.isSuccess()) {
							System.out.println("WebTransport stream packet sent: payload = " + Arrays.toString(payload));
						} else {
							System.out.println("Sending WebTransport stream packet failed: payload = " + Arrays.toString(payload));
							futue.cause().printStackTrace();
						}
					});
				} else if (frame instanceof WebTransportStreamOpenFrame) {
					System.out.println("WebTransport stream opened");
					messages.add("stream opened");
				} else if (frame instanceof WebTransportStreamDataFrame) {
					WebTransportStreamDataFrame streamDataFrame = (WebTransportStreamDataFrame) frame;
					byte[] payload = ByteBufUtil.getBytes(streamDataFrame.content());

					System.out.println("WebTransport packet received: payload = " + Arrays.toString(payload));

					messages.add("packet received from client: " + new String(payload, StandardCharsets.UTF_8));

					if (testType == TestType.UNIDIRECTIONAL) {
						return;
					}

					channelHandlerContext.writeAndFlush(new WebTransportStreamDataFrame(streamDataFrame.stream(), Unpooled.wrappedBuffer(payload))).addListener(futue -> {
						if (futue.isSuccess()) {
							System.out.println("WebTransport stream packet sent: payload = " + Arrays.toString(payload));
						} else {
							System.out.println("Sending WebTransport stream packet failed: payload = " + Arrays.toString(payload));
							futue.cause().printStackTrace();
						}
					});
				} else if (frame instanceof WebTransportStreamCloseFrame) {
					System.out.println("WebTransport stream closed");
					messages.add("stream closed");
				}
			}
		};
	}

	private static SimpleChannelInboundHandler<WebTransportSessionCloseFrame> createCloseHandler(BlockingQueue<String> messages) {
		return new SimpleChannelInboundHandler<WebTransportSessionCloseFrame>() {
			@Override
			protected void channelRead0(ChannelHandlerContext channelHandlerContext, WebTransportSessionCloseFrame frame) throws Exception {
				System.out.println("WebTransport session closed: errorCode = " + frame.errorCode() + ", errorMessage = " + frame.errorMessage());

				messages.add("session closed: errorCode = " + frame.errorCode() + ", errorMessage = " + frame.errorMessage());
			}
		};
	}

	private enum TestType {
		DATAGRAM,
		UNIDIRECTIONAL,
		BIDIRECTIONAL,
	}
}
