package connection;

import messages.Hello;
import messages.MessageReader;
import messages.Request;
import messages.ResponseOnRequest;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class ConnectionHandler implements SocketHandler {

    private static final int BUFFER_SIZE = 4096;

    private final SocketChannel clientChannel;

    private final DNSHandler dns;

    private SocketChannel serverChannel = null;

    private State state = State.HELLO;

    private ByteBuffer readBuff = ByteBuffer.allocateDirect(BUFFER_SIZE);

    private ByteBuffer writeBuff = null;

    private Hello hello = null;

    private Request request = null;

    public ConnectionHandler(SocketChannel client, DNSHandler dns, Selector selector) throws IOException {
        this.dns = dns;

        clientChannel = client;
        clientChannel.configureBlocking(false);
        clientChannel.register(selector, SelectionKey.OP_READ, this);
    }

    public SocketChannel getClientChannel() {
        return clientChannel;
    }

    public ByteBuffer getReadBuff() {
        return readBuff;
    }

    public void setReadBuff(ByteBuffer readBuff) {
        this.readBuff = readBuff;
    }

    @Override
    public void handle(SelectionKey key) {
        try {
            if (!key.isValid()) {
                close();
                key.cancel();
                return;
            }

            if (key.isReadable()) {
                read(key);
            } else if (key.isWritable()) {
                write(key);
            } else if (key.isConnectable() && key.channel() == serverChannel) {
                serverConnect(key);
            }
        } catch (IOException ex) {
            try {
                close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (clientChannel != null) {
            clientChannel.close();
        }

        if (serverChannel != null) {
            serverChannel.close();
        }
    }

    @Override
    public void read(SelectionKey key) throws IOException {
        if (key.channel() == clientChannel) {
            clientRead(key);
        } else if (key.channel() == serverChannel) {
            serverRead(key);
        }
    }

    @Override
    public void write(SelectionKey key) throws IOException {
        if (key.channel() == clientChannel) {
            clientWrite(key);
        } else if (key.channel() == serverChannel) {
            serverWrite(key);
        }
    }

    private void clientRead(SelectionKey key) throws IOException {
        switch (state) {
            case HELLO:
                hello = MessageReader.readHelloMessage(this);
                if (hello == null) {
                    return;
                }

                key.interestOps(SelectionKey.OP_WRITE);
                readBuff.clear();

                break;

            case REQUEST:
                request = MessageReader.readRequestMessage(this);
                if (request == null) {
                    return;
                }

                if (!connect()) {
                    serverChannel = null;
                    key.interestOps(SelectionKey.OP_WRITE);
                } else {
                    serverChannel.register(key.selector(), SelectionKey.OP_CONNECT, this);
                    key.interestOps(0);
                }

                readBuff.clear();
                break;

            case MESSAGE:
                if (this.readFrom(clientChannel, serverChannel, readBuff)) {
                    serverChannel.keyFor(key.selector()).interestOpsOr(SelectionKey.OP_WRITE);
                    key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
                }
        }
    }

    private void clientWrite(SelectionKey key) throws IOException {
        switch (state) {
            case HELLO:
                if (writeBuff == null) {
                    writeBuff = ByteBuffer.wrap(MessageReader.getResponse());
                }

                if (writeTo(clientChannel, writeBuff)) {
                    writeBuff = null;

                    key.interestOps(SelectionKey.OP_READ);
                    state = State.REQUEST;

                    hello = null;
                }

                break;

            case REQUEST:
                if (writeBuff == null) {
                    ResponseOnRequest response = new ResponseOnRequest(request);
                    writeBuff = ByteBuffer.wrap(response.create(serverChannel != null));
                }

                if (writeTo(clientChannel, writeBuff)) {
                    writeBuff = null;

                    if (!request.isCommand(Request.CONNECT_TCP) || serverChannel == null) {
                        this.close();
                    } else {
                        key.interestOps(SelectionKey.OP_READ);
                        serverChannel.register(key.selector(), SelectionKey.OP_READ, this);
                        state = State.MESSAGE;
                    }

                    request = null;
                }

                break;


            case MESSAGE:
                if (writeTo(clientChannel, readBuff)) {
                    key.interestOps(SelectionKey.OP_READ);
                    serverChannel.keyFor(key.selector()).interestOpsOr(SelectionKey.OP_READ);
                }
        }
    }

    private void serverRead(SelectionKey key) throws IOException {
        if (readFrom(serverChannel, clientChannel, readBuff)) {
            clientChannel.register(key.selector(), SelectionKey.OP_WRITE, this);
            key.interestOps(key.interestOps() & ~SelectionKey.OP_READ);
        }
    }

    private void serverWrite(SelectionKey key) throws IOException {
        if (writeTo(serverChannel, readBuff)) {
            key.interestOps(SelectionKey.OP_READ);
            clientChannel.register(key.selector(), SelectionKey.OP_READ, this);
        }
    }

    private void serverConnect(SelectionKey key) throws IOException {
        if (!serverChannel.isConnectionPending()) {
            return;
        }

        if (!serverChannel.finishConnect()) {
            return;
        }

        key.interestOps(0);
        clientChannel.register(key.selector(), SelectionKey.OP_WRITE, this);
    }

    public boolean connectToServer(InetAddress address) {
        try {
            serverChannel.connect(new InetSocketAddress(address, request.getDestPort()));
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean connect() throws IOException {
        serverChannel = SocketChannel.open();
        serverChannel.configureBlocking(false);

        switch (request.getAddressType()) {
            case Request.IPv4:
                return connectToServer(InetAddress.getByAddress(request.getDestAddress()));


            case Request.IPv6:
                System.err.println("NOT SUPPORT IPV6");
                return false;


            case Request.DOMAIN_NAME:
                dns.sendToResolve(new String(request.getDestAddress(), StandardCharsets.US_ASCII), this);
        }

        return true;
    }

    private boolean readFrom(SocketChannel mainChannel, SocketChannel secondChannel, ByteBuffer buffer) throws IOException {
        buffer.compact();

        int read_bytes = mainChannel.read(buffer);
        if (read_bytes == -1) {
            mainChannel.shutdownInput();
            secondChannel.shutdownOutput();
            if (clientChannel.socket().isInputShutdown() && serverChannel.socket().isInputShutdown()) {
                this.close();
            }

            return false;
        }

        if (read_bytes != 0) {
            buffer.flip();
        }

        return read_bytes != 0;
    }

    private boolean writeTo(SocketChannel channel, ByteBuffer buffer) throws IOException {
        channel.write(buffer);
        return !buffer.hasRemaining();
    }

    private enum State {
        HELLO,
        REQUEST,
        MESSAGE
    }
}
