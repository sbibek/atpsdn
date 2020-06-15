package org.decps.atpsdn;

import org.onlab.packet.Ethernet;
import org.onlab.packet.IPv4;
import org.onlab.packet.TCP;

public class Utils {
    public static long getUnsignedInt(int x) {
        return x & 0x00000000ffffffffL;
    }

    public static Integer calculateTotalOutboundMessagesFor(Integer totalIncomingMessages, Double MLR) {
        Double t = totalIncomingMessages * (1 - MLR);
        return t.intValue();
    }

    public static byte[] createCopy(byte[] arr) {
        if(arr == null) return null;

        byte[] copy = new byte[arr.length];
        for (int i = 0; i < arr.length; i++)
            copy[i] = arr[i];
        return copy;
    }

    public static byte[] getSpecifiedBytes(byte[] payload, Integer totalBytes) {
        byte[] r = new byte[totalBytes];
        for (int i = 0; i < totalBytes; i++)
            r[i] = payload[i];
        return r;
    }

    public static Ethernet clone(Ethernet eth) {
        Ethernet ethernet = new Ethernet();
        ethernet.setDestinationMACAddress(eth.getDestinationMACAddress());
        ethernet.setSourceMACAddress(eth.getSourceMACAddress());
        ethernet.setEtherType(eth.getEtherType());
        return ethernet;
    }

    public static IPv4 clone(IPv4 ip) {
        IPv4 ipv4 = new IPv4();
        ipv4.setSourceAddress(ip.getSourceAddress());
        ipv4.setDestinationAddress(ip.getDestinationAddress());
        ipv4.setProtocol(ip.getProtocol());
        ipv4.setFlags(ip.getFlags());
        ipv4.setIdentification(ip.getIdentification());
        ipv4.setTtl(ip.getTtl());
        ipv4.setChecksum((short) 0);
        return ipv4;
    }

    public static TCP clone(TCP tcp) {
        TCP _tcp = new TCP();
        _tcp.setSourcePort(tcp.getSourcePort());
        _tcp.setDestinationPort(tcp.getDestinationPort());
        _tcp.setSequence(tcp.getSequence());
        _tcp.setAcknowledge(tcp.getAcknowledge());
        _tcp.setWindowSize(tcp.getWindowSize());
        _tcp.setFlags(tcp.getFlags());
        _tcp.setDataOffset(tcp.getDataOffset());
        _tcp.setOptions(tcp.getOptions());
        _tcp.setChecksum((short) 0);
        return _tcp;
    }
}
