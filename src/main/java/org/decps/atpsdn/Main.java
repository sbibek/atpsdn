package org.decps.atpsdn;

//import io.pkts.Pcap;
//import io.pkts.packet.Packet;
//import io.pkts.packet.TCPPacket;
//import io.pkts.protocol.Protocol;
import org.decps.atpsdn.session.PayloadManager;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws IOException {
//        List<byte[]> data = new ArrayList<>();
//
//        for (int i = 1; i <=5; i++) {
//            String file = String.format("/Users/bibekshrestha/Documents/lab/atplogs/test/out/%d.bin", i);
//
//            byte[] bytedata = getBytes(file);
//            data.add(bytedata);
//        }
//
//        PayloadManager manager = new PayloadManager();
//        int index = 0;
//        for (byte[] payload : data) {
//            System.out.println(index++);
//            manager.process(payload);
//        }
//
//        System.out.println(manager.totalMessages);
//        splitter();
//        testBench();
        int[] test = new int[]{1,2,3};
        int[] test2 = test;
        int a = 0;
        int b;

        b = a;

        a = 12;
        System.out.println(b);

    }

    public static byte[] getBytes(String file) throws IOException {
        InputStream input = new FileInputStream(file);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = input.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }

        buffer.flush();
        byte[] byteArray = buffer.toByteArray();
        return byteArray;
    }

    public static void splitter() throws IOException {
        String file = String.format("/Users/bibekshrestha/Documents/lab/atplogs/test/%s.bin", "single");
        byte[] bytedata = getBytes(file);
        System.out.println(bytedata.length);

        write("1", chunk(0, 219, bytedata));
        write("2", chunk(219, 899, bytedata));
        write("3", chunk(899, 1800, bytedata));
        write("4", chunk(1800, 2500, bytedata));
        write("5", chunk(2500, bytedata.length, bytedata));
    }

    public static byte[] chunk(Integer from, Integer to, byte[] data) {
       byte[] r = new byte[to-from];
       for(int i=from;i<to;i++) {
           r[i-from] = data[i];
       }
       return r;
    }

    public static void write(String fname, byte[] arr) throws IOException {
        FileOutputStream fos = new FileOutputStream(String.format("/Users/bibekshrestha/Documents/lab/atplogs/test/out/%s.bin", fname));
        fos.write(arr);
        fos.close();
    }

    public static void testBench() throws IOException {
//        final Intizer nextExpected = new Intizer();
//        PayloadManager manager = new PayloadManager();
//        Pcap pcap = Pcap.openStream("/Users/bibekshrestha/Documents/lab/atplogs/test/msg200-5.pcap");
//        pcap.loop((final Packet packet) -> {
//            TCPPacket tcp = (TCPPacket) packet.getPacket(Protocol.TCP);
//
//            if(tcp.getSourcePort() == 48788 &&  tcp.getPayload() != null && tcp.getPayload().getArray().length > 0 ) {
//                if(nextExpected.getCount() != null && nextExpected.getCount() != tcp.getSequenceNumber()){
//                    System.out.println("skipping "+tcp.getSequenceNumber());
//                    return true;
//                }
//                System.out.println(String.format("processing seq=%d ack=%d len=%d", tcp.getSequenceNumber(), tcp.getAcknowledgementNumber(), tcp.getPayload().getArray().length));
//                manager.process(tcp.getPayload().getArray());
//                System.out.println();
//                nextExpected.setCount(tcp.getSequenceNumber()+tcp.getPayload().getArray().length);
//            }
//            return true;
//        });
//        System.out.println(manager.totalMessages);
    }

    public static class Intizer {
        public Long count = null;

        public void setCount(Long c) {
            count = c;
        }

        public  Long getCount() {
            return  count;
        }
    }
}
