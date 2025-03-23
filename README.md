# TcpIpProxy

This repository contains a collection of lightweight, standalone Java tools designed for development environments (not production). The tools include a proxy implementation and other utilities:

- **StreamDumpCli**: A command-line proxy server supporting search-and-replace operations and dynamic routing.
- **HarParser**: A tool to extract entries from a Firefox `.har` file.
- **SocketIOClient**: A client for communicating via the **Socket.IO protocol** (EIO 4, with partial implementation).
- **WebSocketClient**: A basic WebSocket client implementation based on **RFC 6455** (partially completed).

These tools require **no external dependencies**, resulting in some rudimentary features in certain areas.

## Example
Access https://www.w3.org/ via http://127.0.0.1:8443/.

```
$ java -cp target/TcpIpProxy-0.1.0-SNAPSHOT.jar org.rogmann.tcpipproxy.StreamDumpCli 127.0.0.1 8443 tls www.w3.org 443 127.0.0.1:8443 www.w3.org:443
Search-Replaces: [SearchReplace[search=127.0.0.1:8443, replace=www.w3.org:443]]
Server listening on 127.0.0.1:8443
Connection established: Socket[addr=/127.0.0.1,port=49380,localport=8443] -> www.w3.org:443
#20250323-233135.963 C2R /127.0.0.1:8443-/127.0.0.1:49380:
GET / HTTP/1.1\r\nHost: 127.0.0.1:8443\r\nUser-Agent: Mozilla/5.0[...]
[...]
#20250323-233136.195 R2C /127.0.0.1:8443-/127.0.0.1:49380:
HTTP/1.1 200 OK\r\nDate: Sun, 23 Mar 2025 22:31:36 GMT\r\nContent-Type: text/html; charset=UTF-8\r\n[...]
```

## LLM Usage

During the development, I used language models like QwQ-32B to test how well this currently works. In itself, it’s a fine approach, but without expert oversight, it’s not reliable — bugs are to be expected. Typical examples include:  
* The masking in the WebSocket part was missing in the first iteration (clients are required to implement it).  
* In the read method, there was a bug with larger responses when the buffer was insufficient.

The takeaway is clear: Your own reading and understanding of a specification remains essential! Therefore, when in doubt, consider whether and what thoughts you want to outsource.
