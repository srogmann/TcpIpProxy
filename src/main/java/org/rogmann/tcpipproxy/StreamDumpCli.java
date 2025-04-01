package org.rogmann.tcpipproxy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.net.ssl.SSLSocketFactory;

import org.rogmann.tcpipproxy.StreamDump.Direction;


/**
 * Main command-line proxy server implementation.
 * <p>
 * Parses command-line arguments to configure proxy behavior (bind address, destination,
 * TLS settings, search/replace rules, etc.), creates server sockets, and initializes
 * bidirectional data streams via {@link StreamDump} threads for each client connection.</p>
 * <p>
 * Supports dynamic routing via {@link StreamRouter} for advanced connection management.</p>
 */
public class StreamDumpCli {

    /**
     * Main entry point for the proxy server.
     * @param args Command-line parameters:
     *            0: Bind host (e.g., localhost)
     *            1: Bind port
     *            2: Destination transport (tcp/tls)
     *            3: Destination host
     *            4: Destination port
     *            [5: --transfer-connection followed by transfer parameters]
     *            [6..]: Search-and-replace pairs (search string + replacement)
     */
    public static void main(String[] args) {
        if (args.length < 5) {
            System.out.println("Usage: StreamDumpCli <Bind-Host> <Bind-Port> <Dest-Transport> <Dest-Host> <Dest-Port> " +
                    "[--transfer-connection <Dest-Transfer-Host> <Dest-Transfer-Port> <Dest-Transfer-Msg-Port> <Transfer-Init-RegExpr>] [<Search> <Replace>]*");
            System.exit(1);
        }

        String bindHost = args[0];
        int bindPort = Integer.parseInt(args[1]);
        String destTransport = args[2];
        String destHost = args[3];
        int destPort = Integer.parseInt(args[4]);

        int searchStartIndex = 5;

        Supplier<StreamRouter> supplierRouter = () -> null;
        if (args.length > 5 && "--transfer-connection".equals(args[5])) {
            if (args.length < 10) {
                System.err.println("Error: --transfer-connection requires four parameters");
                System.exit(1);
            }
            supplierRouter = () -> new StreamRouter(args[6], Integer.parseInt(args[7]), Integer.parseInt(args[8]), args[9]);
            searchStartIndex += 5;
        }

        List<SearchReplace> searchReplaces = new ArrayList<>();
        for (int i = searchStartIndex; i + 1 < args.length; i += 2) {
            searchReplaces.add(new SearchReplace(unescape(args[i]), unescape(args[i + 1])));
        }
        System.out.println("Search-Replaces: " + searchReplaces);
        try (ServerSocket serverSocket = new ServerSocket(bindPort, 50, java.net.InetAddress.getByName(bindHost))) {
            System.out.println("Server listening on " + bindHost + ":" + bindPort);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                Socket destSocket;
                try {
                    if ("tcp".equalsIgnoreCase(destTransport)) {
                        destSocket = new Socket(destHost, destPort);
                    } else if ("tls".equalsIgnoreCase(destTransport)) {
                        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                        destSocket = factory.createSocket(destHost, destPort);
                    } else {
                        throw new IllegalArgumentException("Invalid Dest-Transport value: " + destTransport);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(String.format("IO-error at connect to %s/%d", destHost, destPort), e);
                }

                System.out.println("Connection established: " + clientSocket + " -> " + destHost + ":" + destPort);

                AtomicBoolean doStop = new AtomicBoolean(false);

                Consumer<String> ausgabe = System.out::println;

                String clientName = String.format("%s-%s", clientSocket.getLocalSocketAddress(), clientSocket.getRemoteSocketAddress());
                StreamDump streamC2R = new StreamDump(clientSocket.getInputStream(), destSocket.getOutputStream(), Direction.C2R, clientName, ausgabe, doStop, searchReplaces);
                StreamDump streamR2C = new StreamDump(destSocket.getInputStream(), clientSocket.getOutputStream(), Direction.R2C, clientName, ausgabe, doStop, searchReplaces);
                StreamRouter router = supplierRouter.get();
                streamC2R.setRouter(router);
                streamR2C.setRouter(router);
                Thread c2rThread = new Thread(streamC2R, "C2R-" + clientName);
                Thread r2cThread = new Thread(streamR2C, "R2C-" + clientName);

                c2rThread.start();
                r2cThread.start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Converts escaped control characters in the given pattern string into their actual characters.
     * The following replacements are performed:
     * - <code>"\\n"</code> → newline character (<code>\n</code>)
     * - <code>"\\r"</code> → carriage return (<code>\r</code>)
     * - <code>"\\t"</code> → tab character (<code>\t</code>)
     * - <code>"\\\\"</code> → single backslash (<code>\\</code>)
     *
     * @param pattern Input string containing escaped control sequences
     * @return Unescaped string with control characters resolved
     */
    static String unescape(String pattern) {
        return pattern.replace("\\n", "\n") // Newline
                     .replace("\\r", "\r") // Carriage return
                     .replace("\\t", "\t") // Tab
                     .replace("\\\\", "\\"); // Backslash
    }
}
