package org.rogmann.tcpipproxy;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.SocketFactory;

/**
 * Class to hand-over a the client-side of a connection to another server.
 */
public class StreamRouter {

    /** new client host */
    private String transferHost;
    /** new client port */
    private int transferPort;
    /** new client port (to serve the old client messages) */
    private int transferMsgPort;
    /** pattern to check for the signal of connection-switch */ 
    private Pattern signalRegExp;
    
    private BlockingQueue<TransferSockets> queue = new ArrayBlockingQueue<>(1);
    
    private boolean hasSwitched = false;

    /**
     * Two sockets.
     * @param socketNewClient socket to new client
     * @param socketMsgsOldClient socket to serve the old client messages
     */
    record TransferSockets(Socket socketNewClient, Socket socketMsgsOldClient) { }

    public StreamRouter(String transferHost, int transferPort, int transferMsgPort, String sRegExp) {
        this.transferHost = transferHost;
        this.transferPort = transferPort;
        this.transferMsgPort = transferMsgPort;
        this.signalRegExp = Pattern.compile(sRegExp);
    }

    /**
     * If the current message contains the signal, we establish a new client-connection to the transfer-host.
     * @param sContent message to check
     * @return new sockets or <code>null</code>
     * @throws IOException in case of an IO-error
     */
    public TransferSockets checkForSwitchMessage(String sContent) throws IOException {
        if (hasSwitched) {
            return null;
        }
        Matcher m = signalRegExp.matcher(sContent);
        if (!m.matches()) {
            return null;
        }
        hasSwitched = true;
        Socket socketNewClient = SocketFactory.getDefault().createSocket(transferHost, transferPort);
        Socket socketMsgsOldClient = SocketFactory.getDefault().createSocket(transferHost, transferMsgPort);
        TransferSockets sockets = new TransferSockets(socketNewClient, socketMsgsOldClient);
        queue.add(sockets);
        return sockets;
    }

    /**
     * Do we have a new client-connection (to the transfer-host)?
     * @return new client-connections or <code>null</code>
     */
    public TransferSockets pullNewClient() {
        return queue.poll();
    }
}
