/**
 * Provides a collection of lightweight, standalone Java tools for network testing, debugging, and prototyping.
 * <p>
 * These "to-go" classes simplify common network-related tasks such as proxying, WebSocket communication, Socket.IO integration, and HTTP traffic analysis.
 * <p>
 * Key components include:
 * <ul>
 *     <li>{@link org.rogmann.tcpipproxy.StreamDumpCli} – A configurable TCP/IP proxy for inspecting/modifying client-server traffic.</li>
 *     <li>{@link org.rogmann.tcpipproxy.WebSocketClient} – A basic WebSocket client for testing real-time communication.</li>
 *     <li>{@link org.rogmann.tcpipproxy.SocketIOClient} – A Socket.IO (EIO 4) client for handling event-driven messaging.</li>
 *     <li>{@link org.rogmann.tcpipproxy.HarParser} – A parser for reading and analyzing Firefox .har (HTTP Archive) files.</li>
 * </ul>
 * <p>
 * Designed for rapid prototyping and troubleshooting, these classes prioritize simplicity over enterprise-grade features. They are ideal for:
 * <ul>
 *     <li>Network protocol analysis</li>
 *     <li>API debugging</li>
 *     <li>WebSocket/Socket.IO message inspection</li>
 *     <li>HTTP traffic replay from .har files</li>
 * </ul>
 * <p><strong>
 * Note: These tools are intended for testing/development scenarios.</strong></p>
 */
package org.rogmann.tcpipproxy;
