import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 
 *
 * One thread watches ALL connections with a Selector and handles whichever
 * socket is ready. Because only that thread touches the store, no locks are
 * needed, and commands run one at a time in the order they arrive.
 *
 * Run with:  java MiniRedis.java [port]     (Java 17+)
 * Test with: redis-cli -p 6379
 */
public class MiniRedis {
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 6379;
        new EventLoopServer(port, new CommandHandler(new MapStore())).run();
    }
}

/** The event loop: accept, read, parse, execute, write, all on one thread. */
class EventLoopServer {
    private final int port;
    private final CommandHandler handler;

    EventLoopServer(int port, CommandHandler handler) {
        this.port = port;
        this.handler = handler;
    }

    void run() throws IOException {
        try (Selector selector = Selector.open();
             ServerSocketChannel server = ServerSocketChannel.open()) {

            server.bind(new InetSocketAddress(port));
            server.configureBlocking(false);
            server.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("MiniRedis (event loop) listening on port " + port);

            while (true) {
                selector.select();   // sleeps until at least one socket is ready
                Iterator<SelectionKey> ready = selector.selectedKeys().iterator();
                while (ready.hasNext()) {
                    SelectionKey key = ready.next();
                    ready.remove();
                    try {
                        if (!key.isValid()) continue;
                        if (key.isAcceptable()) {
                            accept(server, selector);
                            continue;
                        }
                        if (key.isReadable()) onReadable(key);
                        if (key.isValid() && key.isWritable()) onWritable(key);
                    } catch (IOException | RuntimeException e) {
                        close(key);   // one bad client must never stop the loop
                    }
                }
            }
        }
    }

    private void accept(ServerSocketChannel server, Selector selector) throws IOException {
        SocketChannel channel = server.accept();
        if (channel == null) return;
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        channel.register(selector, SelectionKey.OP_READ, new Connection());
    }

    private void onReadable(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        Connection conn = (Connection) key.attachment();

        try {
            conn.ensureReadSpace();
        } catch (IllegalArgumentException e) {
            protocolError(key, channel, e);
            return;
        }

        int n = channel.read(conn.in);
        if (n == -1) {          // client disconnected
            close(key);
            return;
        }

        // Parse every complete command now sitting in the buffer. This handles
        // pipelining (many commands in one read). A half-received command is
        // left in the buffer until the rest of its bytes arrive.
        conn.in.flip();
        try {
            List<String> command;
            while ((command = RespParser.tryParse(conn.in)) != null) {
                conn.out.add(ByteBuffer.wrap(handler.execute(command)));
            }
        } catch (IllegalArgumentException e) {
            protocolError(key, channel, e);
            return;
        }
        conn.in.compact();      // keep unparsed leftovers, make room for more

        flush(key, channel, conn);
    }

    private void onWritable(SelectionKey key) throws IOException {
        flush(key, (SocketChannel) key.channel(), (Connection) key.attachment());
    }

    /**
     * Writes as much pending output as the socket accepts. If the socket's send
     * buffer is full, stop reading from this client (backpressure) and wait for
     * OP_WRITE instead of letting replies pile up in memory.
     */
    private void flush(SelectionKey key, SocketChannel channel, Connection conn) throws IOException {
        while (!conn.out.isEmpty()) {
            ByteBuffer head = conn.out.peekFirst();
            channel.write(head);
            if (head.hasRemaining()) {
                key.interestOps(SelectionKey.OP_WRITE);
                return;
            }
            conn.out.pollFirst();
        }
        key.interestOps(SelectionKey.OP_READ);
    }

    private void protocolError(SelectionKey key, SocketChannel channel, IllegalArgumentException e) {
        try {
            channel.write(ByteBuffer.wrap(Resp.error("ERR " + e.getMessage())));
        } catch (IOException ignored) {
            // best effort: we're closing anyway
        }
        close(key);
    }

    private void close(SelectionKey key) {
        key.cancel();
        try {
            key.channel().close();
        } catch (IOException ignored) {
            // nothing more to do
        }
    }
}

/** Per-client state: bytes received but not yet parsed, and replies not yet sent. */
class Connection {
    private static final int INITIAL_SIZE = 4096;
    private static final int MAX_SIZE = 64 * 1024 * 1024;   // per-client input cap

