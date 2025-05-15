package org.rogmann.tcpipproxy;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Simple client used to send files in a TCP/IP-connection.
 * The transfer is not encrypted. Use rsync/ssh instead if available!
 */
public class RsyncSenderMain {
    private static final int MSG_TYPE_FILE = 1;
    private static final int MSG_TYPE_CLOSE = 0;
    private static final int CHUNK_SIZE = 65536; // 64 KB

    public static void main(String[] args) {
        String sourceDir = null;
        String host = null;
        int port = 0;
        String includeRegex = null;

        // Parsing der Argumente
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--dir")) {
                sourceDir = args[i + 1];
                i++;
            } else if (args[i].equals("--host")) {
                host = args[i + 1];
                i++;
            } else if (args[i].equals("--port")) {
                port = Integer.parseInt(args[i + 1]);
                i++;
            } else if (args[i].equals("--include")) {
                includeRegex = args[i + 1];
                i++;
            }
        }

        if (sourceDir == null || host == null || port == 0 || includeRegex == null) {
            System.out.println("Missing required arguments for the sender.");
            System.out.println("Usage: java RsyncSenderMain --dir <SOURCE-DIRECTORY> --host <TARGET-HOST> --port <TARGET-PORT> --include <REGEXP>");
            System.exit(1);
        }

        Path sourcePath = Paths.get(sourceDir);
        if (!Files.exists(sourcePath) || !Files.isDirectory(sourcePath)) {
            System.out.println("Source directory does not exist or is not a directory.");
            System.exit(2);
        }

        Pattern pattern = Pattern.compile(includeRegex);

        try (Socket socket = new Socket()) {

            socket.connect(new InetSocketAddress(host, port));
            try (OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream()) {
    
                // Initialer Eyecatcher "RsnP"
                byte[] eyecatcher = new byte[]{0x52, 0x73, 0x6e, 0x50};
                out.write(eyecatcher);
                out.flush();
    
                // Sammlung der zu sendenden Dateien
                List<Path> files = new ArrayList<>();
                Files.walkFileTree(sourcePath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String relativePath = sourcePath.relativize(file).toString();
                        if (pattern.matcher(relativePath).matches()) {
                            files.add(file);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
    
                // Status-Thread für alle 5 Sekunden
                final AtomicInteger filesSent = new AtomicInteger(0);
                final AtomicLong totalBytesSent = new AtomicLong(0);
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
                            System.out.printf("Transferred: %d files, %.2f MB, current file: %s (%d KB)%n",
                                    filesSent.get(), totalBytesSent.get() / (1024.0 * 1024.0), currentFileName.get(), currentFileSize.get() / 1024);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            System.err.println("Unexpected break: " + e);
                            break;
                        }
                    }
                });
                statusThread.start();
                
                ByteBuffer bb = ByteBuffer.allocate(8);
    
                // Verarbeitung der Dateien
                for (Path file : files) {
                    String relativePath = sourcePath.relativize(file).toString();
                    currentFileName.set(relativePath);
                    currentFileSize.set(Files.size(file));
    
                    // Nachrichtenheader (Msg ) und Typ 1
                    byte[] msgHeader = new byte[]{0x4d, 0x73, 0x67, 0x20};
                    byte[] msgType = new byte[]{0x00, 0x00, 0x00, 0x01};
                    out.write(msgHeader);
                    out.write(msgType);
    
                    // Dateiname-Länge
                    byte[] filenameBytes = relativePath.getBytes(StandardCharsets.UTF_8);
                    int filenameLength = filenameBytes.length;
                    bb.putInt(0, filenameLength);
                    out.write(bb.array(), 0, 4);
    
                    // Dateiname senden
                    out.write(filenameBytes);
    
                    // Dateigröße senden
                    long fileSize = Files.size(file);
                    bb.putLong(0, fileSize);
                    out.write(bb.array(), 0, 8);

                    // Modification time senden
                    long lastModifiedTime = Files.getLastModifiedTime(file).toMillis();
                    bb.putLong(0, lastModifiedTime);
                    out.write(bb.array(), 0, 8);
    
                    // Dateiinhalt in Chunks senden und SHA256 berechnen
                    try (FileInputStream fis = new FileInputStream(file.toFile())) {
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        byte[] buffer = new byte[CHUNK_SIZE];
                        int bytesRead;
    
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                            digest.update(buffer, 0, bytesRead);
                        }
    
                        // SHA256-Summe senden
                        byte[] checksum = digest.digest();
                        out.write(checksum);
                        out.flush();
                    }
    
                    // Auf ACK vom Empfänger warten
                    byte[] ack = new byte[4];
                    int bytesReceived = 0;
                    while (bytesReceived < 4) {
                        bytesReceived += in.read(ack, bytesReceived, 4 - bytesReceived);
                    }
                    String ackStr = new String(ack, StandardCharsets.US_ASCII);
                    if (!ackStr.equals("Ack ")) {
                        System.out.println("Unexpected response from the receiver: " + ackStr);
                    }
    
                    // Statistik aktualisieren
                    filesSent.incrementAndGet();
                    totalBytesSent.addAndGet(fileSize);
                }

                // Status-Thread beenden
                isRunning.set(false);

                // Close-Meldung senden
                byte[] closeMsgHeader = new byte[]{0x4d, 0x73, 0x67, 0x20};
                byte[] closeMsgType = new byte[]{0x00, 0x00, 0x00, 0x00};
                out.write(closeMsgHeader);
                out.write(closeMsgType);
                out.flush();
    
                System.out.printf("Transferred: %d files, %.2f MB%n",
                        filesSent.get(), totalBytesSent.get() / (1024.0 * 1024.0));
            }

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Missing hash-algorithm", e);
        } catch (IOException e) {
            throw new RuntimeException("IO-Error while sending files", e);
        }
    }
}
