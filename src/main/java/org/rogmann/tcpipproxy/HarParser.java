package org.rogmann.tcpipproxy;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/** Util-class to dump entries of a firefox-.har-file */ 
public class HarParser {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java HarParser <har-file-name>");
            System.exit(1);
        }

        String harFileName = args[0];
        HarParser parser = new HarParser();
        parser.parseHarFile(harFileName, System.out);
    }

    /**
     * Parses a firefox-.har-file and dumps its entries.
     * @param harFileName file-name
     * @param psOut output-stream
     */
    private void parseHarFile(String harFileName, PrintStream psOut) {
        Map<String, Object> harDict;
        try (InputStream fis = new FileInputStream(harFileName);
             InputStreamReader isr = new InputStreamReader(fis);
             BufferedReader br = new BufferedReader(isr)) {
            int c = br.read();
            if (c != '{') {
                throw new RuntimeException("Unexpected start of .har-file: missing {");
            }
            harDict = LightweightJsonHandler.parseJsonDict(br);
        } catch (IOException e) {
            throw new RuntimeException("IO-error while parsing " + harFileName, e);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> logDict = (Map<String, Object>) harDict.get("log");
        psOut.format(".har-version: %s%n", LightweightJsonHandler.getJsonValue(logDict, "version", String.class));

        List<Map<String, Object>> entries = LightweightJsonHandler.getJsonArrayDicts(logDict, "entries");
        if (entries != null) {
            for (Map<String, Object> entry : entries) {
                @SuppressWarnings("unchecked")
                Map<String, Object> request = (Map<String, Object>) LightweightJsonHandler.getJsonValue(entry, "request", Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> response = (Map<String, Object>) LightweightJsonHandler.getJsonValue(entry, "response", Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> responseContent = (Map<String, Object>) LightweightJsonHandler.getJsonValue(response, "content", Map.class);

                String startedDateTime = LightweightJsonHandler.getJsonValue(entry, "startedDateTime", String.class);
                String method = LightweightJsonHandler.getJsonValue(request, "method", String.class);
                String url = LightweightJsonHandler.getJsonValue(request, "url", String.class);
                int sizeIn = LightweightJsonHandler.readInt(request, "bodySize", 0);
                int sizeOut = LightweightJsonHandler.readInt(responseContent, "size", 0);

                // Format the time to hh:mm:ss.SSS
                // DateTimeFormatter formatterIn = DateTimeFormatter.ISO_INSTANT;
                ZonedDateTime zdt;
                try {
                    zdt = ZonedDateTime.parse(startedDateTime);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid ISO 8601 date format: " + startedDateTime, e);
                }
                DateTimeFormatter formatterOut = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
                LocalDateTime ldt = zdt.toLocalDateTime();
                String formattedTime = ldt.format(formatterOut);

                psOut.format("%s;%s;%s;%d;%d%n", formattedTime, method, url, sizeIn, sizeOut);
            }
        }
    }

}
