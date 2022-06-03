package messages;

import java.nio.ByteBuffer;

public class Hello extends ToolsMessage {

    public Hello(ByteBuffer buffer) {
        super(new byte[buffer.limit()]);

        buffer.get(data);
        if (data[1] + 2 != data.length) {
            throw new IllegalArgumentException();
        }
    }

    public static boolean isCorrectSizeOfMessage(ByteBuffer data) {
        return data.position() > 1 && data.position() >= 2 + data.get(1);
    }

}
