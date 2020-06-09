package org.decps.atpsdn;

import org.decps.atpsdn.Kafka.KafkaHeader;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class Main {
    public static void main(String[] args) throws IOException {
        //String file = "/Users/bibekshrestha/Documents/lab/atplogs/kafka1300.bin";
        //String file = "/Users/bibekshrestha/Documents/lab/atplogs/merged.bin";
      //  String file = "/Users/bibekshrestha/Documents/lab/atplogs/KafkaPayload0.bin";
        String file = "/Users/bibekshrestha/Documents/lab/atplogs/checksumtest";
        InputStream input = new FileInputStream(file);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = input.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();
        byte[] byteArray = buffer.toByteArray();

        System.out.println("Total bytes read: "+byteArray.length);

        ByteBuffer bytebuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.BIG_ENDIAN);

        while(bytebuffer.remaining() != 0) {
            KafkaHeader header = new KafkaHeader();
            header.decode(bytebuffer);
            header.log();
            System.out.println("remaining bytes => " + bytebuffer.remaining());
            System.out.println();


            ByteBuffer _buffer = ByteBuffer.allocate(2000).order(ByteOrder.BIG_ENDIAN);
            System.out.println(_buffer.remaining());
            header.encode(_buffer);
            _buffer.flip();
            System.out.println(_buffer.remaining());

            KafkaHeader header2 = new KafkaHeader();
            header2.decode(_buffer);
            header2.log();
            System.out.println("remaining bytes => " + _buffer.remaining());
        }
    }
}
