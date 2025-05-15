package org.rogmann.tcpipproxy;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple server used to receive files in a TCP/IP-connection.
 * The transfer is not encrypted. Use rsync/ssh instead if available!
 */
public class RsyncReceiverMain {
    private static final int EYECATCHER_SIZE = 4;
    private static final int MSG_HEADER_SIZE = 4;
    private static final int MSG_TYPE_FILE = 1;
    private static final int MSG_TYPE_CLOSE = 0;
    private static final int FILE_NAME_LENGTH_SIZE = 4;
    private static final int FILE_SIZE_SIZE = 8;
    private static final int MOD_TIME_SIZE = 8;
    private static final int SHA256_SIZE = 32;
    private static final int CHUNK_SIZE = 65536; // 64 KB

    public static void main(String[] args) {
        String destDir = null;
        String bindIp = null;
        int bindPort = 0;

        // Parsing der Argumente
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--destDir")) {
                destDir = args[i + 1];
                i++;
            } else if (args[i].equals("--bind-ip")) {
                bindIp = args[i + 1];
                i++;
            } else if (args[i].equals("--bind-port")) {
                bindPort = Integer.parseInt(args[i + 1]);
                i++;
            }
        }

        if (destDir == null || bindIp == null || bindPort == 0) {
            System.out.println("Missing required arguments for the receiver.");
            System.out.println("Usage: java RsyncReceiverMain --destDir <DESTINATION-DIRECTORY> --bind-ip <BIND-IP> --bind-port <LISTENER-PORT>");
            System.exit(1);
        }

        Path destinationPath = Paths.get(destDir);
        if (!Files.exists(destinationPath)) {
            try {
                Files.createDirectories(destinationPath);
            } catch (IOException e) {
                System.out.println("Error while creating the destination directory.");
                e.printStackTrace();
                return;
            }
        }

        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName(bindIp), bindPort));
            System.out.println("Receiver is listening on port " + bindPort);

            try (Socket clientSocket = serverSocket.accept();
                 InputStream in = clientSocket.getInputStream();
                 OutputStream out = clientSocket.getOutputStream()) {

                // Eyecatcher "RsnP" prüfen
                byte[] eyecatcher = new byte[EYECATCHER_SIZE];
                int bytesRead = 0;
                while (bytesRead < EYECATCHER_SIZE) {
                    bytesRead += in.read(eyecatcher, bytesRead, EYECATCHER_SIZE - bytesRead);
                }

                if (!new String(eyecatcher, StandardCharsets.US_ASCII).equals("RsnP")) {
                    throw new RuntimeException("Invalid eyecatcher: " + Arrays.toString(eyecatcher));
                }

                // Status-Thread für alle 5 Sekunden
                final AtomicInteger filesReceived = new AtomicInteger(0);
                final AtomicLong totalBytesReceived = new AtomicLong(0);
                final AtomicReference<String> currentFileName = new AtomicReference<>("none");
                final AtomicReference<Long> currentFileSize = new AtomicReference<>(0L);
                final AtomicBoolean isRunning = new AtomicBoolean(true);

                Thread statusThread = new Thread(() -> {
                    while (isRunning.get()) {
                        try {
                            Thread.sleep(5000);
                            if (!isRunning.get()) {
                                break;
                            }
                            System.out.printf("Received: %d files, %.2f MB, current file: %s (%d KB)%n",
                                    filesReceived.get(), totalBytesReceived.get() / (1024.0 * 1024.0), currentFileName.get(), currentFileSize.get() / 1024);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            System.err.println("Unexpected break: " + e);
                            break;
                        }
                    }
                });
                statusThread.start();

                // Nachrichten verarbeiten
                while (true) {
                    // Nachrichtenheader "Msg " prüfen
                    byte[] msgHeader = new byte[MSG_HEADER_SIZE];
                    bytesRead = 0;
                    while (bytesRead < MSG_HEADER_SIZE) {
                        bytesRead += in.read(msgHeader, bytesRead, MSG_HEADER_SIZE - bytesRead);
                    }

                    String msgHeaderStr = new String(msgHeader, StandardCharsets.US_ASCII);
                    if (!msgHeaderStr.equals("Msg ")) {
                        System.out.println("Invalid message header: " + msgHeaderStr);
                        break;
                    }

                    // Nachrichtentyp prüfen
                    byte[] msgTypeBytes = new byte[4];
                    bytesRead = 0;
                    while (bytesRead < 4) {
                        bytesRead += in.read(msgTypeBytes, bytesRead, 4 - bytesRead);
                    }
                    int msgType = ByteBuffer.wrap(msgTypeBytes).getInt();

                    if (msgType == MSG_TYPE_CLOSE) {
                        // Close-Nachricht empfangen
                        System.out.println("Received: Close");
                        break;
                    } else if (msgType == MSG_TYPE_FILE) {
                        // FILE-Nachricht verarbeiten

                        // Dateiname-Länge lesen
                        byte[] filenameLengthBytes = new byte[FILE_NAME_LENGTH_SIZE];
                        bytesRead = 0;
                        while (bytesRead < FILE_NAME_LENGTH_SIZE) {
                            bytesRead += in.read(filenameLengthBytes, bytesRead, FILE_NAME_LENGTH_SIZE - bytesRead);
                        }
                        int filenameLength = ByteBuffer.wrap(filenameLengthBytes).getInt();

                        // Dateiname lesen
                        byte[] filenameBytes = new byte[filenameLength];
                        bytesRead = 0;
                        while (bytesRead < filenameLength) {
                            bytesRead += in.read(filenameBytes, bytesRead, filenameLength - bytesRead);
                        }
                        String filename = new String(filenameBytes, StandardCharsets.UTF_8);
                        currentFileName.set(filename);

                        // Dateigröße lesen
                        byte[] fileSizeBytes = new byte[FILE_SIZE_SIZE];
                        bytesRead = 0;
                        while (bytesRead < FILE_SIZE_SIZE) {
                            bytesRead += in.read(fileSizeBytes, bytesRead, FILE_SIZE_SIZE - bytesRead);
                        }
                        long fileSize = ByteBuffer.wrap(fileSizeBytes).getLong();
                        currentFileSize.set(fileSize);

                        // Modification time lesen
                        byte[] modTimeBytes = new byte[MOD_TIME_SIZE];
                        bytesRead = 0;
                        while (bytesRead < MOD_TIME_SIZE) {
                            bytesRead += in.read(modTimeBytes, bytesRead, MOD_TIME_SIZE - bytesRead);
                        }
                        long modTime = ByteBuffer.wrap(modTimeBytes).getLong();

                        // Sicherstellen, dass der Dateiname im Zielverzeichnis ist
                        Path fullDestPath = destinationPath.resolve(filename).normalize();
                        if (!fullDestPath.startsWith(destinationPath)) {
                            System.out.println("Invalid file path: " + filename);
                            throw new IOException("Invalid file path: " + filename);
                        }

                        // Zielverzeichnis erstellen
                        Path destDirForFile = fullDestPath.getParent();
                        if (destDirForFile != null) {
                            Files.createDirectories(destDirForFile);
                        }

                        // SHA-256-Hash berechnen
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        byte[] buffer = new byte[CHUNK_SIZE];
                        long bytesReceived = 0;

                        try (FileOutputStream fos = new FileOutputStream(fullDestPath.toFile())) {
                            while (bytesReceived < fileSize) {
                                int chunkSize = (int) Math.min(CHUNK_SIZE, fileSize - bytesReceived);
                                bytesRead = 0;
                                while (bytesRead < chunkSize) {
                                    bytesRead += in.read(buffer, bytesRead, chunkSize - bytesRead);
                                }
                                fos.write(buffer, 0, chunkSize);
                                bytesReceived += chunkSize;
                                digest.update(buffer, 0, chunkSize);
                            }
                            fos.flush();
                        }
                        // Setzen der Modifikationszeit nach dem Empfangen
                        Files.setLastModifiedTime(fullDestPath, FileTime.fromMillis(modTime));

                        // SHA256-Summe des empfangenen Inhalts
                        byte[] computedChecksum = digest.digest();

                        // SHA256-Summe vom Sender empfangen
                        byte[] receivedChecksum = new byte[SHA256_SIZE];
                        bytesRead = 0;
                        while (bytesRead < SHA256_SIZE) {
                            bytesRead += in.read(receivedChecksum, bytesRead, SHA256_SIZE - bytesRead);
                        }

                        // Hash-Prüfung
                        if (!Arrays.equals(computedChecksum, receivedChecksum)) {
                            System.out.println("Hash computed: " + Arrays.toString(computedChecksum));
                            System.out.println("Hash received: " + Arrays.toString(receivedChecksum));
                            throw new IOException("Hash does not match: " + filename);
                        }

                        // Bestätigung senden ("Ack ")
                        byte[] ack = new byte[]{0x41, 0x63, 0x6b, 0x20};
                        out.write(ack);
                        out.flush();

                        // Statistik aktualisieren
                        filesReceived.incrementAndGet();
                        totalBytesReceived.addAndGet(fileSize);
                    } else {
                        System.out.println("Unknown message type: " + msgType);
                        break;
                    }
                }

                // Status-Thread beenden
                isRunning.set(false);

                System.out.printf("Received: %d files, %.2f MB%n",
                        filesReceived.get(), totalBytesReceived.get() / (1024.0 * 1024.0));
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Missing hash-algorithm", e);
        } catch (IOException e) {
            throw new RuntimeException("Unexpected IO-error while receiving files", e);
        }
    }
}
