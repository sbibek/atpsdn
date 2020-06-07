/*
 * Copyright 2020-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.decps.atpsdn;

import com.google.common.collect.Maps;
import org.onlab.packet.*;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficSelector;

import static org.onosproject.net.flow.DefaultTrafficTreatment.builder;

import org.onosproject.net.packet.*;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Optional;
import java.util.Properties;

import static org.onlab.util.Tools.get;

import java.util.*;
import java.util.concurrent.*;


/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true,
        service = {SomeInterface.class},
        property = {
                "someProperty=Some Default String Value",
        })
public class AppComponent implements SomeInterface {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private ApplicationId appId;

    /**
     * Some configurable property.
     */
    private String someProperty;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    ExecutorService executor = Executors.newSingleThreadExecutor();
    ScheduledExecutorService sessionExecutor = Executors.newSingleThreadScheduledExecutor();
    ExecutorService outboundExecutor = Executors.newSingleThreadExecutor();
    ExecutorService ackExecutor = Executors.newSingleThreadExecutor();

    Integer sessionExecutorPeriodSecs = 5;

    ThreadedProcessor t_processor = new ThreadedProcessor();
    Tracker queuedSessionTracker = new Tracker();

    private final TrafficSelector interceptTraffic = DefaultTrafficSelector.builder()
            .matchEthType(Ethernet.TYPE_IPV4).matchIPProtocol(IPv4.PROTOCOL_TCP)
            .build();

    private SwitchPacketProcessor processor = new SwitchPacketProcessor();
    protected Map<DeviceId, Map<MacAddress, PortNumber>> mactables = Maps.newConcurrentMap();
    private LinkedBlockingQueue<PacketContext> Q = new LinkedBlockingQueue<>();


    // FLAGS for tcp
    private final Integer SYN = 2;
    private final Integer ACK = 16;
    private final Integer FIN_ACK = 17;
    private final Integer PUSH_ACK = 24;


    private void info(String message) {
        log.info("[ATPSDN] " + message);
    }

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("org.decps.atpsdn");
        packetService.addProcessor(processor, 110);

        // intercept the traffic of just TCP
        packetService.requestPackets(interceptTraffic, PacketPriority.CONTROL, appId,
                Optional.empty());

        executor.execute(t_processor);
        t_processor.init();

        info("(application id, name)  " + appId.id() + ", " + appId.name());
        info("***STARTED***");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        packetService.removeProcessor(processor);
        t_processor.teardown();
        executor.shutdown();
        sessionExecutor.shutdown();
        outboundExecutor.shutdown();
        ackExecutor.shutdown();
        info("***STOPPED***");
    }

    @Modified
    public void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context != null ? context.getProperties() : new Properties();
        if (context != null) {
            someProperty = get(properties, "someProperty");
        }
        info("***RECONFIGURED***");
    }

    @Override
    public void someMethod() {
        log.info("Invoked");
    }

    public void log(String msg) {
        info("[atpsdn] " + msg);
    }


    private class SwitchPacketProcessor implements PacketProcessor {

        public void log(String msg) {
            info("[atpsdn] " + msg);
        }

        public long getUnsignedInt(int x) {
            return x & 0x00000000ffffffffL;
        }

        private Boolean isTargettedSession(PacketContext context) {
            // for now, all the hosts will be considered for the ATP teardowns
            // only standard port 22 will be excluded as that is used for ssh
            TCP tcp = (TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload();
            return (tcp.getSourcePort() >= 9000 && tcp.getSourcePort() <= 9500) || (tcp.getDestinationPort() >= 9000 && tcp.getDestinationPort() <= 9500);
        }

        @Override
        public void process(PacketContext context) {
            // we will anyway the mapping table
            updateTable(context);

            // check if the packet is already handled
            if (context.isHandled()) {
                log("Packet already handled, so returning");
                return;
            }

            InboundPacket iPacket = context.inPacket();
            Ethernet ethPacket = iPacket.parsed();

            if (ethPacket.getEtherType() == Ethernet.TYPE_IPV4
                    && ((IPv4) ethPacket.getPayload()).getProtocol() == IPv4.PROTOCOL_TCP
                    && isTargettedSession(context)) {
                // Q.add(context);
                TCP tcp = (TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload();
                log.info(String.format("[TCP] src=%d dst=%d flag=%d seq=%d ack=%d len=%d", tcp.getSourcePort(), tcp.getDestinationPort(), tcp.getFlags(), getUnsignedInt(tcp.getSequence()), getUnsignedInt(tcp.getAcknowledge()), tcp.getPayload().serialize().length ));
                next(context,null);
                return;
            }

            next(context, null);
        }

        private void initMacTable(ConnectPoint cp) {
            mactables.putIfAbsent(cp.deviceId(), Maps.newConcurrentMap());
        }

        public void updateTable(PacketContext context) {
            initMacTable(context.inPacket().receivedFrom());

            ConnectPoint cp = context.inPacket().receivedFrom();
            Map<MacAddress, PortNumber> macTable = mactables.get(cp.deviceId());
            MacAddress srcMac = context.inPacket().parsed().getSourceMAC();
            MacAddress dstMac = context.inPacket().parsed().getDestinationMAC();
            macTable.put(srcMac, cp.port());
        }

        public void next(PacketContext context, Integer queueId) {
            initMacTable(context.inPacket().receivedFrom());
            actLikeSwitch(context, null);
        }

        public void actLikeHub(PacketContext context, Integer queueId) {
            if (queueId == null) {
                context.treatmentBuilder().setOutput(PortNumber.FLOOD);
            } else {
                context.treatmentBuilder().setQueue(queueId).setOutput(PortNumber.FLOOD);
            }
            context.send();
        }

        public PortNumber getOutport(PacketContext context) {
            ConnectPoint cp = context.inPacket().receivedFrom();
            Map<MacAddress, PortNumber> macTable = mactables.get(cp.deviceId());
            MacAddress dstMac = context.inPacket().parsed().getDestinationMAC();
            PortNumber n = macTable.get(dstMac);
            return n != null ? n : PortNumber.FLOOD;
        }

        public PortNumber getOutport(DeviceId did, MacAddress dstMac) {
            Map<MacAddress, PortNumber> macTable = mactables.get(did);
            PortNumber n = macTable.get(dstMac);
            return n != null ? n : PortNumber.FLOOD;
        }

        public void actLikeSwitch(PacketContext context, Integer queueId) {
            short type = context.inPacket().parsed().getEtherType();
            ConnectPoint cp = context.inPacket().receivedFrom();
            Map<MacAddress, PortNumber> macTable = mactables.get(cp.deviceId());
            MacAddress srcMac = context.inPacket().parsed().getSourceMAC();
            MacAddress dstMac = context.inPacket().parsed().getDestinationMAC();
            PortNumber outPort = macTable.get(dstMac);

            if (outPort != null) {
                if (queueId == null) {
                    context.treatmentBuilder().setOutput(outPort);
                } else {
                    context.treatmentBuilder().setQueue(queueId).setOutput(outPort);
                }

                context.send();
            } else {
                actLikeHub(context, queueId);
            }
        }

        private TCP ackPacketToSender(PacketContext context, SessionQ session) {
            Ethernet ethPacket = context.inPacket().parsed();
            IPv4 ip = (IPv4) ethPacket.getPayload();
            TCP tcp = (TCP) ip.getPayload();

            InboundPacket _iPacket = session.r2s_context.inPacket(); //tp.context_fromdstn.inPacket();
            Ethernet rethPacket = _iPacket.parsed();
            IPv4 rip = (IPv4) rethPacket.getPayload();
            TCP rtcp = (TCP) rip.getPayload();

            // now we need to craft another packet from opposite side
            Ethernet r_eth = new Ethernet();
            r_eth.setDestinationMACAddress(rethPacket.getDestinationMACAddress());
            r_eth.setSourceMACAddress(rethPacket.getSourceMACAddress());
            r_eth.setEtherType(rethPacket.getEtherType());

            IPv4 r_ip = new IPv4();
            r_ip.setSourceAddress(rip.getSourceAddress());
            r_ip.setDestinationAddress(rip.getDestinationAddress());
            r_ip.setProtocol(rip.getProtocol());
            r_ip.setFlags(rip.getFlags());
            r_ip.setIdentification(rip.getIdentification());
            r_ip.setTtl(rip.getTtl());
            r_ip.setChecksum((short) 0);

            TCP r_tcp = new TCP();
            r_tcp.setSourcePort(rtcp.getSourcePort());
            r_tcp.setDestinationPort(rtcp.getDestinationPort());
            r_tcp.setSequence(tcp.getAcknowledge());
            r_tcp.setAcknowledge(tcp.getSequence()+tcp.getPayload().serialize().length);
            r_tcp.setWindowSize(rtcp.getWindowSize());
            r_tcp.setFlags((short) 16);
            r_tcp.setDataOffset(rtcp.getDataOffset());
            r_tcp.setOptions(rtcp.getOptions());
            r_tcp.setChecksum((short) 0);

            r_ip.setPayload(r_tcp);
            r_eth.setPayload(r_ip);

            PortNumber r_outport = getOutport(session.r2s_context);

            DefaultOutboundPacket r_outboundPacket = new DefaultOutboundPacket(
                    session.r2s_context.outPacket().sendThrough(),
                    builder().setOutput(r_outport).build(),
                    ByteBuffer.wrap(r_eth.serialize())
            );
            packetService.emit(r_outboundPacket);
            log(String.format(" [ACK sender] %d -> %d flags: %d seq: %d ack: %d", r_tcp.getSourcePort(), r_tcp.getDestinationPort(), r_tcp.getFlags(), getUnsignedInt(r_tcp.getSequence()), getUnsignedInt(r_tcp.getAcknowledge())));
            return r_tcp;
        }

    }


    private class ThreadedProcessor implements Runnable {
        private Boolean stop = false;
        private SessionProcessor sessionProcessor = new SessionProcessor();
        private OutboundProcessor outboundProcessor = new OutboundProcessor();
        private AckProcessor ackProcessor = new AckProcessor();

        public ThreadedProcessor() {

        }

        public void init() {
            sessionExecutor.scheduleAtFixedRate(sessionProcessor, 0, sessionExecutorPeriodSecs, TimeUnit.SECONDS);
            outboundExecutor.execute(outboundProcessor);
            ackExecutor.execute(ackProcessor);
            log("session executor scheduled, outbound exeuctor started");
        }

        public void teardown() {
            outboundProcessor.stop = true;
            this.stop = true;
        }

        public long getUnsignedInt(int x) {
            return x & 0x00000000ffffffffL;
        }

        public void stop() {
            this.stop = true;
        }

        public Boolean isPushAck(PacketContext context) {
            return ((TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload()).getFlags() == 24;
        }

        public Boolean isDestnPort(PacketContext context, Integer port) {
            return ((TCP) ((IPv4) context.inPacket().parsed().getPayload()).getPayload()).getDestinationPort() == port;
        }

        public void log(String msg) {
            log.info(String.format("[ThreadedProcessor] %s", msg));
        }

        @Override
        public void run() {
            log("started Qprocessor");
            while (!stop) {
                PacketContext context = null;
                try {
                    context = Q.take();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                // since all the packets we get here is TCP so we can get TCP packet
                IPv4 ip = (IPv4) context.inPacket().parsed().getPayload();
                TCP tcp = (TCP) ip.getPayload();
                Integer src = ip.getSourceAddress();
                Integer dst = ip.getDestinationAddress();
                Integer srcport = tcp.getSourcePort();
                Integer dstport = tcp.getDestinationPort();

                log(String.format("### %d -> %d flags %d seq %d ack %d", tcp.getSourcePort(), tcp.getDestinationPort(), tcp.getFlags(), getUnsignedInt(tcp.getSequence()), getUnsignedInt(tcp.getAcknowledge())));
                if (queuedSessionTracker.sessionExists(src, dst, srcport, dstport)) {
                    SessionQ session = queuedSessionTracker.getSession(src, dst, srcport, dstport);
                    // just add it to the tracker if the packet is sent from sender to receiver and the packet
                    // is PUSH ACK (data packet)
                    if (session.isDirectionSenderToReceiver(ip, tcp) && tcp.getFlags() == PUSH_ACK) {

                        // this will internally take care to check if there is retransmission
                        queuedSessionTracker.addPacket(src, dst, srcport, dstport, context);
                    } else {

                        // context are required for the teardown so we need to update it accordingly
                        if (session.isDirectionSenderToReceiver(ip, tcp)) {
                            // means this is sender->receiver
                            // in this case, we just want to update the context so that we can use it to
                            // teardown the connection in the future
                            session.s2r_context = context;
                            session.s2r_seq = tcp.getSequence();
                            session.s2r_ack = tcp.getAcknowledge();
                        } else {
                            // means this is receiver -> sender
                            // we update the context only
                            session.r2s_context = context;
                            session.r2s_seq = tcp.getSequence();
                            session.r2s_ack = tcp.getAcknowledge();
                        }

                        // this packet is good to be sent so we just send it
                        // then decide if we should start the teardown or not

                        processor.next(context, null);
                        //session.acknowledge(context);

                        if(tcp.getFlags() == FIN_ACK) {
                            log("<received FIN>");
                        }
                    }
                } else {
                    // means we need to create the new session for this but just if this is SYN ack
                    if (tcp.getFlags() == SYN) {
                        queuedSessionTracker.createSession(src, dst, srcport, dstport, context);
                        processor.next(context, null);
                        log(String.format("initialized context for %d -> %d", srcport, dstport));
                    } else {
                        // just block the packets
                        context.block();
                    }
                }

            }
            log("EOL");
        }


        private class AckProcessor implements Runnable {
            public Boolean stop = false;

            @Override
            public void run() {
                while (!stop) {
                    queuedSessionTracker.runAcks();
                }
            }
        }


        // runs tracker processor
        private class SessionProcessor implements Runnable {
            public void log(String msg) {
                log.info(String.format("[SessionProcessor] %s", msg));
            }

            @Override
            public void run() {
                try {
                    List<String> toBeCleared = new ArrayList<>();

                    // for now just push to the outbound queue
                    queuedSessionTracker.tracker.forEach((k, session) -> {

                    });

                    // run a clearing every tick
                    toBeCleared.forEach((key) -> {
                        queuedSessionTracker.removeSession(key);
                    });

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        private class OutboundProcessor implements Runnable {

            public Boolean stop = false;

            public void log(String msg) {
                log.info(String.format("[OutboundProcessor] %s", msg));
            }

            @Override
            public void run() {
                log("**started**");
                while (!stop) {
                    // this thread will take one packet out of each of the session and send it to the destination
                    // we will make decision based on loss rate whether we send the packet just now or not
                    queuedSessionTracker.tracker.forEach((k, session) -> {
                        // do something only if the session has packets to process
                        // but important thing is to check if the teardown has started for this session
                        // if we ignore the session packets
                        SessionQ.PacketTuple packetTuple = session.getQueuedPacket();
                        if(packetTuple !=  null) {
                            if(packetTuple.flag == false) {
                                // if flag is false then it means that we can send it to the normal route
                                processor.next(packetTuple.context, 0);
                                log("<send/>");
                            } else {
                                log("----------> <ack/>");
                                // else means that we should acknowledge it to the sender
                                TCP ackPacketTcp = processor.ackPacketToSender(packetTuple.context, session);
                                //session.acknowledge(ackPacketTcp);
                            }
                        }
                    });
                }
                log("**shutdown**");
            }
        }
    }
}