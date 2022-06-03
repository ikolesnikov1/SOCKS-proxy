package connection;

import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.ResolverConfig;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class DNSHandler implements SocketHandler {

    private final DatagramChannel resolverChannel = DatagramChannel.open();

    private final InetSocketAddress DnsServerAddr;

    private final ByteBuffer readBuff = ByteBuffer.allocateDirect(Message.MAXLENGTH);

    private final ByteBuffer writeBuff = ByteBuffer.allocateDirect(Message.MAXLENGTH);

    private final SelectionKey key;

    private final Deque<Message> deque = new LinkedList<>();

    private final Map<Integer, ConnectionHandler> attachments = new HashMap<>();

    private final Map<ConnectionHandler, Instant> lostPackageDetector = new HashMap<>();

    public DNSHandler(int port, Selector selector) throws IOException {
        resolverChannel.configureBlocking(false);
        resolverChannel.register(selector, 0, this);
        resolverChannel.bind(new InetSocketAddress(port));

        key = resolverChannel.keyFor(selector);
        DnsServerAddr = ResolverConfig.getCurrentConfig().server();

        resolverChannel.connect(DnsServerAddr);

        readBuff.clear();
        writeBuff.clear();
    }

    public void sendToResolve(String domainName, ConnectionHandler handler) {
        try {
            Message dnsRequest = Message.newQuery(Record.newRecord(new Name(domainName + '.'), Type.A, DClass.IN));
            deque.addLast(dnsRequest);
            attachments.put(dnsRequest.getHeader().getID(), handler);
            lostPackageDetector.put(handler, Instant.now());
            key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        } catch (TextParseException ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public void close() throws IOException {
        resolverChannel.close();
    }

    @Override
    public void handle(SelectionKey key) {
        try {
            if (!key.isValid()) {
                this.close();
                key.cancel();
                return;
            }

            if (key.isReadable()) {
                read(key);
            } else if (key.isWritable()) {
                write(key);
            }

            for (Map.Entry<ConnectionHandler, Instant> entry : lostPackageDetector.entrySet()) {
                if (Duration.between(Instant.now(), entry.getValue()).getSeconds() > 30) {
                    entry.getKey().close();
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();

            try {
                this.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void read(SelectionKey key) throws IOException {
        if (resolverChannel.receive(readBuff) != null) {
            readBuff.flip();
            byte[] data = new byte[readBuff.limit()];
            readBuff.get(data);
            readBuff.clear();

            Message response = new Message(data);
            ConnectionHandler session = attachments.remove(response.getHeader().getID());

            for (Record record : response.getSection(Section.ANSWER))
                if (record instanceof ARecord) {
                    ARecord it = (ARecord) record;
                    if (session != null && session.connectToServer(it.getAddress())) {
                        break;
                    }
                }
        }
        if (attachments.isEmpty()) {
            key.interestOps(key.interestOps() ^ SelectionKey.OP_READ);
        }
    }

    @Override
    public void write(SelectionKey key) throws IOException {
        Message dnsRequest = deque.pollFirst();

        while (dnsRequest != null) {
            writeBuff.clear();
            writeBuff.put(dnsRequest.toWire());
            writeBuff.flip();

            if (resolverChannel.send(writeBuff, DnsServerAddr) == 0) {
                deque.addFirst(dnsRequest);
                break;
            }

            key.interestOps(key.interestOps() | SelectionKey.OP_READ);
            dnsRequest = deque.pollFirst();
        }

        key.interestOps(key.interestOps() ^ SelectionKey.OP_WRITE);
    }
}

