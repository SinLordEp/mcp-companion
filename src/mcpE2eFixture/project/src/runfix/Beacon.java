package runfix;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Fixture for mc_run: prints a tick every second, READY after two seconds, then answers "42" on a loopback port;
 * every fifth tick goes to stderr. Exits when stdin closes, otherwise runs until it is stopped. No dependencies, no
 * network beyond 127.0.0.1.
 */
public class Beacon {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        Thread stdin = new Thread(Beacon::exitWhenStdinCloses, "beacon-stdin");
        stdin.setDaemon(true);
        stdin.start();
        System.out.println("Beacon starting");
        for (int tick = 1; ; tick++) {
            Thread.sleep(1000);
            System.out.println("tick " + tick + " " + message());
            if (tick == 2) {
                ServerSocket server = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
                Thread serving = new Thread(() -> serve(server), "beacon-server");
                serving.setDaemon(true);
                serving.start();
                System.out.println("READY port " + server.getLocalPort());
            }
            if (tick % 5 == 0) {
                System.err.println("warning: tick " + tick);
            }
        }
    }

    static String message() {
        return "v1";
    }

    private static void serve(ServerSocket server) {
        while (true) {
            try (Socket client = server.accept(); OutputStream out = client.getOutputStream()) {
                out.write("42\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return;
            }
        }
    }

    private static void exitWhenStdinCloses() {
        try {
            while (System.in.read() >= 0) {
                // Ignore input; only the end of the stream matters.
            }
        } catch (IOException ignored) {
            // Treated like a closed stream.
        }
        System.out.println("stdin closed, exiting");
        System.exit(0);
    }
}
