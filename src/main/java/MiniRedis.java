import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mini Redis, layer 1: TCP server + RESP parser + SET/GET/DEL.
 * Run with:  java MiniRedis.java [port]     (Java 17+)
 * Test with: redis-cli -p 6379
 * installing redis-cli  curl -fsSL https://packages.redis.io/redis-cli/install.sh | sh
 */
public class MiniRedis {

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6379;
        Store store = new MapStore();
        CommandHandler handler = new CommandHandler(store);

        try (ServerSocket server = new ServerSocket(port)) {
            System.out.println("MiniRedis listening on port " + port);
            while (true) {
                Socket socket = server.accept();
                // Layer 1: one thread per connection. Revisit in layer 2.
                new Thread(() -> serve(socket, handler)).start();
            }
        }
    }

    private static void serve(Socket socket, CommandHandler handler) {
        try (socket;
             InputStream in = new BufferedInputStream(socket.getInputStream());
             OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

            RespParser parser = new RespParser(in);
            List<String> command;
            while ((command = parser.readCommand()) != null) {
                out.write(handler.execute(command));
                out.flush();
            }
        } catch (Exception e) {
            System.err.println("Connection closed: " + e.getMessage());
        }
    }
}


interface Store {
    void set(String key, String value);
    String get(String key);          // null if missing
    boolean delete(String key);      // true if the key existed
}

/** Layer 1 store: a plain hash map. */
class MapStore implements Store {
    private final ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();

    public void set(String key, String value) { map.put(key, value); }
    public String get(String key) { return map.get(key); }
    public boolean delete(String key) { return map.remove(key) != null; }
}

/** Turns a parsed command into a RESP reply. */
class CommandHandler {
    private final Store store;

    CommandHandler(Store store) { this.store = store; }

    byte[] execute(List<String> args) {
        if (args.isEmpty()) return Resp.error("ERR empty command");
        String name = args.get(0).toUpperCase();

        switch (name) {
            case "PING":
                return Resp.simple("PONG");
            case "SET":
                if (args.size() != 3) return wrongArgs("set");
                store.set(args.get(1), args.get(2));
                return Resp.simple("OK");
            case "GET":
                if (args.size() != 2) return wrongArgs("get");
                return Resp.bulk(store.get(args.get(1)));
            case "DEL":
                if (args.size() < 2) return wrongArgs("del");
                int deleted = 0;
                for (int i = 1; i < args.size(); i++) {
                    if (store.delete(args.get(i))) deleted++;
                }
                return Resp.integer(deleted);
            default:
                return Resp.error("ERR unknown command '" + args.get(0) + "'");
        }
    }

    private static byte[] wrongArgs(String cmd) {
        return Resp.error("ERR wrong number of arguments for '" + cmd + "' command");
    }
}

/**
 * Reads one RESP array of bulk strings per call, e.g.
 * *3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n
 *
 * Partial reads: InputStream.read / readNBytes block until the bytes arrive,
 * so a command split across several TCP packets is handled naturally here.
 * (An event loop in a later layer would need an explicit buffer instead.)
 */
class RespParser {
    private final InputStream in;

    RespParser(InputStream in) { this.in = in; }

    /** Returns null when the client disconnects cleanly between commands. */
    List<String> readCommand() throws IOException {
        String header = readLine();
        if (header == null) return null;
        if (!header.startsWith("*")) {
            throw new IOException("Protocol error: expected '*' but got: " + header);
        }

        int count = Integer.parseInt(header.substring(1));
        List<String> args = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            String lengthLine = readLine();
            if (lengthLine == null || !lengthLine.startsWith("$")) {
                throw new IOException("Protocol error: expected '$'");
            }
            int length = Integer.parseInt(lengthLine.substring(1));

            byte[] data = in.readNBytes(length);
            if (data.length < length) throw new EOFException("Client closed mid-command");
            if (in.read() != '\r' || in.read() != '\n') {
                throw new IOException("Protocol error: missing CRLF after bulk string");
            }
            args.add(new String(data, StandardCharsets.UTF_8));
        }
        return args;
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                if (in.read() != '\n') throw new IOException("Protocol error: expected LF");
                return buf.toString(StandardCharsets.UTF_8);
            }
            buf.write(b);
        }
        if (buf.size() == 0) return null;   // clean disconnect
        throw new EOFException("Client closed mid-line");
    }
}

/** RESP reply encoders. */
final class Resp {
    private Resp() {}

    static byte[] simple(String s)  { return bytes("+" + s + "\r\n"); }
    static byte[] error(String s)   { return bytes("-" + s + "\r\n"); }
    static byte[] integer(long n)   { return bytes(":" + n + "\r\n"); }

    static byte[] bulk(String s) {
        if (s == null) return bytes("$-1\r\n");   // nil
        int byteLength = s.getBytes(StandardCharsets.UTF_8).length;
        return bytes("$" + byteLength + "\r\n" + s + "\r\n");
    }

    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}