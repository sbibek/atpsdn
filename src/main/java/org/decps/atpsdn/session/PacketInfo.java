package org.decps.atpsdn.session;

import org.decps.atpsdn.Utils;
import org.decps.atpsdn.atp.ModifiedTcpPayload;
import org.onlab.packet.Ethernet;
import org.onlab.packet.IP;
import org.onlab.packet.IPv4;
import org.onlab.packet.TCP;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacketInfo {
    private final Logger log = LoggerFactory.getLogger(getClass());

    public static final Integer TCP_FLAG_SYN = 2;
    public static final Integer TCP_FLAG_ACK = 16;
    public static final Integer TCP_FLAG_SYN_ACK = 18;
    public static final Integer TCP_FLAG_PSH_ACK = 24;
    public static final Integer TCP_FLAG_FIN_ACK = 17;

    public PacketContext context;
    public IPv4 ip;
    public TCP tcp;
    public Integer srcAddr, dstAddr, srcPort, dstPort;
    public String sourceAddress, destinationAddress;
    public Integer flag;
    public Long seq, ack;
    public Integer payloadLength;

    // if we had to modify the tcp packets, then we will craft entire batch of
    // eth, ip and tcp packet that will take precedence over the context
    public Ethernet modifiedEthernet = null;

    // a unique key that represents this connection
    public String key;

    /**
     * Information below will be used for tracking the transmission of this packet
     * The time when the this packet was sent
      */
    public Long sentTime = 0L;
    public Long expectedAcknowledgementSeq = 0L;
    public Long expectedAcknowledgementAck = 0L;

    public PacketInfo(PacketContext context) {
        this.context = context;
        ip = (IPv4) context.inPacket().parsed().getPayload();
        tcp = (TCP) ip.getPayload();
        srcAddr = ip.getSourceAddress();
        dstAddr = ip.getDestinationAddress();
        srcPort = tcp.getSourcePort();
        dstPort = tcp.getDestinationPort();
        sourceAddress = IPv4.fromIPv4Address(srcAddr);
        destinationAddress = IPv4.fromIPv4Address(dstAddr);
        flag = (int) tcp.getFlags();
        seq = Utils.getUnsignedInt(tcp.getSequence());
        ack = Utils.getUnsignedInt(tcp.getAcknowledge());
        payloadLength = tcp.getPayload().serialize().length;
        key = generateUniqueKey();
    }

    public String getFlag() {
        if (flag == TCP_FLAG_SYN) return "SYN";
        else if (flag == TCP_FLAG_ACK) return "ACK";
        else if (flag == TCP_FLAG_PSH_ACK) return "PSH-ACK";
        else if (flag == TCP_FLAG_FIN_ACK) return "FIN-ACK";
        else if (flag == TCP_FLAG_SYN_ACK) return "SYN-ACK";
        else return "";
    }

    public byte[] getPayload() {
        return tcp.getPayload().serialize();
    }


    private String generateUniqueKey() {
        // each unique key is unique to a specific connection in both the direction
        String address1, address2;
        Integer p1, p2;

        if (srcAddr < dstAddr) {
            address1 = sourceAddress;
            address2 = destinationAddress;
        } else {
            address1 = destinationAddress;
            address2 = sourceAddress;
        }

        if (srcPort < dstPort) {
            p1 = srcPort;
            p2 = dstPort;
        } else {
            p1 = dstPort;
            p2 = srcPort;
        }

        return String.format("%s-%s-%d-%d", address1, address2, p1, p2);
    }

    public void buildWithModifiedPayload(byte[] payload) {
        InboundPacket iPacket = context.inPacket();
        Ethernet ethPacket = iPacket.parsed();
        IPv4 ipPacket = (IPv4) ethPacket.getPayload();
        TCP tcpPacket = (TCP) ipPacket.getPayload();

        Ethernet eth = Utils.clone(ethPacket);
        IPv4 ip = Utils.clone(ipPacket);
        TCP tcp = Utils.clone(tcpPacket);
        tcp.setPayload(new ModifiedTcpPayload(payload));

        ip.setPayload(tcp);
        eth.setPayload(ip);

        this.modifiedEthernet = eth;
    }

    public void setAcknowledgementParams(){
        expectedAcknowledgementSeq = ack;
        expectedAcknowledgementAck = seq + payloadLength;
    }

    public void setSent() {
        this.sentTime = System.currentTimeMillis();
    }

    public void log() {
        String message = String.format("[ %s : %d -> %s : %d flag=%d(%s) seq=%d ack=%d payload=%d ]", IPv4.fromIPv4Address(srcAddr), srcPort, IPv4.fromIPv4Address(dstAddr), dstPort, flag, getFlag(), seq, ack, payloadLength);
        log.info(message);
    }

    public void logCSV(Integer id) {
        String message = String.format("%d,%d,%d,%d", seq, ack, payloadLength, id);
        log.info(message);
    }
}
