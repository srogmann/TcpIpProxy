package org.rogmann.tcpipproxy;

import static org.junit.Assert.assertEquals;

import java.util.function.Consumer;

import org.junit.Before;
import org.junit.Test;

public class AdjustContentLengthTest {

    private Consumer<String> dummyConsumer;

    @Before
    public void setup() {
        dummyConsumer = s -> {}; // No-op consumer for logging
    }

    @Test
    public void testAdjustContentLength_ExactLength() {
        String inputOrig = "GET / HTTP/1.1\r\nContent-Length: 12\r\n\r\nHelloContent";
        String inputMod = "GET / HTTP/1.1\r\nContent-Length: 13\r\n\r\nHello Content";

        String result = StreamDump.adjustContentLength(inputOrig, inputMod, dummyConsumer);
        String expected = "GET / HTTP/1.1\r\nContent-Length: 13\r\n\r\nHello Content";

        assertEquals(expected, result);
    }

    @Test
    public void testAdjustContentLength_TooShort() {
        String inputOrig = "POST /data HTTP/1.1\r\nContent-Length: 5\r\n\r\nL/B/C";
        String inputMod = "POST /data HTTP/1.1\r\nContent-Length: 5\r\n\r\nLongBodyContent";
        String expected = "POST /data HTTP/1.1\r\nContent-Length: 15\r\n\r\nLongBodyContent";

        String result = StreamDump.adjustContentLength(inputOrig, inputMod, dummyConsumer);

        assertEquals(expected, result);
    }

    @Test
    public void testAdjustContentLength_TooLong() {
        String inputOrig = "PUT /update HTTP/1.1\r\nContent-Length: 15\r\n\r\nShort         !";
        String inputMod = "PUT /update HTTP/1.1\r\nContent-Length: 15\r\n\r\nShort";
        String expected = "PUT /update HTTP/1.1\r\nContent-Length: 5\r\n\r\nShort";

        String result = StreamDump.adjustContentLength(inputOrig, inputMod, dummyConsumer);

        assertEquals(expected, result);
    }

    @Test
    public void testAdjustContentLength_Linebreaks() {
        String inputOrig = "PUT /update HTTP/1.1\r\nContent-Length: 12\r\n\r\nShortLongEnd";
        String inputMod = "PUT /update HTTP/1.1\r\nContent-Length: 12\r\n\r\nShort\nLong\r\nEnd";
        String expected = "PUT /update HTTP/1.1\r\nContent-Length: 15\r\n\r\nShort\nLong\r\nEnd";

        String result = StreamDump.adjustContentLength(inputOrig, inputMod, dummyConsumer);

        assertEquals(expected, result);
    }

    @Test
    public void testAdjustContentLength_NoHeader() {
        String input = "GET /noheader HTTP/1.1\r\nConnection: close\r\n\r\nSomeBody";

        String result = StreamDump.adjustContentLength(input, input, dummyConsumer);

        assertEquals(input, result);
    }

    @Test
    public void testAdjustContentLength_MalformedValue() {
        String input = "GET /badvalue HTTP/1.1\r\nContent-Length: invalid\r\n\r\nSomeBody";

        String result = StreamDump.adjustContentLength(input, input, dummyConsumer);

        assertEquals(input, result);
    }

    @Test
    public void testAdjustContentLength_CaseInsensitiveHeader() {
        String inputOrig = "POST /lowercase HTTP/1.1\r\ncontent-length: 4\r\n\r\nAAAA";
        String inputMod = "POST /lowercase HTTP/1.1\r\ncontent-length: 4\r\n\r\nAAA";
        String expected = "POST /lowercase HTTP/1.1\r\nContent-Length: 3\r\n\r\nAAA";

        String result = StreamDump.adjustContentLength(inputOrig, inputMod, dummyConsumer);

        assertEquals(expected, result);
    }

    @Test
    public void testAdjustContentLength_EmptyBody() {
        {
            String inputOrig = "GET /empty HTTP/1.1\r\nContent-Length: 4\r\n\r\nTest";
            String inputMod = "GET /empty HTTP/1.1\r\nContent-Length: 4\r\n\r\n";
            String expected = "GET /empty HTTP/1.1\r\nContent-Length: 0\r\n\r\n";

            String result = StreamDump.adjustContentLength(inputOrig, inputMod, dummyConsumer);

            assertEquals(expected, result);
        }
        {
            String inputOrig = "GET /empty HTTP/1.1\r\nContent-Length: 0\r\n\r\n";
            String inputMod = "GET /empty HTTP/1.1\r\nContent-Length: 0\r\n\r\nTest";
            String expected = "GET /empty HTTP/1.1\r\nContent-Length: 4\r\n\r\nTest";

            String result = StreamDump.adjustContentLength(inputOrig, inputMod, dummyConsumer);

            assertEquals(expected, result);
        }
    }

    @Test
    public void testAdjustContentLength_MultipleHeaders() {
        String inputOrig = "PUT /multi HTTP/1.1\r\nContent-Type: text/plain\r\nContent-Length: 6\r\nX-Custom: test\r\n\r\nTest-1";
        String inputMod = "PUT /multi HTTP/1.1\r\nContent-Type: text/plain\r\nContent-Length: 6\r\nX-Custom: test\r\n\r\nTest";
        String expected = "PUT /multi HTTP/1.1\r\nContent-Type: text/plain\r\nContent-Length: 4\r\nX-Custom: test\r\n\r\nTest";

        String result = StreamDump.adjustContentLength(inputOrig, inputMod, dummyConsumer);

        assertEquals(expected, result);
    }

    @Test
    public void testAdjustContentLength_InvalidMessageFormat() {
        String input = "Invalid message with no CRLF"; // No headers/body structure

        String result = StreamDump.adjustContentLength(input, input, dummyConsumer);

        assertEquals(input, result);
    }
    
    @Test
    public void testAdjustContentLength_UTF8Body() {
        String inputOrig = "GET /utf HTTP/1.1\r\nContent-Length: 7\r\n\r\nKarotte"; 
        String inputMod = "GET /utf HTTP/1.1\r\nContent-Length: 7\r\n\r\nMöhre"; 
        // The body "Möhre" is 5 characters but 6 bytes in UTF-8 (ö is 2 bytes)

        String expected = "GET /utf HTTP/1.1\r\nContent-Length: 6\r\n\r\nMöhre";

        String result = StreamDump.adjustContentLength(inputOrig, inputMod, dummyConsumer);

        assertEquals(expected, result);
    }
}
