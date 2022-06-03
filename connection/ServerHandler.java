package connection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;

public class ServerHandler implements Handler {

    private final ServerSocketChannel serverChannel = ServerSocketChannel.open();

    private final DNSHandler dns;

    public ServerHandler(int port, Selector selector) throws IOException {
        dns = new DNSHandler(port, selector);

        serverChannel.bind(new InetSocketAddress(port));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT, this);
    }

    public void closeDNS() throws IOException {
        dns.close();
    }

    @Override
    public void close() throws IOException {
        serverChannel.close();
    }

    @Override
    public void handle(SelectionKey key) {
        try {
            if (!key.isValid()) {
                close();
                return;
            }

            new ConnectionHandler(serverChannel.accept(), dns, key.selector());
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
}
