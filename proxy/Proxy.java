package proxy;

import connection.Handler;
import connection.ServerHandler;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Set;

public class Proxy implements AutoCloseable {

    private final Selector selector = Selector.open();

    private final ServerHandler server;

    public Proxy(int port) throws IOException {
        server = new ServerHandler(port, selector);
    }

    @Override
    public void close() throws Exception {
        selector.close();
        server.close();
        server.closeDNS();
    }

    public void start() {
        try {
            while (true) {
                int readyChannels = 0;

                try {
                    readyChannels = selector.select(10000);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }

                if (readyChannels == 0) {
                    continue;
                }

                Set<SelectionKey> modified = selector.selectedKeys();
                for (SelectionKey selected : modified) {
                    Handler key = (Handler) selected.attachment();
                    key.handle(selected);
                }

                modified.clear();
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
        } finally {
            try {
                close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