    /** Kept in "write mode" between events: position = how many bytes we hold. */
    ByteBuffer in = ByteBuffer.allocate(INITIAL_SIZE);
    final Deque<ByteBuffer> out = new ArrayDeque<>();

    /** Double the buffer when a single command is bigger than it. */
    void ensureReadSpace() {
        if (in.hasRemaining()) return;
        if (in.capacity() >= MAX_SIZE) {
            throw new IllegalArgumentException("Protocol error: command too large");
        }
        ByteBuffer bigger = ByteBuffer.allocate(in.capacity() * 2);
        in.flip();
        bigger.put(in);
        in = bigger;
    }
}

/** Storage interface, so later layers (expiry, LRU) can plug in. */
interface Store {
    void set(String key, String value);
    String get(String key);          // null if missing
    boolean delete(String key);      // true if the key existed
}

/** A plain HashMap is enough: only the event-loop thread ever touches it. */
class MapStore implements Store {
    private final Map<String, String> map = new HashMap<>();

    public void set(String key, String value) { map.put(key, value); }
    public String get(String key) { return map.get(key); }
    public boolean delete(String key) { return map.remove(key) != null; }
}

/** Turns a parsed command into a RESP reply. (Unchanged from layer 1.) */
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
 * Incremental RESP parser. Unlike layer 1 it never blocks: if the buffer does
 * not yet hold a whole command it returns null and leaves the buffer untouched,
 * so the caller can try again after more bytes arrive.
 *
 * Expects: *3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n
 */
final class RespParser {
    private static final int MAX_ARGS = 1_048_576;
    private static final int MAX_BULK = 32 * 1024 * 1024;
    private static final int MAX_LINE = 1024;

    private RespParser() {}

    /**
     * @param buf a buffer in read mode (flipped)
     * @return one command and the buffer advanced past it, or null if incomplete
     * @throws IllegalArgumentException on malformed input
     */
    static List<String> tryParse(ByteBuffer buf) {
        int start = buf.position();
        List<String> command = parseArray(buf);
        if (command == null) buf.position(start);   // incomplete: rewind
        return command;
    }

    private static List<String> parseArray(ByteBuffer buf) {
        String header = readLine(buf);
        if (header == null) return null;
        if (!header.startsWith("*")) {
            throw new IllegalArgumentException("Protocol error: expected '*'");
        }

        int count = Integer.parseInt(header.substring(1));
        if (count > MAX_ARGS) throw new IllegalArgumentException("Protocol error: too many arguments");
        List<String> args = new ArrayList<>(Math.max(0, Math.min(count, 16)));

        for (int i = 0; i < count; i++) {
            String lengthLine = readLine(buf);
            if (lengthLine == null) return null;
            if (!lengthLine.startsWith("$")) {
                throw new IllegalArgumentException("Protocol error: expected '$'");
            }
            int length = Integer.parseInt(lengthLine.substring(1));
            if (length < 0 || length > MAX_BULK) {
                throw new IllegalArgumentException("Protocol error: invalid bulk length");
            }

            if (buf.remaining() < length + 2) return null;   // data + CRLF not all here yet
            byte[] data = new byte[length];
            buf.get(data);
            if (buf.get() != '\r' || buf.get() != '\n') {
                throw new IllegalArgumentException("Protocol error: missing CRLF after bulk string");
            }
            args.add(new String(data, StandardCharsets.UTF_8));
        }
        return args;
    }

    /** Returns one CRLF-terminated line (without the CRLF), or null if none is complete yet. */
    private static String readLine(ByteBuffer buf) {
        int pos = buf.position();
        int limit = buf.limit();
        for (int i = pos; i + 1 < limit; i++) {
            if (buf.get(i) == '\r' && buf.get(i + 1) == '\n') {
                byte[] bytes = new byte[i - pos];
                buf.get(bytes);
                buf.position(i + 2);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        }
        if (limit - pos > MAX_LINE) {
            throw new IllegalArgumentException("Protocol error: line too long");
        }
        return null;
    }
}

/* RESP reply encoders.  */
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